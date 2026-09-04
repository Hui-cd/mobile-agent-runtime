package ai.mobileagent.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginStateProbeTest {
    @Test
    fun `recognizes only high confidence WeChat signed in navigation`() {
        assertEquals(
            LoginState.SIGNED_IN,
            LoginStateProbe.classify("com.tencent.mm", listOf("微信", "通讯录", "发现", "我", "搜索")),
        )
        assertEquals(
            LoginState.UNKNOWN,
            LoginStateProbe.classify("com.tencent.mm", listOf("微信", "发现", "我")),
        )
    }

    @Test
    fun `recognizes the WeChat signed out landing screen`() {
        assertEquals(
            LoginState.SIGNED_OUT,
            LoginStateProbe.classify("com.tencent.mm", listOf("登录", "注册", "更多")),
        )
    }

    @Test
    fun `does not infer login for other apps`() {
        assertEquals(
            LoginState.UNKNOWN,
            LoginStateProbe.classify("com.xingin.xhs", listOf("首页", "消息", "我")),
        )
        assertEquals(LoginState.NOT_APPLICABLE, LoginStateProbe.classify("ai.mobileagent", emptyList()))
    }

    @Test
    fun `requires two signed in observations before verifying persistence`() {
        val tracker = LoginStateTracker()
        tracker.record("com.tencent.mm", listOf("微信", "通讯录", "发现", "我"))
        assertNull(tracker.loginLost)
        tracker.record("com.tencent.mm", listOf("微信", "通讯录", "发现", "我", "搜索"))
        assertFalse(tracker.loginLost!!)
    }

    @Test
    fun `detects an observed signed in to signed out transition`() {
        val tracker = LoginStateTracker()
        tracker.record("com.tencent.mm", listOf("微信", "通讯录", "发现", "我"))
        tracker.record("com.tencent.mm", listOf("登录", "注册"))
        tracker.record("com.tencent.mm", listOf("微信", "通讯录", "发现", "我"))
        assertTrue(tracker.loginLost!!)
    }
}
