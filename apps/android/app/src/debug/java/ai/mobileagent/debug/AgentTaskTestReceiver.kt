package ai.mobileagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Base64
import ai.mobileagent.session.AgentSession

class AgentTaskTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prompt = intent.getStringExtra(EXTRA_PROMPT_BASE64)
            ?.let { encoded -> runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }
            ?.trim()
            .orEmpty()
        if (prompt.isBlank()) {
            Log.e(TAG, "task rejected: missing prompt")
            return
        }
        Log.i(TAG, "task accepted; promptLength=${prompt.length}")
        AgentSession.start(context.applicationContext, prompt)
    }

    private companion object {
        const val ACTION = "ai.mobileagent.DEBUG_RUN_AGENT_TASK"
        const val EXTRA_PROMPT_BASE64 = "prompt_base64"
        const val TAG = "MobileAgentTaskTest"
    }
}
