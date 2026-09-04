package ai.mobileagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.mobileagent.agent.DebugModelDelay

class ModelDelayTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val delayMs = DebugModelDelay.schedule(context, intent.getLongExtra(EXTRA_DELAY_MS, 0L))
        Log.i(TAG, "next model delay scheduled; delayMs=$delayMs")
    }

    private companion object {
        const val ACTION = "ai.mobileagent.DEBUG_DELAY_NEXT_MODEL"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val TAG = "MobileAgentModelDelayTest"
    }
}
