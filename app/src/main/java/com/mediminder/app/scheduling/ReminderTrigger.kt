package com.mediminder.app.scheduling

import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.DoseEventListener
import com.mediminder.app.domain.service.ReRemindPolicy
import com.mediminder.app.notifications.NotificationHelper
import com.mediminder.app.tts.ReminderAnnouncer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place where a reminder "fires". Shared by the exact-alarm
 * receiver and the WorkManager safety net so both paths behave identically:
 *
 *   notify -> count attempt -> schedule re-remind -> ... -> mark MISSED
 */
@Singleton
class ReminderTrigger @Inject constructor(
    private val doseLogRepository: DoseLogRepository,
    private val medicationRepository: MedicationRepository,
    private val settingsRepository: SettingsRepository,
    private val reRemindPolicy: ReRemindPolicy,
    private val notificationHelper: NotificationHelper,
    private val alarmScheduler: AlarmScheduler,
    private val clockProvider: ClockProvider,
    private val doseEventListener: DoseEventListener,
    private val reminderAnnouncer: ReminderAnnouncer,
) {
    suspend fun fire(doseId: Long) {
        val dose = doseLogRepository.getDose(doseId)
        if (dose == null) {
            // The dose row disappeared (schedule edited) — clean up its alarm.
            alarmScheduler.cancelDoseReminder(doseId)
            notificationHelper.cancelDoseNotification(doseId)
            return
        }

        val medication = medicationRepository.getMedication(dose.medicationId)
        if (medication == null || !medication.isActive) {
            alarmScheduler.cancelDoseReminder(doseId)
            notificationHelper.cancelDoseNotification(doseId)
            return
        }

        when (dose.status) {
            DoseStatus.TAKEN, DoseStatus.SKIPPED -> {
                notificationHelper.cancelDoseNotification(doseId)
                return
            }
            else -> Unit
        }

        val settings = settingsRepository.current()

        // Reminder budget exhausted without an answer -> missed (caregiver hook).
        if (reRemindPolicy.shouldMarkMissed(dose.reminderAttempts, settings.maxReReminds)) {
            val missed = dose.copy(status = DoseStatus.MISSED)
            doseLogRepository.update(missed)
            notificationHelper.cancelDoseNotification(doseId)
            doseEventListener.onDoseMissed(missed, medication)
            return
        }

        notificationHelper.showDoseReminder(dose, medication, settings)
        doseLogRepository.update(dose.copy(reminderAttempts = dose.reminderAttempts + 1))

        val nextAtMillis = clockProvider.clock.millis() +
            reRemindPolicy.nextReminderDelayMinutes(settings) * MILLIS_PER_MINUTE
        alarmScheduler.scheduleDoseReminder(doseId, medication.id, nextAtMillis)

        // Said out loud at the fire moment — the user may be in another app,
        // where Android only shows a banner and never our full-screen screen.
        // Best-effort: never blocks the notification chain above.
        reminderAnnouncer.announce(medication, settings.languageTag, settings.ttsEnabled)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
