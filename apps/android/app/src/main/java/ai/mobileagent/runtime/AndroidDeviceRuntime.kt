package ai.mobileagent.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import ai.mobileagent.accessibility.AccessibilityBridge
import ai.mobileagent.model.ToolExecution
import kotlinx.coroutines.delay
import org.json.JSONObject

class AndroidDeviceRuntime(private val context: Context) {
    suspend fun execute(name: String, arguments: JSONObject): ToolExecution = when (name) {
        "device_observe" -> observe(arguments.optBoolean("include_screen", false))
        "device_act" -> {
            val service = awaitAccessibilityService()
            val observation = service.act(arguments)
            ToolExecution(observation.toJson().toString(), observation.screenshotDataUrl)
        }
        "device_invoke" -> invoke(arguments)
        else -> error("UNKNOWN_TOOL: $name")
    }

    private suspend fun observe(includeScreen: Boolean): ToolExecution {
        val service = awaitAccessibilityService()
        val observation = service.observe(includeScreen)
        return ToolExecution(observation.toJson().toString(), observation.screenshotDataUrl)
    }

    private suspend fun awaitAccessibilityService(): ai.mobileagent.accessibility.MobileAgentAccessibilityService {
        repeat(50) {
            AccessibilityBridge.service?.let { return it }
            delay(100)
        }
        error("ACCESSIBILITY_SERVICE_NOT_CONNECTED")
    }

    private suspend fun invoke(arguments: JSONObject): ToolExecution {
        val capability = arguments.getString("capability")
        val params = arguments.optJSONObject("params") ?: JSONObject()
        if (capability == "open_app" && Build.VERSION.SDK_INT >= 33) {
            val packageName = params.getString("package")
            runCatching {
                context.packageManager.getLaunchIntentSenderForPackage(packageName)
                    .sendIntent(context, 0, null, null, null)
            }.getOrElse { error("APP_NOT_INSTALLED_OR_NOT_LAUNCHABLE: $packageName") }
            delay(900)
            return observe(arguments.optBoolean("include_screen", false))
        }
        val intent = when (capability) {
            "open_app" -> context.packageManager.getLaunchIntentForPackage(params.getString("package"))
                ?: error("APP_NOT_INSTALLED: ${params.getString("package")}")
            "open_url", "deep_link" -> Intent(Intent.ACTION_VIEW, Uri.parse(params.getString("url")))
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${params.getString("number")}"))
            "navigate" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(params.getString("destination"))}"))
            "open_settings" -> settingsIntent(params)
            "share" -> Intent(Intent.ACTION_SEND).apply {
                type = params.optString("mime_type", "text/plain")
                params.optString("text").takeIf(String::isNotBlank)?.let { putExtra(Intent.EXTRA_TEXT, it) }
            }
            else -> error("UNSUPPORTED_CAPABILITY: $capability")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        params.optString("package").takeIf { it.isNotBlank() && capability != "open_app" }?.let(intent::setPackage)
        context.startActivity(intent)
        delay(900)
        return observe(arguments.optBoolean("include_screen", false))
    }

    private fun settingsIntent(params: JSONObject): Intent {
        val action = when (params.optString("page", "main")) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "app_details" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return Intent(action).apply {
            if (params.optString("page") == "app_details" && params.optString("package").isNotBlank()) {
                data = Uri.parse("package:${params.getString("package")}")
            }
        }
    }
}
