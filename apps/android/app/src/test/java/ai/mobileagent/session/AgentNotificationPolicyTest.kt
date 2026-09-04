package ai.mobileagent.session

import ai.mobileagent.model.AgentState
import ai.mobileagent.model.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNotificationPolicyTest {
    @Test
    fun `acting exposes current step and stop action`() {
        val content = AgentNotificationPolicy.content(
            AgentState(status = AgentStatus.ACTING, running = true, currentStep = "正在打开时钟"),
        )

        assertEquals("Agent 正在操作手机", content.title)
        assertEquals("正在打开时钟", content.text)
        assertTrue(content.ongoing)
        assertTrue(content.showStop)
        assertFalse(content.attention)
    }

    @Test
    fun `approval attracts attention without exposing action details`() {
        val content = AgentNotificationPolicy.content(
            AgentState(status = AgentStatus.WAITING_APPROVAL, running = true),
        )

        assertEquals("需要你的确认", content.title)
        assertEquals("返回 Mobile Agent 查看操作详情", content.text)
        assertTrue(content.attention)
        assertTrue(content.showStop)
    }

    @Test
    fun `terminal states remove stop action`() {
        listOf(AgentStatus.COMPLETE, AgentStatus.ERROR, AgentStatus.CANCELLED).forEach { status ->
            val content = AgentNotificationPolicy.content(AgentState(status = status))
            assertFalse(content.ongoing)
            assertFalse(content.showStop)
        }
    }
}
