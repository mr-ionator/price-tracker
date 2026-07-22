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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pricetracker.app.MainActivity
import com.pricetracker.app.R
import com.pricetracker.app.data.Repository
import java.util.concurrent.TimeUnit

const val CHANNEL_ID = "price_alerts"
private const val PERIODIC_WORK = "price-check-periodic"
private const val IMMEDIATE_WORK = "price-check-now"

private const val PREFS_NAME = "price_tracker_settings"
private const val KEY_INTERVAL_MINUTES = "check_interval_minutes"

const val DEFAULT_CHECK_INTERVAL_MINUTES = 180

data class CheckIntervalOption(val label: String, val minutes: Int)

/** Selectable background-check frequencies. 15 min is WorkManager's floor. */
val CHECK_INTERVAL_OPTIONS = listOf(
    CheckIntervalOption("Every 15 minutes", 15),
    CheckIntervalOption("Every 30 minutes", 30),
    CheckIntervalOption("Every hour", 60),
    CheckIntervalOption("Every 3 hours", 180),
    CheckIntervalOption("Every 6 hours", 360),
    CheckIntervalOption("Every 12 hours", 720),
    CheckIntervalOption("Once a day", 1440),
)

fun getCheckIntervalMinutes(context: Context): Int =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_INTERVAL_MINUTES, DEFAULT_CHECK_INTERVAL_MINUTES)

/** Persist a new interval and re-schedule the background job to match. */
fun applyCheckIntervalMinutes(context: Context, minutes: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putInt(KEY_INTERVAL_MINUTES, minutes).apply()
    reschedulePeriodicChecks(context)
}

fun ensureNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Price alerts",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = "Price drops, increases and target-price alerts" }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

private fun networkConstraint() = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

private fun periodicRequest(context: Context): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<PriceCheckWorker>(
        getCheckIntervalMinutes(context).toLong(), TimeUnit.MINUTES,
    ).setConstraints(networkConstraint()).build()

/** Schedule background checks if not already scheduled (called on app start). */
fun schedulePeriodicChecks(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_WORK,
        ExistingPeriodicWorkPolicy.KEEP,
        periodicRequest(context),
    )
}

/** Replace the scheduled job after the user changes the interval. */
fun reschedulePeriodicChecks(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_WORK,
        ExistingPeriodicWorkPolicy.UPDATE,
        periodicRequest(context),
    )
}

/** Run a check right now (used by the "Check all prices now" button). */
fun enqueueImmediateCheck(context: Context) {
    val request = OneTimeWorkRequestBuilder<PriceCheckWorker>()
        .setConstraints(networkConstraint())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        IMMEDIATE_WORK,
        ExistingWorkPolicy.REPLACE,
        request,
    )
}

class PriceCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val events = try {
            Repository(applicationContext).checkAll()
        } catch (e: Exception) {
            return Result.retry()
        }
        if (events.isEmpty()) return Result.success()

        val canPost = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!canPost) return Result.success()

        val manager = NotificationManagerCompat.from(applicationContext)
        events.forEach { event ->
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
        return Result.success()
    }
}
