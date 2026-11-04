package app.claudroid.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.claudroid.R
import app.claudroid.ui.MainActivity

/**
 * Keeps a running turn alive when the user leaves the app and gives it a visible
 * notification. Android 8+ kills background processes aggressively; without this,
 * a long `magisk --install-module` would be interrupted the moment the user switches away.
 */
class TurnService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在执行…", "Claudroid Agent"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "正在执行…"
        val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Claudroid Agent"
        notificationManager().notify(NOTIFICATION_ID, buildNotification(title, detail))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, detail: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent 运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示正在执行的任务"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "claudroid_turns"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"

        fun start(context: Context, title: String, detail: String) {
            val intent = Intent(context, TurnService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DETAIL, detail)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TurnService::class.java)) }
        }
    }
}
