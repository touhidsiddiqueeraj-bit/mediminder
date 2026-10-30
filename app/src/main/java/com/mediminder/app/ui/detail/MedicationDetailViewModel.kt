package com.mediminder.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediminder.app.domain.engine.AdherenceCalculator
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.MedicationAdherence
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.domain.usecase.ComputeAdherenceUseCase
import com.mediminder.app.domain.usecase.DeactivateMedicationUseCase
import com.mediminder.app.domain.usecase.DeleteMedicationUseCase
import com.mediminder.app.domain.usecase.UpdatePillCountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class MedicationDetailUiState(
    val medication: Medication? = null,
    val adherence: MedicationAdherence? = null,
    val confirmDelete: Boolean = false,
    val finished: Boolean = false,
)

@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val medicationRepository: MedicationRepository,
    private val computeAdherenceUseCase: ComputeAdherenceUseCase,
    private val calculator: AdherenceCalculator,
    private val deactivateMedicationUseCase: DeactivateMedicationUseCase,
    private val deleteMedicationUseCase: DeleteMedicationUseCase,
    private val updatePillCountUseCase: UpdatePillCountUseCase,
    private val reminderScheduler: ReminderScheduler,
    clockProvider: ClockProvider,
) : ViewModel() {

    private val medicationId: Long = savedStateHandle.get<Long>("medicationId") ?: 0L
    private val clock = clockProvider.clock

    private val _uiState = MutableStateFlow(MedicationDetailUiState())
    val uiState: StateFlow<MedicationDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val medication = medicationRepository.getMedication(medicationId)
            val logs = computeAdherenceUseCase.logsForPeriod(30)
            val adherence = calculator.perMedication(logs, listOfNotNull(medication)).firstOrNull()
            _uiState.update { it.copy(medication = medication, adherence = adherence) }
        }
    }

    fun setPills(pills: Int?) {
        viewModelScope.launch {
            updatePillCountUseCase(medicationId, pills)
            refresh()
        }
    }

    fun activate(active: Boolean) {
        viewModelScope.launch {
            if (active) {
                medicationRepository.setActive(medicationId, true)
                reminderScheduler.rescheduleAllAlarms()
            } else {
                deactivateMedicationUseCase(medicationId)
            }
            refresh()
        }
    }

    fun requestDelete() {
        _uiState.update { it.copy(confirmDelete = true) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(confirmDelete = false) }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            deleteMedicationUseCase(medicationId)
            _uiState.update { it.copy(confirmDelete = false, finished = true) }
        }
    }
}
