package com.mediminder.app.ui.model

import com.mediminder.app.domain.model.FieldConfidence
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.LabelField
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.MedicationForm
import com.mediminder.app.domain.model.ParsedLabel
import com.mediminder.app.domain.model.Schedule
import com.mediminder.app.domain.parser.LabelDefaults
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The single editable form state shared by the manual add flow, the edit flow
 * and the scan confirmation screen — one source of truth, three doors in.
 */
data class MedicationDraft(
    val id: Long = 0L,
    val name: String = "",
    val genericName: String = "",
    val dosageValue: String = "",
    val dosageUnit: String = "",
    val form: MedicationForm = MedicationForm.TABLET,
    val instructions: String = "",
    val photoUri: String? = null,
    val startDate: LocalDate = LocalDate.now(),
    val hasEndDate: Boolean = false,
    val endDate: LocalDate? = null,
    val trackStock: Boolean = false,
    val pillsRemaining: Int = 0,
    val frequency: FrequencyType = FrequencyType.DAILY,
    val times: List<LocalTime> = listOf(LabelDefaults.MORNING),
    val selectedDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val intervalHours: Int = 8,
    val intervalDays: Int = 1,
    val asNeeded: Boolean = false,
    /** Field confidence from the label scanner; null in the manual flow. */
    val confidence: Map<LabelField, FieldConfidence>? = null,
) {
    val isEditing: Boolean get() = id != 0L

    fun toSchedules(): List<Schedule> = when (frequency) {
        FrequencyType.DAILY -> listOf(
            Schedule(
                type = FrequencyType.DAILY,
                times = times,
                intervalDays = if (intervalDays > 1) intervalDays else null,
            ),
        )
        FrequencyType.SPECIFIC_DAYS -> listOf(
            Schedule(
                type = FrequencyType.SPECIFIC_DAYS,
                daysOfWeek = selectedDays,
                times = times,
            ),
        )
        FrequencyType.EVERY_X_HOURS -> listOf(
            Schedule(
                type = FrequencyType.EVERY_X_HOURS,
                intervalHours = intervalHours,
                times = times.take(1),
            ),
        )
        FrequencyType.AS_NEEDED -> listOf(Schedule(type = FrequencyType.AS_NEEDED))
    }

    fun toMedication(): Medication = Medication(
        id = id,
        name = name.trim(),
        genericName = genericName.trim().takeIf { it.isNotBlank() },
        dosageValue = dosageValue.trim().takeIf { it.isNotBlank() },
        dosageUnit = dosageUnit.trim().takeIf { it.isNotBlank() },
        form = form,
        instructions = instructions.trim().takeIf { it.isNotBlank() },
        photoUri = photoUri,
        startDate = startDate,
        endDate = if (hasEndDate) endDate else null,
        pillsRemaining = if (trackStock) pillsRemaining.coerceAtLeast(0) else null,
        isActive = true,
        schedules = toSchedules(),
    )

    companion object {
        fun from(medication: Medication): MedicationDraft {
            val schedule = medication.schedules.firstOrNull()
            return MedicationDraft(
                id = medication.id,
                name = medication.name,
                genericName = medication.genericName.orEmpty(),
                dosageValue = medication.dosageValue.orEmpty(),
                dosageUnit = medication.dosageUnit.orEmpty(),
                form = medication.form,
                instructions = medication.instructions.orEmpty(),
                photoUri = medication.photoUri,
                startDate = medication.startDate,
                hasEndDate = medication.endDate != null,
                endDate = medication.endDate,
                trackStock = medication.pillsRemaining != null,
                pillsRemaining = medication.pillsRemaining ?: 0,
                frequency = schedule?.type ?: FrequencyType.DAILY,
                times = schedule?.times?.takeIf { it.isNotEmpty() } ?: listOf(LabelDefaults.MORNING),
                selectedDays = schedule?.daysOfWeek?.takeIf { it.isNotEmpty() } ?: DayOfWeek.entries.toSet(),
                intervalHours = schedule?.intervalHours ?: 8,
                intervalDays = schedule?.intervalDays ?: 1,
                asNeeded = schedule?.type == FrequencyType.AS_NEEDED,
            )
        }

        /** Pre-fills from an OCR-parsed label; every field stays editable. */
        fun fromParsed(label: ParsedLabel, photoUri: String?): MedicationDraft {
            val frequency = label.frequency
            return MedicationDraft(
                name = label.name.orEmpty(),
                dosageValue = label.dosageAmount ?: label.strengthValue.orEmpty(),
                dosageUnit = label.dosageUnit ?: label.strengthUnit.orEmpty(),
                form = label.form ?: MedicationForm.TABLET,
                instructions = label.instructionText.orEmpty(),
                photoUri = photoUri,
                frequency = frequency?.type ?: FrequencyType.DAILY,
                times = when {
                    frequency == null -> listOf(LabelDefaults.MORNING)
                    frequency.type == FrequencyType.EVERY_X_HOURS ->
                        listOf(frequency.times.firstOrNull() ?: LabelDefaults.anchorForInterval(frequency.intervalHours ?: 8))
                    frequency.times.isEmpty() -> listOf(LabelDefaults.MORNING)
                    else -> frequency.times
                },
                selectedDays = frequency?.daysOfWeek?.takeIf { it.isNotEmpty() } ?: DayOfWeek.entries.toSet(),
                intervalHours = frequency?.intervalHours ?: 8,
                intervalDays = frequency?.intervalDays ?: 1,
                asNeeded = frequency?.asNeeded == true,
                confidence = label.confidence,
            )
        }
    }
}
