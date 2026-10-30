package com.mediminder.app.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * How often a medicine is taken.
 *
 * - [DAILY]: every day at [times]
 * - [SPECIFIC_DAYS]: on [daysOfWeek] at [times]
 * - [EVERY_X_HOURS]: a continuous chain starting at [times] (single anchor time)
 *   and repeating every [intervalHours] hours, crossing midnight if needed
 * - [AS_NEEDED]: no scheduled reminders; the user logs doses manually
 *
 * [intervalDays] (>1) models "every other day" style schedules for DAILY.
 */
enum class FrequencyType {
    DAILY,
    SPECIFIC_DAYS,
    EVERY_X_HOURS,
    AS_NEEDED,
}

data class Schedule(
    val id: Long = 0L,
    val medicationId: Long = 0L,
    val type: FrequencyType,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val intervalHours: Int? = null,
    val intervalDays: Int? = null,
    val times: List<LocalTime> = emptyList(),
)
