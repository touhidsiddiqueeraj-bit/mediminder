package com.mediminder.app.ui.addmedication

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediminder.app.domain.model.ScoredDrugName
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.service.DrugNameIndex
import com.mediminder.app.domain.usecase.AddMedicationUseCase
import com.mediminder.app.domain.usecase.DuplicateMedicationChecker
import com.mediminder.app.ui.model.MedicationDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AddMedicationUiState(
    val draft: MedicationDraft = MedicationDraft(),
    val step: Int = 0,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val invalidReason: AddMedicationUseCase.InvalidReason? = null,
    /** Set when a possible duplicate ingredient was found; user must confirm. */
    val duplicateOf: String? = null,
    val duplicateAcknowledged: Boolean = false,
    val nameSuggestions: List<ScoredDrugName> = emptyList(),
    val checkingName: Boolean = false,
    val deletePhotoAfterSave: Boolean = true,
)

@HiltViewModel
class AddMedicationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val medicationRepository: MedicationRepository,
    private val addMedicationUseCase: AddMedicationUseCase,
    private val duplicateMedicationChecker: DuplicateMedicationChecker,
    private val drugNameIndex: DrugNameIndex,
) : ViewModel() {

    private val medicationId: Long = savedStateHandle.get<Long>("medicationId") ?: 0L
    private val photoUriArg: String? =
        savedStateHandle.get<String>("photoUri")?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(AddMedicationUiState())
    val uiState: StateFlow<AddMedicationUiState> = _uiState.asStateFlow()

    init {
        if (medicationId != 0L) {
            viewModelScope.launch {
                medicationRepository.getMedication(medicationId)?.let { medication ->
                    _uiState.update { it.copy(draft = MedicationDraft.from(medication)) }
                }
            }
        } else if (photoUriArg != null) {
            // Came from a failed scan: manual entry with the photo attached.
            _uiState.update { it.copy(draft = MedicationDraft(photoUri = photoUriArg)) }
        }
    }

    fun updateDraft(transform: (MedicationDraft) -> MedicationDraft) {
        _uiState.update {
            it.copy(draft = transform(it.draft), invalidReason = null, duplicateOf = null)
        }
    }

    fun nextStep() {
        val state = _uiState.value
        val canContinue = when (state.step) {
            0 -> state.draft.name.isNotBlank()
            else -> true
        }
        if (canContinue) {
            _uiState.update { it.copy(step = (it.step + 1).coerceAtMost(2), invalidReason = null) }
        } else {
            _uiState.update {
                it.copy(invalidReason = AddMedicationUseCase.InvalidReason.EMPTY_NAME)
            }
        }
    }

    fun previousStep() {
        _uiState.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }
    }

    fun setDeletePhotoAfterSave(enabled: Boolean) {
        _uiState.update { it.copy(deletePhotoAfterSave = enabled) }
    }

    /** Asks the offline dictionary for the closest matching medicine names. */
    fun checkName() {
        val name = _uiState.value.draft.name.trim()
        if (name.length < 3) return
        _uiState.update { it.copy(checkingName = true) }
        viewModelScope.launch {
            val suggestions = drugNameIndex.bestMatches(name, maxResults = 3)
            _uiState.update { it.copy(checkingName = false, nameSuggestions = suggestions) }
        }
    }

    fun applySuggestion(suggestion: ScoredDrugName) {
        updateDraft {
            it.copy(
                name = suggestion.name,
                genericName = suggestion.genericName.orEmpty(),
            )
        }
        _uiState.update { it.copy(nameSuggestions = emptyList()) }
    }

    fun save() {
        val state = _uiState.value
        if (state.saving || state.saved) return

        viewModelScope.launch {
            // Duplicate-ingredient safety check: warn, but let the user decide.
            val duplicate = duplicateMedicationChecker.findDuplicate(state.draft.toMedication())
            if (duplicate != null && !state.duplicateAcknowledged) {
                _uiState.update { it.copy(duplicateOf = duplicate.name) }
                return@launch
            }

            _uiState.update { it.copy(saving = true) }
            val draft = state.draft
            val keepPhoto = !(photoUriArg != null && state.deletePhotoAfterSave && !draft.isEditing)
            val medication = draft.toMedication().let {
                if (keepPhoto) it else it.copy(photoUri = null)
            }
            when (val result = addMedicationUseCase(medication)) {
                is AddMedicationUseCase.Result.Success -> {
                    if (!keepPhoto) draft.photoUri?.let { uri ->
                        runCatching { File(uri.removePrefix("file:")).delete() }
                    }
                    _uiState.update { it.copy(saving = false, saved = true, duplicateOf = null) }
                }
                is AddMedicationUseCase.Result.Invalid -> {
                    _uiState.update { it.copy(saving = false, invalidReason = result.reason) }
                }
            }
        }
    }

    fun confirmDuplicate() {
        _uiState.update { it.copy(duplicateOf = null, duplicateAcknowledged = true) }
        save()
    }

    fun dismissDuplicate() {
        _uiState.update { it.copy(duplicateOf = null) }
    }
}
