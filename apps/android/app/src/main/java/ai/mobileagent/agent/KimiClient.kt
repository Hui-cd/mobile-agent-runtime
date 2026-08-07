package ai.mobileagent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KimiClient(
    private val apiKey: String,
    private val model: String = "kimi-k3",
    private val baseUrl: String = "https://api.moonshot.cn/v1",
) {
    suspend fun complete(messages: JSONArray, tools: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("reasoning_effort", "low")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            if (connection.responseCode !in 200..299) {
                val message = json.optJSONObject("error")?.optString("message") ?: "HTTP ${connection.responseCode}"
                error("KIMI_API_ERROR: $message")
            }
            json
        } finally {
            connection.disconnect()
        }
    }
}
