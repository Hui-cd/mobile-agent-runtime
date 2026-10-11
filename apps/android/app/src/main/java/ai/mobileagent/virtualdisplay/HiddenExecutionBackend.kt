package ai.mobileagent.virtualdisplay

import ai.mobileagent.model.ToolExecution
import org.json.JSONObject

/**
 * 隐藏执行 backend 接口。与前台 Accessibility backend 相同的三个工具面，
 * 平台/后端差异只体现在实现与 capabilities 报告中。
 */
interface HiddenExecutionBackend {
    val id: String
    suspend fun observe(arguments: JSONObject): ToolExecution
    suspend fun act(arguments: JSONObject): ToolExecution
    suspend fun invoke(arguments: JSONObject): ToolExecution
}

/** backend 可用性判定结果。任何一项不满足都不得静默降级到伪造成功。 */
sealed interface BackendAvailability {
    data object Ready : BackendAvailability
    data class Unavailable(val errorCode: String, val detail: String) : BackendAvailability
}

/**
 * 前台 / 隐藏 backend 的选择策略。纯数据输入，便于 JVM 单测。
 */
data class BackendSelectionInput(
    val hiddenAvailable: BackendAvailability,
    val foregroundAvailable: Boolean,
    val screenInteractive: Boolean,
    val deviceLocked: Boolean,
    val hiddenSessionActive: Boolean,
)

sealed interface BackendDecision {
    /** 使用隐藏 VirtualDisplay backend。 */
    data object Hidden : BackendDecision

    /** 使用前台 Accessibility backend。 */
    data object Foreground : BackendDecision

    /** 不执行设备动作，返回错误码。 */
    data class Fail(val errorCode: String, val detail: String) : BackendDecision
}

object BackendSelector {
    /**
     * 锁屏/熄屏语义与前台 backend 一致：不绕过锁屏。
     * 已建立的隐藏会话在熄屏后可继续观察与记账（由调用方保证推理不依赖 GUI 注入），
     * 新的 GUI 动作仍要求设备处于解锁交互态。
     */
    fun select(input: BackendSelectionInput, wantsGuiAction: Boolean): BackendDecision {
        if (wantsGuiAction && input.deviceLocked) {
            return BackendDecision.Fail("DEVICE_LOCKED", "unlock the device before using GUI tools")
        }
        if (wantsGuiAction && !input.screenInteractive && !input.hiddenSessionActive) {
            return BackendDecision.Fail("SCREEN_NOT_INTERACTIVE", "wake the screen before starting GUI work")
        }
        return when (val hidden = input.hiddenAvailable) {
            BackendAvailability.Ready -> BackendDecision.Hidden
            is BackendAvailability.Unavailable ->
                if (input.foregroundAvailable) {
                    // 隐藏 backend 不可用：允许显式回退到前台 backend，
                    // 但仅在策略允许时由调用方选择，不在 backend 内部静默切换。
                    BackendDecision.Foreground
                } else {
                    BackendDecision.Fail(hidden.errorCode, hidden.detail)
                }
        }
    }
}
