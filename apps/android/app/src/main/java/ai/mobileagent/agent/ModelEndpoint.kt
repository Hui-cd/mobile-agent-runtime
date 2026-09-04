package ai.mobileagent.agent

import android.content.Context
import java.net.URI

class ModelEndpoint private constructor(
    val baseUrl: String,
    val model: String,
) {
    val host: String = URI(baseUrl).host.lowercase()
    val sendsReasoningEffort: Boolean = host == KIMI_HOST

    companion object {
        const val DEFAULT_BASE_URL = "https://api.moonshot.cn/v1"
        const val DEFAULT_MODEL = "kimi-k3"
        private const val KIMI_HOST = "api.moonshot.cn"

        val default = ModelEndpoint(DEFAULT_BASE_URL, DEFAULT_MODEL)

        fun parse(baseUrl: String, model: String): ModelEndpoint {
            val normalizedBase = baseUrl.trim().trimEnd('/')
            val normalizedModel = model.trim()
            val uri = runCatching { URI(normalizedBase) }
                .getOrElse { throw IllegalArgumentException("模型 Base URL 格式无效") }
            require(uri.scheme.equals("https", ignoreCase = true)) { "模型 Base URL 必须使用 HTTPS" }
            require(!uri.host.isNullOrBlank()) { "模型 Base URL 缺少 host" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "模型 Base URL 不能包含凭据、query 或 fragment"
            }
            require(normalizedModel.isNotBlank() && normalizedModel.length <= 160) { "模型 ID 无效" }
            return ModelEndpoint(normalizedBase, normalizedModel)
        }
    }
}

class ModelEndpointStore(context: Context) {
    private val preferences = context.getSharedPreferences("model-endpoint", Context.MODE_PRIVATE)

    fun load(): ModelEndpoint = runCatching {
        ModelEndpoint.parse(
            preferences.getString("base_url", null) ?: ModelEndpoint.DEFAULT_BASE_URL,
            preferences.getString("model", null) ?: ModelEndpoint.DEFAULT_MODEL,
        )
    }.getOrDefault(ModelEndpoint.default)

    fun save(baseUrl: String, model: String): ModelEndpoint = ModelEndpoint.parse(baseUrl, model).also {
        preferences.edit().putString("base_url", it.baseUrl).putString("model", it.model).apply()
    }
}
