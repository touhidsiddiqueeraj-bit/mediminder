package com.mediminder.app.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mediminder.app.domain.service.ClockProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, well-tested wrapper around [AlarmManager].
 *
 * Strategy (reliability first):
 * 1. `setExactAndAllowWhileIdle` — fires in Doze, to the second.
 * 2. If the exact-alarm permission is missing: `setAndAllowWhileIdle`
 *    (Doze-tolerant, may drift) plus a 30-minute WorkManager safety net
 *    (see [SafetyNetWorker]).
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clockProvider: ClockProvider,
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleDoseReminder(doseId: Long, medicationId: Long, triggerAtMillis: Long) {
        val pendingIntent = doseReminderPendingIntent(doseId, medicationId)
        val inexactAt = triggerAtMillis.coerceAtLeast(clockProvider.clock.millis())
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    inexactAt,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, inexactAt, pendingIntent)
            }
        } catch (securityException: SecurityException) {
            // Permission revoked between the check and this call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, inexactAt, pendingIntent)
        }
    }

    fun cancelDoseReminder(doseId: Long) {
        // Intent extras are ignored for matching, so medicationId can be 0 here.
        val pendingIntent = doseReminderPendingIntent(doseId, medicationId = 0L)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun doseReminderPendingIntent(doseId: Long, medicationId: Long): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_DOSE_REMINDER
            putExtra(ReminderAlarmReceiver.EXTRA_DOSE_ID, doseId)
            putExtra(ReminderAlarmReceiver.EXTRA_MEDICATION_ID, medicationId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(doseId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun requestCodeFor(doseId: Long): Int = (REQUEST_CODE_BASE + doseId).toInt()

    private companion object {
        const val REQUEST_CODE_BASE = 10_000
    }
}
