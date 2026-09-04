package ai.mobileagent.pi

import android.content.Context
import android.util.Log
import ai.mobileagent.agent.OpenAICompatibleClient
import ai.mobileagent.model.ToolExecution
import ai.mobileagent.runtime.AndroidDeviceRuntime
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

object QuickJsPiAgentRunner {
    private const val ASSET_NAME = "pi-mobile-quickjs-runtime.js"
    private const val MEMORY_LIMIT_BYTES = 128L * 1024L * 1024L
    private const val STACK_LIMIT_BYTES = 1024L * 1024L
    private const val BUNDLE_EVALUATION_TIMEOUT_MS = 60_000L
    private const val TASK_TIMEOUT_MS = 15L * 60L * 1_000L
    private const val TAG = "MobileAgentQuickJs"

    private data class ActiveRun(
        val client: OpenAICompatibleClient,
        val runtime: AndroidDeviceRuntime,
        val onStep: suspend (String) -> Unit,
        val requestApproval: suspend (String) -> Boolean,
        val metrics: PiRunMetrics,
        var nativeRequestJob: Job? = null,
        var quickJs: QuickJs? = null,
    )

    @Volatile
    private var activeRun: ActiveRun? = null

    suspend fun run(
        context: Context,
        client: OpenAICompatibleClient,
        runtime: AndroidDeviceRuntime,
        prompt: String,
        messagesJson: String?,
        metrics: PiRunMetrics,
        onStep: suspend (String) -> Unit,
        requestApproval: suspend (String) -> Boolean,
    ): PiAgentResult {
        check(activeRun == null) { "PI_AGENT_ALREADY_RUNNING" }
        val run = ActiveRun(client, runtime, onStep, requestApproval, metrics)
        activeRun = run
        val source = context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.IO)
        run.quickJs = quickJs
        try {
            quickJs.memoryLimit = MEMORY_LIMIT_BYTES
            quickJs.maxStackSize = STACK_LIMIT_BYTES
            quickJs.evaluationTimeoutMillis = BUNDLE_EVALUATION_TIMEOUT_MS
            quickJs.asyncFunction<String, String>("mobileNativeCall") { encoded ->
                handleNativeCall(encoded, run)
            }
            metrics.failureStage = "runtime:quickjs"
            quickJs.evaluate<Any?>(source, filename = ASSET_NAME)
            // Instance evaluation timeouts also interrupt after a long native async
            // await. Bound the complete task with coroutine cancellation instead.
            quickJs.evaluationTimeoutMillis = 0L
            val input = JSONObject()
                .put("prompt", prompt)
                .put("platform", "android")
            messagesJson?.takeIf(String::isNotBlank)?.let { input.put("messages", JSONArray(it)) }
            Log.i(TAG, "starting Pi prompt; restored=${messagesJson != null}")
            val encoded = withTimeout(TASK_TIMEOUT_MS) {
                quickJs.evaluate<String>(
                    "JSON.stringify(await globalThis.PiMobileQuickJsRuntime.run($input))",
                    filename = "run-agent.js",
                )
            }
            return parseAgentResult(encoded, run)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: QuickJsInterruptedException) {
            metrics.failureStage = "runtime:quickjs"
            error("PI_RUNTIME_INTERRUPTED:${interrupted.message}")
        } catch (scriptError: QuickJsException) {
            val firstLine = scriptError.message?.lineSequence()?.firstOrNull().orEmpty()
            val nativeCode = Regex("^Error: ([A-Z][A-Z0-9_]+)(?::|$)")
                .find(firstLine)?.groupValues?.get(1)
            if (nativeCode != null) error("$nativeCode:$firstLine")
            metrics.failureStage = "runtime:quickjs"
            error("PI_RUNTIME_ERROR:$firstLine")
        } finally {
            if (activeRun === run) activeRun = null
            run.quickJs = null
            quickJs.close()
        }
    }

    fun cancel() {
        activeRun?.let { run ->
            run.client.cancel()
            run.nativeRequestJob?.cancel(CancellationException("USER_CANCELLED"))
            run.quickJs?.interruptEvaluation()
        }
    }

    private suspend fun handleNativeCall(encoded: String, run: ActiveRun): String {
        val request = runCatching { JSONObject(encoded) }.getOrElse {
            return errorResponse("unknown", "NATIVE_REQUEST_INVALID").toString()
        }
        val id = request.optString("id", "unknown")
        Log.i(TAG, "native request method=${request.optString("method")}")
        val response = when (request.optString("method")) {
            "model_complete" -> handleModelCompletion(id, request.optJSONObject("params") ?: JSONObject(), run)
            "tool_execute" -> handleToolExecution(id, request, run)
            "runtime_event", "agent_complete" -> successResponse(id, JSONObject().put("accepted", true))
            else -> errorResponse(id, "NATIVE_METHOD_UNSUPPORTED")
        }
        return response.toString()
    }

    private suspend fun handleModelCompletion(
        id: String,
        params: JSONObject,
        run: ActiveRun,
    ): JSONObject {
        run.metrics.failureStage = "model"
        run.metrics.modelCalls += 1
        val requestJob = currentCoroutineContext().job
        run.nativeRequestJob = requestJob
        return try {
            val response = run.client.complete(
                params.optJSONArray("messages") ?: JSONArray(),
                params.optJSONArray("tools") ?: JSONArray(),
            )
            run.metrics.failureStage = null
            successResponse(id, response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            errorResponse(id, error.message ?: "MODEL_COMPLETE_FAILED")
        } finally {
            if (run.nativeRequestJob === requestJob) run.nativeRequestJob = null
        }
    }

    private suspend fun handleToolExecution(id: String, request: JSONObject, run: ActiveRun): JSONObject {
        val params = request.optJSONObject("params") ?: return errorResponse(id, "TOOL_PARAMS_MISSING")
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val label = stepLabel(name, arguments)
        run.metrics.failureStage = "tool:$name"
        run.onStep(label)
        val needsApproval = requiresApproval(name, arguments)
        if (needsApproval) run.metrics.approvalInteractions += 1
        val execution = if (needsApproval && !run.requestApproval(label)) {
            ToolExecution(JSONObject().put("error", "USER_DENIED").toString())
        } else runCatching { run.runtime.execute(name, arguments) }
            .getOrElse { ToolExecution(JSONObject().put("error", it.message ?: "TOOL_FAILED").toString()) }
        run.metrics.recordTool(name, arguments, execution)
        run.metrics.failureStage = null
        return successResponse(
            id,
            JSONObject()
                .put("json", execution.json)
                .put("isError", JSONObject(execution.json).has("error"))
                .apply { execution.screenshotDataUrl?.let { put("screenshotDataUrl", it) } },
        )
    }

    private fun parseAgentResult(encoded: String, run: ActiveRun): PiAgentResult {
        val result = JSONObject(encoded)
        val messages = result.optJSONArray("messages") ?: JSONArray()
        val events = result.optJSONArray("eventTypes") ?: JSONArray()
        run.metrics.agentTurns = (0 until events.length()).count { events.optString(it) == "turn_end" }
        val failedToolResults = (0 until messages.length())
            .mapNotNull(messages::optJSONObject)
            .filter { it.optString("role") == "toolResult" && it.optBoolean("isError") }
        run.metrics.observationFailures = maxOf(
            run.metrics.observationFailures,
            failedToolResults.count { it.optString("toolName") == "device_observe" },
        )
        run.metrics.actionFailures = maxOf(
            run.metrics.actionFailures,
            failedToolResults.count { it.optString("toolName") != "device_observe" },
        )
        run.metrics.finishForegroundInterval()
        run.metrics.failureStage = null
        Log.i(TAG, "Pi prompt completed; messages=${messages.length()}")
        return PiAgentResult(result.optString("finalText", "任务已完成。"), messages.toString())
    }

    private fun stepLabel(name: String, args: JSONObject): String = when (name) {
        "device_observe" -> "正在观察当前界面"
        "device_act" -> "正在执行 ${args.optString("action")}${args.optJSONObject("target")?.optString("text")?.takeIf(String::isNotBlank)?.let { "：$it" } ?: ""}"
        "device_invoke" -> "正在调用 ${args.optString("capability")}"
        else -> "正在执行 $name"
    }

    private fun requiresApproval(name: String, args: JSONObject): Boolean {
        if (name == "device_invoke" && args.optString("capability") in setOf("dial", "share")) return true
        val text = args.optJSONObject("target")?.optString("text").orEmpty()
        return listOf("支付", "购买", "下单", "发送", "删除", "提交订单", "确认付款").any(text::contains)
    }

    private fun successResponse(id: String, result: Any): JSONObject = JSONObject()
        .put("id", id)
        .put("result", result)

    private fun errorResponse(id: String, error: String): JSONObject = JSONObject()
        .put("id", id)
        .put("error", error)
}
