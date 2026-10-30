package com.mediminder.app.domain.repository

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

interface DoseLogRepository {

    fun observeLogsBetween(from: LocalDateTime, to: LocalDateTime): Flow<List<DoseLog>>

    suspend fun getLogsBetween(from: LocalDateTime, to: LocalDateTime): List<DoseLog>

    fun observeDose(id: Long): Flow<DoseLog?>

    suspend fun getDose(id: Long): DoseLog?

    /**
     * Creates the given doses if they do not exist yet (unique per
     * medication + scheduled time) and returns the ids of all of them.
     */
    suspend fun ensureScheduledDoses(doses: List<DoseLog>): List<Long>

    suspend fun update(dose: DoseLog)

    suspend fun getScheduledDosesBetween(from: LocalDateTime, to: LocalDateTime): List<DoseLog>

    /** Returns doses still SCHEDULED whose time is before [cutoff], then marks them MISSED. */
    suspend fun markOverdueAsMissed(cutoff: LocalDateTime): List<DoseLog>

    suspend fun logsForMedication(medicationId: Long, from: LocalDateTime, to: LocalDateTime): List<DoseLog>

    /** Removes future doses for a medication (history is kept). */
    suspend fun deleteFutureDoses(medicationId: Long)

    /**
     * Reminder planner support: drops a medication's future SCHEDULED doses that
     * are not in [keepDoseIds] (i.e. their schedule changed or the dose vanished).
     */
    suspend fun pruneFutureScheduledDoses(
        medicationId: Long,
        keepDoseIds: List<Long>,
        from: LocalDateTime,
    )

    /** Future scheduled doses of deactivated medications are removed. */
    suspend fun pruneFutureScheduledForInactiveMedications(from: LocalDateTime)

    /** Counts doses with the given statuses in a period (for reports/tests). */
    suspend fun countByStatus(from: LocalDateTime, to: LocalDateTime, statuses: List<DoseStatus>): Int
}
