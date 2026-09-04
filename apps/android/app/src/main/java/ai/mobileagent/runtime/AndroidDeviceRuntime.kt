package ai.mobileagent.runtime

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import ai.mobileagent.accessibility.AccessibilityBridge
import ai.mobileagent.model.ToolExecution
import kotlinx.coroutines.delay
import org.json.JSONObject

class AndroidDeviceRuntime(private val context: Context) {
    suspend fun execute(name: String, arguments: JSONObject): ToolExecution = when (name) {
        "device_observe" -> observe(arguments.optBoolean("include_screen", false))
        "device_act" -> {
            requireUiAvailable()
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
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        val json = observation.toJson()
            .put("device_state", JSONObject()
                .put("screen_interactive", interactive)
                .put("device_locked", locked))
        json.optJSONObject("capabilities")
            ?.put("screen_interactive", interactive)
            ?.put("device_locked", locked)
            ?.put("global_ui_control", interactive && !locked)
        when {
            locked -> json.put("error", "DEVICE_LOCKED: unlock the device before using GUI tools")
            !interactive -> json.put("error", "SCREEN_NOT_INTERACTIVE: wake the screen before using GUI tools")
        }
        return ToolExecution(json.toString(), observation.screenshotDataUrl)
    }

    private suspend fun awaitAccessibilityService(): ai.mobileagent.accessibility.MobileAgentAccessibilityService {
        repeat(50) {
            AccessibilityBridge.service?.let { return it }
            delay(100)
        }
        error("ACCESSIBILITY_SERVICE_NOT_CONNECTED")
    }

    private suspend fun invoke(arguments: JSONObject): ToolExecution {
        requireUiAvailable()
        val capability = arguments.getString("capability")
        val params = arguments.optJSONObject("params") ?: JSONObject()
        val launchPackage = if (capability == "open_app") resolveLaunchPackage(params) else null
        if (capability == "open_app" && Build.VERSION.SDK_INT >= 33) {
            val packageName = launchPackage!!
            runCatching {
                context.packageManager.getLaunchIntentSenderForPackage(packageName)
                    .sendIntent(context, 0, null, null, null)
            }.getOrElse { error("APP_NOT_INSTALLED_OR_NOT_LAUNCHABLE: $packageName") }
            delay(900)
            return observe(arguments.optBoolean("include_screen", false))
        }
        val intent = when (capability) {
            "open_app" -> context.packageManager.getLaunchIntentForPackage(launchPackage!!)
                ?: error("APP_NOT_LAUNCHABLE: $launchPackage")
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

    private fun requireUiAvailable() {
        val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (locked) error("DEVICE_LOCKED: unlock the device before using GUI tools")
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        if (!interactive) error("SCREEN_NOT_INTERACTIVE: wake the screen before using GUI tools")
    }

    private fun resolveLaunchPackage(params: JSONObject): String {
        val requested = params.optString("package").ifBlank { params.optString("app") }.trim()
        if (requested.isBlank()) error("OPEN_APP_TARGET_MISSING")
        val normalized = requested.lowercase()
            .removeSuffix(" app")
            .removeSuffix("应用")
            .trim()
        val candidates = buildList {
            add(requested)
            APP_ALIASES.entries
                .filter { (aliases, _) -> normalized in aliases }
                .flatMapTo(this) { it.value }
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .filter { info ->
                    val label = info.loadLabel(context.packageManager).toString().lowercase()
                    label == normalized || label.contains(normalized) || normalized.contains(label)
                }
                .mapTo(this) { it.activityInfo.packageName }
        }.distinct()
        return candidates.firstOrNull { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        } ?: error("APP_NOT_INSTALLED: $requested")
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

    companion object {
        private val APP_ALIASES = mapOf(
            setOf("clock", "deskclock", "时钟", "com.android.deskclock", "com.google.android.deskclock") to
                listOf("com.google.android.deskclock", "com.android.deskclock"),
            setOf("meituan", "美团") to listOf("com.sankuai.meituan"),
            setOf("xiaohongshu", "rednote", "小红书") to listOf("com.xingin.xhs"),
            setOf("douyin", "抖音") to listOf("com.ss.android.ugc.aweme"),
            setOf("wechat", "weixin", "微信") to listOf("com.tencent.mm"),
            setOf("chrome", "google chrome", "谷歌浏览器", "com.android.chrome") to listOf("com.android.chrome"),
        )
    }
}
