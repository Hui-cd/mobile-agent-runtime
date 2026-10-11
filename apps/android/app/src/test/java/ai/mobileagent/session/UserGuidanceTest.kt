package ai.mobileagent.session

import org.junit.Assert.*
import org.junit.Test

class UserGuidanceTest {
    @Test fun `all startup paths must satisfy model control and notification requirements`() {
        assertNull(UserGuidance.readiness(true, true, true))
        assertTrue(UserGuidance.readiness(false, true, true)!!.contains("密钥"))
        assertTrue(UserGuidance.readiness(true, false, true)!!.contains("设备控制"))
        assertTrue(UserGuidance.readiness(true, true, false)!!.contains("通知"))
    }
    @Test fun `model failures expose an actionable message without raw provider details`() {
        val raw = "MODEL_API_ERROR: HTTP 401: private provider diagnostic"
        val guidance = UserGuidance.error(raw)
        assertTrue(guidance.contains("密钥"))
        assertFalse(guidance.contains("private"))
        assertTrue(UserGuidance.error("MODEL_API_ERROR: HTTP 429").contains("额度"))
    }
    @Test fun `device blocks and limits tell user how to recover`() {
        assertTrue(UserGuidance.error("DEVICE_LOCKED").contains("解锁"))
        assertTrue(UserGuidance.error("ACCESSIBILITY_SERVICE_NOT_CONNECTED").contains("无障碍"))
        assertTrue(UserGuidance.error("PI_AGENT_STEP_LIMIT").contains("缩小目标"))
    }
}
