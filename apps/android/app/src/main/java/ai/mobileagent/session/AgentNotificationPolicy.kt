package ai.mobileagent.session

import ai.mobileagent.model.AgentState
import ai.mobileagent.model.AgentStatus

internal data class AgentNotificationContent(
    val title: String,
    val text: String,
    val attention: Boolean = false,
    val ongoing: Boolean = true,
    val showStop: Boolean = true,
)

internal object AgentNotificationPolicy {
    fun content(state: AgentState): AgentNotificationContent = when (state.status) {
        AgentStatus.THINKING -> AgentNotificationContent(
            title = "Agent 正在处理任务",
            text = state.currentStep.ifBlank { "正在思考下一步" },
        )
        AgentStatus.ACTING -> AgentNotificationContent(
            title = "Agent 正在操作手机",
            text = state.currentStep.ifBlank { "正在执行任务" },
        )
        AgentStatus.WAITING_APPROVAL -> AgentNotificationContent(
            title = "需要你的确认",
            text = "返回 Mobile Agent 查看操作详情",
            attention = true,
        )
        AgentStatus.COMPLETE -> AgentNotificationContent(
            title = "任务完成",
            text = "点击查看结果",
            ongoing = false,
            showStop = false,
        )
        AgentStatus.ERROR -> AgentNotificationContent(
            title = "任务未完成",
            text = "点击查看原因",
            attention = true,
            ongoing = false,
            showStop = false,
        )
        AgentStatus.CANCELLED -> AgentNotificationContent(
            title = "任务已停止",
            text = "没有继续执行后续步骤",
            ongoing = false,
            showStop = false,
        )
        AgentStatus.IDLE -> AgentNotificationContent(
            title = "Mobile Agent",
            text = "等待任务",
            ongoing = false,
            showStop = false,
        )
    }
}
