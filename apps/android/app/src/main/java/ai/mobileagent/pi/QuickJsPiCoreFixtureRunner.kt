package ai.mobileagent.pi

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class QuickJsPiCoreFixtureResult(
    val finalText: String,
    val observedPlatform: String,
    val eventTypes: List<String>,
    val messageRoles: List<String>,
)

object QuickJsPiCoreFixtureRunner {
    private const val ASSET_NAME = "pi-mobile-quickjs-runtime.js"
    private const val MEMORY_LIMIT_BYTES = 64L * 1024L * 1024L
    private const val STACK_LIMIT_BYTES = 1024L * 1024L
    private const val FIXTURE_TIMEOUT_MS = 30_000L
    private const val TAG = "MobileAgentQuickJs"

    suspend fun run(context: Context): QuickJsPiCoreFixtureResult = withTimeout(FIXTURE_TIMEOUT_MS) {
        val source = context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.IO)
        try {
            quickJs.memoryLimit = MEMORY_LIMIT_BYTES
            quickJs.maxStackSize = STACK_LIMIT_BYTES
            quickJs.evaluationTimeoutMillis = FIXTURE_TIMEOUT_MS
            quickJs.asyncFunction<String, String>("mobileNativeCall", ::handleNativeCall)
            quickJs.evaluate<Any?>(source, filename = ASSET_NAME)
            val encoded = quickJs.evaluate<String>(
                "JSON.stringify(await globalThis.PiMobileQuickJsRuntime.runFixture())",
                filename = "run-fixture.js",
            )
            parseAndValidate(encoded).also {
                Log.i(TAG, "fixture passed; quickJs=${quickJs.version} events=${it.eventTypes.size}")
            }
        } finally {
            quickJs.close()
        }
    }

    private suspend fun handleNativeCall(encoded: String): String {
        val request = runCatching { JSONObject(encoded) }.getOrElse {
            return errorResponse("unknown", "NATIVE_REQUEST_INVALID").toString()
        }
        val id = request.optString("id", "unknown")
        return when (request.optString("method")) {
            "device_observe" -> successResponse(
                id,
                JSONObject()
                    .put("platform", "android-quickjs")
                    .put("bridge", "quickjs-kt-async-function"),
            ).toString()
            "fixture_complete" -> successResponse(id, JSONObject().put("accepted", true)).toString()
            else -> errorResponse(id, "NATIVE_METHOD_UNSUPPORTED").toString()
        }
    }

    private fun parseAndValidate(encoded: String): QuickJsPiCoreFixtureResult {
        val result = JSONObject(encoded)
        val eventTypes = result.getJSONArray("eventTypes").let { array ->
            (0 until array.length()).map(array::getString)
        }
        val messageRoles = result.getJSONArray("messageRoles").let { array ->
            (0 until array.length()).map(array::getString)
        }
        val parsed = QuickJsPiCoreFixtureResult(
            finalText = result.getString("finalText"),
            observedPlatform = result.getString("observedPlatform"),
            eventTypes = eventTypes,
            messageRoles = messageRoles,
        )
        check(parsed.finalText == "Pi mobile fixture complete.") { "PI_CORE_FIXTURE_TEXT_INVALID" }
        check(parsed.observedPlatform == "android-quickjs") { "PI_CORE_FIXTURE_PLATFORM_INVALID" }
        check(parsed.messageRoles == listOf("user", "assistant", "toolResult", "assistant")) {
            "PI_CORE_FIXTURE_ROLES_INVALID"
        }
        check(parsed.eventTypes.lastOrNull() == "agent_end") { "PI_CORE_FIXTURE_EVENTS_INVALID" }
        return parsed
    }

    private fun successResponse(id: String, result: Any): JSONObject = JSONObject()
        .put("id", id)
        .put("result", result)

    private fun errorResponse(id: String, error: String): JSONObject = JSONObject()
        .put("id", id)
        .put("error", error)
}
