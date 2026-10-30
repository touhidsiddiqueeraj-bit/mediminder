package com.mediminder.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.MedicationForm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * On-device DAO tests for the dose log — the idempotency of dose
 * materialization and the missed-dose sweep are the two invariants the whole
 * reminder system relies on. Run: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DoseLogDaoTest {

    private lateinit var database: MediMinderDatabase
    private lateinit var medicationDao: MedicationDao
    private lateinit var doseLogDao: DoseLogDao

    private val medicationId: Long = 1L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MediMinderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medicationDao = database.medicationDao()
        doseLogDao = database.doseLogDao()
        medicationDao.insertMedication(
            MedicationEntity(
                id = medicationId,
                name = "Test Medicine",
                form = MedicationForm.TABLET,
                startDate = LocalDate.of(2026, 1, 1),
            ),
        )
        Unit
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun dose(at: LocalDateTime, status: DoseStatus = DoseStatus.SCHEDULED) =
        DoseLogEntity(
            medicationId = medicationId,
            scheduledTime = at,
            status = status,
        )

    @Test
    fun insertAll_isIdempotentForSameMedicationAndTime() = runBlocking {
        val first = doseLogDao.insertAll(listOf(dose(LocalDateTime.of(2026, 1, 1, 8, 0))))
        assertEquals(1, first.size)
        assertTrue(first.single() > 0)

        // Same (medicationId, scheduledTime): IGNORE returns -1.
        val second = doseLogDao.insertAll(listOf(dose(LocalDateTime.of(2026, 1, 1, 8, 0))))
        assertEquals(-1L, second.single())

        val all = doseLogDao.getBetween(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 2, 0, 0),
        )
        assertEquals(1, all.size)
    }

    @Test
    fun findOverdue_returnsOnlyPastScheduledDoses() = runBlocking {
        doseLogDao.insertAll(
            listOf(
                dose(LocalDateTime.of(2026, 1, 1, 8, 0)), // old + pending -> overdue
                dose(LocalDateTime.of(2026, 1, 1, 20, 0)), // later -> not overdue
                dose(LocalDateTime.of(2026, 1, 1, 7, 0), DoseStatus.TAKEN), // final -> ignored
                dose(LocalDateTime.of(2026, 1, 1, 7, 30), DoseStatus.MISSED), // final -> ignored
            ),
        )
        val overdue = doseLogDao.findOverdue(LocalDateTime.of(2026, 1, 1, 10, 0))
        assertEquals(listOf(LocalDateTime.of(2026, 1, 1, 8, 0)), overdue.map { it.scheduledTime })
    }

    @Test
    fun markOverdue_updatesStatusToMissed() = runBlocking {
        doseLogDao.insertAll(listOf(dose(LocalDateTime.of(2026, 1, 1, 8, 0))))
        val overdue = doseLogDao.findOverdue(LocalDateTime.of(2026, 1, 1, 10, 0))
        assertEquals(1, overdue.size)
        doseLogDao.update(overdue.single().copy(status = DoseStatus.MISSED))

        val updated = doseLogDao.getById(overdue.single().id)
        assertNotNull(updated)
        assertEquals(DoseStatus.MISSED, updated!!.status)
    }

    @Test
    fun deleteFutureScheduledForMedicationNotIn_keepsListedDoses() = runBlocking {
        val ids = doseLogDao.insertAll(
            listOf(
                dose(LocalDateTime.of(2026, 1, 5, 8, 0)),
                dose(LocalDateTime.of(2026, 1, 5, 20, 0)),
            ),
        )
        doseLogDao.insertAll(listOf(dose(LocalDateTime.of(2026, 1, 6, 8, 0))))

        doseLogDao.deleteFutureScheduledForMedicationNotIn(
            medicationId = medicationId,
            keepIds = ids,
            from = LocalDateTime.of(2026, 1, 2, 0, 0),
        )

        val remaining = doseLogDao.getBetween(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 7, 0, 0),
        )
        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.scheduledTime == LocalDateTime.of(2026, 1, 6, 8, 0) })
    }

    @Test
    fun schedules_persistAndLoadWithConverters() = runBlocking {
        medicationDao.insertSchedules(
            listOf(
                ScheduleEntity(
                    medicationId = medicationId,
                    type = FrequencyType.SPECIFIC_DAYS,
                    daysOfWeek = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY),
                    times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                ),
            ),
        )
        val schedules = database.scheduleDao().forMedication(medicationId)
        assertEquals(1, schedules.size)
        assertEquals(FrequencyType.SPECIFIC_DAYS, schedules.single().type)
        assertEquals(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY), schedules.single().daysOfWeek)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), schedules.single().times)
    }
}
