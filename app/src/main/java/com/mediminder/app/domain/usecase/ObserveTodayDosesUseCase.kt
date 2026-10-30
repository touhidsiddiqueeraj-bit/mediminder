package com.mediminder.app.domain.usecase

import com.mediminder.app.domain.model.DoseDisplayStatus
import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.UpcomingDose
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.ReminderSettings
import com.mediminder.app.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Live list of today's doses with display status, grouped later by the UI into
 * Morning / Afternoon / Evening / Night.
 *
 * [nowFlow] ticks every minute so "upcoming" flips to "missed" on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTodayDosesUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke(nowFlow: Flow<LocalDateTime>): Flow<List<UpcomingDose>> =
        nowFlow.flatMapLatest { now ->
            val dayStart = now.toLocalDate().atStartOfDay()
            val dayEnd = dayStart.plusDays(1).minusNanos(1)
            combine(
                medicationRepository.observeActiveWithSchedules(),
                doseLogRepository.observeLogsBetween(dayStart, dayEnd),
                settingsRepository.settings,
            ) { medications, logs, settings ->
                val byId = medications.associateBy { it.id }
                logs.mapNotNull { log ->
                    val medication = byId[log.medicationId] ?: return@mapNotNull null
                    UpcomingDose(log, medication, deriveStatus(log, now, settings))
                }.sortedBy { it.dose.scheduledTime }
            }
        }

    /**
     * A scheduled dose in the past stays "upcoming" while its re-remind chain
     * can still be running, then turns "missed".
     */
    private fun deriveStatus(
        dose: DoseLog,
        now: LocalDateTime,
        settings: ReminderSettings,
    ): DoseDisplayStatus = when (dose.status) {
        DoseStatus.TAKEN -> DoseDisplayStatus.TAKEN
        DoseStatus.SKIPPED -> DoseDisplayStatus.SKIPPED
        DoseStatus.SNOOZED -> DoseDisplayStatus.SNOOZED
        DoseStatus.MISSED -> DoseDisplayStatus.MISSED
        DoseStatus.SCHEDULED -> {
            val graceMinutes = (settings.maxReReminds + 1L) * settings.reRemindIntervalMinutes
            val dueBy = dose.scheduledTime.plusMinutes(graceMinutes)
            if (dueBy.isBefore(now)) DoseDisplayStatus.MISSED else DoseDisplayStatus.UPCOMING
        }
    }
}
