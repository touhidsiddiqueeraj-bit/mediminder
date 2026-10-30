package com.mediminder.app.domain.service

import com.mediminder.app.domain.repository.ReminderSettings
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure decision logic for the re-remind chain, kept free of Android APIs so it
 * can be unit-tested:
 *
 *   t0      notify (attempt 1)
 *   t0+10m  notify (attempt 2)
 *   t0+20m  notify (attempt 3)
 *   t0+30m  final check: no response -> mark the dose MISSED
 */
@Singleton
class ReRemindPolicy @Inject constructor() {

    /** True when the reminder budget is exhausted and the dose becomes MISSED. */
    fun shouldMarkMissed(attempts: Int, maxReReminds: Int): Boolean = attempts >= maxReReminds

    /** Minutes to wait before the next re-remind. */
    fun nextReminderDelayMinutes(settings: ReminderSettings): Int =
        settings.reRemindIntervalMinutes

    /**
     * Sweep cutoff: doses still SCHEDULED before this moment have certainly
     * finished their whole reminder chain without an answer.
     */
    fun missedCutoff(now: LocalDateTime, settings: ReminderSettings): LocalDateTime = now.minusMinutes(
        (settings.maxReReminds + 1L) * settings.reRemindIntervalMinutes + SWEEP_BUFFER_MINUTES,
    )

    private companion object {
        const val SWEEP_BUFFER_MINUTES = 5L
    }
}
