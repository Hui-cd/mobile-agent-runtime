package ai.mobileagent.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.mobileagent.MainActivity
import ai.mobileagent.R
import ai.mobileagent.model.AgentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var taskObserved = false
    private var terminalNotificationPublished = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "任务执行", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示 Agent 的执行进度和停止入口"
                },
            )
            createNotificationChannel(
                NotificationChannel(ATTENTION_CHANNEL_ID, "需要处理", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "在 Agent 需要确认或任务失败时提醒你"
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentSession.stop()
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(AgentNotificationContent("正在停止任务", "正在结束当前执行")),
            )
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            notification(AgentNotificationContent("Mobile Agent 正在启动", "正在准备任务")),
        )
        observer?.cancel()
        observer = scope.launch {
            AgentSession.state.collectLatest { state ->
                if (state.running) taskObserved = true
                val content = AgentNotificationPolicy.content(state)
                if (state.running) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(content),
                    )
                } else if (taskObserved || state.status in TERMINAL_STATES) {
                    publishTerminal(content)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(content: AgentNotificationContent): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val open = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, if (content.attention) ATTENTION_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(open)
            .setOngoing(content.ongoing)
            .setAutoCancel(!content.ongoing)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(if (content.attention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .apply {
                if (content.attention && content.ongoing) addAction(0, "查看", open)
                if (content.showStop) addAction(0, "停止", stop)
            }
            .build()
    }

    private fun publishTerminal(content: AgentNotificationContent) {
        if (terminalNotificationPublished) return
        terminalNotificationPublished = true
        observer?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
        stopSelf()
    }

    override fun onDestroy() {
        observer?.cancel()
        if (!terminalNotificationPublished) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "agent_tasks"
        const val ATTENTION_CHANNEL_ID = "agent_attention"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "ai.mobileagent.STOP"
        private val TERMINAL_STATES = setOf(AgentStatus.COMPLETE, AgentStatus.ERROR, AgentStatus.CANCELLED)
    }
}
