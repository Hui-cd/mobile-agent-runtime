package ai.mobileagent.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
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

object AgentSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AgentState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var approvalResult: CompletableDeferred<Boolean>? = null

    fun start(context: Context, prompt: String) {
        if (_state.value.running || prompt.isBlank()) return
        val appContext = context.applicationContext
        val key = ApiKeyStore(appContext).load()
        if (key.isNullOrBlank()) {
            add(MessageRole.ERROR, "请先保存模型 API Key。")
            return
        }
        ContextCompat.startForegroundService(appContext, Intent(appContext, AgentForegroundService::class.java))
        val metrics = PiRunMetrics()
        val endpoint = ModelEndpointStore(appContext).load()
        val benchmarkRun = BenchmarkRunStore.start(appContext, prompt, metrics, endpoint.model, endpoint.host)
        val benchmarkMode = prompt.startsWith("[BENCH:")
        val deviceStateContract = "<device_state_contract>工具返回 DEVICE_LOCKED 或 SCREEN_NOT_INTERACTIVE 时，当前 GUI 任务不能继续；不要重试设备工具。普通任务请提示用户解锁/点亮屏幕；benchmark 只输出所需 JSON，无法读取的字段填 null。</device_state_contract>"
        val runtimePrompt = if (benchmarkMode) "$prompt\n\n<benchmark_contract>最终只输出合法 JSON，不要 Markdown，不要代码围栏，不要解释执行过程；第一个字符必须是 {，最后一个字符必须是 }，前后不得有其他文字；字段和数量严格遵守任务中给出的结构。</benchmark_contract>\n$deviceStateContract"
        else "$prompt\n\n$deviceStateContract"
        _state.value = _state.value.copy(running = true, status = AgentStatus.THINKING, error = null, currentStep = "正在读取当前页面")
        add(MessageRole.USER, prompt)
        job = scope.launch {
            try {
                val transcriptStore = appContext.getSharedPreferences("pi-agent-session", Context.MODE_PRIVATE)
                val result = QuickJsPiAgentRunner.run(
                    context = appContext,
                    client = OpenAICompatibleClient(key, endpoint, appContext),
                    runtime = AndroidDeviceRuntime(appContext),
                    prompt = runtimePrompt,
                    messagesJson = if (benchmarkMode) null else transcriptStore.getString("messages", null),
                    metrics = metrics,
                    onStep = { step ->
                        _state.value = _state.value.copy(status = AgentStatus.ACTING, currentStep = step)
                        add(MessageRole.STATUS, step)
                    },
                    requestApproval = { description -> requestApproval(description) },
                )
                val answer = result.finalText
                if (!benchmarkMode) transcriptStore.edit().putString("messages", result.messagesJson).apply()
                runCatching { benchmarkRun.complete(answer) }
                Log.i("MobileAgent", "Agent request completed; answerLength=${answer.length}")
                add(MessageRole.AGENT, answer)
                _state.value = _state.value.copy(
                    running = false,
                    status = AgentStatus.COMPLETE,
                    currentStep = "任务完成",
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
                add(MessageRole.ERROR, error.message ?: "任务失败")
                _state.value = _state.value.copy(
                    running = false,
                    status = AgentStatus.ERROR,
                    error = error.message,
                    currentStep = "任务失败",
                    approval = null,
                )
            }
        }
    }

    private suspend fun requestApproval(description: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approvalResult = deferred
        _state.value = _state.value.copy(status = AgentStatus.WAITING_APPROVAL, approval = ApprovalRequest(description = description), currentStep = "等待用户确认")
        return deferred.await().also {
            approvalResult = null
            _state.value = _state.value.copy(approval = null, status = AgentStatus.ACTING)
        }
    }

    fun resolveApproval(approved: Boolean) { approvalResult?.complete(approved) }

    fun stop() {
        approvalResult?.complete(false)
        QuickJsPiAgentRunner.cancel()
        job?.cancel()
    }

    private fun add(role: MessageRole, text: String) {
        _state.value = _state.value.copy(messages = _state.value.messages + ChatMessage(role = role, text = text))
    }
}
