package ai.mobileagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.mobileagent.pi.PiCoreFixtureRunner

class RendererTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val terminated = PiCoreFixtureRunner.terminateRendererForTest()
        Log.i(TAG, "renderer termination requested; accepted=$terminated")
    }

    private companion object {
        const val ACTION = "ai.mobileagent.DEBUG_TERMINATE_RENDERER"
        const val TAG = "MobileAgentRendererTest"
    }
}
