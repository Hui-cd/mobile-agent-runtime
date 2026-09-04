package ai.mobileagent.benchmark

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import ai.mobileagent.BuildConfig
import ai.mobileagent.accessibility.AccessibilityBridge
import ai.mobileagent.pi.PiRunMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

class BenchmarkRunStore private constructor(
    private val context: Context,
    private val prompt: String,
    private val metrics: PiRunMetrics,
    private val model: String,
    private val modelEndpointHost: String,
) {
    private val runId = UUID.randomUUID().toString()
    private val startedAt = Instant.now().toString()
    private val taskId = TASK_PATTERN.find(prompt)?.groupValues?.get(1) ?: "ad_hoc"
    private val benchmarkCohort = COHORT_PATTERN.find(prompt)?.groupValues?.get(1) ?: "unspecified"
    private val attempt = nextAttempt(context, taskId)

    init {
        runCatching { writePending() }
            .onFailure { Log.e("MobileAgentBenchmark", "pending write failed", it) }
    }

    fun complete(answer: String) {
        val result = parseResult(answer)
        val validation = validateResult(result)
        val toolFailure = metrics.lastToolErrorCode.takeIf {
            validation.status == "failed" && metrics.lastToolWasError
        }
        persist(
            status = validation.status,
            result = result,
            failureCode = toolFailure ?: validation.failureCode,
            failureStage = toolFailure?.let { metrics.lastToolFailureStage ?: "tool" }
                ?: validation.failureCode?.let { "adjudication" },
            notes = toolFailure?.let { "$it prevented task completion; ${validation.notes}" }
                ?: validation.notes,
        )
    }

    fun fail(error: Throwable) {
        val stage = metrics.failureStage ?: "agent"
        persist(
            "failed",
            failureCode = error.message?.substringBefore(':') ?: error.javaClass.simpleName,
            failureStage = stage,
            notes = error.message,
            crash = stage == "runtime:webview_renderer",
        )
    }

    fun cancel() = persist("cancelled", failureCode = "USER_CANCELLED", failureStage = metrics.failureStage ?: "agent")

    private fun persist(
        status: String,
        result: Any = JSONObject.NULL,
        failureCode: String? = null,
        failureStage: String? = null,
        notes: String? = null,
        crash: Boolean = false,
    ) {
        metrics.finishForegroundInterval()
        val record = buildRecord(
            status = status,
            result = result,
            failureCode = failureCode,
            failureStage = failureStage,
            notes = notes,
            crash = crash,
        )

        appendRecord(context, record)
        clearPending(context, runId)
    }

    private fun buildRecord(
        status: String,
        result: Any = JSONObject.NULL,
        failureCode: String? = null,
        failureStage: String? = null,
        notes: String? = null,
        crash: Boolean,
    ): JSONObject {
        val targetPackage = metrics.lastExternalPackage
        val targetVersion = targetPackage?.let { packageName ->
            runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        }
        val emulator = isEmulator()
        val defaultLoginState = if (taskId in setOf("M1", "X1", "D1", "W1")) "unknown" else "not_applicable"
        return JSONObject()
            .put("schema_version", 1)
            .put("adjudicator_version", 2)
            .put("run_id", runId)
            .put("started_at", startedAt)
            .put("platform", "android")
            .put("os_version", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("environment", if (emulator) "emulator" else "physical_device")
            .put("dev_only", emulator)
            .put("runtime_version", BuildConfig.VERSION_NAME)
            .put("agent_runtime", "@mariozechner/pi-agent-core@0.73.1")
            .put("runtime_carrier", "quickjs-kt@1.0.12")
            .put("model", model)
            .put("model_endpoint_host", modelEndpointHost)
            .put("backend", "android_accessibility_foreground")
            .put("task_id", taskId)
            .put("benchmark_cohort", benchmarkCohort)
            .put("attempt", attempt)
            .put("prompt", prompt)
            .put("status", status)
            .put("duration_ms", metrics.durationMs())
            .put("agent_turns", metrics.agentTurns)
            .put("model_calls", metrics.modelCalls)
            .put("tool_calls", metrics.toolCalls)
            .put("manual_takeovers", metrics.approvalInteractions)
            .put("foreground_interrupt_ms", metrics.foregroundInterruptMs)
            .put("crash", crash)
            .put("login_state_before", metrics.loginStateBefore?.wireValue ?: defaultLoginState)
            .put("login_state_after", metrics.loginStateAfter?.wireValue ?: defaultLoginState)
            .put("login_lost", metrics.loginLost ?: JSONObject.NULL)
            .put("permission_lost", !AccessibilityBridge.isEnabled(context))
            .put("observation_failures", metrics.observationFailures)
            .put("action_failures", metrics.actionFailures)
            .put("target_package", targetPackage ?: JSONObject.NULL)
            .put("app_version", targetVersion ?: JSONObject.NULL)
            .put("result", result)
            .put("evidence", JSONArray(metrics.evidence))
            .put("failure_code", failureCode ?: JSONObject.NULL)
            .put("failure_stage", failureStage ?: JSONObject.NULL)
            .put("notes", notes ?: JSONObject.NULL)
    }

    private fun writePending() {
        val record = buildRecord(
            status = "failed",
            failureCode = "RUN_INTERRUPTED",
            failureStage = "process",
            notes = "Run started but did not reach a terminal recorder write; counters may be incomplete.",
            crash = true,
        )
        val atomic = AtomicFile(pendingFile(context))
        val stream = atomic.startWrite()
        try {
            stream.write(record.toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun parseResult(answer: String?): Any {
        if (answer.isNullOrBlank()) return JSONObject.NULL
        return runCatching { JSONObject(answer) }.getOrElse {
            runCatching { JSONArray(answer) }.getOrElse { JSONObject().put("raw_text", answer) }
        }
    }

    private fun validateResult(result: Any): Validation {
        if (taskId == "ad_hoc") return Validation("completed_unverified")
        val json = result as? JSONObject
            ?: return Validation("failed", "TASK_RESULT_INVALID", "final 不是 JSON object")
        if (taskId == "C1") return validateChromeSearchResult(json)
        if (taskId == "W1") {
            if (metrics.loginStateBefore != LoginState.SIGNED_IN) {
                return Validation("failed", "LOGIN_STATE_UNVERIFIED", "未观察到微信测试账号的高置信已登录界面")
            }
            if (metrics.loginLost == true) {
                return Validation("failed", "LOGIN_LOST", "任务期间观察到微信从已登录主界面转为登录/注册页")
            }
            if (metrics.loginLost != false) {
                return Validation("failed", "LOGIN_PERSISTENCE_UNVERIFIED", "需要至少两次高置信已登录观察才能验证登录态保持")
            }
        }
        val valid = when (taskId) {
            "L1" -> json.optString("visible_title").isNotBlank() &&
                metrics.lastExternalPackage == "com.google.android.deskclock"
            "M1" -> hasMinimumObjects(json.optJSONArray("items"), 10, "name")
            "X1" -> hasMinimumObjects(json.optJSONArray("items"), 10, "title", "author") &&
                (json.optJSONArray("details")?.length() ?: 0) >= 3
            "D1" -> hasMinimumObjects(json.optJSONArray("items"), 10, "author")
            "W1" -> hasMinimumObjects(json.optJSONArray("messages"), 5, "direction", "text")
            else -> false
        }
        return if (valid) Validation("success")
        else Validation("failed", "TASK_RESULT_INVALID", "未达到 $taskId 的本地最小结构或数量门槛")
    }

    private fun validateChromeSearchResult(json: JSONObject): Validation {
        val detail = json.optJSONObject("detail")
        val summary = detail?.optString("summary").orEmpty()
        val reportsReadFailure = listOf(
            "no internet", "未能读取", "无法读取", "无法加载", "未加载", "连接失败",
        ).any(summary.lowercase()::contains)
        return when {
            json.optString("query").isBlank() ||
                !hasMinimumObjects(json.optJSONArray("items"), 5, "title", "snippet") ||
                detail?.optString("title")?.isNullOrBlank() != false || summary.isBlank() ||
                !json.optBoolean("scrolled") ->
                Validation("failed", "TASK_RESULT_INVALID", "C1 结构化结果缺少必需字段或数量")
            reportsReadFailure ->
                Validation("failed", "C1_DETAIL_READ_FAILED", "详情摘要明确报告网络或读取失败")
            metrics.lastExternalPackage != "com.android.chrome" ->
                Validation("failed", "C1_TARGET_MISMATCH", "最终目标 App 不是 Chrome")
            "click" !in metrics.deviceActActions ||
                !("input" in metrics.deviceActActions || metrics.successfulSearchInvoke) ->
                Validation("failed", "C1_SEARCH_EVIDENCE_MISSING", "没有成功地址栏输入或带查询参数的 open_url 证据")
            metrics.deviceActActions.none { it == "scroll" || it == "swipe" } ->
                Validation("failed", "C1_SCROLL_MISSING", "没有成功 scroll/swipe 证据")
            metrics.lastSuccessfulScrollVisibleTextCount < 8 || metrics.lastSuccessfulScrollHadNetworkError ->
                Validation("failed", "C1_DETAIL_CONTENT_MISSING", "滚动后的详情页正文不足或存在阻断性网络错误")
            else -> Validation("success")
        }
    }

    private fun hasMinimumObjects(array: JSONArray?, minimum: Int, vararg required: String): Boolean {
        if (array == null || array.length() < minimum) return false
        return (0 until array.length()).all { index ->
            val item = array.optJSONObject(index) ?: return@all false
            required.all { key -> item.optString(key).isNotBlank() }
        }
    }

    private data class Validation(
        val status: String,
        val failureCode: String? = null,
        val notes: String? = null,
    )

    companion object {
        private val TASK_PATTERN = Regex("^\\[BENCH:([A-Z][0-9]+)]")
        private val COHORT_PATTERN = Regex("\\[COHORT:([A-Za-z0-9._-]{1,64})]")

        fun start(
            context: Context,
            prompt: String,
            metrics: PiRunMetrics,
            model: String,
            modelEndpointHost: String,
        ): BenchmarkRunStore {
            val appContext = context.applicationContext
            reconcileInterrupted(appContext)
            return BenchmarkRunStore(appContext, prompt, metrics, model, modelEndpointHost)
        }

        fun file(context: Context) = File(File(context.filesDir, "benchmarks"), "runs.jsonl")

        @Synchronized
        fun reconcileInterrupted(context: Context) {
            try {
                val pending = pendingFile(context)
                if (!pending.exists()) return
                val record = runCatching { JSONObject(pending.readText()) }.getOrNull()
                if (record == null) {
                    pending.delete()
                    return
                }
                val runId = record.optString("run_id")
                val runs = file(context)
                val alreadyFinalized = runs.exists() && runs.useLines { lines ->
                    lines.any { line -> line.contains("\"run_id\":\"$runId\"") }
                }
                if (!alreadyFinalized) {
                    val elapsed = runCatching {
                        java.time.Duration.between(Instant.parse(record.getString("started_at")), Instant.now()).toMillis()
                    }.getOrDefault(0L).coerceAtLeast(0L)
                    record.put("duration_ms", elapsed)
                        .put("status", "failed")
                        .put("crash", true)
                        .put("permission_lost", !AccessibilityBridge.isEnabled(context))
                        .put("failure_code", "RUN_INTERRUPTED")
                        .put("failure_stage", "process")
                        .put("notes", "Recovered an unfinished run after process termination; counters may be incomplete.")
                    appendRecord(context, record)
                }
                pending.delete()
            } catch (error: Throwable) {
                Log.e("MobileAgentBenchmark", "pending reconciliation failed", error)
            }
        }

        private fun appendRecord(context: Context, record: JSONObject) {
            val directory = File(context.filesDir, "benchmarks").apply { mkdirs() }
            File(directory, "runs.jsonl").appendText(record.toString() + "\n")
        }

        private fun pendingFile(context: Context): File {
            val directory = File(context.filesDir, "benchmarks").apply { mkdirs() }
            return File(directory, "pending-run.json")
        }

        private fun clearPending(context: Context, runId: String) {
            val pending = pendingFile(context)
            val pendingRunId = runCatching { JSONObject(pending.readText()).optString("run_id") }.getOrNull()
            if (pendingRunId == runId) pending.delete()
        }

        private fun nextAttempt(context: Context, taskId: String): Int {
            val preferences = context.getSharedPreferences("benchmark-attempts", Context.MODE_PRIVATE)
            val next = preferences.getInt(taskId, 0) + 1
            preferences.edit().putInt(taskId, next).apply()
            return next
        }

        private fun isEmulator(): Boolean =
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("sdk_gphone", ignoreCase = true)
    }
}
