package ai.mobileagent.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ai.mobileagent.agent.AgentEngine
import ai.mobileagent.agent.KimiClient
import ai.mobileagent.model.AgentState
import ai.mobileagent.model.AgentStatus
import ai.mobileagent.model.ApprovalRequest
import ai.mobileagent.model.ChatMessage
import ai.mobileagent.model.MessageRole
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
            add(MessageRole.ERROR, "请先保存 Kimi API Key。")
            return
        }
        val conversationContext = _state.value.messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.AGENT }
            .takeLast(12)
        ContextCompat.startForegroundService(appContext, Intent(appContext, AgentForegroundService::class.java))
        _state.value = _state.value.copy(running = true, status = AgentStatus.THINKING, error = null, currentStep = "正在读取当前页面")
        add(MessageRole.USER, prompt)
        job = scope.launch {
            try {
                val engine = AgentEngine(
                    client = KimiClient(key),
                    runtime = AndroidDeviceRuntime(appContext),
                    onStep = { step ->
                        _state.value = _state.value.copy(status = AgentStatus.ACTING, currentStep = step)
                        add(MessageRole.STATUS, step)
                    },
                    requestApproval = { description -> requestApproval(description) },
                )
                val answer = engine.run(prompt, conversationContext)
                Log.i("MobileAgent", "Agent request completed; answerLength=${answer.length}")
                add(MessageRole.AGENT, answer)
                _state.value = _state.value.copy(running = false, status = AgentStatus.COMPLETE, currentStep = "任务完成")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                add(MessageRole.STATUS, "任务已停止")
                _state.value = _state.value.copy(running = false, status = AgentStatus.IDLE, currentStep = "")
            } catch (error: Throwable) {
                Log.e("MobileAgent", "Agent request failed", error)
                add(MessageRole.ERROR, error.message ?: "任务失败")
                _state.value = _state.value.copy(running = false, status = AgentStatus.ERROR, error = error.message, currentStep = "任务失败")
            } finally {
                appContext.stopService(Intent(appContext, AgentForegroundService::class.java))
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
        job?.cancel()
    }

    private fun add(role: MessageRole, text: String) {
        _state.value = _state.value.copy(messages = _state.value.messages + ChatMessage(role = role, text = text))
    }
}
