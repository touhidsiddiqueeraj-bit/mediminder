package com.mediminder.app.domain.usecase

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.DoseEventListener
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.domain.service.ReRemindPolicy
import java.time.LocalDateTime
import javax.inject.Inject

/** The two final outcomes a user can choose for a dose. */
enum class DoseOutcome {
    TAKEN,
    SKIPPED,
}

/** Marks a dose as taken (decrementing pill count) or skipped. */
class MarkDoseUseCase @Inject constructor(
    private val doseLogRepository: DoseLogRepository,
    private val medicationRepository: MedicationRepository,
    private val reminderScheduler: ReminderScheduler,
    private val doseEventListener: DoseEventListener,
    private val clockProvider: ClockProvider,
) {
    /** @return the (possibly already final) dose, or null when it no longer exists. */
    suspend operator fun invoke(doseId: Long, outcome: DoseOutcome): DoseLog? {
        val dose = doseLogRepository.getDose(doseId) ?: return null
        if (dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.SKIPPED) return dose

        val now = LocalDateTime.now(clockProvider.clock)
        val updated = dose.copy(
            status = if (outcome == DoseOutcome.TAKEN) DoseStatus.TAKEN else DoseStatus.SKIPPED,
            actualTime = if (outcome == DoseOutcome.TAKEN) now else dose.actualTime,
        )
        doseLogRepository.update(updated)
        if (outcome == DoseOutcome.TAKEN) {
            medicationRepository.decrementPills(dose.medicationId)
        }
        reminderScheduler.onDoseHandled(doseId)

        val medication = medicationRepository.getMedication(dose.medicationId)
        if (medication != null && outcome == DoseOutcome.TAKEN) {
            doseEventListener.onDoseTaken(updated, medication)
        }
        return updated
    }
}

/** Postpones a reminder by [minutes]; the dose shows as "Snoozed" meanwhile. */
class SnoozeDoseUseCase @Inject constructor(
    private val doseLogRepository: DoseLogRepository,
    private val reminderScheduler: ReminderScheduler,
) {
    suspend operator fun invoke(doseId: Long, minutes: Int): DoseLog? {
        val dose = doseLogRepository.getDose(doseId) ?: return null
        if (dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.SKIPPED) return dose
        val updated = dose.copy(status = DoseStatus.SNOOZED)
        doseLogRepository.update(updated)
        reminderScheduler.scheduleSnooze(doseId, dose.medicationId, minutes)
        return updated
    }
}

/**
 * Sweep: turns overdue, unanswered doses into MISSED (their whole re-remind
 * chain has finished). Runs on app open, after alarms, and in the maintenance
 * worker. Also the single hook where a future caregiver notification fires.
 */
class MarkMissedDosesUseCase @Inject constructor(
    private val doseLogRepository: DoseLogRepository,
    private val medicationRepository: MedicationRepository,
    private val settingsRepository: SettingsRepository,
    private val reRemindPolicy: ReRemindPolicy,
    private val clockProvider: ClockProvider,
    private val doseEventListener: DoseEventListener,
) {
    /** @return how many doses were marked missed. */
    suspend operator fun invoke(): Int {
        val settings = settingsRepository.current()
        val cutoff = reRemindPolicy.missedCutoff(LocalDateTime.now(clockProvider.clock), settings)
        val missed = doseLogRepository.markOverdueAsMissed(cutoff)
        if (missed.isNotEmpty()) {
            val medications = medicationRepository.getActiveMedications().associateBy { it.id }
            missed.forEach { dose ->
                medications[dose.medicationId]?.let { doseEventListener.onDoseMissed(dose, it) }
            }
        }
        return missed.size
    }
}
