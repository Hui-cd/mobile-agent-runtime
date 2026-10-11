package ai.mobileagent.virtualdisplay

import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

class HiddenExecutionSession(private val context: Context) {
    private var broker: IExecutionBroker? = null
    private var connection: ServiceConnection? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ExecutionBroker::class.java))
        .daemon(false).processNameSuffix("execution").tag("execution-v1").version(4)
    var displayId = 0
        private set
    suspend fun start(): Int {
        if (displayId != 0) { check(Shizuku.pingBinder() && broker?.asBinder()?.isBinderAlive == true) { "SHIZUKU_DIED" }; return displayId }
        val running = Shizuku.pingBinder()
        ShizukuAccessPolicy.requireConnectable(android.os.Build.VERSION.SDK_INT, running, running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        val ready = CompletableDeferred<IExecutionBroker>()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.complete(IExecutionBroker.Stub.asInterface(service)) }
            override fun onServiceDisconnected(name: ComponentName) { broker = null; ready.completeExceptionally(IllegalStateException("SHIZUKU_DIED")) }
        }
        connection = callback
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, callback) }
        broker = withTimeout(15_000) { ready.await() }
        displayId = withContext(Dispatchers.IO) { broker!!.createDisplay() }
        check(displayId > 0) { "INVALID_EXECUTION_DISPLAY" }
        return displayId
    }
    fun launch(intent: Intent) { check(displayId > 0); (broker ?: error("SHIZUKU_DIED")).launch(intent) }
    fun key(keyCode: Int) { check(displayId > 0); (broker ?: error("SHIZUKU_DIED")).key(keyCode) }
    fun install(apk: android.os.ParcelFileDescriptor, size: Long) = (broker ?: error("SHIZUKU_DIED")).install(apk, size)
    fun close() {
        runCatching { broker?.releaseDisplay() }
        connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        broker = null; connection = null; displayId = 0
    }
    companion object {
        fun enabled(context: Context) = context.getSharedPreferences("execution", Context.MODE_PRIVATE).getBoolean("hidden", false)
        fun setEnabled(context: Context, value: Boolean) { context.getSharedPreferences("execution", Context.MODE_PRIVATE).edit().putBoolean("hidden", value).apply() }
    }
}
