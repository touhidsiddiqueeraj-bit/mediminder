package com.mediminder.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.usecase.DoseOutcome
import com.mediminder.app.domain.usecase.MarkDoseUseCase
import com.mediminder.app.domain.usecase.SnoozeDoseUseCase
import com.mediminder.app.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the three big buttons on the reminder notification:
 * Taken / Snooze N min / Skip.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var markDoseUseCase: MarkDoseUseCase

    @Inject
    lateinit var snoozeDoseUseCase: SnoozeDoseUseCase

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var medicationRepository: MedicationRepository

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_TAKEN && action != ACTION_SNOOZE && action != ACTION_SKIP) return
        val doseId = intent.getLongExtra(EXTRA_DOSE_ID, -1L)
        if (doseId <= 0L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(action, doseId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle action $action for dose $doseId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(action: String, doseId: Long) {
        when (action) {
            ACTION_TAKEN -> {
                val dose = markDoseUseCase(doseId, DoseOutcome.TAKEN)
                val medication = dose?.let { medicationRepository.getMedication(it.medicationId) }
                if (medication != null) {
                    // Quiet confirmation so the user knows the tap worked.
                    // Language tag keeps the text in the app language even
                    // though the app process was never opened.
                    val languageTag = settingsRepository.current().languageTag
                    notificationHelper.showTakenFeedback(doseId, medication.name, languageTag)
                }
            }
            ACTION_SNOOZE -> {
                val settings = settingsRepository.current()
                snoozeDoseUseCase(doseId, settings.snoozeMinutes)
            }
            ACTION_SKIP -> {
                markDoseUseCase(doseId, DoseOutcome.SKIPPED)
            }
        }
    }

    companion object {
        private const val TAG = "ReminderActionReceiver"

        const val ACTION_TAKEN = "com.mediminder.app.action.DOSE_TAKEN"
        const val ACTION_SNOOZE = "com.mediminder.app.action.DOSE_SNOOZE"
        const val ACTION_SKIP = "com.mediminder.app.action.DOSE_SKIP"
        const val EXTRA_DOSE_ID = "extra_dose_id"
    }
}
