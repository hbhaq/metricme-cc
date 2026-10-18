package com.hbhaq.metricme

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.hbhaq.metricme.reminders.ReminderCoordinator
import javax.inject.Inject

@HiltAndroidApp
class MetricMeApplication : Application() {
    @Inject lateinit var reminders: ReminderCoordinator
    override fun onCreate() { super.onCreate(); reminders.start() }
}
