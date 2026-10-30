package com.mediminder.app.domain.engine

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Schedule math is what reminders are built from — it must be exactly right. */
class DoseGenerationEngineTest {

    private val engine = DoseGenerationEngine()
    private val startDate = LocalDate.of(2026, 1, 1) // Thursday

    private fun medication(
        schedules: List<Schedule>,
        startDate: LocalDate = this.startDate,
        endDate: LocalDate? = null,
        isActive: Boolean = true,
    ) = Medication(
        id = 1L,
        name = "Test",
        startDate = startDate,
        endDate = endDate,
        isActive = isActive,
        schedules = schedules,
    )

    private fun timesOf(doses: List<DoseLog>): List<LocalTime> =
        doses.map { it.scheduledTime.toLocalTime() }

    // ----- Daily -----------------------------------------------------------

    @Test
    fun `daily schedule generates every day at each time`() {
        val med = medication(
            listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))),
        )
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3))
        assertEquals(6, doses.size)
        assertEquals(2, doses.count { it.scheduledTime.toLocalDate() == LocalDate.of(2026, 1, 1) })
        assertEquals(2, doses.count { it.scheduledTime.toLocalDate() == LocalDate.of(2026, 1, 2) })
        assertEquals(2, doses.count { it.scheduledTime.toLocalDate() == LocalDate.of(2026, 1, 3) })
    }

    @Test
    fun `doses before medication start date are not generated`() {
        val med = medication(
            listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.NOON))),
            startDate = LocalDate.of(2026, 1, 10),
        )
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 12))
        assertEquals(3, doses.size) // 10th, 11th, 12th
        assertTrue(doses.all { !it.scheduledTime.toLocalDate().isBefore(LocalDate.of(2026, 1, 10)) })
    }

    @Test
    fun `end date is respected`() {
        val med = medication(
            listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.NOON))),
            endDate = LocalDate.of(2026, 1, 2),
        )
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5))
        assertEquals(2, doses.size)
    }

    @Test
    fun `inactive medications generate nothing`() {
        val med = medication(
            listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.NOON))),
            isActive = false,
        )
        assertTrue(engine.generate(listOf(med), startDate, startDate.plusDays(2)).isEmpty())
    }

    // ----- Specific weekdays -----------------------------------------------

    @Test
    fun `specific weekdays only generate on those days`() {
        val med = medication(
            listOf(
                Schedule(
                    type = FrequencyType.SPECIFIC_DAYS,
                    daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                    times = listOf(LocalTime.of(9, 0)),
                ),
            ),
        )
        // 2026-01-01 is a Thursday; window covers Mon 5th and Fri 2nd/9th.
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10))
        assertEquals(3, doses.size)
        assertEquals(
            setOf(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9)),
            doses.map { it.scheduledTime.toLocalDate() }.toSet(),
        )
    }

    // ----- Every N hours ----------------------------------------------------

    @Test
    fun `every 8 hours anchored at 6am gives 06 14 22 each day`() {
        val med = medication(
            listOf(
                Schedule(
                    type = FrequencyType.EVERY_X_HOURS,
                    intervalHours = 8,
                    times = listOf(LocalTime.of(6, 0)),
                ),
            ),
        )
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6))
        assertEquals(6, doses.size)
        assertEquals(
            listOf(LocalTime.of(6, 0), LocalTime.of(14, 0), LocalTime.of(22, 0)),
            timesOf(doses.filter { it.scheduledTime.toLocalDate() == LocalDate.of(2026, 1, 5) }),
        )
        assertEquals(
            listOf(LocalTime.of(6, 0), LocalTime.of(14, 0), LocalTime.of(22, 0)),
            timesOf(doses.filter { it.scheduledTime.toLocalDate() == LocalDate.of(2026, 1, 6) }),
        )
    }

    @Test
    fun `every 12 hours starting much earlier keeps its phase`() {
        val med = medication(
            listOf(
                Schedule(
                    type = FrequencyType.EVERY_X_HOURS,
                    intervalHours = 12,
                    times = listOf(LocalTime.of(8, 0)),
                ),
            ),
            startDate = LocalDate.of(2025, 1, 1), // a year earlier
        )
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5))
        assertEquals(2, doses.size)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), timesOf(doses))
    }

    @Test
    fun `every 6 hours crosses midnight`() {
        val med = medication(
            listOf(
                Schedule(
                    type = FrequencyType.EVERY_X_HOURS,
                    intervalHours = 6,
                    times = listOf(LocalTime.of(8, 0)),
                ),
            ),
        )
        // Chain from 08:00: 08, 14, 20, then 02:00 next day...
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2))
        assertEquals(4, doses.size)
        assertEquals(
            listOf(LocalTime.of(2, 0), LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)),
            timesOf(doses.sortedBy { it.scheduledTime.toLocalTime() }),
        )
    }

    // ----- Every N days ------------------------------------------------------

    @Test
    fun `every other day schedule alternates from the start date`() {
        val med = medication(
            listOf(
                Schedule(
                    type = FrequencyType.DAILY,
                    intervalDays = 2,
                    times = listOf(LocalTime.of(8, 0)),
                ),
            ),
        )
        val doses = engine.generate(listOf(med), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 8))
        // 1, 3, 5, 7
        assertEquals(4, doses.size)
        assertEquals(
            setOf(1, 3, 5, 7).map { LocalDate.of(2026, 1, it) },
            doses.map { it.scheduledTime.toLocalDate() },
        )
    }

    // ----- As needed ---------------------------------------------------------

    @Test
    fun `as needed medicines never generate doses`() {
        val med = medication(listOf(Schedule(type = FrequencyType.AS_NEEDED)))
        assertTrue(engine.generate(listOf(med), startDate, startDate.plusDays(3)).isEmpty())
    }

    // ----- Sorting + helpers --------------------------------------------------

    @Test
    fun `doses are sorted chronologically across medications`() {
        val morningMed = medication(
            listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.of(20, 0)))),
        )
        val eveningMed = medication(
            listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.of(8, 0)))),
        )
        val doses = engine.generate(
            listOf(morningMed, eveningMed),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 1),
        )
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), timesOf(doses))
    }

    @Test
    fun `dosesOnDay counts a single day`() {
        val med = medication(
            listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))),
        )
        assertEquals(2, engine.dosesOnDay(med, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `dose log fields are correctly initialized`() {
        val med = medication(listOf(Schedule(type = FrequencyType.DAILY, times = listOf(LocalTime.NOON))))
        val dose = engine.generate(listOf(med), startDate, startDate).single()
        assertEquals(1L, dose.medicationId)
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 0), dose.scheduledTime)
        assertEquals(DoseStatus.SCHEDULED, dose.status)
        assertEquals(0, dose.reminderAttempts)
    }
}
