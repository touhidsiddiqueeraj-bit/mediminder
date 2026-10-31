package com.mediminder.app.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediminder.app.domain.model.ScoredDrugName
import com.mediminder.app.domain.service.DrugNameIndex
import com.mediminder.app.domain.usecase.AddMedicationUseCase
import com.mediminder.app.domain.usecase.DuplicateMedicationChecker
import com.mediminder.app.domain.usecase.ExtractMedicationsFromPhoto
import com.mediminder.app.domain.usecase.ExtractedMedication
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.ui.model.MedicationDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class ScanStep {
    CAMERA,
    PROCESSING,
    REVIEW,
    ERROR,
}

/**
 * One medication found on the photo: the editable draft plus the dictionary
 * evidence behind the pre-filled name.
 */
data class ScanItem(
    val draft: MedicationDraft,
    /** The name exactly as the OCR read it (for the "we read / did you mean" banner). */
    val rawNameText: String,
    /** Dictionary score of the pre-filled name; 0 when no match at all. */
    val nameScore: Float,
    /** Top dictionary candidates for one-tap correction. */
    val candidates: List<ScoredDrugName>,
)

data class ScanUiState(
    val step: ScanStep = ScanStep.CAMERA,
    val photoUri: String? = null,
    /** Every medication found on the photo, reviewed one at a time. */
    val items: List<ScanItem> = emptyList(),
    val currentIndex: Int = 0,
    /** False when neither the OCR nor the extractor found anything useful. */
    val parsedAnything: Boolean = false,
    val saving: Boolean = false,
    /** True once the user has been through every extracted item. */
    val saved: Boolean = false,
    val duplicateOf: String? = null,
    val duplicateAcknowledged: Boolean = false,
    val deletePhotoAfterSave: Boolean = true,
    val checkingName: Boolean = false,
    val nameSuggestions: List<ScoredDrugName> = emptyList(),
)

/**
 * Photo -> on-device OCR -> one candidate per medication -> the user confirms
 * every field of every medication. Nothing is saved until an explicit Save
 * tap, and each medication is saved separately.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val extractMedications: ExtractMedicationsFromPhoto,
    private val drugNameIndex: DrugNameIndex,
    private val addMedicationUseCase: AddMedicationUseCase,
    private val duplicateMedicationChecker: DuplicateMedicationChecker,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            _uiState.update { it.copy(deletePhotoAfterSave = settings.deletePhotosAfterScan) }
        }
    }

    fun reset() {
        _uiState.update { ScanUiState(deletePhotoAfterSave = _uiState.value.deletePhotoAfterSave) }
    }

    /** Called after the camera saved a photo of the label or prescription. */
    fun onPhotoCaptured(photoFile: File) {
        val photoUri = photoFile.toURI().toString()
        _uiState.update { it.copy(step = ScanStep.PROCESSING, photoUri = photoUri) }
        viewModelScope.launch {
            val result = extractMedications(photoUri)
            if (result.items.isEmpty()) {
                // Nothing useful extracted (or nothing readable at all):
                // fall back to the manual form, keeping the photo attached.
                _uiState.update {
                    it.copy(step = ScanStep.ERROR, parsedAnything = result.ocrSawText)
                }
            } else {
                val items = result.items.map { it.toScanItem(photoUri) }
                _uiState.update {
                    it.copy(step = ScanStep.REVIEW, items = items, currentIndex = 0, parsedAnything = true)
                }
            }
        }
    }

    /** Edits the medication currently under review. */
    fun updateDraft(transform: (MedicationDraft) -> MedicationDraft) {
        _uiState.update { state ->
            val index = state.currentIndex
            val newItems = state.items.mapIndexed { i, item ->
                if (i == index) item.copy(draft = transform(item.draft)) else item
            }
            state.copy(items = newItems)
        }
    }

    fun setDeletePhotoAfterSave(enabled: Boolean) {
        _uiState.update { it.copy(deletePhotoAfterSave = enabled) }
    }

    /** Re-checks the typed name against the offline dictionary. */
    fun checkName() {
        val item = _uiState.value.items.getOrNull(_uiState.value.currentIndex) ?: return
        val name = item.draft.name.trim()
        if (name.length < 3) return
        _uiState.update { it.copy(checkingName = true) }
        viewModelScope.launch {
            val suggestions = drugNameIndex.bestMatches(name, maxResults = 3)
            _uiState.update { it.copy(checkingName = false, nameSuggestions = suggestions) }
        }
    }

    /** Applies a dictionary candidate to the name under review. */
    fun applySuggestion(suggestion: ScoredDrugName) {
        updateDraft { draft ->
            draft.copy(
                name = suggestion.name,
                genericName = suggestion.genericName.orEmpty(),
            )
        }
        _uiState.update { it.copy(nameSuggestions = emptyList()) }
    }

    /** Saves the medication under review, then moves to the next one. */
    fun save() {
        val state = _uiState.value
        val item = state.items.getOrNull(state.currentIndex) ?: return
        if (state.saving || state.saved) return
        viewModelScope.launch {
            val duplicate = duplicateMedicationChecker.findDuplicate(item.draft.toMedication())
            if (duplicate != null && !state.duplicateAcknowledged) {
                _uiState.update { it.copy(duplicateOf = duplicate.name) }
                return@launch
            }
            _uiState.update { it.copy(saving = true) }
            val medication = if (state.deletePhotoAfterSave) {
                item.draft.toMedication().copy(photoUri = null)
            } else {
                item.draft.toMedication()
            }
            when (val result = addMedicationUseCase(medication)) {
                is AddMedicationUseCase.Result.Success -> {
                    if (state.deletePhotoAfterSave) {
                        item.draft.photoUri?.let { uri ->
                            runCatching { File(uri.removePrefix("file:")).delete() }
                        }
                    }
                    advanceAfterSave()
                }
                is AddMedicationUseCase.Result.Invalid -> {
                    _uiState.update { it.copy(saving = false) }
                }
            }
        }
    }

    /** Leaves the medication under review unsaved and moves on. */
    fun skipItem() {
        val state = _uiState.value
        if (state.saving || state.saved) return
        if (state.currentIndex < state.items.lastIndex) {
            _uiState.update {
                it.copy(
                    currentIndex = it.currentIndex + 1,
                    duplicateAcknowledged = false,
                    duplicateOf = null,
                    nameSuggestions = emptyList(),
                )
            }
        } else {
            _uiState.update { it.copy(saved = true, duplicateOf = null) }
        }
    }

    private fun advanceAfterSave() {
        _uiState.update { state ->
            if (state.currentIndex < state.items.lastIndex) {
                state.copy(
                    saving = false,
                    currentIndex = state.currentIndex + 1,
                    duplicateAcknowledged = false,
                    duplicateOf = null,
                    nameSuggestions = emptyList(),
                )
            } else {
                // All extracted medications handled — done with the photo.
                state.copy(saving = false, saved = true, duplicateOf = null)
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

    private fun ExtractedMedication.toScanItem(photoUri: String): ScanItem {
        var draft = MedicationDraft.fromParsed(label, photoUri)
        if (nameScore < REVIEW_NAME_SCORE) {
            // Weak match: leave the name empty and let the raw OCR text and
            // the dictionary candidates speak for themselves.
            draft = draft.copy(name = "")
        }
        return ScanItem(
            draft = draft,
            rawNameText = rawNameText,
            nameScore = nameScore,
            candidates = candidates,
        )
    }

    private companion object {
        const val REVIEW_NAME_SCORE = 0.60f
    }
}
