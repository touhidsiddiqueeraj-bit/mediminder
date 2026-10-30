package com.mediminder.app.scheduling

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.engine.DoseGenerationEngine
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.notifications.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements the domain's [ReminderScheduler]. Idempotent — safe to call after
 * every change, on app start, after boot, timezone change or app update.
 *
 * Pipeline:
 * 1. regenerate dose rows for the planning window (today + 2 days)
 * 2. prune future scheduled rows that are no longer wanted
 * 3. register an exact alarm for every pending dose in the window
 * 4. keep the WorkManager safety net aligned with the exact-alarm permission
 */
@Singleton
class ReminderPlanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val engine: DoseGenerationEngine,
    private val alarmScheduler: AlarmScheduler,
    private val notificationHelper: NotificationHelper,
    private val clockProvider: ClockProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ReminderScheduler {

    override suspend fun rescheduleAllAlarms() = withContext(ioDispatcher) {
        val now = LocalDateTime.now(clockProvider.clock)
        val today = now.toLocalDate()
        val windowEnd = today.plusDays(PLANNING_WINDOW_DAYS.toLong())

        // 1. Regenerate dose rows for the window (past rows of today included:
        //    IGNORE keeps existing history and reminder attempts).
        val medications = medicationRepository.getActiveMedications()
        val generated = engine.generate(medications, today, windowEnd)
        val doseIds = doseLogRepository.ensureScheduledDoses(generated)
        val keepIdsByMedication = generated.indices.groupBy(
            keySelector = { generated[it].medicationId },
            valueTransform = { doseIds[it] },
        )

        // 2. Prune future scheduled rows that are not wanted anymore.
        medications.forEach { medication ->
            doseLogRepository.pruneFutureScheduledDoses(
                medicationId = medication.id,
                keepDoseIds = keepIdsByMedication[medication.id].orEmpty(),
                from = now,
            )
        }
        doseLogRepository.pruneFutureScheduledForInactiveMedications(now)

        // 3. One exact alarm per pending dose in the window.
        val zone = clockProvider.clock.zone
        val windowStart = today.atStartOfDay()
        val windowEndExclusive = windowEnd.plusDays(1).atStartOfDay()
        doseLogRepository.getScheduledDosesBetween(windowStart, windowEndExclusive)
            .filter { !it.scheduledTime.isBefore(now) }
            .forEach { dose ->
                val triggerAtMillis = dose.scheduledTime.atZone(zone).toInstant().toEpochMilli()
                alarmScheduler.scheduleDoseReminder(dose.id, dose.medicationId, triggerAtMillis)
            }

        // 4. Safety net only while exact alarms are unavailable.
        ensureSafetyNetWorker()
    }

    override suspend fun onDoseHandled(doseId: Long) = withContext(ioDispatcher) {
        alarmScheduler.cancelDoseReminder(doseId)
        notificationHelper.cancelDoseNotification(doseId)
    }

    override suspend fun scheduleSnooze(
        doseId: Long,
        medicationId: Long,
        minutesFromNow: Int,
    ) = withContext(ioDispatcher) {
        val triggerAt = clockProvider.clock.millis() + minutesFromNow * 60_000L
        alarmScheduler.scheduleDoseReminder(doseId, medicationId, triggerAt)
    }

    private fun ensureSafetyNetWorker() {
        val workManager = WorkManager.getInstance(context)
        if (alarmScheduler.canScheduleExact()) {
            workManager.cancelUniqueWork(SAFETY_NET_WORK_NAME)
        } else {
            val request = PeriodicWorkRequestBuilder<SafetyNetWorker>(
                SAFETY_NET_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                SAFETY_NET_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    companion object {
        const val PLANNING_WINDOW_DAYS = 2L
        const val MAINTENANCE_WORK_NAME = "mediminder_maintenance"
        const val SAFETY_NET_WORK_NAME = "mediminder_safety_net"
        const val SAFETY_NET_INTERVAL_MINUTES = 30L
    }
}
