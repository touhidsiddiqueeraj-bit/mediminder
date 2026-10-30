package com.mediminder.app.domain.usecase

import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.service.ReminderScheduler
import javax.inject.Inject

/**
 * Saves a new medication (or updates an existing one) after validation and
 * immediately (re)plans its reminders.
 *
 * Safety: a duplicate-ingredient warning is returned for the UI to show, but
 * saving is allowed — two brands sharing one active ingredient is the user's
 * call (e.g. prescribed switch with leftover stock).
 */
class AddMedicationUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val reminderScheduler: ReminderScheduler,
    private val duplicateMedicationChecker: DuplicateMedicationChecker,
) {
    sealed interface Result {
        data class Success(val medicationId: Long, val duplicateOf: Medication?) : Result
        data class Invalid(val reason: InvalidReason) : Result
    }

    enum class InvalidReason {
        EMPTY_NAME,
        NO_SCHEDULE,
        NO_TIMES,
    }

    suspend operator fun invoke(medication: Medication): Result {
        if (medication.name.isBlank()) return Result.Invalid(InvalidReason.EMPTY_NAME)
        if (medication.schedules.isEmpty()) return Result.Invalid(InvalidReason.NO_SCHEDULE)
        if (medication.schedules.any { it.type != FrequencyType.AS_NEEDED && it.times.isEmpty() }) {
            return Result.Invalid(InvalidReason.NO_TIMES)
        }
        val duplicate = duplicateMedicationChecker.findDuplicate(medication)
        val id = medicationRepository.upsert(medication)
        reminderScheduler.rescheduleAllAlarms()
        return Result.Success(id, duplicate)
    }
}
