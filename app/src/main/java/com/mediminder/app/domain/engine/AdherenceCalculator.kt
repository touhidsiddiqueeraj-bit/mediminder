package com.mediminder.app.domain.engine

import com.mediminder.app.domain.model.DayAdherence
import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.MedicationAdherence
import com.mediminder.app.domain.model.WeeklyAdherence
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure adherence math. Skipped doses are excluded from percentages (they are
 * the user's deliberate choice); still-pending doses (SCHEDULED/SNOOZED) are
 * ignored until they become TAKEN or MISSED.
 */
@Singleton
class AdherenceCalculator @Inject constructor() {

    fun perDay(logs: List<DoseLog>, dates: List<LocalDate>): List<DayAdherence> = dates.map { date ->
        val dayLogs = logs.filter {
            it.scheduledTime.toLocalDate() == date && it.status.isFinal()
        }
        DayAdherence(
            date = date,
            taken = dayLogs.count { it.status == DoseStatus.TAKEN },
            missed = dayLogs.count { it.status == DoseStatus.MISSED },
            skipped = dayLogs.count { it.status == DoseStatus.SKIPPED },
            scheduled = dayLogs.size,
        )
    }

    /** Last 7 days including [today]. */
    fun weekly(logs: List<DoseLog>, today: LocalDate): WeeklyAdherence {
        val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val days = perDay(logs, dates)
        return WeeklyAdherence(
            days = days,
            takenTotal = days.sumOf { it.taken },
            missedTotal = days.sumOf { it.missed },
        )
    }

    fun perMedication(
        logs: List<DoseLog>,
        medications: List<Medication>,
    ): List<MedicationAdherence> = medications.map { med ->
        val medLogs = logs.filter { it.medicationId == med.id && it.status.isFinal() }
        MedicationAdherence(
            medicationId = med.id,
            name = med.name,
            taken = medLogs.count { it.status == DoseStatus.TAKEN },
            missed = medLogs.count { it.status == DoseStatus.MISSED },
            skipped = medLogs.count { it.status == DoseStatus.SKIPPED },
        )
    }

    private fun DoseStatus.isFinal(): Boolean =
        this == DoseStatus.TAKEN || this == DoseStatus.MISSED || this == DoseStatus.SKIPPED
}
