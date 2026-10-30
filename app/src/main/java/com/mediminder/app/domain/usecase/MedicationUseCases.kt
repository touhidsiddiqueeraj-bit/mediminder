package com.mediminder.app.domain.usecase

import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.service.ReminderScheduler
import javax.inject.Inject

/** Stops reminders for a medication but keeps its history. */
class DeactivateMedicationUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val reminderScheduler: ReminderScheduler,
) {
    suspend operator fun invoke(medicationId: Long) {
        medicationRepository.setActive(medicationId, active = false)
        doseLogRepository.deleteFutureDoses(medicationId)
        reminderScheduler.rescheduleAllAlarms()
    }
}

/** Deletes a medication, its schedules and all of its dose history. */
class DeleteMedicationUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val reminderScheduler: ReminderScheduler,
) {
    suspend operator fun invoke(medicationId: Long) {
        medicationRepository.delete(medicationId)
        reminderScheduler.rescheduleAllAlarms()
    }
}

/** Updates the remaining pill count after a refill. */
class UpdatePillCountUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
) {
    suspend operator fun invoke(medicationId: Long, pills: Int?) {
        medicationRepository.setPills(medicationId, pills?.coerceIn(0, 10_000))
    }
}
