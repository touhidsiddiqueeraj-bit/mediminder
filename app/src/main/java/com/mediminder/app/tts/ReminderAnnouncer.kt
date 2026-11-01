package com.mediminder.app.tts

import android.content.Context
import com.mediminder.app.R
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.util.appLocale
import com.mediminder.app.util.withAppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks the reminder line at the moment a dose fires.
 *
 * Why here and not in the full-screen activity: Android only launches a
 * full-screen intent with the screen off or on the lock screen — while the
 * user is actively using another app, the reminder is a heads-up banner
 * instead (documented platform behaviour). The voice therefore belongs to
 * the fire moment, not to a screen that may never open. This way the phone
 * says "It is time to take X" no matter which app is in the foreground,
 * and the full-screen screen stays purely visual.
 *
 * Reliability rule: speech is best-effort. A missing engine, a missing
 * language or a hung engine must never delay or block the notification, so
 * every failure is swallowed and the budget is capped by the speaker.
 */
@Singleton
class ReminderAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Speaks the reminder line if [ttsEnabled]. Never throws. */
    suspend fun announce(medication: Medication?, languageTag: String, ttsEnabled: Boolean) {
        if (!ttsEnabled) return
        val speaker = ReminderSpeaker(context)
        try {
            speaker.language(localeFor(languageTag))
            val strings = context.withAppLanguage(languageTag)
            val text = if (medication != null) {
                strings.getString(R.string.tts_reminder_with_name, medication.name)
            } else {
                strings.getString(R.string.tts_reminder_generic)
            }
            speaker.speakAwaitingDone(text)
        } catch (_: Exception) {
            // The notification is the critical path; speech is not.
        } finally {
            speaker.shutdown()
        }
    }

    private fun localeFor(languageTag: String): Locale =
        if (languageTag.isBlank()) appLocale() else Locale.forLanguageTag(languageTag)
}
