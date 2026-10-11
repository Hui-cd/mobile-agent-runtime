package ai.mobileagent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPolicyTest {
    @Test
    fun `English labels and send resource identifiers require approval`() {
        for (target in listOf(JSONObject().put("text", "Send message"), JSONObject().put("text", "Confirm").put("resource_id", "app:id/btn_delete"))) {
            assertTrue(ApprovalPolicy.approvalFor("device_act", JSONObject().put("action", "click").put("target", target)) != null)
        }
        assertNull(ApprovalPolicy.approvalFor("device_act", JSONObject().put("action", "click").put("target", JSONObject().put("text", "Stopwatch"))))
    }
    @Test
    fun `dial requires approval with number as target`() {
        val approval = ApprovalPolicy.approvalFor(
            "device_invoke",
            JSONObject().put("capability", "dial").put("params", JSONObject().put("number", "10086")),
        )
        assertEquals("拨打电话", approval?.action)
        assertEquals("10086", approval?.target)
        assertTrue(approval?.consequence?.isNotBlank() == true)
    }

    @Test
    fun `share requires approval and summarizes content`() {
        val approval = ApprovalPolicy.approvalFor(
            "device_invoke",
            JSONObject().put("capability", "share").put("params", JSONObject().put("text", "会议纪要")),
        )
        assertEquals("分享内容", approval?.action)
        assertEquals("会议纪要", approval?.target)
    }

    @Test
    fun `read-only invoke capabilities need no approval`() {
        assertNull(
            ApprovalPolicy.approvalFor(
                "device_invoke",
                JSONObject().put("capability", "open_url").put("params", JSONObject().put("url", "https://example.com")),
            ),
        )
        assertNull(
            ApprovalPolicy.approvalFor(
                "device_invoke",
                JSONObject().put("capability", "open_app").put("params", JSONObject().put("package", "com.google.android.deskclock")),
            ),
        )
    }

    @Test
    fun `device act on risky keywords requires approval and names them`() {
        val approval = ApprovalPolicy.approvalFor(
            "device_act",
            JSONObject().put("action", "click").put("target", JSONObject().put("text", "确认付款")),
        )
        assertEquals("界面操作：click", approval?.action)
        assertEquals("确认付款", approval?.target)
        assertTrue(approval?.consequence?.contains("确认付款") == true)
    }

    @Test
    fun `plain device act needs no approval`() {
        assertNull(
            ApprovalPolicy.approvalFor(
                "device_act",
                JSONObject().put("action", "click").put("target", JSONObject().put("text", "闹钟")),
            ),
        )
    }

    @Test
    fun `opaque click target requires approval`() {
        val approval = ApprovalPolicy.approvalFor(
            "device_act",
            JSONObject().put("action", "click").put("target", JSONObject().put("index", 7)),
        )
        assertEquals("无法识别的界面目标", approval?.target)
        assertTrue(approval?.consequence?.contains("无法确认") == true)
    }
}
