package com.mediminder.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediminder.app.domain.engine.AdherenceCalculator
import com.mediminder.app.domain.model.LowStockInfo
import com.mediminder.app.domain.model.UpcomingDose
import com.mediminder.app.domain.model.WeeklyAdherence
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.domain.usecase.CheckLowStockUseCase
import com.mediminder.app.domain.usecase.ComputeAdherenceUseCase
import com.mediminder.app.domain.usecase.DoseOutcome
import com.mediminder.app.domain.usecase.MarkDoseUseCase
import com.mediminder.app.domain.usecase.MarkMissedDosesUseCase
import com.mediminder.app.domain.usecase.ObserveTodayDosesUseCase
import com.mediminder.app.domain.usecase.SnoozeDoseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class TodayUiState(
    val doses: List<UpcomingDose> = emptyList(),
    val lowStock: List<LowStockInfo> = emptyList(),
    val weekly: WeeklyAdherence? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val observeTodayDosesUseCase: ObserveTodayDosesUseCase,
    private val markDoseUseCase: MarkDoseUseCase,
    private val snoozeDoseUseCase: SnoozeDoseUseCase,
    private val markMissedDosesUseCase: MarkMissedDosesUseCase,
    private val checkLowStockUseCase: CheckLowStockUseCase,
    private val computeAdherenceUseCase: ComputeAdherenceUseCase,
    private val reminderScheduler: ReminderScheduler,
    private val settingsRepository: SettingsRepository,
    private val calculator: AdherenceCalculator,
    clockProvider: ClockProvider,
) : ViewModel() {

    private val clock = clockProvider.clock

    /** Ticks every minute so statuses flip from Upcoming to Missed on their own. */
    private val nowTicker = flow {
        while (coroutineContext.isActive) {
            emit(LocalDateTime.now(clock))
            delay(TICK_MILLIS)
        }
    }

    val uiState: StateFlow<TodayUiState> = observeTodayDosesUseCase(nowTicker)
        .mapLatest { doses ->
            TodayUiState(
                doses = doses,
                lowStock = checkLowStockUseCase(),
                weekly = calculator.weekly(
                    computeAdherenceUseCase.logsForPeriod(7),
                    LocalDate.now(clock),
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        // App open is the best moment to sweep missed doses and refresh alarms.
        viewModelScope.launch {
            markMissedDosesUseCase()
            reminderScheduler.rescheduleAllAlarms()
        }
    }

    fun markTaken(doseId: Long) {
        viewModelScope.launch { markDoseUseCase(doseId, DoseOutcome.TAKEN) }
    }

    fun markSkipped(doseId: Long) {
        viewModelScope.launch { markDoseUseCase(doseId, DoseOutcome.SKIPPED) }
    }

    fun snooze(doseId: Long) {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            snoozeDoseUseCase(doseId, settings.snoozeMinutes)
        }
    }

    private companion object {
        const val TICK_MILLIS = 60_000L
    }
}
