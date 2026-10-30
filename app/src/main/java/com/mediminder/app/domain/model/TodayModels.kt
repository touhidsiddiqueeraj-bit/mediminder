package com.mediminder.app.domain.model

/** Time-of-day buckets used on the Today screen. */
enum class TimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT;

    companion object {
        fun ofHour(hour: Int): TimeOfDay = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** What the Today screen shows for a dose. */
enum class DoseDisplayStatus {
    UPCOMING,
    TAKEN,
    MISSED,
    SKIPPED,
    SNOOZED,
}

/** A dose combined with its medication, ready for display. */
data class UpcomingDose(
    val dose: DoseLog,
    val medication: Medication,
    val displayStatus: DoseDisplayStatus,
)

/** "Only 3 days of Metformin left." */
data class LowStockInfo(
    val medicationId: Long,
    val medicationName: String,
    val daysRemaining: Int,
)
