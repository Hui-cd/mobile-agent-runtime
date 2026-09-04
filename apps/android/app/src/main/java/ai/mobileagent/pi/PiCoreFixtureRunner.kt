package ai.mobileagent.pi

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import ai.mobileagent.BuildConfig
import ai.mobileagent.agent.OpenAICompatibleClient
import ai.mobileagent.benchmark.LoginState
import ai.mobileagent.benchmark.LoginStateTracker
import ai.mobileagent.model.ToolExecution
import ai.mobileagent.runtime.AndroidDeviceRuntime
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.security.MessageDigest

data class PiCoreFixtureState(
    val status: Status = Status.IDLE,
    val detail: String = "未运行",
) {
    enum class Status { IDLE, RUNNING, PASSED, FAILED }
}

data class PiAgentResult(val finalText: String, val messagesJson: String)

class PiRunMetrics {
    private val startedElapsedMs = SystemClock.elapsedRealtime()
    private var externalForegroundStartedMs: Long? = null
    var modelCalls = 0
    var toolCalls = 0
    var agentTurns = 0
    var approvalInteractions = 0
    var foregroundInterruptMs = 0L
    var observationFailures = 0
    var actionFailures = 0
    var lastExternalPackage: String? = null
    var lastToolErrorCode: String? = null
    var lastToolFailureStage: String? = null
    var lastToolWasError = false
    var failureStage: String? = null
    val deviceActActions = mutableSetOf<String>()
    var successfulSearchInvoke = false
    var lastSuccessfulScrollVisibleTextCount = 0
    var lastSuccessfulScrollHadNetworkError = false
    val evidence = mutableListOf<JSONObject>()
    private val loginStateTracker = LoginStateTracker()
    val loginStateBefore: LoginState? get() = loginStateTracker.first
    val loginStateAfter: LoginState? get() = loginStateTracker.last
    val loginLost: Boolean? get() = loginStateTracker.loginLost

    fun durationMs(): Long = SystemClock.elapsedRealtime() - startedElapsedMs

    fun recordTool(name: String, arguments: JSONObject, execution: ToolExecution) {
        toolCalls += 1
        val action = arguments.optString("action").takeIf { name == "device_act" && it.isNotBlank() }
        val capability = arguments.optString("capability").takeIf { name == "device_invoke" && it.isNotBlank() }
        val invokeUrl = arguments.optJSONObject("params")?.optString("url")
            ?.takeIf { capability == "open_url" && it.isNotBlank() }
        val targetUri = invokeUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val searchQueryPresent = targetUri?.let { uri ->
            runCatching {
                uri.queryParameterNames.any { it.lowercase() in setOf("q", "query", "keyword", "wd") }
            }.getOrDefault(false)
        } ?: false
        val parsed = runCatching { JSONObject(execution.json) }.getOrNull()
        val failed = parsed?.has("error") == true
        val visibleTexts = parsed?.optJSONArray("ui_tree")?.let { tree ->
            (0 until tree.length()).mapNotNull { index ->
                tree.optJSONObject(index)?.let { node ->
                    node.optString("text").ifBlank { node.optString("content_description") }.takeIf(String::isNotBlank)
                }
            }
        }.orEmpty()
        val distinctVisibleTexts = visibleTexts.distinct()
        val blockingNetworkErrorVisible = visibleTexts.any { text ->
            val normalized = text.lowercase()
            listOf("err_", "webpage not available", "无法访问此网页", "网页无法打开").any(normalized::contains)
        }
        val connectivityBannerVisible = visibleTexts.any { it.lowercase().contains("no internet connection") }
        val networkErrorVisible = blockingNetworkErrorVisible ||
            (connectivityBannerVisible && distinctVisibleTexts.size < 8)
        if (!failed) {
            action?.let(deviceActActions::add)
            if (capability == "open_url" && searchQueryPresent) successfulSearchInvoke = true
            if (action == "scroll" || action == "swipe") {
                lastSuccessfulScrollVisibleTextCount = distinctVisibleTexts.size
                lastSuccessfulScrollHadNetworkError = networkErrorVisible
            }
        }
        val errorCode = parsed?.optString("error")?.takeIf(String::isNotBlank)?.substringBefore(':')
        lastToolWasError = failed
        if (failed) {
            lastToolErrorCode = errorCode
            lastToolFailureStage = "tool:$name"
        }
        if (failed && name == "device_observe") observationFailures += 1
        if (failed && name != "device_observe") actionFailures += 1
        val currentPackage = parsed?.optString("current_app")?.takeIf { it.isNotBlank() && it != "null" }
        val loginState = loginStateTracker.record(currentPackage, distinctVisibleTexts)
        if (currentPackage != null && currentPackage != "ai.mobileagent") {
            lastExternalPackage = currentPackage
            if (externalForegroundStartedMs == null) externalForegroundStartedMs = SystemClock.elapsedRealtime()
        } else if (currentPackage == "ai.mobileagent") {
            finishForegroundInterval()
        }
        evidence += JSONObject()
            .put("observed_at", System.currentTimeMillis())
            .put("tool", name)
            .put("result_sha256", sha256(execution.json))
            .put("screenshot_sha256", execution.screenshotDataUrl?.let(::sha256) ?: JSONObject.NULL)
            .put("current_app", currentPackage ?: JSONObject.NULL)
            .put("action", action ?: JSONObject.NULL)
            .put("capability", capability ?: JSONObject.NULL)
            .put("target_host", targetUri?.host ?: JSONObject.NULL)
            .put("target_reference_sha256", invokeUrl?.let(::sha256) ?: JSONObject.NULL)
            .put("search_query_present", searchQueryPresent)
            .put("visible_text_count", distinctVisibleTexts.size)
            .put("network_error_visible", networkErrorVisible)
            .put("login_state", loginState.wireValue)
            .put("error_code", errorCode ?: JSONObject.NULL)
            .put("is_error", failed)
    }

