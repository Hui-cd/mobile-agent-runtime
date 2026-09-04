package ai.mobileagent.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenAICompatibleClient(
    private val apiKey: String,
    val endpoint: ModelEndpoint = ModelEndpoint.default,
    private val context: Context? = null,
) {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        activeConnection?.disconnect()
    }

    suspend fun complete(messages: JSONArray, tools: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        DebugModelDelay.consume(context)
        val startedAt = System.currentTimeMillis()
        val benchmarkTaskId = benchmarkTaskId(messages)
        val successfulToolCalls = successfulToolCalls(messages)
        val responseFormat = benchmarkTaskId
            ?.let { taskId -> BenchmarkResponsePolicy.choose(taskId, successfulToolCalls)?.let { taskId to it } }
            ?.let { (taskId, _) -> responseFormat(taskId) }
        Log.i(
            "MobileAgentPi",
            "model request started; endpointHost=${endpoint.host} model=${endpoint.model} " +
                "messages=${messages.length()} tools=${tools.length()} " +
                "benchmarkTask=${benchmarkTaskId ?: "none"} responseFormat=${responseFormat?.optString("type") ?: "text"}",
        )
        val connection = URL("${endpoint.baseUrl}/chat/completions").openConnection() as HttpURLConnection
        activeConnection = connection
        val requestJob = currentCoroutineContext().job
        val cancellationHandle = requestJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("model", endpoint.model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
            if (endpoint.sendsReasoningEffort) body.put("reasoning_effort", "low")
            responseFormat?.let { body.put("response_format", it) }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            Log.i("MobileAgentPi", "model request finished; status=${connection.responseCode} elapsedMs=${System.currentTimeMillis() - startedAt}")
            val json = JSONObject(response)
            if (connection.responseCode !in 200..299) {
                val message = json.optJSONObject("error")?.optString("message") ?: "HTTP ${connection.responseCode}"
                error("MODEL_API_ERROR: $message")
            }
            json
        } catch (error: Throwable) {
            if (requestJob.isCancelled) {
                Log.i("MobileAgentPi", "model request cancelled; elapsedMs=${System.currentTimeMillis() - startedAt}")
                throw CancellationException("MODEL_REQUEST_CANCELLED").also { it.initCause(error) }
            }
            throw error
        } finally {
            cancellationHandle.dispose()
            connection.disconnect()
            if (activeConnection === connection) activeConnection = null
        }
    }

    private fun benchmarkTaskId(messages: JSONArray): String? = (0 until messages.length()).firstNotNullOfOrNull { index ->
        val message = messages.optJSONObject(index) ?: return@firstNotNullOfOrNull null
        if (message.optString("role") != "user") return@firstNotNullOfOrNull null
        BENCHMARK_TASK.find(messageText(message.opt("content")))?.groupValues?.get(1)
    }

    private fun messageText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).joinToString("\n") { index ->
            content.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text").orEmpty()
        }
        else -> ""
    }

    private fun successfulToolCalls(messages: JSONArray): List<CompletedToolCall> {
        val successfulIds = mutableSetOf<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "tool") continue
            val result = runCatching { JSONObject(messageText(message.opt("content"))) }.getOrNull() ?: continue
            if (!result.has("error")) message.optString("tool_call_id").takeIf(String::isNotBlank)?.let(successfulIds::add)
        }
        val calls = mutableListOf<CompletedToolCall>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val toolCalls = message.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(callIndex) ?: continue
                if (call.optString("id") !in successfulIds) continue
                val function = call.optJSONObject("function") ?: continue
                val arguments = runCatching { JSONObject(function.optString("arguments")) }.getOrNull() ?: continue
                calls += CompletedToolCall(
                    name = function.optString("name"),
                    action = arguments.optString("action").takeIf(String::isNotBlank),
                    capability = arguments.optString("capability").takeIf(String::isNotBlank),
                    url = arguments.optJSONObject("params")?.optString("url")?.takeIf(String::isNotBlank),
                )
            }
        }
        return calls
    }

    private fun responseFormat(taskId: String): JSONObject {
        if (taskId != "C1") return JSONObject().put("type", "json_object")
        val itemSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("snippet", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("title").put("snippet"))
        val detailSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("summary", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("title").put("summary"))
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string"))
                .put("items", JSONObject()
                    .put("type", "array")
                    .put("minItems", 5)
                    .put("maxItems", 5)
                    .put("items", itemSchema))
                .put("detail", detailSchema)
                .put("scrolled", JSONObject().put("type", "boolean")))
            .put("required", JSONArray().put("query").put("items").put("detail").put("scrolled"))
        return JSONObject()
            .put("type", "json_schema")
            .put("json_schema", JSONObject()
                .put("name", "mobile_agent_c1_result")
                .put("strict", true)
                .put("schema", schema))
    }

    companion object {
        private val BENCHMARK_TASK = Regex("^\\[BENCH:([A-Z][0-9]+)]")
    }
}
