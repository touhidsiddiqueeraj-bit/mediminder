package com.mediminder.app.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/** Physical form of a medicine. Display names are resolved in the UI layer. */
enum class MedicationForm {
    TABLET,
    CAPSULE,
    LIQUID,
    INJECTION,
    DROPS,
}

/**
 * A medicine the user takes. See docs/ARCHITECTURE.md for the data model.
 *
 * @param dosageValue the amount per dose as entered (e.g. "500" or "1")
 * @param dosageUnit the unit for that amount (e.g. "mg", "mL", "tablet")
 */
data class Medication(
    val id: Long = 0L,
    val name: String,
    val genericName: String? = null,
    val dosageValue: String? = null,
    val dosageUnit: String? = null,
    val form: MedicationForm = MedicationForm.TABLET,
    val instructions: String? = null,
    val photoUri: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val pillsRemaining: Int? = null,
    val isActive: Boolean = true,
    val schedules: List<Schedule> = emptyList(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    /** e.g. "500 mg" or "1 tablet"; null when the user did not enter a dose. */
    val dosageText: String?
        get() = if (dosageValue.isNullOrBlank()) null else "$dosageValue $dosageUnit".trim()
}
