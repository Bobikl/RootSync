package com.rootsync.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.rootsync.android.MainActivity
import com.rootsync.android.engine.RootSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class TransferForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var incomingTransfer = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootSync:Transfer")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_LOCK_MILLIS) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RootSync:TransferWifi")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        incomingTransfer = intent?.getBooleanExtra(EXTRA_INCOMING, false) ?: incomingTransfer
        val notification = buildNotification(
            title = intent?.getStringExtra(EXTRA_TITLE) ?: "RootSync 正在传输",
            detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "正在保持后台网络传输",
            eta = intent?.getStringExtra(EXTRA_ETA),
            progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.launch {
            if (!incomingTransfer) {
                markTransferPaused("系统后台传输时限到达，已自动暂停，可重新打开后继续")
            }
            withTimeoutOrNull(2_000L) {
                RootSyncEngine(applicationContext).pauseTransfer()
            }
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        serviceScope.launch {
            if (!incomingTransfer) {
                markTransferPaused("应用任务已被移除，底层 rsync 已终止，可重新打开后继续")
            }
            withTimeoutOrNull(4_000L) {
                RootSyncEngine(applicationContext).cleanupStaleRuntimeProcesses {}
            }
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun markTransferPaused(message: String) {
        val preferences = getSharedPreferences("rootsync", 0)
        preferences.getString("transferRecord", null)?.let { raw ->
            runCatching {
                val updated = JSONObject(raw)
                    .put("status", "PAUSED")
                    .put("updatedAtMillis", System.currentTimeMillis())
                    .put("message", message)
                preferences.edit { putString("transferRecord", updated.toString()) }
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文件同步进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示 RootSync 后台传输进度和预计完成时间"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String,
        detail: String,
        eta: String?,
        progress: Int
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(listOfNotNull(detail, eta).joinToString(" · "))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(detail, eta).joinToString("\n")
                )
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rootsync_transfer"
        private const val COMPLETION_CHANNEL_ID = "rootsync_completion"
        private const val NOTIFICATION_ID = 8873
        private const val COMPLETION_NOTIFICATION_ID = 8875
        private const val ACTION_UPDATE = "com.rootsync.android.action.UPDATE_TRANSFER"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"
        private const val EXTRA_ETA = "eta"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_INCOMING = "incoming"
        private const val MAX_WAKE_LOCK_MILLIS = 6L * 60L * 60L * 1_000L

        fun update(
            context: Context,
            title: String,
            detail: String,
            eta: String?,
            progress: Float?,
            incoming: Boolean = false
        ) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DETAIL, detail)
                .putExtra(EXTRA_ETA, eta)
                .putExtra(EXTRA_PROGRESS, progress?.times(100)?.toInt() ?: -1)
                .putExtra(EXTRA_INCOMING, incoming)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferForegroundService::class.java))
        }

        fun notifyCompleted(context: Context, title: String, detail: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    COMPLETION_CHANNEL_ID,
                    "同步完成提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "仅在数据完整性校验通过后提醒同步完成" }
            )
            val openApp = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                COMPLETION_NOTIFICATION_ID,
                NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .build()
            )
        }
    }
}
