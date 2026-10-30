package com.mediminder.app.data.repository

import android.content.Context
import com.mediminder.app.data.local.MedicationDao
import com.mediminder.app.data.mapper.toDomain
import com.mediminder.app.data.mapper.toEntity
import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.repository.MedicationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicationDao: MedicationDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MedicationRepository {

    override fun observeActiveWithSchedules(): Flow<List<Medication>> =
        medicationDao.observeActiveWithSchedules().map { list -> list.map { it.toDomain() } }

    override fun observeAllWithSchedules(): Flow<List<Medication>> =
        medicationDao.observeAllWithSchedules().map { list -> list.map { it.toDomain() } }

    override fun observeMedication(id: Long): Flow<Medication?> =
        medicationDao.observeById(id).map { it?.toDomain() }

    override suspend fun getMedication(id: Long): Medication? = withContext(ioDispatcher) {
        medicationDao.getById(id)?.toDomain()
    }

    override suspend fun getActiveMedications(): List<Medication> = withContext(ioDispatcher) {
        medicationDao.getActive().map { it.toDomain() }
    }

    override suspend fun getAllMedications(): List<Medication> = withContext(ioDispatcher) {
        medicationDao.getAll().map { it.toDomain() }
    }

    override suspend fun upsert(medication: Medication): Long = withContext(ioDispatcher) {
        if (medication.id == 0L) {
            val id = medicationDao.insertMedication(medication.toEntity())
            if (medication.schedules.isNotEmpty()) {
                medicationDao.insertSchedules(medication.schedules.map { it.toEntity(id) })
            }
            id
        } else {
            medicationDao.updateMedication(medication.toEntity())
            medicationDao.deleteSchedules(medication.id)
            if (medication.schedules.isNotEmpty()) {
                medicationDao.insertSchedules(medication.schedules.map { it.toEntity(medication.id) })
            }
            medication.id
        }
    }

    override suspend fun setActive(id: Long, active: Boolean) = withContext(ioDispatcher) {
        medicationDao.setActive(id, active)
    }

    override suspend fun delete(id: Long) = withContext(ioDispatcher) {
        getMedication(id)?.photoUri?.let { uri -> deletePhotoFile(uri) }
        medicationDao.deleteMedication(id) // schedules + dose logs cascade
    }

    override suspend fun decrementPills(medicationId: Long) = withContext(ioDispatcher) {
        medicationDao.decrementPills(medicationId)
    }

    override suspend fun setPills(medicationId: Long, pills: Int?) = withContext(ioDispatcher) {
        medicationDao.setPillsRemaining(medicationId, pills)
    }

    private fun deletePhotoFile(uri: String) {
        runCatching {
            val file = File(uri.removePrefix("file:"))
            if (file.exists()) file.delete()
        }
    }
}
