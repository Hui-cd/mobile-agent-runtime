package ai.mobileagent.agent

import android.content.Context
import android.util.Log
import ai.mobileagent.BuildConfig
import kotlinx.coroutines.delay

object DebugModelDelay {
    private const val PREFERENCES = "debug-model-delay"
    private const val NEXT_DELAY_MS = "next-delay-ms"
    private const val MAX_DELAY_MS = 5 * 60 * 1_000L

    fun schedule(context: Context, delayMs: Long): Long {
        check(BuildConfig.DEBUG) { "DEBUG_MODEL_DELAY_NOT_AVAILABLE" }
        val bounded = delayMs.coerceIn(0L, MAX_DELAY_MS)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putLong(NEXT_DELAY_MS, bounded).apply()
        return bounded
    }

    suspend fun consume(context: Context?) {
        if (!BuildConfig.DEBUG || context == null) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val delayMs = preferences.getLong(NEXT_DELAY_MS, 0L)
        preferences.edit().remove(NEXT_DELAY_MS).apply()
        if (delayMs <= 0L) return
        Log.i("MobileAgentPi", "debug model delay started; delayMs=$delayMs")
        delay(delayMs)
        Log.i("MobileAgentPi", "debug model delay finished; delayMs=$delayMs")
    }
}
