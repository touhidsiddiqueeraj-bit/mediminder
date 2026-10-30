package com.mediminder.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mediminder.app.notifications.NotificationHelper
import com.mediminder.app.scheduling.MaintenanceWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MediMinderApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var notificationHelper: NotificationHelper

    /** WorkManager with Hilt-injected workers (on-demand initialization). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Channels exist before the first reminder could ever fire.
        notificationHelper.ensureChannels()
        // Re-plan alarms + mark missed doses shortly after every app start,
        // and every 6 hours while the device is used (survives reboots).
        MaintenanceWorker.enqueueOneTimeSync(this)
        MaintenanceWorker.enqueuePeriodic(this)
    }
}
