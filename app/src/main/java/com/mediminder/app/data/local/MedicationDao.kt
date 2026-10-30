package com.mediminder.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {

    @Transaction
    @Query("SELECT * FROM medications WHERE isActive = 1 ORDER BY name COLLATE NOCASE")
    fun observeActiveWithSchedules(): Flow<List<MedicationWithSchedules>>

    @Transaction
    @Query("SELECT * FROM medications ORDER BY isActive DESC, name COLLATE NOCASE")
    fun observeAllWithSchedules(): Flow<List<MedicationWithSchedules>>

    @Transaction
    @Query("SELECT * FROM medications WHERE id = :id")
    fun observeById(id: Long): Flow<MedicationWithSchedules?>

    @Transaction
    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getById(id: Long): MedicationWithSchedules?

    @Transaction
    @Query("SELECT * FROM medications WHERE isActive = 1 ORDER BY name COLLATE NOCASE")
    suspend fun getActive(): List<MedicationWithSchedules>

    @Transaction
    @Query("SELECT * FROM medications ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<MedicationWithSchedules>

    @Insert
    suspend fun insertMedication(medication: MedicationEntity): Long

    @Update
    suspend fun updateMedication(medication: MedicationEntity)

    @Insert
    suspend fun insertSchedules(schedules: List<ScheduleEntity>): List<Long>

    @Query("DELETE FROM schedules WHERE medicationId = :medicationId")
    suspend fun deleteSchedules(medicationId: Long)

    @Query("UPDATE medications SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMedication(id: Long)

    /** Never below zero; keeps NULL when the user does not track stock. */
    @Query(
        "UPDATE medications SET pillsRemaining = MAX(0, COALESCE(pillsRemaining, 1) - 1) " +
            "WHERE id = :id AND pillsRemaining IS NOT NULL",
    )
    suspend fun decrementPills(id: Long)

    @Query("UPDATE medications SET pillsRemaining = :pills WHERE id = :id")
    suspend fun setPillsRemaining(id: Long, pills: Int?)
}

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId")
    suspend fun forMedication(medicationId: Long): List<ScheduleEntity>
}
