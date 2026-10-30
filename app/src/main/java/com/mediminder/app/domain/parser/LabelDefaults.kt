package com.mediminder.app.domain.parser

import java.time.LocalTime

/**
 * Sensible, editable defaults used to turn label wording into concrete times.
 * These are deliberately daytime-friendly for elderly users.
 */
object LabelDefaults {

    val MORNING: LocalTime = LocalTime.of(8, 0)
    val AFTERNOON: LocalTime = LocalTime.of(14, 0)
    val EVENING: LocalTime = LocalTime.of(18, 0)
    val BEDTIME: LocalTime = LocalTime.of(22, 0)

    /** "twice daily" -> 08:00 and 20:00, etc. */
    val MEAL_TIMES: List<LocalTime> = listOf(
        LocalTime.of(8, 0),
        LocalTime.of(12, 0),
        LocalTime.of(18, 0),
    )

    fun timesPerDay(count: Int): List<LocalTime> = when (count) {
        1 -> listOf(LocalTime.of(8, 0))
        2 -> listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))
        3 -> listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(22, 0))
        else -> listOf(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(17, 0), LocalTime.of(22, 0))
    }

    /**
     * Anchor time for "every N hours" chains. 8h intervals anchor at 06:00 so
     * the day's doses land at 06:00 / 14:00 / 22:00 instead of crossing midnight.
     */
    fun anchorForInterval(hours: Int): LocalTime = if (hours == 8) {
        LocalTime.of(6, 0)
    } else {
        LocalTime.of(8, 0)
    }
}
