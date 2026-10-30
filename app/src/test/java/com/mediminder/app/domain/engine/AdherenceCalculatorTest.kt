package com.mediminder.app.domain.engine

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AdherenceCalculatorTest {

    private val calculator = AdherenceCalculator()
    private val today = LocalDate.of(2026, 1, 31)

    private fun dose(at: LocalDateTime, status: DoseStatus) =
        DoseLog(id = at.hashCode().toLong(), medicationId = 1L, scheduledTime = at, status = status)

    @Test
    fun `per day counts taken missed skipped`() {
        val logs = listOf(
            dose(LocalDateTime.of(2026, 1, 30, 8, 0), DoseStatus.TAKEN),
            dose(LocalDateTime.of(2026, 1, 30, 20, 0), DoseStatus.MISSED),
            dose(LocalDateTime.of(2026, 1, 30, 14, 0), DoseStatus.SKIPPED),
            dose(LocalDateTime.of(2026, 1, 30, 22, 0), DoseStatus.SCHEDULED), // pending: ignored
        )
        val day = calculator.perDay(logs, listOf(LocalDate.of(2026, 1, 30))).single()
        assertEquals(1, day.taken)
        assertEquals(1, day.missed)
        assertEquals(1, day.skipped)
        assertEquals(3, day.scheduled)
        assertEquals(50, day.adherencePercent)
    }

    @Test
    fun `skipped doses do not count against adherence`() {
        val logs = listOf(
            dose(LocalDateTime.of(2026, 1, 30, 8, 0), DoseStatus.TAKEN),
            dose(LocalDateTime.of(2026, 1, 30, 20, 0), DoseStatus.SKIPPED),
        )
        val day = calculator.perDay(logs, listOf(LocalDate.of(2026, 1, 30))).single()
        assertEquals(100, day.adherencePercent)
    }

    @Test
    fun `days without final doses have null percent`() {
        val logs = listOf(dose(LocalDateTime.of(2026, 1, 30, 8, 0), DoseStatus.SNOOZED))
        val day = calculator.perDay(logs, listOf(LocalDate.of(2026, 1, 30))).single()
        assertNull(day.adherencePercent)
    }

    @Test
    fun `weekly totals seven days including today`() {
        val logs = (0 until 7).flatMap { daysAgo ->
            val day = today.minusDays(daysAgo.toLong())
            listOf(
                dose(day.atTime(8, 0), DoseStatus.TAKEN),
                dose(day.atTime(20, 0), DoseStatus.TAKEN),
            )
        }
        val weekly = calculator.weekly(logs, today)
        assertEquals(14, weekly.takenTotal)
        assertEquals(0, weekly.missedTotal)
        assertEquals(100, weekly.percent)
        assertEquals(7, weekly.days.size)
        assertEquals(today, weekly.days.last().date)
    }

    @Test
    fun `per medication separates the medicines`() {
        val medicationA = Medication(id = 1L, name = "A", startDate = today)
        val medicationB = Medication(id = 2L, name = "B", startDate = today)
        val logs = listOf(
            DoseLog(medicationId = 1L, scheduledTime = today.atTime(8, 0), status = DoseStatus.TAKEN),
            DoseLog(medicationId = 2L, scheduledTime = today.atTime(8, 0), status = DoseStatus.MISSED),
        )
        val perMed = calculator.perMedication(logs, listOf(medicationA, medicationB))
        assertEquals(1, perMed.first { it.medicationId == 1L }.taken)
        assertEquals(1, perMed.first { it.medicationId == 2L }.missed)
        assertEquals(100, perMed.first { it.medicationId == 1L }.adherencePercent)
        assertEquals(0, perMed.first { it.medicationId == 2L }.adherencePercent)
    }
}

class LowStockCalculatorTest {

    private val calculator = LowStockCalculator()
    private val today = LocalDate.of(2026, 1, 1)

    private fun medication(
        pills: Int?,
        timesPerDay: Int = 1,
        intervalHours: Int? = null,
        isActive: Boolean = true,
    ): Medication {
        val schedule = if (intervalHours != null) {
            Schedule(type = FrequencyType.EVERY_X_HOURS, intervalHours = intervalHours, times = listOf(LocalTime.of(6, 0)))
        } else {
            Schedule(type = FrequencyType.DAILY, times = List(timesPerDay) { LocalTime.of(8, 0) })
        }
        return Medication(
            id = 1L,
            name = "Med",
            startDate = today,
            pillsRemaining = pills,
            isActive = isActive,
            schedules = listOf(schedule),
        )
    }

    @Test
    fun `plenty of pills means no warning`() {
        val warnings = calculator.lowStockMedications(listOf(medication(pills = 30, timesPerDay = 1)))
        assertEquals(0, warnings.size)
    }

    @Test
    fun `three days left warns with exact day count`() {
        val warnings = calculator.lowStockMedications(listOf(medication(pills = 3, timesPerDay = 1)))
        assertEquals(1, warnings.size)
        assertEquals(3, warnings.single().daysRemaining)
        assertEquals("Med", warnings.single().medicationName)
    }

    @Test
    fun `consumption scales with doses per day`() {
        // 6 pills at 3 doses/day = 2 days -> warns (threshold 3).
        val warnings = calculator.lowStockMedications(listOf(medication(pills = 6, timesPerDay = 3)))
        assertEquals(1, warnings.size)
        assertEquals(2, warnings.single().daysRemaining)
    }

    @Test
    fun `interval schedules estimate 24 hours divided by interval`() {
        // Every 8 hours = 3 doses/day; 7 pills = 2 days.
        val warnings = calculator.lowStockMedications(listOf(medication(pills = 7, intervalHours = 8)))
        assertEquals(2, warnings.single().daysRemaining)
    }

    @Test
    fun `untracked and inactive and zero-stock medications are ignored`() {
        val warnings = calculator.lowStockMedications(
            listOf(
                medication(pills = null),
                medication(pills = 1, isActive = false),
                medication(pills = 0),
            ),
        )
        assertEquals(0, warnings.size)
    }

    @Test
    fun `as needed medicines never warn`() {
        val med = Medication(
            id = 1L,
            name = "PRN",
            startDate = today,
            pillsRemaining = 1,
            schedules = listOf(Schedule(type = FrequencyType.AS_NEEDED)),
        )
        assertEquals(0, calculator.lowStockMedications(listOf(med)).size)
    }
}
