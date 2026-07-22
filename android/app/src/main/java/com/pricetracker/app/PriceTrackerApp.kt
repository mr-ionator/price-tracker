package com.pricetracker.app

import android.app.Application
import com.pricetracker.app.notifications.ensureNotificationChannel
import com.pricetracker.app.notifications.schedulePeriodicChecks

class PriceTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        schedulePeriodicChecks(this)
    }
}
