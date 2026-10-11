package ai.mobileagent.accessibility

import ai.mobileagent.model.UiNodeSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiTargetResolverTest {
    private fun node(index: Int, text: String = "打开") = UiNodeSnapshot(
        index, text, null, "example:id/button", "button", "Button",
        clickable = true, editable = false, scrollable = false, enabled = true,
        bounds = intArrayOf(0, 0, 100, 100),
    )

    @Test fun `empty and unknown selectors never select the root`() {
        for (target in listOf(JSONObject(), JSONObject().put("label", "打开"), JSONObject().put("index", -1))) {
            assertThrows(IllegalArgumentException::class.java) { UiTargetResolver.resolve(listOf(node(0)), target, "click") }
        }
    }

    @Test fun `duplicate labels require disambiguation`() {
        val nodes = listOf(node(0), node(1))
        val error = assertThrows(IllegalStateException::class.java) {
            UiTargetResolver.resolve(nodes, JSONObject().put("text", "打开"), "click")
        }
        assertTrue(error.message!!.startsWith("TARGET_AMBIGUOUS"))
        assertEquals(1, UiTargetResolver.resolve(nodes, JSONObject().put("index", 1).put("text", "打开"), "click").index)
    }

    @Test fun `index and text must both match`() {
        assertThrows(IllegalStateException::class.java) {
            UiTargetResolver.resolve(listOf(node(0, "付款")), JSONObject().put("index", 0).put("text", "打开"), "click")
        }
    }

    @Test fun `disabled hidden and zero area nodes cannot be selected`() {
        val nodes = listOf(node(0).copy(enabled = false), node(1).copy(visible = false), node(2).copy(bounds = intArrayOf(0, 0, 0, 0)))
        assertThrows(IllegalStateException::class.java) { UiTargetResolver.resolve(nodes, JSONObject().put("text", "打开"), "click") }
    }

    @Test fun `input targets must be editable and scroll targets scrollable`() {
        val nodes = listOf(node(0), node(1).copy(editable = true), node(2).copy(scrollable = true))
        assertEquals(1, UiTargetResolver.resolve(nodes, JSONObject().put("text", "打开"), "input").index)
        assertEquals(2, UiTargetResolver.resolve(nodes, JSONObject().put("text", "打开"), "scroll").index)
    }

    @Test fun `new identity changed labels and changed selected state invalidate observations`() {
        val original = listOf(node(0))
        UiTargetResolver.validateObservation("a", "a", original, original.map { it.copy() })
        for ((id, current) in listOf("b" to original, "a" to listOf(node(0, "付款")), "a" to listOf(node(0).copy(selected = true)))) {
            assertThrows(IllegalStateException::class.java) { UiTargetResolver.validateObservation(id, "a", original, current) }
        }
    }
    @Test fun `dynamic unrelated text does not invalidate an unchanged target`() {
        val before = listOf(node(0, "Pause"), node(1, "00:01"))
        val after = listOf(node(0, "Pause"), node(1, "00:02"))
        UiTargetResolver.validateTargetObservation("a", "a", before, after, JSONObject().put("index", 0), "click")
    }

    @Test fun `changed target and foreign observation remain rejected`() {
        val before = listOf(node(0, "Start"))
        assertThrows(IllegalStateException::class.java) {
            UiTargetResolver.validateTargetObservation("a", "a", before, listOf(node(0, "Pause")), JSONObject().put("index", 0), "click")
        }
        assertThrows(IllegalStateException::class.java) {
            UiTargetResolver.validateTargetObservation("b", "a", before, before, JSONObject().put("index", 0), "click")
        }
    }
}
