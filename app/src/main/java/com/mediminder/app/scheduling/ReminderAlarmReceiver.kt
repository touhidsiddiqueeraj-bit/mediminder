package com.mediminder.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fired by AlarmManager exactly when a dose is due (or for a re-remind /
 * snooze follow-up). goAsync() gives us ~10s; the work itself is a couple of
 * small database operations.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderTrigger: ReminderTrigger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DOSE_REMINDER) return
        val doseId = intent.getLongExtra(EXTRA_DOSE_ID, -1L)
        if (doseId <= 0L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                reminderTrigger.fire(doseId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fire reminder for dose $doseId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderAlarmReceiver"

        const val ACTION_DOSE_REMINDER = "com.mediminder.app.action.DOSE_REMINDER"
        const val EXTRA_DOSE_ID = "extra_dose_id"
        const val EXTRA_MEDICATION_ID = "extra_medication_id"
    }
}
