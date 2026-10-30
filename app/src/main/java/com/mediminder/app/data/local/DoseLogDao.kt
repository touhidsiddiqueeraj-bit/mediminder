package com.mediminder.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mediminder.app.domain.model.DoseStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

@Dao
interface DoseLogDao {

    @Query("SELECT * FROM dose_logs WHERE scheduledTime BETWEEN :from AND :to ORDER BY scheduledTime")
    fun observeBetween(from: LocalDateTime, to: LocalDateTime): Flow<List<DoseLogEntity>>

    @Query("SELECT * FROM dose_logs WHERE scheduledTime BETWEEN :from AND :to ORDER BY scheduledTime")
    suspend fun getBetween(from: LocalDateTime, to: LocalDateTime): List<DoseLogEntity>

    @Query("SELECT * FROM dose_logs WHERE id = :id")
    fun observeById(id: Long): Flow<DoseLogEntity?>

    @Query("SELECT * FROM dose_logs WHERE id = :id")
    suspend fun getById(id: Long): DoseLogEntity?

    /** Returns -1 for rows that already exist (IGNORE) — callers resolve those ids. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(doses: List<DoseLogEntity>): List<Long>

    @Update
    suspend fun update(dose: DoseLogEntity)

    @Query(
        "SELECT * FROM dose_logs WHERE status = 'SCHEDULED' " +
            "AND scheduledTime BETWEEN :from AND :to ORDER BY scheduledTime",
    )
    suspend fun getScheduledBetween(from: LocalDateTime, to: LocalDateTime): List<DoseLogEntity>

    @Query("SELECT * FROM dose_logs WHERE status = 'SCHEDULED' AND scheduledTime < :cutoff")
    suspend fun findOverdue(cutoff: LocalDateTime): List<DoseLogEntity>

    @Query(
        "SELECT * FROM dose_logs WHERE medicationId = :medicationId " +
            "AND scheduledTime BETWEEN :from AND :to ORDER BY scheduledTime",
    )
    suspend fun forMedication(
        medicationId: Long,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<DoseLogEntity>

    /** All future still-scheduled doses of one medication (used when deactivating it). */
    @Query(
        "DELETE FROM dose_logs WHERE status = 'SCHEDULED' " +
            "AND scheduledTime >= :from AND medicationId = :medicationId",
    )
    suspend fun deleteFutureScheduledForMedication(medicationId: Long, from: LocalDateTime)

    /**
     * Drops a medication's future scheduled doses that are not in [keepIds]
     * (their schedule changed). Split per medication so the IN-list stays small.
     */
    @Query(
        "DELETE FROM dose_logs WHERE status = 'SCHEDULED' " +
            "AND scheduledTime >= :from AND medicationId = :medicationId AND id NOT IN (:keepIds)",
    )
    suspend fun deleteFutureScheduledForMedicationNotIn(
        medicationId: Long,
        keepIds: List<Long>,
        from: LocalDateTime,
    )

    /** Future scheduled rows of deactivated medications are never wanted. */
    @Query(
        "DELETE FROM dose_logs WHERE status = 'SCHEDULED' AND scheduledTime >= :from " +
            "AND medicationId IN (SELECT id FROM medications WHERE isActive = 0)",
    )
    suspend fun deleteFutureScheduledForInactiveMedications(from: LocalDateTime)

    @Query("DELETE FROM dose_logs WHERE medicationId = :medicationId")
    suspend fun deleteAllForMedication(medicationId: Long)

    @Query(
        "SELECT COUNT(*) FROM dose_logs WHERE scheduledTime BETWEEN :from AND :to " +
            "AND status IN (:statuses)",
    )
    suspend fun countByStatus(
        from: LocalDateTime,
        to: LocalDateTime,
        statuses: List<DoseStatus>,
    ): Int
}
