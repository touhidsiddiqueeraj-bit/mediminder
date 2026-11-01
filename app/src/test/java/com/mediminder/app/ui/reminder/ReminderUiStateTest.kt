package com.mediminder.app.ui.reminder

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure state-machine tests for the full-screen reminder: loading, the
 * double-tap guard, already-final doses and failure recovery.
 */
class ReminderUiStateTest {

    private fun dose(status: DoseStatus = DoseStatus.SCHEDULED) = DoseLog(
        id = 42L,
        medicationId = 7L,
        scheduledTime = LocalDateTime.of(2026, 11, 1, 8, 0),
        status = status,
    )

    private fun medication() = Medication(
        id = 7L,
        name = "Metformin",
        dosageValue = "500",
        dosageUnit = "mg",
        form = MedicationForm.TABLET,
        startDate = LocalDate.of(2026, 1, 1),
    )

    private fun content(status: DoseStatus = DoseStatus.SCHEDULED) =
        ReminderContent(dose(status), medication())

    @Test
    fun `loading a due dose shows the action buttons`() {
        val state = reduce(ReminderUiState.Loading, ReminderEvent.Loaded(content()))
        assertTrue(state is ReminderUiState.Content)
    }

    @Test
    fun `loading a deleted dose finishes the screen`() {
        val state = reduce(ReminderUiState.Loading, ReminderEvent.Loaded(null))
        assertEquals(ReminderUiState.Done, state)
    }

    @Test
    fun `a dose that was already taken or skipped never offers buttons`() {
        for (status in listOf(DoseStatus.TAKEN, DoseStatus.SKIPPED)) {
            val state = reduce(ReminderUiState.Loading, ReminderEvent.Loaded(content(status)))
            assertTrue("expected AlreadyDone for $status", state is ReminderUiState.AlreadyDone)
        }
    }

    @Test
    fun `a snoozed or missed dose is still actionable`() {
        for (status in listOf(DoseStatus.SNOOZED, DoseStatus.MISSED)) {
            val state = reduce(ReminderUiState.Loading, ReminderEvent.Loaded(content(status)))
            assertTrue("expected Content for $status", state is ReminderUiState.Content)
        }
    }

    @Test
    fun `a second tap while a write is in flight is ignored`() {
        var state: ReminderUiState = reduce(
            ReminderUiState.Loading,
            ReminderEvent.Loaded(content()),
        )
        state = reduce(state, ReminderEvent.ActionStarted(ReminderAction.TAKEN))
        state = reduce(state, ReminderEvent.ActionStarted(ReminderAction.SKIPPED))
        assertTrue(state is ReminderUiState.Acting)
        assertEquals(ReminderAction.TAKEN, (state as ReminderUiState.Acting).action)
    }

    @Test
    fun `a successful write finishes the screen`() {
        var state: ReminderUiState = reduce(
            ReminderUiState.Loading,
            ReminderEvent.Loaded(content()),
        )
        state = reduce(state, ReminderEvent.ActionStarted(ReminderAction.SNOOZE))
        state = reduce(state, ReminderEvent.ActionFinished)
        assertEquals(ReminderUiState.Done, state)
    }

    @Test
    fun `a failed write re-enables the buttons so nothing is lost`() {
        var state: ReminderUiState = reduce(
            ReminderUiState.Loading,
            ReminderEvent.Loaded(content()),
        )
        state = reduce(state, ReminderEvent.ActionStarted(ReminderAction.TAKEN))
        state = reduce(state, ReminderEvent.ActionFailed)
        assertTrue(state is ReminderUiState.Content)
    }

    @Test
    fun `an action on an already final dose is ignored`() {
        var state: ReminderUiState = reduce(
            ReminderUiState.Loading,
            ReminderEvent.Loaded(content(DoseStatus.TAKEN)),
        )
        state = reduce(state, ReminderEvent.ActionStarted(ReminderAction.TAKEN))
        assertTrue(state is ReminderUiState.AlreadyDone)
    }

    @Test
    fun `a stale load result cannot overwrite a screen that already has content`() {
        var state: ReminderUiState = reduce(
            ReminderUiState.Loading,
            ReminderEvent.Loaded(content()),
        )
        state = reduce(state, ReminderEvent.Loaded(content(DoseStatus.TAKEN)))
        assertTrue(state is ReminderUiState.Content)
    }
}
