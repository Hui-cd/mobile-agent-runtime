package ai.mobileagent.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ai.mobileagent.accessibility.AccessibilityBridge
import android.app.KeyguardManager
import android.os.PowerManager
import ai.mobileagent.agent.ModelEndpointStore
import ai.mobileagent.agent.OpenAICompatibleClient
import ai.mobileagent.benchmark.BenchmarkRunStore
import ai.mobileagent.model.AgentState
import ai.mobileagent.model.AgentStatus
import ai.mobileagent.model.ApprovalRequest
import ai.mobileagent.model.ChatMessage
import ai.mobileagent.model.MessageRole
import ai.mobileagent.pi.PiRunMetrics
import ai.mobileagent.pi.QuickJsPiAgentRunner
import ai.mobileagent.runtime.AndroidDeviceRuntime
import ai.mobileagent.security.ApiKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

object AgentSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AgentState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var approvalResult: CompletableDeferred<Boolean>? = null
    // Context follows the visible conversation. Never silently restore a hidden old task.
    private var sessionMessagesJson: String? = null

    fun start(context: Context, prompt: String, freshSession: Boolean = false): Boolean {
        if (_state.value.running || prompt.isBlank()) return false
        val appContext = context.applicationContext
        val key = ApiKeyStore(appContext).load()
        val notReady = UserGuidance.readiness(!key.isNullOrBlank(), AccessibilityBridge.isEnabled(appContext), AgentForegroundService.notificationsAvailable(appContext))
        if (notReady != null) return reject(notReady)
        if (ai.mobileagent.virtualdisplay.HiddenExecutionSession.enabled(appContext)) {
            val readiness = runCatching {
                val running = rikka.shizuku.Shizuku.pingBinder()
                ai.mobileagent.virtualdisplay.ShizukuAccessPolicy.requireConnectable(android.os.Build.VERSION.SDK_INT, running, running && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }.exceptionOrNull()
            if (readiness != null) return reject(UserGuidance.error(readiness.message))
        }
        if (appContext.getSystemService(KeyguardManager::class.java).isDeviceLocked) return reject(UserGuidance.error("DEVICE_LOCKED"))
        if (!appContext.getSystemService(PowerManager::class.java).isInteractive) return reject(UserGuidance.error("SCREEN_NOT_INTERACTIVE"))
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, AgentForegroundService::class.java))
        } catch (error: Exception) {
            Log.e("MobileAgent", "Unable to start task service", error)
            return reject("暂时无法启动任务。请返回 Mobile Agent，检查通知权限后重试。")
        }
        val metrics = PiRunMetrics()
        val endpoint = ModelEndpointStore(appContext).load()
        val benchmarkRun = BenchmarkRunStore.start(appContext, prompt, metrics, endpoint.model, endpoint.host)
        val benchmarkMode = prompt.startsWith("[BENCH:")
        val deviceStateContract = "<device_state_contract>后台模式遇到登录、验证码、安全页面停止并报告，不要尝试切到主屏。工具返回 SHIZUKU_UNAVAILABLE、SHIZUKU_DIED、SHIZUKU_NOT_AUTHORIZED、DEVICE_LOCKED 或 SCREEN_NOT_INTERACTIVE 时，当前 GUI 任务不能继续；不要重试设备工具。普通任务请提示用户解锁/点亮屏幕；benchmark 只输出所需 JSON，无法读取的字段填 null。</device_state_contract>"
        val runtimePrompt = if (benchmarkMode) "$prompt\n\n<benchmark_contract>最终只输出合法 JSON，不要 Markdown，不要代码围栏，不要解释执行过程；第一个字符必须是 {，最后一个字符必须是 }，前后不得有其他文字；字段和数量严格遵守任务中给出的结构。</benchmark_contract>\n$deviceStateContract"
        else "$prompt\n\n$deviceStateContract"
        _state.value = _state.value.copy(running = true, status = AgentStatus.THINKING, error = null, currentStep = "正在读取当前页面")
        add(MessageRole.USER, prompt)
        job = scope.launch {
            val runtime = AndroidDeviceRuntime(appContext)
            try {
                val transcriptStore = appContext.getSharedPreferences("pi-agent-session", Context.MODE_PRIVATE)
                val result = QuickJsPiAgentRunner.run(
                    context = appContext,
                    client = OpenAICompatibleClient(key!!, endpoint, appContext),
                    runtime = runtime,
                    prompt = runtimePrompt,
                    messagesJson = if (benchmarkMode || freshSession) null else sessionMessagesJson,
                    metrics = metrics,
                    onStep = { step ->
                        _state.value = _state.value.copy(status = AgentStatus.ACTING, currentStep = step)
                    },
                    requestApproval = { description -> requestApproval(description) },
                )
                val answer = result.finalText
                if (freshSession) {
                    if (ai.mobileagent.BuildConfig.DEBUG) transcriptStore.edit()
                        .putString("debug-last-prompt", prompt)
                        .putString("debug-last-messages", result.messagesJson).apply()
                } else if (!benchmarkMode) {
                    sessionMessagesJson = result.messagesJson
                    transcriptStore.edit().putString("messages", result.messagesJson).apply()
                }
                runCatching { benchmarkRun.complete(answer, result.evidenceJson) }
                Log.i("MobileAgent", "Agent request completed; answerLength=${answer.length}")
                add(MessageRole.AGENT, answer)
                evidenceSummary(result.evidenceJson)?.let { add(MessageRole.STATUS, it) }
                _state.value = _state.value.copy(
                    running = false,
                    status = AgentStatus.COMPLETE,
                    currentStep = "已返回结果",
                    approval = null,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                runCatching { benchmarkRun.cancel() }
                add(MessageRole.STATUS, "任务已停止")
                _state.value = _state.value.copy(
                    running = false,
                    status = AgentStatus.CANCELLED,
                    currentStep = "任务已停止",
                    approval = null,
                    error = null,
                )
            } catch (error: Throwable) {
                runCatching { benchmarkRun.fail(error) }
                Log.e("MobileAgent", "Agent request failed", error)
                val guidance = UserGuidance.error(error.message)
                add(MessageRole.ERROR, guidance)
                _state.value = _state.value.copy(
                    running = false,
                    status = AgentStatus.ERROR,
                    error = guidance,
                    currentStep = "任务失败",
                    approval = null,
                )
            } finally {
                runtime.close()
            }
        }
        return true
    }

    private fun reject(message: String): Boolean {
        add(MessageRole.ERROR, message)
        _state.value = _state.value.copy(status = AgentStatus.ERROR, error = message, currentStep = "需要处理")
        return false
    }

    private suspend fun requestApproval(approval: ApprovalRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approvalResult = deferred
        _state.value = _state.value.copy(status = AgentStatus.WAITING_APPROVAL, approval = approval, currentStep = "等待用户确认")
        return deferred.await().also {
            approvalResult = null
            _state.value = _state.value.copy(approval = null, status = AgentStatus.ACTING)
        }
    }

    /** 把最终声明与最后一次 Observation/ToolResult 绑定成一条用户可见的完成依据。 */
    private fun evidenceSummary(evidenceJson: String?): String? {
        val evidence = evidenceJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val observation = evidence.optJSONObject("last_observation")
        val app = observation?.optString("current_app")?.takeIf { it.isNotBlank() && it != "null" }
        val bound = evidence.optBoolean("claim_follows_tool_evidence")
        return if (bound && app != null) "结果依据：已读取操作后的页面。请核对上面的结果是否满足你的目标。"
        else "结果尚未获得最新页面验证，请核对后再继续。"
    }

    fun resolveApproval(approved: Boolean) { approvalResult?.complete(approved) }

    fun newTask(): Boolean {
        if (_state.value.running) return false
        sessionMessagesJson = null
        _state.value = AgentState()
        return true
    }

    fun stop() {
        approvalResult?.complete(false)
        QuickJsPiAgentRunner.cancel()
        job?.cancel()
    }

    private fun add(role: MessageRole, text: String) {
        _state.value = _state.value.copy(messages = _state.value.messages + ChatMessage(role = role, text = text))
    }
}
