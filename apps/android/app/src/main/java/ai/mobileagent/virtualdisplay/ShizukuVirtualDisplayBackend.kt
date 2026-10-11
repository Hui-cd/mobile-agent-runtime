package ai.mobileagent.virtualdisplay

import ai.mobileagent.model.ToolExecution
import org.json.JSONObject

/**
 * Shizuku binder 连接契约。真机 lane 以不破坏现有构建的方式接入 rikka.shizuku；
 * 实现侧必须只接受参数数组形式的注入请求（broker 白名单），不接受任意 shell 字符串。
 */
interface ShizukuConnection {
    sealed interface State {
        data object NotRunning : State
        data object RunningNotAuthorized : State
        data object Authorized : State
        data object Died : State
    }

    fun state(): State
}

/**
 * Shizuku trusted VirtualDisplay 隐藏执行 backend 骨架。
 *
 * 当前状态：未接入 Shizuku binder 与虚拟显示创建，所有工具调用在未授权时返回
 * SHIZUKU_UNAVAILABLE；授权后返回明确的待实现错误。**任何情况下不伪造观察或动作成功。**
 */
class ShizukuVirtualDisplayBackend(
    private val connection: ShizukuConnection,
) : HiddenExecutionBackend {

    override val id: String = "android_virtualdisplay_shizuku"

    fun availability(): BackendAvailability = when (connection.state()) {
        ShizukuConnection.State.NotRunning ->
            BackendAvailability.Unavailable(
                "SHIZUKU_UNAVAILABLE",
                "Shizuku not running; activate via on-device wireless debugging pairing",
            )
        ShizukuConnection.State.RunningNotAuthorized ->
            BackendAvailability.Unavailable("SHIZUKU_NOT_AUTHORIZED", "grant Shizuku authorization to ai.mobileagent")
        ShizukuConnection.State.Died ->
            BackendAvailability.Unavailable("SHIZUKU_DIED", "session interrupted; next session must not replay actions")
        ShizukuConnection.State.Authorized ->
            BackendAvailability.Unavailable(
                "VIRTUAL_DISPLAY_NOT_CREATED",
                "trusted virtual display creation pending device evidence",
            )
    }

    override suspend fun observe(arguments: JSONObject): ToolExecution = unavailable("observe")

    override suspend fun act(arguments: JSONObject): ToolExecution = unavailable("act")

    override suspend fun invoke(arguments: JSONObject): ToolExecution = unavailable("invoke")

    private fun unavailable(tool: String): ToolExecution {
        val availability = availability()
        val code = (availability as? BackendAvailability.Unavailable)?.errorCode ?: "UNAVAILABLE"
        val detail = (availability as? BackendAvailability.Unavailable)?.detail ?: ""
        return ToolExecution(
            JSONObject()
                .put("observed_at", System.currentTimeMillis())
                .put("backend", id)
                .put("tool", "device_$tool")
                .put("error", "$code: $detail")
                .put("capabilities", capabilitiesJson())
                .toString(),
        )
    }

    private fun capabilitiesJson() = JSONObject()
        .put("platform", "android")
        .put("hidden_execution", false)
        .put("trusted_virtual_display", false)
        .put("foreground_independent", false)
        .put("notes", "skeleton; device evidence pending under cohort android-virtualdisplay-*")
}
