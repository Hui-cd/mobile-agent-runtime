package ai.mobileagent.virtualdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.AttributionSource
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.*
import android.view.*

/** On-device shell broker. Only typed operations on its owned display; no shell command API. */
class ExecutionBroker(private val clientContext: Context) : IExecutionBroker.Stub() {
    private val ownerUid = clientContext.applicationInfo.uid
    private val worker = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(worker.looper)
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private fun <T> authorized(block: () -> T): T {
        check(Binder.getCallingUid() == ownerUid) { "BROKER_CALLER_DENIED" }
        val token = Binder.clearCallingIdentity()
        try { return block() } finally { Binder.restoreCallingIdentity(token) }
    }
    private fun shellContext(): Context {
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getMethod("currentActivityThread").invoke(null)
        val system = thread.getMethod("getSystemContext").invoke(instance) as Context
        return object : ContextWrapper(system) {
            override fun getPackageName() = "com.android.shell"
            override fun getOpPackageName() = "com.android.shell"
            override fun getAttributionSource() = AttributionSource.Builder(Process.myUid()).setPackageName(packageName).build()
            override fun getApplicationContext(): Context = this
        }
    }
    @Synchronized override fun createDisplay(): Int = authorized {
        check(Build.VERSION.SDK_INT >= 34) { "HIDDEN_EXECUTION_REQUIRES_ANDROID_14" }
        display?.let { return@authorized it.display.displayId }
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val manager = ctor.newInstance(shellContext())
        val metrics = android.util.DisplayMetrics()
        manager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val image = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        image.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.close() }, handler)
        fun flag(name: String) = DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_$name").getInt(null)
        // Independent focus without ALWAYS_UNLOCKED; screen lock remains a runtime barrier.
        val flags = listOf("PUBLIC", "PRESENTATION", "OWN_CONTENT_ONLY", "SUPPORTS_TOUCH", "TRUSTED", "OWN_DISPLAY_GROUP", "OWN_FOCUS", "DESTROY_CONTENT_ON_REMOVAL", "STEAL_TOP_FOCUS_DISABLED").fold(0) { n, name -> n or flag(name) }
        try {
            val created = manager.createVirtualDisplay("Mobile Agent execution", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, image.surface, flags)
                ?: error("VIRTUAL_DISPLAY_CREATION_FAILED")
            display = created
            reader = image
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, created.display.displayId, 0)
            created.display.displayId
        } catch (error: Throwable) { display?.release(); display = null; image.close(); reader = null; throw error }
    }
    @Synchronized override fun launch(intent: Intent) = authorized {
        val id = display?.display?.displayId ?: error("EXECUTION_DISPLAY_GONE")
        check(intent.action in setOf(Intent.ACTION_MAIN, Intent.ACTION_VIEW, Intent.ACTION_DIAL, Intent.ACTION_SEND, android.provider.Settings.ACTION_SETTINGS, android.provider.Settings.ACTION_WIFI_SETTINGS, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) { "BROKER_INTENT_DENIED" }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val method = am.javaClass.getMethod("startActivityAsUser", Class.forName("android.app.IApplicationThread"), String::class.java, Intent::class.java, String::class.java, IBinder::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Class.forName("android.app.ProfilerInfo"), Bundle::class.java, Int::class.javaPrimitiveType)
        val result = method.invoke(am, null, "com.android.shell", intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), intent.type, null, null, 0, 0, null, options, -2) as Int
        check(result >= 0) { "APP_LAUNCH_FAILED: $result" }
    }
    @Synchronized override fun key(keyCode: Int) = authorized {
        check(keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) { "BROKER_KEY_DENIED" }
        val id = display?.display?.displayId ?: error("EXECUTION_DISPLAY_GONE")
        val clazz = Class.forName("android.hardware.input.InputManagerGlobal")
        val input = clazz.getMethod("getInstance").invoke(null)
        val setDisplay = InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
        val inject = clazz.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, keyCode, 0)
            setDisplay.invoke(event, id)
            check(inject.invoke(input, event, 2) == true) { "DISPLAY_KEY_REJECTED" }
        }
    }
    @Synchronized override fun releaseDisplay() = authorized { display?.release(); display = null; reader?.close(); reader = null }
    override fun install(apk: ParcelFileDescriptor, size: Long): String = authorized {
        require(size in 1..500_000_000) { "APK_SIZE_INVALID" }
        val process = ProcessBuilder("/system/bin/pm", "install", "-S", size.toString()).redirectErrorStream(true).start()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(apk).use { input -> process.outputStream.use { output -> input.copyTo(output) } }
            check(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) { "INSTALL_TIMEOUT" }
            val output = process.inputStream.bufferedReader().readText().take(4000)
            check(process.exitValue() == 0 && output.trim() == "Success") { "INSTALL_FAILED: $output" }
            output
        } finally { process.destroy(); apk.close() }
    }
    override fun destroy() {
        // Shizuku server invokes lifecycle cleanup as shell; operational calls remain owner-only.
        check(Binder.getCallingUid() in setOf(ownerUid, Process.myUid())) { "BROKER_CALLER_DENIED" }
        display?.release(); reader?.close(); worker.quitSafely(); kotlin.system.exitProcess(0)
    }
}
