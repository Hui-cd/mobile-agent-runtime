package ai.mobileagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEndpointTest {
    @Test
    fun `normalizes a compatible HTTPS endpoint`() {
        val endpoint = ModelEndpoint.parse("  https://models.example.com/v1/ ", " custom-model ")

        assertEquals("https://models.example.com/v1", endpoint.baseUrl)
        assertEquals("models.example.com", endpoint.host)
        assertEquals("custom-model", endpoint.model)
        assertFalse(endpoint.sendsReasoningEffort)
    }

    @Test
    fun `keeps Kimi defaults backward compatible`() {
        assertEquals("https://api.moonshot.cn/v1", ModelEndpoint.default.baseUrl)
        assertEquals("kimi-k3", ModelEndpoint.default.model)
        assertTrue(ModelEndpoint.default.sendsReasoningEffort)
    }

    @Test
    fun `rejects cleartext credentials and empty model`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelEndpoint.parse("http://models.example.com/v1", "model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelEndpoint.parse("https://secret@models.example.com/v1", "model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelEndpoint.parse("https://models.example.com/v1", " ")
        }
    }
}
