package com.mediminder.app.domain.repository

import com.mediminder.app.domain.model.Medication
import kotlinx.coroutines.flow.Flow

interface MedicationRepository {

    fun observeActiveWithSchedules(): Flow<List<Medication>>

    fun observeAllWithSchedules(): Flow<List<Medication>>

    fun observeMedication(id: Long): Flow<Medication?>

    suspend fun getMedication(id: Long): Medication?

    suspend fun getActiveMedications(): List<Medication>

    suspend fun getAllMedications(): List<Medication>

    /** Inserts a new medication or updates an existing one (id != 0), replacing its schedules. */
    suspend fun upsert(medication: Medication): Long

    suspend fun setActive(id: Long, active: Boolean)

    /** Deletes the medication, its schedules and its dose history. */
    suspend fun delete(id: Long)

    /** Decrements the remaining pill count by one (never below zero). */
    suspend fun decrementPills(medicationId: Long)

    /** Sets the remaining pill count (null = stop tracking stock). */
    suspend fun setPills(medicationId: Long, pills: Int?)
}
