package com.mediminder.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.MedicationForm
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.DayOfWeek

@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val genericName: String? = null,
    val dosageValue: String? = null,
    val dosageUnit: String? = null,
    val form: MedicationForm = MedicationForm.TABLET,
    val instructions: String? = null,
    val photoUri: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val pillsRemaining: Int? = null,
    val isActive: Boolean = true,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val medicationId: Long,
    val type: FrequencyType,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val intervalHours: Int? = null,
    val intervalDays: Int? = null,
    val times: List<LocalTime> = emptyList(),
)

@Entity(
    tableName = "dose_logs",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("medicationId"),
        // Idempotent dose materialization: one row per medication + scheduled time.
        Index(value = ["medicationId", "scheduledTime"], unique = true),
    ],
)
data class DoseLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val medicationId: Long,
    val scheduledTime: LocalDateTime,
    val actualTime: LocalDateTime? = null,
    val status: DoseStatus = DoseStatus.SCHEDULED,
    val reminderAttempts: Int = 0,
)

data class MedicationWithSchedules(
    @Embedded val medication: MedicationEntity,
    @Relation(parentColumn = "id", entityColumn = "medicationId")
    val schedules: List<ScheduleEntity>,
)
