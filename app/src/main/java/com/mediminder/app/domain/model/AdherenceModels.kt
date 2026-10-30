package com.mediminder.app.domain.model

import java.time.LocalDate

/** Adherence for a single day. Skipped doses are excluded from the percentage. */
data class DayAdherence(
    val date: LocalDate,
    val taken: Int,
    val missed: Int,
    val skipped: Int,
    val scheduled: Int,
) {
    val adherencePercent: Int?
        get() = if (taken + missed == 0) null else taken * 100 / (taken + missed)
}

/** Last 7 days (today included). */
data class WeeklyAdherence(
    val days: List<DayAdherence>,
    val takenTotal: Int,
    val missedTotal: Int,
) {
    val percent: Int?
        get() = if (takenTotal + missedTotal == 0) null else takenTotal * 100 / (takenTotal + missedTotal)
}

/** Adherence for one medication over a period; used in the PDF report. */
data class MedicationAdherence(
    val medicationId: Long,
    val name: String,
    val taken: Int,
    val missed: Int,
    val skipped: Int,
) {
    val adherencePercent: Int?
        get() = if (taken + missed == 0) null else taken * 100 / (taken + missed)
}
