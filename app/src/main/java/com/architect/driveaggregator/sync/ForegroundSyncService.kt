package com.architect.driveaggregator.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.architect.driveaggregator.auth.DefaultOAuthNetworkClient
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.engine.VirtualDriveEngine
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.upload.GoogleResumableUploadManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * ForegroundSyncService acts as the core background orchestrator for MDrive backups.
 * Runs as a foreground service with android:foregroundServiceType="dataSync", acquiring
 * PowerManager.WakeLock and WifiManager.WifiLock to ensure execution resilience during
 * screen-off and network transitions.
 */
@AndroidEntryPoint
class ForegroundSyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Inject
    lateinit var syncOrchestrator: SyncOrchestrator

    companion object {
        private const val TAG = "ForegroundSyncService"
        private const val CHANNEL_ID = "mdrive_sync_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYNC = "com.architect.driveaggregator.action.START_SYNC"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_VIRTUAL_FILE_ID = "extra_virtual_file_id"

        fun startSync(context: Context, filePath: String, virtualFileId: String) {
            val intent = Intent(context, ForegroundSyncService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_VIRTUAL_FILE_ID, virtualFileId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ForegroundSyncService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("MDrive Sync Active", "Synchronizing files securely across Google Drive accounts...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireLocks()

        val action = intent?.action
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH)
        val virtualFileId = intent?.getStringExtra(EXTRA_VIRTUAL_FILE_ID)

        if (action == ACTION_START_SYNC && filePath != null && virtualFileId != null) {
            serviceScope.launch {
                try {
                    Log.i(TAG, "Starting sync orchestration for file: $filePath (ID: $virtualFileId)")
                    syncOrchestrator.coordinateSync(filePath, virtualFileId) { progress, message ->
                        updateNotification("Synchronizing: $progress%", message)
                    }
                    Log.i(TAG, "Sync orchestration completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Sync orchestration failed", e)
                    updateNotification("Sync Failed", e.localizedMessage ?: "Unknown error")
                } finally {
                    releaseLocks()
                    stopSelf(startId)
                }
            }
        } else {
            serviceScope.launch {
                try {
                    Log.i(TAG, "Processing pending sync items...")
                    syncOrchestrator.processPendingSyncs { progress, message ->
                        updateNotification("Syncing: $progress%", message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Pending sync processing failed", e)
                } finally {
                    releaseLocks()
                    stopSelf(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ForegroundSyncService destroyed")
        releaseLocks()
        serviceScope.cancel()
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MDrive::SyncWakeLock"
                ).apply {
                    acquire(30 * 60 * 1000L) // 30 mins max timeout safety
                }
                Log.d(TAG, "WakeLock acquired")
            }

            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "MDrive::SyncWifiLock"
                ).apply {
                    acquire()
                }
                Log.d(TAG, "WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire power or wifi locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiLock released")
                }
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release locks", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MDrive Foreground Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps MDrive synchronization running reliably in the background"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
