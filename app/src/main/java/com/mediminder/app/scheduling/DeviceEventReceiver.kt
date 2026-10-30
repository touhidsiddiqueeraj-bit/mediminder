package com.mediminder.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.domain.usecase.MarkMissedDosesUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-registers every alarm after:
 * - reboot (BOOT_COMPLETED)
 * - timezone change (TIMEZONE_CHANGED)
 * - manual clock change (TIME_SET)
 * - app update (MY_PACKAGE_REPLACED)
 *
 * Alarms do not survive any of these events, so this receiver is what makes
 * reminders reliable across the device's life.
 */
@AndroidEntryPoint
class DeviceEventReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var markMissedDosesUseCase: MarkMissedDosesUseCase

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                markMissedDosesUseCase()
                reminderScheduler.rescheduleAllAlarms()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule alarms after $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DeviceEventReceiver"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
