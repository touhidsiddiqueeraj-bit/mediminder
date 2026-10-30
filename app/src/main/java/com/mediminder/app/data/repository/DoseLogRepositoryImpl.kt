package com.mediminder.app.data.repository

import com.mediminder.app.data.local.DoseLogDao
import com.mediminder.app.data.mapper.toDomain
import com.mediminder.app.data.mapper.toEntity
import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.repository.DoseLogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoseLogRepositoryImpl @Inject constructor(
    private val doseLogDao: DoseLogDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DoseLogRepository {

    override fun observeLogsBetween(from: LocalDateTime, to: LocalDateTime): Flow<List<DoseLog>> =
        doseLogDao.observeBetween(from, to).map { list -> list.map { it.toDomain() } }

    override suspend fun getLogsBetween(from: LocalDateTime, to: LocalDateTime): List<DoseLog> =
        withContext(ioDispatcher) {
            doseLogDao.getBetween(from, to).map { it.toDomain() }
        }

    override fun observeDose(id: Long): Flow<DoseLog?> =
        doseLogDao.observeById(id).map { it?.toDomain() }

    override suspend fun getDose(id: Long): DoseLog? = withContext(ioDispatcher) {
        doseLogDao.getById(id)?.toDomain()
    }

    override suspend fun ensureScheduledDoses(doses: List<DoseLog>): List<Long> =
        withContext(ioDispatcher) {
            if (doses.isEmpty()) return@withContext emptyList()
            val insertResults = doseLogDao.insertAll(doses.map { it.toEntity() })

            if (!insertResults.any { it == -1L }) {
                return@withContext insertResults
            }
            // Some rows already existed: resolve their ids from the unique
            // (medicationId, scheduledTime) pair.
            val windowStart = doses.minOf { it.scheduledTime }
            val windowEnd = doses.maxOf { it.scheduledTime }
            val existing = doseLogDao.getBetween(windowStart, windowEnd)
                .associateBy { it.medicationId to it.scheduledTime }

            doses.mapIndexed { index, dose ->
                val result = insertResults[index]
                if (result != -1L) result else existing[dose.medicationId to dose.scheduledTime]?.id ?: result
            }
        }

    override suspend fun update(dose: DoseLog) = withContext(ioDispatcher) {
        doseLogDao.update(dose.toEntity())
    }

    override suspend fun getScheduledDosesBetween(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<DoseLog> = withContext(ioDispatcher) {
        doseLogDao.getScheduledBetween(from, to).map { it.toDomain() }
    }

    override suspend fun markOverdueAsMissed(cutoff: LocalDateTime): List<DoseLog> =
        withContext(ioDispatcher) {
            val overdue = doseLogDao.findOverdue(cutoff)
            overdue.forEach { entity ->
                doseLogDao.update(entity.copy(status = DoseStatus.MISSED))
            }
            overdue.map { it.toDomain().copy(status = DoseStatus.MISSED) }
        }

    override suspend fun logsForMedication(
        medicationId: Long,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<DoseLog> = withContext(ioDispatcher) {
        doseLogDao.forMedication(medicationId, from, to).map { it.toDomain() }
    }

    override suspend fun deleteFutureDoses(medicationId: Long) = withContext(ioDispatcher) {
        doseLogDao.deleteFutureScheduledForMedication(
            medicationId,
            LocalDateTime.now(),
        )
    }

    override suspend fun pruneFutureScheduledDoses(
        medicationId: Long,
        keepDoseIds: List<Long>,
        from: LocalDateTime,
    ) = withContext(ioDispatcher) {
        if (keepDoseIds.isEmpty()) {
            doseLogDao.deleteFutureScheduledForMedication(medicationId, from)
        } else {
            doseLogDao.deleteFutureScheduledForMedicationNotIn(medicationId, keepDoseIds, from)
        }
    }

    override suspend fun pruneFutureScheduledForInactiveMedications(from: LocalDateTime) =
        withContext(ioDispatcher) {
            doseLogDao.deleteFutureScheduledForInactiveMedications(from)
        }

    override suspend fun countByStatus(
        from: LocalDateTime,
        to: LocalDateTime,
        statuses: List<DoseStatus>,
    ): Int = withContext(ioDispatcher) {
        doseLogDao.countByStatus(from, to, statuses)
    }
}
