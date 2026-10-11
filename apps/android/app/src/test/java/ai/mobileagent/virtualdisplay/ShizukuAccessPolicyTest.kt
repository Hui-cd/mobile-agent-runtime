package ai.mobileagent.virtualdisplay

import org.junit.Assert.*
import org.junit.Test

class ShizukuAccessPolicyTest {
    @Test fun `unsupported android does not start a broker`() {
        val error = assertThrows(IllegalStateException::class.java) { ShizukuAccessPolicy.requireConnectable(33, true, true) }
        assertEquals("HIDDEN_EXECUTION_REQUIRES_ANDROID_14", error.message)
    }
    @Test fun `missing and unauthorized service are distinct`() {
        assertEquals("SHIZUKU_UNAVAILABLE", assertThrows(IllegalStateException::class.java) { ShizukuAccessPolicy.requireConnectable(34, false, false) }.message)
        assertEquals("SHIZUKU_NOT_AUTHORIZED", assertThrows(IllegalStateException::class.java) { ShizukuAccessPolicy.requireConnectable(34, true, false) }.message)
    }
    @Test fun `android 14 and authorized service allow connecting`() {
        ShizukuAccessPolicy.requireConnectable(34, true, true)
        ShizukuAccessPolicy.requireConnectable(37, true, true)
    }
}
