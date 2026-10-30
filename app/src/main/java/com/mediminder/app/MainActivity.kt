package com.mediminder.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.repository.ThemeMode
import com.mediminder.app.ui.navigation.MediMinderNavHost
import com.mediminder.app.ui.theme.MediMinderTheme
import com.mediminder.app.tts.ReminderSpeaker
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val themeMode = settingsRepository.settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val onboardingCompleted = settingsRepository.settings
        .map { it.onboardingCompleted }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Emits a dose id when the app was opened from a reminder notification. */
    val reminderOpened = MutableSharedFlow<Long>(extraBufferCapacity = 4)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var medicationRepository: MedicationRepository

    @Inject
    lateinit var doseLogRepository: DoseLogRepository

    private lateinit var speaker: ReminderSpeaker

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        speaker = ReminderSpeaker(this)
        handleReminderIntent(intent)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()

            MediMinderTheme(themeMode = themeMode) {
                MediMinderNavHost(
                    showOnboardingFirst = !onboardingCompleted,
                    reminderOpened = viewModel.reminderOpened,
                    onOnboardingFinished = {
                        lifecycleScope.launch { settingsRepository.setOnboardingCompleted() }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReminderIntent(intent)
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    /**
     * Full-screen reminders: show over the lock screen only when the app was
     * opened from a reminder (health data is never exposed on the lock screen
     * otherwise).
     */
    private fun handleReminderIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(EXTRA_DOSE_ID)) {
            applyKeyguardVisibility(false)
            return
        }
        applyKeyguardVisibility(true)
        val doseId = intent.getLongExtra(EXTRA_DOSE_ID, -1L)
        if (doseId > 0) {
            viewModel.reminderOpened.tryEmit(doseId)
            maybeSpeakReminder(doseId)
        }
    }

    private fun maybeSpeakReminder(doseId: Long) {
        lifecycleScope.launch {
            val settings = settingsRepository.current()
            if (!settings.ttsEnabled) return@launch

            val medicationName = withContext(Dispatchers.IO) {
                val dose = doseLogRepository.getDose(doseId)
                dose?.let { medicationRepository.getMedication(it.medicationId) }?.name
            }
            speaker.init()
            val text = if (medicationName != null) {
                getString(R.string.tts_reminder_with_name, medicationName)
            } else {
                getString(R.string.tts_reminder_generic)
            }
            speaker.speak(text)
        }
    }

    private fun applyKeyguardVisibility(fromReminder: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(fromReminder)
            setTurnScreenOn(fromReminder)
        } else {
            @Suppress("DEPRECATION")
            if (fromReminder) {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
            } else {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
            }
        }
    }

    companion object {
        const val ACTION_OPEN_REMINDER = "com.mediminder.app.action.OPEN_REMINDER"
        const val EXTRA_DOSE_ID = "extra_dose_id"
    }
}
