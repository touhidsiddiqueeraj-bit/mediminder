package com.mediminder.app.domain.model

import java.time.LocalDateTime

/**
 * One scheduled dose and its outcome. Rows are materialized a few days ahead
 * so that history, adherence and "missed" detection have a single source of truth.
 */
enum class DoseStatus {
    /** Created ahead of time, waiting for its slot. */
    SCHEDULED,

    /** The user confirmed taking it. */
    TAKEN,

    /** The reminder chain finished without any response (after re-reminders). */
    MISSED,

    /** The user explicitly skipped it. */
    SKIPPED,

    /** The user postponed it; the reminder will fire again. */
    SNOOZED,
}

data class DoseLog(
    val id: Long = 0L,
    val medicationId: Long,
    val scheduledTime: LocalDateTime,
    val actualTime: LocalDateTime? = null,
    val status: DoseStatus = DoseStatus.SCHEDULED,
    /** How many reminder notifications have been shown for this dose. */
    val reminderAttempts: Int = 0,
)
