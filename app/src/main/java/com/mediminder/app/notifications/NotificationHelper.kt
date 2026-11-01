package com.mediminder.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mediminder.app.R
import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.LowStockInfo
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.repository.ReminderSettings
import com.mediminder.app.scheduling.ReminderActionReceiver
import com.mediminder.app.ui.reminder.FullScreenReminderActivity
import com.mediminder.app.util.withAppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All notifications live here: channels, the dose reminder (high priority,
 * full-screen intent, big text, three actions), low-stock alerts and the
 * quiet "marked as taken" feedback.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun areNotificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun fullScreenIntentGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            notificationManager.canUseFullScreenIntent()

    // ------------------------------------------------------------------
    // Channels
    // ------------------------------------------------------------------

    /**
     * Creates the notification channels. Channel names are a snapshot taken
     * at creation (Android ignores later renames), so a language change does
     * not retro-translate them — only fresh installs pick the new language.
     */
    fun ensureChannels(languageTag: String = "") {
        val strings = context.withAppLanguage(languageTag)
        val chime = Uri.parse("android.resource://${context.packageName}/${R.raw.reminder_chime}")
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            strings.getString(R.string.channel_reminders_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = strings.getString(R.string.channel_reminders_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 250, 400)
            setSound(chime, audio)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(reminders)

        val lowStock = NotificationChannel(
            CHANNEL_LOW_STOCK,
            strings.getString(R.string.channel_low_stock_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = strings.getString(R.string.channel_low_stock_desc)
        }
        notificationManager.createNotificationChannel(lowStock)

        val feedback = NotificationChannel(
            CHANNEL_FEEDBACK,
            strings.getString(R.string.channel_feedback_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = strings.getString(R.string.channel_feedback_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(feedback)
    }

    // ------------------------------------------------------------------
    // Dose reminder
    // ------------------------------------------------------------------

    fun showDoseReminder(dose: DoseLog, medication: Medication, settings: ReminderSettings) {
        ensureChannels(settings.languageTag)
        if (!hasPostNotifications()) return

        // Localized strings: on Android 8–12 the application context does not
        // follow the per-app language, so re-apply the chosen one.
        val strings = context.withAppLanguage(settings.languageTag)
        val doseId = dose.id
        val title = strings.getString(R.string.notif_reminder_title, medication.name)
        val details = listOfNotNull(medication.dosageText, medication.instructions)
            .joinToString(separator = "  ·  ")
            .ifBlank { strings.getString(R.string.notif_reminder_fallback_text) }

        // Tapping the notification opens the dedicated full-screen reminder —
        // the same screen the full-screen intent launches when the phone is
        // locked — so the "what do I take now?" answer is one tap away.
        val openIntent = Intent(context, FullScreenReminderActivity::class.java).apply {
            action = FullScreenReminderActivity.ACTION_SHOW_REMINDER
            putExtra(FullScreenReminderActivity.EXTRA_DOSE_ID, doseId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            (NOTIF_DOSE_BASE + doseId).toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The same screen, shown full-screen when the phone is locked.
        val fullScreenPi = PendingIntent.getActivity(
            context,
            (NOTIF_FULLSCREEN_BASE + doseId).toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val takenPi = actionPendingIntent(ReminderActionReceiver.ACTION_TAKEN, doseId, 0)
        val snoozePi = actionPendingIntent(
            ReminderActionReceiver.ACTION_SNOOZE,
            doseId,
            1,
        )
        val skipPi = actionPendingIntent(ReminderActionReceiver.ACTION_SKIP, doseId, 2)

        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false) // every re-remind should alert again
            .setContentIntent(contentPi)
            .addAction(0, strings.getString(R.string.action_taken), takenPi)
            .addAction(
                0,
                strings.getString(R.string.action_snooze, settings.snoozeMinutes),
                snoozePi,
            )
            .addAction(0, strings.getString(R.string.action_skip), skipPi)

        if (fullScreenIntentGranted()) {
            builder.setFullScreenIntent(fullScreenPi, true)
        }

        safelyNotify((NOTIF_DOSE_BASE + doseId).toInt(), builder)
    }

    fun cancelDoseNotification(doseId: Long) {
        NotificationManagerCompat.from(context).cancel((NOTIF_DOSE_BASE + doseId).toInt())
    }

    private fun actionPendingIntent(action: String, doseId: Long, requestCodeOffset: Int): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderActionReceiver.EXTRA_DOSE_ID, doseId)
        }
        return PendingIntent.getBroadcast(
            context,
            (doseId * 10 + requestCodeOffset).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ------------------------------------------------------------------
    // Feedback + low stock
    // ------------------------------------------------------------------

    fun showTakenFeedback(doseId: Long, medicationName: String, languageTag: String = "") {
        ensureChannels(languageTag)
        if (!hasPostNotifications()) return
        val strings = context.withAppLanguage(languageTag)
        val builder = NotificationCompat.Builder(context, CHANNEL_FEEDBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(strings.getString(R.string.notif_taken_title))
            .setContentText(strings.getString(R.string.notif_taken_text, medicationName))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
        safelyNotify((NOTIF_TAKEN_BASE + doseId).toInt(), builder)
    }

    fun showLowStock(info: LowStockInfo, languageTag: String = "") {
        ensureChannels(languageTag)
        if (!hasPostNotifications()) return
        val strings = context.withAppLanguage(languageTag)
        val days = info.daysRemaining
        val quantityText = strings.resources.getQuantityString(
            R.plurals.notif_low_stock_days,
            days,
            days,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_LOW_STOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(strings.getString(R.string.notif_low_stock_title))
            .setContentText(
                strings.getString(
                    R.string.notif_low_stock_text,
                    info.medicationName,
                    quantityText,
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    strings.getString(
                        R.string.notif_low_stock_text,
                        info.medicationName,
                        quantityText,
                    ),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
        safelyNotify((NOTIF_STOCK_BASE + info.medicationId).toInt(), builder)
    }

    // ------------------------------------------------------------------

    private fun hasPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun safelyNotify(id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between check and notify — ignore.
        }
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_LOW_STOCK = "low_stock"
        const val CHANNEL_FEEDBACK = "feedback"

        private const val NOTIF_DOSE_BASE = 100_000L
        private const val NOTIF_TAKEN_BASE = 200_000L
        private const val NOTIF_STOCK_BASE = 300_000L
        private const val NOTIF_FULLSCREEN_BASE = 400_000L
    }
}
