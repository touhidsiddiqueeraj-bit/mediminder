package com.mediminder.app.data.mapper

import com.mediminder.app.data.local.DoseLogEntity
import com.mediminder.app.data.local.MedicationEntity
import com.mediminder.app.data.local.MedicationWithSchedules
import com.mediminder.app.data.local.ScheduleEntity
import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.Schedule

// ---- Medication + Schedule ----

fun MedicationWithSchedules.toDomain(): Medication = Medication(
    id = medication.id,
    name = medication.name,
    genericName = medication.genericName,
    dosageValue = medication.dosageValue,
    dosageUnit = medication.dosageUnit,
    form = medication.form,
    instructions = medication.instructions,
    photoUri = medication.photoUri,
    startDate = medication.startDate,
    endDate = medication.endDate,
    pillsRemaining = medication.pillsRemaining,
    isActive = medication.isActive,
    createdAt = medication.createdAt,
    schedules = schedules.map { it.toDomain() },
)

fun ScheduleEntity.toDomain(): Schedule = Schedule(
    id = id,
    medicationId = medicationId,
    type = type,
    daysOfWeek = daysOfWeek,
    intervalHours = intervalHours,
    intervalDays = intervalDays,
    times = times,
)

fun Medication.toEntity(): MedicationEntity = MedicationEntity(
    id = id,
    name = name.trim(),
    genericName = genericName?.trim()?.takeIf { it.isNotBlank() },
    dosageValue = dosageValue?.trim()?.takeIf { it.isNotBlank() },
    dosageUnit = dosageUnit?.trim()?.takeIf { it.isNotBlank() },
    form = form,
    instructions = instructions?.trim()?.takeIf { it.isNotBlank() },
    photoUri = photoUri,
    startDate = startDate,
    endDate = endDate,
    pillsRemaining = pillsRemaining,
    isActive = isActive,
    createdAt = createdAt,
)

fun Schedule.toEntity(medicationId: Long): ScheduleEntity = ScheduleEntity(
    id = id,
    medicationId = medicationId,
    type = type,
    daysOfWeek = daysOfWeek,
    intervalHours = intervalHours,
    intervalDays = intervalDays,
    times = times,
)

// ---- DoseLog ----

fun DoseLogEntity.toDomain(): DoseLog = DoseLog(
    id = id,
    medicationId = medicationId,
    scheduledTime = scheduledTime,
    actualTime = actualTime,
    status = status,
    reminderAttempts = reminderAttempts,
)

fun DoseLog.toEntity(): DoseLogEntity = DoseLogEntity(
    id = id,
    medicationId = medicationId,
    scheduledTime = scheduledTime,
    actualTime = actualTime,
    status = status,
    reminderAttempts = reminderAttempts,
)
