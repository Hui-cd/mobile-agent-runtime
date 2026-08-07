package ai.mobileagent.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MessageRole { USER, AGENT, STATUS, ERROR }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class AgentStatus { IDLE, THINKING, ACTING, WAITING_APPROVAL, COMPLETE, ERROR }

data class ApprovalRequest(val id: String = UUID.randomUUID().toString(), val description: String)

data class AgentState(
    val messages: List<ChatMessage> = emptyList(),
    val status: AgentStatus = AgentStatus.IDLE,
    val running: Boolean = false,
    val currentStep: String = "",
    val approval: ApprovalRequest? = null,
    val error: String? = null,
)

data class UiNodeSnapshot(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val role: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val bounds: IntArray,
) {
    fun toJson() = JSONObject().apply {
        put("index", index)
        text?.let { put("text", it) }
        contentDescription?.let { put("content_description", it) }
        resourceId?.let { put("resource_id", it) }
        put("role", role)
        put("class_name", className)
        put("clickable", clickable)
        put("editable", editable)
        put("scrollable", scrollable)
        put("enabled", enabled)
        put("bounds", JSONArray(bounds.toList()))
        put("center", JSONObject().put("x", (bounds[0] + bounds[2]) / 2).put("y", (bounds[1] + bounds[3]) / 2))
    }
}

data class Observation(
    val packageName: String?,
    val className: String?,
    val nodes: List<UiNodeSnapshot>,
    val screenshotDataUrl: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("observed_at", System.currentTimeMillis())
        put("current_app", packageName ?: JSONObject.NULL)
        put("current_activity", className ?: JSONObject.NULL)
        put("ui_tree", JSONArray(nodes.map { it.toJson() }))
        put("ui_tree_truncated", nodes.size >= 300)
        put("capabilities", JSONObject()
            .put("platform", "android")
            .put("accessibility_connected", ai.mobileagent.accessibility.AccessibilityBridge.connected.value)
            .put("global_ui_control", true)
            .put("screenshot", true)
            .put("unicode_input", true))
        if (screenshotDataUrl != null) put("screen_attached", true)
    }
}

data class ToolExecution(val json: String, val screenshotDataUrl: String? = null)
