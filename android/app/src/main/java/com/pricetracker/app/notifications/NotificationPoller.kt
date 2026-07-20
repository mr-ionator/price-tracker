package com.pricetracker.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pricetracker.app.MainActivity
import com.pricetracker.app.R
import com.pricetracker.app.data.Repository
import java.util.concurrent.TimeUnit

const val CHANNEL_ID = "price_alerts"
private const val WORK_NAME = "notification-poller"

fun ensureNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Price alerts",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = "Price drops, increases and target-price alerts" }
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}

/** Poll the backend every 15 minutes and surface new events as notifications. */
fun scheduleNotificationPolling(context: Context) {
    val request = PeriodicWorkRequestBuilder<NotificationPoller>(15, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

class NotificationPoller(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = Repository(applicationContext)
        val pending = try {
            repository.undeliveredNotifications()
        } catch (_: Exception) {
            return Result.retry() // backend unreachable; try next period
        }
        if (pending.isEmpty()) return Result.success()

        val canPost = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (canPost) {
            val manager = NotificationManagerCompat.from(applicationContext)
            pending.forEach { event ->
                val intent = Intent(applicationContext, MainActivity::class.java)
                val contentIntent = PendingIntent.getActivity(
                    applicationContext,
                    event.id,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(event.title)
                    .setContentText(event.body.lineSequence().first())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()
                manager.notify(event.id, notification)
            }
        }

        // Mark delivered even without POST_NOTIFICATIONS permission, otherwise
        // the same events would pile up forever; they stay visible in-app.
        try {
            repository.markDelivered(pending.map { it.id })
        } catch (_: Exception) {
            return Result.retry()
        }
        return Result.success()
    }
}
