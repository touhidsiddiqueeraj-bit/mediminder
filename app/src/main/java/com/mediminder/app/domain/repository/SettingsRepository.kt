package com.mediminder.app.domain.repository

import kotlinx.coroutines.flow.Flow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * User preferences. Defaults are chosen for reliability:
 * 10-minute snooze, up to 3 re-reminders 10 minutes apart.
 */
data class ReminderSettings(
    val snoozeMinutes: Int = 10,
    val reRemindIntervalMinutes: Int = 10,
    val maxReReminds: Int = 3,
    val ttsEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val deletePhotosAfterScan: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * BCP-47 tag of the app language chosen in Settings ("" = follow the
     * phone). Kept in sync with the per-app locale so notifications and
     * reports — which use the application context — can be localized on
     * Android 8–12, where per-app locales only apply to activity contexts.
     */
    val languageTag: String = "",
)

interface SettingsRepository {

    val settings: Flow<ReminderSettings>

    suspend fun current(): ReminderSettings

    suspend fun setSnoozeMinutes(minutes: Int)

    suspend fun setReRemindIntervalMinutes(minutes: Int)

    suspend fun setMaxReReminds(count: Int)

    suspend fun setTtsEnabled(enabled: Boolean)

    suspend fun setOnboardingCompleted()

    suspend fun setDeletePhotosAfterScan(enabled: Boolean)

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setLanguageTag(tag: String)
}
