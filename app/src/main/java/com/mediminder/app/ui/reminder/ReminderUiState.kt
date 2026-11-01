package com.mediminder.app.ui.reminder

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.model.Medication

/** What the user asked the full-screen reminder to do with the dose. */
enum class ReminderAction { TAKEN, SNOOZE, SKIP }

/** The dose plus its medicine, as shown on the full-screen reminder. */
data class ReminderContent(
    val dose: DoseLog,
    val medication: Medication?,
) {
    /** True when the dose already has a final outcome, so no buttons are offered. */
    val isFinal: Boolean
        get() = dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.SKIPPED
}

/** UI states of the full-screen reminder activity. */
sealed interface ReminderUiState {
    /** The dose is being read from the database. */
    data object Loading : ReminderUiState

    /** The dose is due — show the three big buttons. */
    data class Content(val content: ReminderContent) : ReminderUiState

    /** The dose was already taken or skipped — confirm and close. */
    data class AlreadyDone(val content: ReminderContent) : ReminderUiState

    /** A button was tapped and the write is in flight (buttons disabled). */
    data class Acting(val content: ReminderContent, val action: ReminderAction) : ReminderUiState

    /** Finished, or nothing to show — the activity closes itself. */
    data object Done : ReminderUiState
}

sealed interface ReminderEvent {
    /** Database read finished; null when the dose no longer exists. */
    data class Loaded(val content: ReminderContent?) : ReminderEvent

    /** The user tapped one of the buttons. */
    data class ActionStarted(val action: ReminderAction) : ReminderEvent

    /** The write completed successfully. */
    data object ActionFinished : ReminderEvent

    /** The write failed — the user may retry. */
    data object ActionFailed : ReminderEvent
}

/**
 * Pure state machine for the full-screen reminder. Keeping this free of
 * Android types makes the whole flow unit-testable: a double tap can never
 * fire two writes ([ReminderUiState.Acting] ignores further ActionStarted),
 * a dose with a final outcome never shows buttons again, and a failed write
 * re-enables the buttons so nothing is silently lost.
 */
fun reduce(state: ReminderUiState, event: ReminderEvent): ReminderUiState = when (event) {
    is ReminderEvent.Loaded -> when (state) {
        is ReminderUiState.Loading -> when {
            event.content == null -> ReminderUiState.Done
            event.content.isFinal -> ReminderUiState.AlreadyDone(event.content)
            else -> ReminderUiState.Content(event.content)
        }
        // A different dose was loaded before; ignore stale read results.
        else -> state
    }

    is ReminderEvent.ActionStarted -> when (state) {
        is ReminderUiState.Content -> ReminderUiState.Acting(state.content, event.action)
        // AlreadyDone has no buttons, Acting blocks double taps,
        // Loading/Done cannot act.
        else -> state
    }

    ReminderEvent.ActionFinished -> when (state) {
        is ReminderUiState.Acting -> ReminderUiState.Done
        else -> state
    }

    ReminderEvent.ActionFailed -> when (state) {
        is ReminderUiState.Acting -> ReminderUiState.Content(state.content)
        else -> state
    }
}
