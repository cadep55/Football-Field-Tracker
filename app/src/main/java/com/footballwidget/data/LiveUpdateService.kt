package com.footballwidget.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.work.*
import com.footballwidget.widget.FootballWidgetProvider
import java.util.concurrent.TimeUnit

class LiveUpdateService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            FootballWidgetProvider.updateAllWidgets(this@LiveUpdateService)
            if (WidgetPrefs.hasAnyLiveGame(this@LiveUpdateService)) {
                handler.postDelayed(this, 30_000)
            } else {
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            createChannel()
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Football Widget")
                .setContentText("Live game tracking active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
            startForeground(NOTIFICATION_ID, notification)
            handler.post(pollRunnable)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live Game Updates",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Tracks live football games" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "football_live"
        private const val NOTIFICATION_ID = 1001

        fun startIfLive(context: Context) {
            if (WidgetPrefs.hasAnyLiveGame(context)) {
                val intent = Intent(context, LiveUpdateService::class.java)
                context.startForegroundService(intent)
            }
        }

        fun scheduleCheck(context: Context) {
            val request = PeriodicWorkRequestBuilder<GameCheckWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "football_game_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

class GameCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        FootballWidgetProvider.updateAllWidgets(applicationContext)
        LiveUpdateService.startIfLive(applicationContext)
        return Result.success()
    }
}
