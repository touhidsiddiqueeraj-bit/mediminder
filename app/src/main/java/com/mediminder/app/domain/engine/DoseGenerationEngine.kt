package com.mediminder.app.domain.engine

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.Schedule
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure, deterministic translation of medication schedules into concrete dose
 * times for a date window. Thoroughly unit-tested; all other layers depend on
 * it, which is what keeps reminders reliable.
 */
@Singleton
class DoseGenerationEngine @Inject constructor() {

    /** All dose times for the given medications between [from] and [to] (inclusive). */
    fun generate(medications: List<Medication>, from: LocalDate, to: LocalDate): List<DoseLog> {
        require(!to.isBefore(from)) { "'to' must not be before 'from'" }
        val doses = mutableListOf<DoseLog>()
        for (medication in medications) {
            if (!medication.isActive) continue
            for (schedule in medication.schedules) {
                when (schedule.type) {
                    FrequencyType.AS_NEEDED -> Unit // no scheduled reminders
                    FrequencyType.DAILY,
                    FrequencyType.SPECIFIC_DAYS,
                    -> generateForDaySchedule(medication, schedule, from, to, doses)

                    FrequencyType.EVERY_X_HOURS ->
                        generateForIntervalSchedule(medication, schedule, from, to, doses)
                }
            }
        }
        return doses.sortedBy { it.scheduledTime }
    }

    /** Number of doses generated for a single day (used for low-stock estimates). */
    fun dosesOnDay(medication: Medication, date: LocalDate): Int =
        generate(listOf(medication), date, date).size

    private fun generateForDaySchedule(
        medication: Medication,
        schedule: Schedule,
        from: LocalDate,
        to: LocalDate,
        out: MutableList<DoseLog>,
    ) {
        var date = maxOf(from, medication.startDate)
        while (!date.isAfter(to)) {
            if (withinMedicationWindow(medication, date) && dayMatches(schedule, medication, date)) {
                for (time in schedule.times) {
                    out += DoseLog(
                        medicationId = medication.id,
                        scheduledTime = date.atTime(time),
                    )
                }
            }
            date = date.plusDays(1)
        }
    }

    private fun generateForIntervalSchedule(
        medication: Medication,
        schedule: Schedule,
        from: LocalDate,
        to: LocalDate,
        out: MutableList<DoseLog>,
    ) {
        val interval = schedule.intervalHours ?: return
        if (interval <= 0) return
        val anchor = schedule.times.firstOrNull() ?: LocalTime.of(8, 0)

        val windowStart = from.atStartOfDay()
        val windowEndExclusive = to.plusDays(1).atStartOfDay()

        // The chain starts on the medication's start date and never changes phase.
        var time = medication.startDate.atTime(anchor)
        while (time.isBefore(windowStart.minusDays(1))) {
            time = time.plusHours(interval.toLong())
        }
        while (time.isBefore(windowStart)) {
            time = time.plusHours(interval.toLong())
        }
        while (time.isBefore(windowEndExclusive)) {
            val date = time.toLocalDate()
            if (withinMedicationWindow(medication, date) && dayMatches(schedule, medication, date)) {
                out += DoseLog(medicationId = medication.id, scheduledTime = time)
            }
            time = time.plusHours(interval.toLong())
        }
    }

    private fun withinMedicationWindow(medication: Medication, date: LocalDate): Boolean =
        !date.isBefore(medication.startDate) &&
            (medication.endDate == null || !date.isAfter(medication.endDate))

    private fun dayMatches(schedule: Schedule, medication: Medication, date: LocalDate): Boolean {
        if (schedule.type == FrequencyType.SPECIFIC_DAYS) {
            if (schedule.daysOfWeek.isNotEmpty() && date.dayOfWeek !in schedule.daysOfWeek) {
                return false
            }
        }
        val stepDays = schedule.intervalDays ?: 1
        if (stepDays > 1) {
            val daysSinceStart = date.toEpochDay() - medication.startDate.toEpochDay()
            if (daysSinceStart % stepDays != 0L) return false
        }
        return true
    }
}
