package ai.mobileagent.agent

import java.net.URI

internal data class CompletedToolCall(
    val name: String,
    val action: String? = null,
    val capability: String? = null,
    val url: String? = null,
)

internal enum class BenchmarkResponseFormat {
    JSON_OBJECT,
    JSON_SCHEMA,
}

internal object BenchmarkResponsePolicy {
    fun choose(taskId: String, calls: List<CompletedToolCall>): BenchmarkResponseFormat? {
        if (taskId != "C1") {
            return BenchmarkResponseFormat.JSON_OBJECT.takeIf { calls.isNotEmpty() }
        }
        val searchedByUrl = calls.any { call ->
            call.name == "device_invoke" && call.capability == "open_url" && hasSearchQuery(call.url)
        }
        val searchedByInput = calls.any { it.name == "device_act" && it.action == "input" }
        val clicked = calls.any { it.name == "device_act" && it.action == "click" }
        val scrolled = calls.any { it.name == "device_act" && it.action in setOf("scroll", "swipe") }
        return BenchmarkResponseFormat.JSON_SCHEMA.takeIf { (searchedByUrl || searchedByInput) && clicked && scrolled }
    }

    private fun hasSearchQuery(url: String?): Boolean = runCatching {
        URI(url.orEmpty()).rawQuery
            ?.split('&')
            ?.map { it.substringBefore('=').lowercase() }
            ?.any { it in SEARCH_QUERY_NAMES }
            ?: false
    }.getOrDefault(false)

    private val SEARCH_QUERY_NAMES = setOf("q", "query", "keyword", "wd")
}
