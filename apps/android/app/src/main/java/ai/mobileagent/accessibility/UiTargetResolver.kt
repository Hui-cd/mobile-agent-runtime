package ai.mobileagent.accessibility

import ai.mobileagent.model.UiNodeSnapshot
import org.json.JSONObject

/** Resolve an explicit target in the bounded observation; never guess the first match. */
object UiTargetResolver {
    private val fields = setOf("text", "content_description", "resource_id", "role", "index")

    fun resolve(nodes: List<UiNodeSnapshot>, target: JSONObject, action: String): UiNodeSnapshot {
        require(target.length() > 0) { "TARGET_REQUIRED: specify a selector from device_observe" }
        for (key in target.keys()) {
            require(key in fields) { "TARGET_INVALID: unknown selector $key" }
            if (key == "index") {
                val value = target.opt(key)
                require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() >= 0) {
                    "TARGET_INVALID: index must be a non-negative integer"
                }
            } else require(target.opt(key) is String && target.getString(key).isNotBlank()) {
                "TARGET_INVALID: $key must be a non-empty string"
            }
        }
        val matches = nodes.filter { node ->
            node.enabled && node.visible && node.bounds[2] > node.bounds[0] && node.bounds[3] > node.bounds[1] &&
                (!target.has("index") || node.index == target.getInt("index")) &&
                (!target.has("text") || node.text == target.getString("text")) &&
                (!target.has("content_description") || node.contentDescription == target.getString("content_description")) &&
                (!target.has("resource_id") || node.resourceId == target.getString("resource_id")) &&
                (!target.has("role") || node.role == target.getString("role")) &&
                (action != "input" || node.editable) &&
                (action != "scroll" || node.scrollable)
        }
        check(matches.isNotEmpty()) { "TARGET_NOT_FOUND: observe again; selector has no eligible visible match" }
        val actionable = if (action == "click") matches.filter { it.clickable }.ifEmpty { matches } else matches
        check(actionable.size == 1) {
            "TARGET_AMBIGUOUS: ${actionable.size} matches at indices ${actionable.take(10).map { it.index }}; use index with observation_id or a more specific selector"
        }
        return actionable.single()
    }

    fun validateObservation(expectedId: String?, previousId: String?, previous: List<UiNodeSnapshot>?, current: List<UiNodeSnapshot>) {
        if (expectedId == null) return
        check(expectedId == previousId && previous != null &&
            previous.map { it.toJson().toString() } == current.map { it.toJson().toString() }) {
            "STALE_OBSERVATION: page changed; call device_observe and select a fresh target"
        }
    }
}
