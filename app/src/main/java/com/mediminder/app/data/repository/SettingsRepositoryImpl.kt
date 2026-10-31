package com.mediminder.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediminder.app.domain.repository.ReminderSettings
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.repository.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "mediminder_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val RE_REMIND_INTERVAL_MINUTES = intPreferencesKey("re_remind_interval_minutes")
        val MAX_RE_REMINDS = intPreferencesKey("max_re_reminds")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DELETE_PHOTOS = booleanPreferencesKey("delete_photos_after_scan")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
    }

    override val settings: Flow<ReminderSettings> = context.dataStore.data.map { prefs ->
        ReminderSettings(
            snoozeMinutes = prefs[Keys.SNOOZE_MINUTES] ?: DEFAULT_SNOOZE_MINUTES,
            reRemindIntervalMinutes =
                prefs[Keys.RE_REMIND_INTERVAL_MINUTES] ?: DEFAULT_RE_REMIND_INTERVAL_MINUTES,
            maxReReminds = prefs[Keys.MAX_RE_REMINDS] ?: DEFAULT_MAX_RE_REMINDS,
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: false,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            deletePhotosAfterScan = prefs[Keys.DELETE_PHOTOS] ?: true,
            themeMode = when (prefs[Keys.THEME_MODE]) {
                ThemeMode.LIGHT.name -> ThemeMode.LIGHT
                ThemeMode.DARK.name -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            },
            languageTag = prefs[Keys.LANGUAGE_TAG] ?: "",
        )
    }

    override suspend fun current(): ReminderSettings = settings.first()

    override suspend fun setSnoozeMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SNOOZE_MINUTES] = minutes.coerceIn(5, 60) }
    }

    override suspend fun setReRemindIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.RE_REMIND_INTERVAL_MINUTES] = minutes.coerceIn(5, 30) }
    }

    override suspend fun setMaxReReminds(count: Int) {
        context.dataStore.edit { it[Keys.MAX_RE_REMINDS] = count.coerceIn(1, 5) }
    }

    override suspend fun setTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    override suspend fun setOnboardingCompleted() {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = true }
    }

    override suspend fun setDeletePhotosAfterScan(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DELETE_PHOTOS] = enabled }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setLanguageTag(tag: String) {
        context.dataStore.edit { it[Keys.LANGUAGE_TAG] = tag }
    }

    private companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
        const val DEFAULT_RE_REMIND_INTERVAL_MINUTES = 10
        const val DEFAULT_MAX_RE_REMINDS = 3
    }
}
