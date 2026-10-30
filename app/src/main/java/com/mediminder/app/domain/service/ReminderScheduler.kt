package com.mediminder.app.domain.service

/**
 * Abstraction over the Android alarm machinery so the domain layer stays
 * testable and free of framework code.
 *
 * Reliability contract (implemented in `scheduling/`):
 * - exact alarms via `setExactAndAllowWhileIdle` when permitted
 * - inexact-but-doze-tolerant alarms + a WorkManager safety net otherwise
 * - everything re-registered after reboot / timezone change / app update
 */
interface ReminderScheduler {

    /**
     * Materializes dose rows for the next few days and (re)registers alarms for
     * every pending dose. Safe to call at any time; idempotent.
     */
    suspend fun rescheduleAllAlarms()

    /** Cancels all pending alarms and notifications for a dose that was handled. */
    suspend fun onDoseHandled(doseId: Long)

    /** Re-alerts the user for a dose after [minutesFromNow] minutes. */
    suspend fun scheduleSnooze(doseId: Long, medicationId: Long, minutesFromNow: Int)
}
