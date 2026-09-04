package ai.mobileagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkResponsePolicyTest {
    @Test
    fun `C1 stays text until search click and scroll all succeeded`() {
        val search = CompletedToolCall(
            name = "device_invoke",
            capability = "open_url",
            url = "https://cn.bing.com/search?q=site%3Acnblogs.com",
        )
        val click = CompletedToolCall(name = "device_act", action = "click")
        val scroll = CompletedToolCall(name = "device_act", action = "swipe")

        assertNull(BenchmarkResponsePolicy.choose("C1", emptyList()))
        assertNull(BenchmarkResponsePolicy.choose("C1", listOf(search)))
        assertNull(BenchmarkResponsePolicy.choose("C1", listOf(search, click)))
        assertEquals(
            BenchmarkResponseFormat.JSON_SCHEMA,
            BenchmarkResponsePolicy.choose("C1", listOf(search, click, scroll)),
        )
    }

    @Test
    fun `C1 ignores non-search URLs and failed calls omitted by transport`() {
        val detail = CompletedToolCall(
            name = "device_invoke",
            capability = "open_url",
            url = "https://www.cnblogs.com/example/article",
        )
        val click = CompletedToolCall(name = "device_act", action = "click")
        val scroll = CompletedToolCall(name = "device_act", action = "scroll")

        assertNull(BenchmarkResponsePolicy.choose("C1", listOf(detail, click, scroll)))
    }

    @Test
    fun `C1 accepts address bar input as the search path`() {
        val input = CompletedToolCall(name = "device_act", action = "input")
        val click = CompletedToolCall(name = "device_act", action = "click")
        val scroll = CompletedToolCall(name = "device_act", action = "scroll")

        assertEquals(
            BenchmarkResponseFormat.JSON_SCHEMA,
            BenchmarkResponsePolicy.choose("C1", listOf(input, click, scroll)),
        )
    }

    @Test
    fun `other benchmarks enable JSON only after one successful tool`() {
        assertNull(BenchmarkResponsePolicy.choose("L1", emptyList()))
        assertEquals(
            BenchmarkResponseFormat.JSON_OBJECT,
            BenchmarkResponsePolicy.choose("L1", listOf(CompletedToolCall("device_invoke"))),
        )
    }
}
