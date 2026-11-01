package com.mediminder.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.ReminderSettings
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.repository.ThemeMode
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.notifications.NotificationHelper
import com.mediminder.app.scheduling.AlarmScheduler
import com.mediminder.app.ui.settings.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live permission status, re-checked whenever [refresh] is called. */
data class PermissionStatus(
    val notifications: Boolean = true,
    val exactAlarms: Boolean = true,
    val batteryOptimizationIgnored: Boolean = true,
    val fullScreenIntent: Boolean = true,
)

data class SettingsUiState(
    val settings: ReminderSettings = ReminderSettings(),
    val permissions: PermissionStatus = PermissionStatus(),
    val confirmDeleteAll: Boolean = false,
    val deletingAll: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val medicationRepository: MedicationRepository,
    private val reminderScheduler: ReminderScheduler,
    private val alarmScheduler: AlarmScheduler,
    private val notificationHelper: NotificationHelper,
) : ViewModel() {

    private val _permissions = MutableStateFlow(PermissionStatus())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _deleteFlow = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _deleteFlow.asStateFlow()

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderSettings())

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _permissions.value = PermissionStatus(
            notifications = notificationHelper.areNotificationsEnabled(),
            exactAlarms = alarmScheduler.canScheduleExact(),
            batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            fullScreenIntent = notificationHelper.fullScreenIntentGranted(),
        )
    }

    fun setSnoozeMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setSnoozeMinutes(minutes)
            reminderScheduler.rescheduleAllAlarms()
        }
    }

    fun setReRemindIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setReRemindIntervalMinutes(minutes)
            reminderScheduler.rescheduleAllAlarms()
        }
    }

    fun setMaxReReminds(count: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxReReminds(count)
            reminderScheduler.rescheduleAllAlarms()
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTtsEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    /**
     * Switches the app language. The choice is stored in settings as well so
     * that notifications and reports (application context) can use it on
     * Android 8–12, where per-app locales only apply to activity contexts.
     */
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguageTag(language.tag ?: "") }
        language.select()
    }

    fun setDeletePhotosAfterScan(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDeletePhotosAfterScan(enabled) }
    }

    fun requestDeleteAll() {
        _deleteFlow.update { it.copy(confirmDeleteAll = true) }
    }

    fun cancelDeleteAll() {
        _deleteFlow.update { it.copy(confirmDeleteAll = false) }
    }

    fun confirmDeleteAll() {
        if (_deleteFlow.value.deletingAll) return
        _deleteFlow.update { it.copy(deletingAll = true) }
        viewModelScope.launch {
            medicationRepository.getAllMedications().forEach { medication ->
                medicationRepository.delete(medication.id)
            }
            reminderScheduler.rescheduleAllAlarms()
            _deleteFlow.update { it.copy(confirmDeleteAll = false, deletingAll = false) }
        }
    }
}

/** Opens the system screen where the user can allow exact alarms for the app. */
fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Asks the system to exempt the app from battery optimization. */
fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(fallback) } }
}

fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Opens the system page of the medicine-reminder channel itself, where
 * Sound and Banner ("pop on screen") are controlled. Those two settings
 * decide whether a reminder that arrives while the user is in another app
 * is heard and seen — some OEM skins or tidy-up flows switch them off, and
 * no in-app permission can force them back on. Falls back to the app's
 * notification page if a device has no channel page.
 */
fun openReminderChannelSettings(context: Context) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_REMINDERS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { openAppNotificationSettings(context) }
}

fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Android 14+: the dedicated screen where the user allows this app to show
 * its full-screen reminder. Falls back to the general app settings page.
 */
fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppSettings(context) }
    } else {
        openAppSettings(context)
    }
}

/**
 * Opens Google Speech Services on the Play Store so a phone without any
 * text-to-speech engine can get one. No INTERNET permission needed — the
 * store app handles the download.
 */
fun installVoiceEngine(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }
        .onFailure { runCatching { context.startActivity(web) } }
}

/**
 * Opens the system screen where text-to-speech voices are managed. There is
 * no public Settings constant for it, so try the well-known action first and
 * fall back to the related voice settings pages.
 */
fun openVoiceSettings(context: Context) {
    val ttsSettings = Intent("com.android.settings.TTS_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val voiceInput = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val allSettings = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(ttsSettings) }
        .onFailure {
            runCatching { context.startActivity(voiceInput) }
                .onFailure { runCatching { context.startActivity(allSettings) } }
        }
}
