package com.mediminder.app.domain.service

import com.mediminder.app.domain.repository.ReminderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReRemindPolicyTest {

    private val policy = ReRemindPolicy()
    private val settings = ReminderSettings(
        snoozeMinutes = 10,
        reRemindIntervalMinutes = 10,
        maxReReminds = 3,
    )

    @Test
    fun `missed only after the whole reminder budget is used`() {
        assertFalse(policy.shouldMarkMissed(attempts = 0, maxReReminds = 3))
        assertFalse(policy.shouldMarkMissed(attempts = 1, maxReReminds = 3))
        assertFalse(policy.shouldMarkMissed(attempts = 2, maxReReminds = 3))
        assertTrue(policy.shouldMarkMissed(attempts = 3, maxReReminds = 3))
        assertTrue(policy.shouldMarkMissed(attempts = 4, maxReReminds = 3))
    }

    @Test
    fun `next reminder delay comes from settings`() {
        assertEquals(10, policy.nextReminderDelayMinutes(settings))
        assertEquals(15, policy.nextReminderDelayMinutes(settings.copy(reRemindIntervalMinutes = 15)))
    }

    @Test
    fun `missed cutoff covers the full chain plus a buffer`() {
        val now = LocalDateTime.of(2026, 1, 1, 12, 0)
        // (3 re-reminds + 1 final check) * 10 minutes + 5 minutes buffer = 45.
        assertEquals(LocalDateTime.of(2026, 1, 1, 11, 15), policy.missedCutoff(now, settings))
    }

    @Test
    fun `sweep timing example`() {
        // A dose scheduled at 08:00 with default settings becomes MISSABLE at
        // 08:45 at the earliest (three reminders at 08:00/08:10/08:20, final
        // check at 08:30, then the 5-minute sweep buffer).
        val now = LocalDateTime.of(2026, 1, 1, 8, 44)
        val cutoff = policy.missedCutoff(now, settings)
        // The 08:00 dose is not yet past the cutoff.
        assertTrue(LocalDateTime.of(2026, 1, 1, 8, 0).isAfter(cutoff))
        // One minute later it is.
        val later = LocalDateTime.of(2026, 1, 1, 8, 46)
        assertFalse(LocalDateTime.of(2026, 1, 1, 8, 0).isAfter(policy.missedCutoff(later, settings)))
    }
}
