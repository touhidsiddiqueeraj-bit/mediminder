package com.mediminder.app.ui.reminder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mediminder.app.R
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.repository.ThemeMode
import com.mediminder.app.domain.usecase.DoseOutcome
import com.mediminder.app.domain.usecase.MarkDoseUseCase
import com.mediminder.app.domain.usecase.SnoozeDoseUseCase
import com.mediminder.app.notifications.NotificationHelper
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.BigOutlinedButton
import com.mediminder.app.ui.components.timeFormatter
import com.mediminder.app.ui.theme.MediMinderTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The dose the full-screen reminder is acting on, loaded from the database.
 */
@HiltViewModel
class FullScreenReminderViewModel @Inject constructor(
    private val doseLogRepository: DoseLogRepository,
    private val medicationRepository: MedicationRepository,
    private val markDoseUseCase: MarkDoseUseCase,
    private val snoozeDoseUseCase: SnoozeDoseUseCase,
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper,
) : ViewModel() {

    private val _state = MutableStateFlow<ReminderUiState>(ReminderUiState.Loading)
    val state: StateFlow<ReminderUiState> = _state.asStateFlow()

    val themeMode = settingsRepository.settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val snoozeMinutes = settingsRepository.settings
        .map { it.snoozeMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10)

    private var loadingDoseId = 0L

    /** Idempotent; also accepts a new dose id after onNewIntent. */
    fun load(doseId: Long) {
        if (doseId <= 0L) {
            _state.value = ReminderUiState.Done
            return
        }
        if (loadingDoseId == doseId && _state.value !is ReminderUiState.Loading) return
        loadingDoseId = doseId
        _state.value = ReminderUiState.Loading
        viewModelScope.launch {
            val dose = withContext(Dispatchers.IO) { doseLogRepository.getDose(doseId) }
            val medication = dose?.let {
                withContext(Dispatchers.IO) { medicationRepository.getMedication(it.medicationId) }
            }
            _state.value = reduce(
                _state.value,
                ReminderEvent.Loaded(dose?.let { ReminderContent(it, medication) }),
            )
        }
    }

    fun take() = act(ReminderAction.TAKEN)
    fun snooze() = act(ReminderAction.SNOOZE)
    fun skip() = act(ReminderAction.SKIP)

    private fun act(action: ReminderAction) {
        val doseId = loadingDoseId
        val next = reduce(_state.value, ReminderEvent.ActionStarted(action))
        if (next == _state.value) return // reducer refused (double tap, wrong state)
        _state.value = next
        val content = (next as ReminderUiState.Acting).content
        viewModelScope.launch {
            try {
                when (action) {
                    ReminderAction.TAKEN -> {
                        markDoseUseCase(doseId, DoseOutcome.TAKEN)
                        val languageTag = settingsRepository.current().languageTag
                        content.medication?.let {
                            notificationHelper.showTakenFeedback(doseId, it.name, languageTag)
                        }
                    }
                    ReminderAction.SNOOZE -> {
                        val minutes = settingsRepository.current().snoozeMinutes
                        snoozeDoseUseCase(doseId, minutes)
                    }
                    ReminderAction.SKIP -> markDoseUseCase(doseId, DoseOutcome.SKIPPED)
                }
                _state.value = reduce(_state.value, ReminderEvent.ActionFinished)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle $action for dose $doseId", e)
                _state.value = reduce(_state.value, ReminderEvent.ActionFailed)
            }
        }
    }

    private companion object {
        const val TAG = "FullScreenReminder"
    }
}

/**
 * The dedicated full-screen reminder, opened by the notification's
 * full-screen intent (screen off / locked) and by tapping the notification.
 *
 * Unlike the old behaviour — which cold-started the whole app over the lock
 * screen — this screen shows exactly one thing: the medicine that is due,
 * with three giant buttons. It turns the screen on, shows over the lock
 * screen and never lets the display sleep while visible. The spoken line
 * happens once at the fire moment in ReminderTrigger (so it also plays when
 * the user is in another app and only a banner appears). All writes go
 * through the same use cases the notification buttons use, so behaviour
 * stays identical across both entry points.
 */
@AndroidEntryPoint
class FullScreenReminderActivity : AppCompatActivity() {

    private val viewModel: FullScreenReminderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Light the screen up and keep it lit while the medicine info is on it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        loadDoseFrom(intent)
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val snoozeMinutes by viewModel.snoozeMinutes.collectAsStateWithLifecycle()
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(state) {
                if (state is ReminderUiState.Done) finish()
            }

            MediMinderTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReminderScreen(
                        state = state,
                        snoozeMinutes = snoozeMinutes,
                        onTaken = viewModel::take,
                        onSnooze = viewModel::snooze,
                        onSkip = viewModel::skip,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadDoseFrom(intent)
    }

    private fun loadDoseFrom(intent: Intent?) {
        val doseId = intent?.getLongExtra(EXTRA_DOSE_ID, -1L) ?: -1L
        viewModel.load(doseId)
    }

    companion object {
        const val ACTION_SHOW_REMINDER = "com.mediminder.app.action.SHOW_REMINDER"
        const val EXTRA_DOSE_ID = "extra_dose_id"
    }
}

@Composable
private fun ReminderScreen(
    state: ReminderUiState,
    snoozeMinutes: Int,
    onTaken: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    when (state) {
        ReminderUiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.fullscreen_loading),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        ReminderUiState.Done -> Unit // closing

        is ReminderUiState.AlreadyDone -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.fullscreen_already_done),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            state.content.medication?.let { medication ->
                Spacer(Modifier.height(8.dp))
                Text(
                    medication.name,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
            BigOutlinedButton(text = stringResource(R.string.fullscreen_close), onClick = onClose)
        }

        is ReminderUiState.Content -> DueDoseContent(
            content = state.content,
            busy = false,
            snoozeMinutes = snoozeMinutes,
            onTaken = onTaken,
            onSnooze = onSnooze,
            onSkip = onSkip,
        )

        is ReminderUiState.Acting -> DueDoseContent(
            content = state.content,
            busy = true,
            snoozeMinutes = snoozeMinutes,
            onTaken = onTaken,
            onSnooze = onSnooze,
            onSkip = onSkip,
        )
    }
}

@Composable
private fun DueDoseContent(
    content: ReminderContent,
    busy: Boolean,
    snoozeMinutes: Int,
    onTaken: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.fullscreen_reminder_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.fullscreen_reminder_at,
                timeFormatter().format(content.dose.scheduledTime),
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        content.medication?.let { medication ->
            MedicationSummary(medication)
            Spacer(Modifier.height(40.dp))
        }
        if (busy) {
            LinearProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.saving),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(16.dp))
        }
        BigButton(
            text = stringResource(R.string.action_taken),
            onClick = onTaken,
            enabled = !busy,
            leadingIcon = Icons.Filled.Check,
        )
        Spacer(Modifier.height(12.dp))
        BigOutlinedButton(
            text = stringResource(R.string.action_snooze, snoozeMinutes),
            onClick = onSnooze,
            enabled = !busy,
            leadingIcon = Icons.Filled.Snooze,
        )
        Spacer(Modifier.height(12.dp))
        BigOutlinedButton(
            text = stringResource(R.string.action_skip),
            onClick = onSkip,
            enabled = !busy,
            leadingIcon = Icons.Filled.Close,
        )
    }
}

@Composable
private fun MedicationSummary(medication: Medication) {
    Text(
        medication.name,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    val details = listOfNotNull(medication.dosageText, medication.instructions)
        .joinToString(separator = " · ")
    if (details.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            details,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
