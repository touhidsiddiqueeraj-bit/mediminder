package com.mediminder.app.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.domain.usecase.CheckLowStockUseCase
import com.mediminder.app.domain.usecase.MarkMissedDosesUseCase
import com.mediminder.app.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Periodic maintenance (every 6h): marks missed doses, refreshes the alarm
 * plan and warns about low stock. The exact alarms remain the primary
 * mechanism — this worker only guarantees eventual correctness.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val reminderScheduler: ReminderScheduler,
    private val markMissedDosesUseCase: MarkMissedDosesUseCase,
    private val checkLowStockUseCase: CheckLowStockUseCase,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        return try {
            markMissedDosesUseCase()
            reminderScheduler.rescheduleAllAlarms()
            checkLowStockUseCase().forEach(notificationHelper::showLowStock)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        /** Every-6-hours periodic maintenance; kept across reboots by WorkManager. */
        fun enqueuePeriodic(context: Context) {
            val request: PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                ReminderPlanner.MAINTENANCE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** One-time sync after app start / app update. */
        fun enqueueOneTimeSync(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<MaintenanceWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                STARTUP_SYNC_WORK,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        private const val STARTUP_SYNC_WORK = "mediminder_startup_sync"
    }
}

/**
 * Fallback while the exact-alarm permission is missing: every 30 minutes,
 * notify any pending dose whose slot arrived but which was never notified
 * (reminderAttempts == 0 — after that, the inexact re-remind chain owns it).
 */
@HiltWorker
class SafetyNetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val doseLogRepository: DoseLogRepository,
    private val clockProvider: ClockProvider,
    private val reminderTrigger: ReminderTrigger,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        return try {
            val now = LocalDateTime.now(clockProvider.clock)
            val candidates = doseLogRepository.getScheduledDosesBetween(
                now.minusHours(2),
                now,
            ).filter { it.reminderAttempts == 0 }
            candidates.forEach { dose -> reminderTrigger.fire(dose.id) }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
