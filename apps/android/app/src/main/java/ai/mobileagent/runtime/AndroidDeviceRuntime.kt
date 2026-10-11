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
import org.json.JSONArray

class AndroidDeviceRuntime(private val context: Context) {
    private val hidden = if (ai.mobileagent.virtualdisplay.HiddenExecutionSession.enabled(context)) ai.mobileagent.virtualdisplay.HiddenExecutionSession(context) else null
    private suspend fun executionDisplay() = hidden?.start() ?: 0
    fun close() { hidden?.close() }

    suspend fun approvalArguments(name: String, arguments: JSONObject): JSONObject {
        if (name != "device_act" || arguments.optString("action") !in setOf("click", "long_press")) return arguments
        requireUiAvailable()
        val target = awaitAccessibilityService().approvalTarget(arguments, executionDisplay())
        return JSONObject(arguments.toString()).put("target", target)
    }

    suspend fun execute(name: String, arguments: JSONObject): ToolExecution = when (name) {
        "device_observe" -> observe(arguments.optBoolean("include_screen", false), arguments.optBoolean("include_apps", false))
        "device_act" -> {
            requireUiAvailable()
            val service = awaitAccessibilityService()
            val displayId = executionDisplay()
            val action = arguments.optString("action")
            val observation = if (hidden != null && action in setOf("back", "home")) {
                hidden.key(if (action == "back") android.view.KeyEvent.KEYCODE_BACK else android.view.KeyEvent.KEYCODE_HOME)
                delay(700)
                service.observe(arguments.optBoolean("include_screen", false), displayId)
            } else service.act(arguments, displayId)
            ToolExecution(observation.toJson().put("display_id", displayId).put("execution_mode", if (hidden != null) "hidden" else "foreground").toString(), observation.screenshotDataUrl)
        }
        "device_invoke" -> invoke(arguments)
        else -> error("UNKNOWN_TOOL: $name")
    }

    private suspend fun observe(includeScreen: Boolean, includeApps: Boolean = false): ToolExecution {
        val service = awaitAccessibilityService()
        val observation = service.observe(includeScreen, executionDisplay())
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        val json = observation.toJson()
            .put("display_id", hidden?.displayId ?: 0)
            .put("execution_mode", if (hidden != null) "hidden" else "foreground")
            .put("device_state", JSONObject()
                .put("screen_interactive", interactive)
                .put("device_locked", locked))
        if (includeApps) {
            val apps = launchableApps()
            json.put("apps", JSONArray(apps.take(200).map {
                JSONObject().put("package", it.packageName).put("label", it.label)
            })).put("apps_truncated", apps.size > 200)
        }
        if (includeScreen) {
            json.put("screen_attached", observation.screenshotDataUrl != null)
            if (observation.screenshotDataUrl == null) json.put("screen_error", "SCREENSHOT_UNAVAILABLE")
        }
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
        val downloads = AppDownloads(context)
        when (capability) {
            "download_app" -> return ToolExecution(downloads.download(params).toString())
            "download_status" -> return ToolExecution(downloads.status(params.getLong("download_id")).toString())
            "install_app" -> {
                val id = params.getLong("download_id")
                check(downloads.status(id).getString("status") == "downloaded") { "DOWNLOAD_NOT_READY" }
                val file = downloads.file(id)
                val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("INVALID_APK")
                val expected = params.getString("package")
                check(archive.packageName == expected) { "APK_PACKAGE_MISMATCH" }
                check(hidden != null) { "INSTALL_REQUIRES_SHIZUKU_MODE" }
                executionDisplay()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { hidden.install(it, file.length()) }
                }
                val installed = context.packageManager.getPackageInfo(expected, 0)
                return ToolExecution(JSONObject().put("installed", true).put("package", installed.packageName).put("version", installed.versionName).toString())
            }
        }
        val launchPackage = if (capability == "open_app") resolveLaunchPackage(params) else null
        if (hidden == null && capability == "open_app" && Build.VERSION.SDK_INT >= 33) {
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
        if (hidden != null) { executionDisplay(); hidden.launch(intent) } else context.startActivity(intent)
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
        return AppCatalog.resolve(launchableApps(), requested)
    }

    private fun launchableApps(): List<LaunchableApp> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        .filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
        .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }

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