    fun finishForegroundInterval() {
        externalForegroundStartedMs?.let { foregroundInterruptMs += SystemClock.elapsedRealtime() - it }
        externalForegroundStartedMs = null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

object PiCoreFixtureRunner {
    private const val ORIGIN = "https://mobile-agent.local"
    private const val PAGE_URL = "$ORIGIN/runtime"
    private const val BRIDGE_NAME = "MobileAgentNative"
    private const val ASSET_NAME = "pi-mobile-runtime.js"

    private val _state = MutableStateFlow(PiCoreFixtureState())
    val state = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activityRef: WeakReference<Activity>? = null
    private var webViewRef: WeakReference<WebView>? = null
    private var activeRun: ActiveRun? = null

    private data class ActiveRun(
        val client: OpenAICompatibleClient,
        val runtime: AndroidDeviceRuntime,
        val onStep: suspend (String) -> Unit,
        val requestApproval: suspend (String) -> Boolean,
        val result: CompletableDeferred<PiAgentResult>,
        val metrics: PiRunMetrics,
        var nativeRequestJob: Job? = null,
    )

    fun start(activity: Activity) {
        activity.runOnUiThread {
            val owner = activityRef?.get()
            val webView = webViewRef?.get()
            if (owner === activity && webView != null &&
                _state.value.status in setOf(PiCoreFixtureState.Status.RUNNING, PiCoreFixtureState.Status.PASSED)
            ) return@runOnUiThread
            if (owner !== activity) disposeRuntime("PI_RUNTIME_ACTIVITY_RECREATED")
            startOnMainThread(activity)
        }
    }

    private fun startOnMainThread(activity: Activity) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            fail("WEB_MESSAGE_LISTENER_UNAVAILABLE")
            return
        }
        webViewRef?.get()?.let { old ->
            (old.parent as? android.view.ViewGroup)?.removeView(old)
            old.destroy()
        }
        activityRef = WeakReference(activity)
        webViewRef = null
        _state.value = PiCoreFixtureState(PiCoreFixtureState.Status.RUNNING, "Pi core 正在调用原生工具")

        val webView = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.domStorageEnabled = false
            settings.setSupportMultipleWindows(false)
        }
        webViewRef = WeakReference(webView)
        activity.addContentView(webView, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))

        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            setOf(ORIGIN),
        ) { view, message, sourceOrigin, isMainFrame, replyProxy ->
            scope.launch {
                val response = handleMessage(message, sourceOrigin, isMainFrame)
                replyProxy.postMessage(response.toString())
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url != PAGE_URL) return
                val bundle = activity.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                if (BuildConfig.DEBUG) {
                    view.evaluateJavascript("$bundle\n;window.PiMobileRuntime.run({prompt:'观察设备',platform:'android'});", null)
                } else {
                    view.evaluateJavascript(bundle, null)
                    _state.value = PiCoreFixtureState(PiCoreFixtureState.Status.PASSED, "Pi runtime 已就绪")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.toString() != PAGE_URL

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val reason = "WEBVIEW_RENDERER_GONE:${detail.didCrash()}"
                activeRun?.let { run ->
                    run.metrics.failureStage = "runtime:webview_renderer"
                    run.client.cancel()
                    run.nativeRequestJob?.cancel(CancellationException(reason))
                    run.result.completeExceptionally(IllegalStateException(reason))
                }
                activeRun = null
                activityRef = null
                webViewRef = null
                fail(reason)
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                scope.launch {
                    delay(250)
                    if (!activity.isFinishing && !activity.isDestroyed) startOnMainThread(activity)
                }
                return true
            }
        }
        webView.loadDataWithBaseURL(
            PAGE_URL,
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
            "text/html",
            "UTF-8",
            null,
        )
    }

    suspend fun run(
        client: OpenAICompatibleClient,
        runtime: AndroidDeviceRuntime,
        prompt: String,
        messagesJson: String?,
        metrics: PiRunMetrics,
        onStep: suspend (String) -> Unit,
        requestApproval: suspend (String) -> Boolean,
    ): PiAgentResult {
        var attempts = 0
        while (_state.value.status != PiCoreFixtureState.Status.PASSED && attempts++ < 100) {
            if (_state.value.status == PiCoreFixtureState.Status.FAILED) error("PI_RUNTIME_UNAVAILABLE:${_state.value.detail}")
            delay(50)
        }
        val webView = webViewRef?.get() ?: error("PI_RUNTIME_WEBVIEW_UNAVAILABLE")
        if (_state.value.status != PiCoreFixtureState.Status.PASSED) error("PI_RUNTIME_NOT_READY")
        if (activeRun != null) error("PI_AGENT_ALREADY_RUNNING")
        val deferred = CompletableDeferred<PiAgentResult>()
        activeRun = ActiveRun(client, runtime, onStep, requestApproval, deferred, metrics)
        val input = JSONObject()
            .put("prompt", prompt)
            .put("platform", "android")
        messagesJson?.takeIf(String::isNotBlank)?.let { input.put("messages", JSONArray(it)) }
        withContext(Dispatchers.Main.immediate) {
            Log.i("MobileAgentPi", "starting Pi prompt; restored=${messagesJson != null}")
            webView.evaluateJavascript("window.PiMobileRuntime.run(${input});", null)
        }
        return try {
            deferred.await()
        } finally {
            if (activeRun?.result === deferred) activeRun = null
        }
    }

    fun cancel() {
        activeRun?.client?.cancel()
        activeRun?.nativeRequestJob?.cancel(CancellationException("USER_CANCELLED"))
        webViewRef?.get()?.post { webViewRef?.get()?.evaluateJavascript("window.PiMobileRuntime.cancel();", null) }
    }

    fun terminateRendererForTest(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val webView = webViewRef?.get() ?: return false
        return webView.webViewRenderProcess?.terminate() == true
    }

    private suspend fun handleMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
    ): JSONObject {
        val request = runCatching { JSONObject(message.data ?: "") }.getOrElse {
            fail("NATIVE_REQUEST_INVALID")
            return errorResponse("unknown", "NATIVE_REQUEST_INVALID")
        }
        val id = request.optString("id", "unknown")
        Log.i("MobileAgentPi", "native request method=${request.optString("method")} active=${activeRun != null}")
        if (!isMainFrame || sourceOrigin.toString() != ORIGIN) {
            fail("NATIVE_REQUEST_ORIGIN_REJECTED")
            return errorResponse(id, "NATIVE_REQUEST_ORIGIN_REJECTED")
        }

        return when (request.optString("method")) {
            "device_observe" -> successResponse(id, JSONObject()
                .put("platform", "android")
                .put("bridge", "androidx.webkit.WebMessageListener"))
            "model_complete" -> activeRun?.let { run ->
                run.metrics.failureStage = "model"
                run.metrics.modelCalls += 1
                val requestJob = currentCoroutineContext().job
                run.nativeRequestJob = requestJob
                try {
                    val response = run.client.complete(
                        request.optJSONObject("params")?.optJSONArray("messages") ?: JSONArray(),
                        request.optJSONObject("params")?.optJSONArray("tools") ?: JSONArray(),
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
            } ?: handleModelCompletion(id, request.optJSONObject("params") ?: JSONObject())
            "tool_execute" -> activeRun?.let { run -> handleToolExecution(id, request, run) }
                ?: successResponse(id, JSONObject().put("json", JSONObject()
                    .put("platform", "android")
                    .put("bridge", "androidx.webkit.WebMessageListener")
                    .toString()))
            "runtime_event" -> activeRun?.let { run ->
                successResponse(id, JSONObject().put("accepted", true))
            } ?: successResponse(id, JSONObject().put("accepted", true))
            "agent_complete" -> activeRun?.let { run -> handleAgentCompletion(id, request, run) }
                ?: handleCompletion(id, request.optJSONObject("params") ?: JSONObject())
            else -> errorResponse(id, "NATIVE_METHOD_UNSUPPORTED")
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
        return successResponse(id, JSONObject()
            .put("json", execution.json)
            .put("isError", JSONObject(execution.json).has("error"))
            .apply { execution.screenshotDataUrl?.let { put("screenshotDataUrl", it) } })
    }

    private fun handleAgentCompletion(id: String, request: JSONObject, run: ActiveRun): JSONObject {
        val params = request.optJSONObject("params") ?: JSONObject()
        params.optString("error").takeIf(String::isNotBlank)?.let {
            run.result.completeExceptionally(IllegalStateException(it))
            return errorResponse(id, it)
        }
        val result = params.optJSONObject("result") ?: run {
            run.result.completeExceptionally(IllegalStateException("PI_AGENT_RESULT_MISSING"))
            return errorResponse(id, "PI_AGENT_RESULT_MISSING")
        }
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
        run.result.complete(PiAgentResult(result.optString("finalText", "任务已完成。"), messages.toString()))
        Log.i("MobileAgentPi", "Pi prompt completed; messages=${messages.length()}")
        return successResponse(id, JSONObject().put("accepted", true))
    }

    private fun handleModelCompletion(id: String, params: JSONObject): JSONObject {
        val messages = params.optJSONArray("messages") ?: return errorResponse(id, "MODEL_MESSAGES_MISSING")
        val hasToolResult = (0 until messages.length()).any { messages.optJSONObject(it)?.optString("role") == "tool" }
        val message = if (hasToolResult) {
            JSONObject().put("content", "Pi mobile runtime complete.")
        } else {
            JSONObject().put("content", JSONObject.NULL).put("tool_calls", org.json.JSONArray().put(
                JSONObject().put("id", "observe-1").put("type", "function").put("function",
                    JSONObject().put("name", "device_observe").put("arguments", "{\"include_screen\":false}")),
            ))
        }
        return successResponse(id, JSONObject().put("choices", org.json.JSONArray().put(
            JSONObject().put("message", message).put("finish_reason", if (hasToolResult) "stop" else "tool_calls"),
        )))
    }

    private fun handleCompletion(id: String, params: JSONObject): JSONObject {
        params.optString("error").takeIf(String::isNotBlank)?.let {
            fail(it)
            return errorResponse(id, it)
        }
        val result = params.optJSONObject("result")
        val roles = result?.optJSONArray("messages")?.let { messages ->
            (0 until messages.length()).map { messages.optJSONObject(it)?.optString("role") }
        }
        val valid = result?.optString("finalText") == "Pi mobile runtime complete." &&
            roles == listOf("user", "assistant", "toolResult", "assistant") &&
            result.optJSONArray("eventTypes")?.optString(result.optJSONArray("eventTypes")!!.length() - 1) == "agent_end"
        if (!valid) {
            fail("PI_CORE_FIXTURE_RESULT_INVALID")
            return errorResponse(id, "PI_CORE_FIXTURE_RESULT_INVALID")
        }
        _state.value = PiCoreFixtureState(PiCoreFixtureState.Status.PASSED, "Pi runtime + Android model/tool bridge 已通过")
        return successResponse(id, JSONObject().put("accepted", true))
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

    private fun fail(detail: String) {
        _state.value = PiCoreFixtureState(PiCoreFixtureState.Status.FAILED, detail)
    }

    private fun disposeRuntime(reason: String) {
        activeRun?.let { run ->
            run.metrics.failureStage = "runtime:activity_recreated"
            run.client.cancel()
            run.nativeRequestJob?.cancel(CancellationException(reason))
            run.result.completeExceptionally(IllegalStateException(reason))
        }
        activeRun = null
        webViewRef?.get()?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        webViewRef = null
        activityRef = null
        _state.value = PiCoreFixtureState(PiCoreFixtureState.Status.IDLE, "等待 Pi runtime 重建")
    }
}
