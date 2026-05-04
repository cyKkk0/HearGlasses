package com.hearglasses.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hearglasses.app.MainActivity
import com.hearglasses.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HearGlassesService : Service() {

    private lateinit var container: AppContainer
    private val scope = CoroutineScope(Dispatchers.Main)
    private var stateUpdateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.getInstance(this)
        startForeground(NOTIFICATION_ID, buildNotification("准备就绪"))
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = container.controller
        when {
            intent?.action == ACTION_STOP -> {
                controller.stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                controller.startListening()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        container.controller.stopListening()
        stateUpdateJob?.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        stateUpdateJob?.cancel()
        stateUpdateJob = scope.launch {
            container.controller.uiState.collectLatest { state ->
                val statusText = when {
                    state.transcriptItems.any { it.isActive } ->
                        "识别中: ${state.transcriptItems.lastOrNull { it.isActive }?.text ?: ""}"
                    state.transcriptItems.isNotEmpty() ->
                        "最近: ${state.transcriptItems.last().text.take(40)}"
                    state.isListening -> "收听中…"
                    else -> "准备就绪"
                }
                updateNotification(statusText)
            }
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "HearGlasses",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HearGlassesService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HearGlasses")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "hearglasses-service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.hearglasses.app.STOP_SERVICE"
    }
}
