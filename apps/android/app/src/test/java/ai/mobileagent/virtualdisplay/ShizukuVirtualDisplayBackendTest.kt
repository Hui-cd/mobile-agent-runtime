package ai.mobileagent.virtualdisplay

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuVirtualDisplayBackendTest {

    private class FakeConnection(private val state: ShizukuConnection.State) : ShizukuConnection {
        override fun state() = state
    }

    @Test
    fun `not running reports SHIZUKU_UNAVAILABLE and never fakes success`() = runBlocking {
        val backend = ShizukuVirtualDisplayBackend(FakeConnection(ShizukuConnection.State.NotRunning))
        val availability = backend.availability() as BackendAvailability.Unavailable
        assertEquals("SHIZUKU_UNAVAILABLE", availability.errorCode)

        val result = backend.observe(JSONObject())
        assertTrue(JSONObject(result.json).getString("error").startsWith("SHIZUKU_UNAVAILABLE"))
        assertNull(result.screenshotDataUrl)
    }

    @Test
    fun `unauthorized and died states are distinguishable`() {
        val unauthorized = ShizukuVirtualDisplayBackend(FakeConnection(ShizukuConnection.State.RunningNotAuthorized)).availability()
        val died = ShizukuVirtualDisplayBackend(FakeConnection(ShizukuConnection.State.Died)).availability()
        assertEquals("SHIZUKU_NOT_AUTHORIZED", (unauthorized as BackendAvailability.Unavailable).errorCode)
        assertEquals("SHIZUKU_DIED", (died as BackendAvailability.Unavailable).errorCode)
    }

    @Test
    fun `authorized without device evidence stays unavailable with explicit code`() = runBlocking {
        val backend = ShizukuVirtualDisplayBackend(FakeConnection(ShizukuConnection.State.Authorized))
        val availability = backend.availability() as BackendAvailability.Unavailable
        assertEquals("VIRTUAL_DISPLAY_NOT_CREATED", availability.errorCode)
        val capabilities = JSONObject(backend.act(JSONObject()).json).getJSONObject("capabilities")
        assertEquals(false, capabilities.getBoolean("hidden_execution"))
    }
}
