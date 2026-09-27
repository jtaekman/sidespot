package com.sidespot.offline

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.sidespot.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive, awake and on Wi-Fi while [DownloadManager] downloads,
 * with a notification showing progress. The downloads themselves run in
 * [DownloadManager]; this service stops itself once they stop.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "SidespotDownloads"
        private const val CHANNEL_ID = "sidespot_downloads"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // The app is in the background; downloads carry on for as long as
                // the process lives, just without the service's protection.
                Log.i(TAG, "can't start download service from the background")
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** The most recent start request; stopping with an older one is ignored. */
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sidespot::downloads")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sidespot::downloads")
            .apply { setReferenceCounted(false); acquire() }

        scope.launch {
            DownloadManager.get().state.collect { state ->
                if (state.status == DownloadStatus.DOWNLOADING) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(state.remaining))
                } else {
                    stopIfIdle()
                }
            }
        }
    }

    /**
     * Stop unless downloading. Only the latest start request can stop the service,
     * so a start still on its way (downloads resumed right after stopping) keeps
     * it running, as Android requires once startForegroundService() was called.
     */
    private fun stopIfIdle() {
        if (DownloadManager.get().state.value.status == DownloadStatus.DOWNLOADING) return
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every startForegroundService() call must be answered with startForeground().
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(DownloadManager.get().state.value.remaining),
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Downloads may have stopped again before this start arrived.
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress of downloads for offline listening"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(remaining: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading for offline listening")
            .setContentText(if (remaining == 1) "1 track left" else "$remaining tracks left")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
