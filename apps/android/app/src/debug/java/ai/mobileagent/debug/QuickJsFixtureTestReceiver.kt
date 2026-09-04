package ai.mobileagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.mobileagent.pi.QuickJsPiCoreFixtureRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QuickJsFixtureTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                val result = QuickJsPiCoreFixtureRunner.run(context.applicationContext)
                Log.i(TAG, "fixture accepted; platform=${result.observedPlatform} finalText=${result.finalText}")
            } catch (error: Throwable) {
                Log.e(TAG, "fixture failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val ACTION = "ai.mobileagent.DEBUG_RUN_QUICKJS_FIXTURE"
        const val TAG = "MobileAgentQuickJsTest"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
