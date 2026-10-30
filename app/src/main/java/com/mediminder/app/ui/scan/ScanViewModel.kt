package com.mediminder.app.ui.scan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.model.ParsedLabel
import com.mediminder.app.domain.parser.MedicationLabelParser
import com.mediminder.app.domain.repository.DrugNameRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.service.LabelScanner
import com.mediminder.app.domain.usecase.AddMedicationUseCase
import com.mediminder.app.domain.usecase.DuplicateMedicationChecker
import com.mediminder.app.ui.model.MedicationDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class ScanStep {
    CAMERA,
    PROCESSING,
    REVIEW,
    ERROR,
}

data class ScanUiState(
    val step: ScanStep = ScanStep.CAMERA,
    val photoUri: String? = null,
    val draft: MedicationDraft? = null,
    val parsedAnything: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val duplicateOf: String? = null,
    val duplicateAcknowledged: Boolean = false,
    val deletePhotoAfterSave: Boolean = true,
    val checkingNameOnline: Boolean = false,
    val nameSuggestions: List<String> = emptyList(),
)

/**
 * Photo -> on-device OCR -> parse -> user confirms every field.
 * Nothing is saved until the user taps Save on the review screen.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val labelScanner: LabelScanner,
    private val parser: MedicationLabelParser,
    private val drugNameRepository: DrugNameRepository,
    private val addMedicationUseCase: AddMedicationUseCase,
    private val duplicateMedicationChecker: DuplicateMedicationChecker,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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

    /** Called after the camera saved a photo of the label. */
    fun onPhotoCaptured(photoFile: File) {
        val photoUri = photoFile.toURI().toString()
        _uiState.update { it.copy(step = ScanStep.PROCESSING, photoUri = photoUri) }
        viewModelScope.launch {
            val text = withContext(ioDispatcher) { labelScanner.recognizeText(photoUri) }
            if (text.isNullOrBlank()) {
                _uiState.update { it.copy(step = ScanStep.ERROR) }
                return@launch
            }
            val parsed: ParsedLabel = parser.parse(text)
            if (parsed.isEmpty) {
                // Nothing useful extracted: fall back to the manual form,
                // keeping the photo attached.
                _uiState.update { it.copy(step = ScanStep.ERROR, parsedAnything = false) }
            } else {
                _uiState.update {
                    it.copy(
                        step = ScanStep.REVIEW,
                        draft = MedicationDraft.fromParsed(parsed, photoUri),
                        parsedAnything = true,
                    )
                }
            }
        }
    }

    fun updateDraft(transform: (MedicationDraft) -> MedicationDraft) {
        _uiState.update { it.copy(draft = it.draft?.let(transform)) }
    }

    fun setDeletePhotoAfterSave(enabled: Boolean) {
        _uiState.update { it.copy(deletePhotoAfterSave = enabled) }
    }

    fun checkNameOnline() {
        val name = _uiState.value.draft?.name?.trim() ?: return
        if (name.length < 3) return
        _uiState.update { it.copy(checkingNameOnline = true) }
        viewModelScope.launch {
            val suggestions = drugNameRepository.suggestNames(name)
            _uiState.update { it.copy(checkingNameOnline = false, nameSuggestions = suggestions) }
        }
    }

    fun applySuggestion(name: String) {
        updateDraft { it.copy(name = name) }
        _uiState.update { it.copy(nameSuggestions = emptyList()) }
    }

    fun save() {
        val state = _uiState.value
        val draft = state.draft ?: return
        if (state.saving || state.saved) return
        viewModelScope.launch {
            val duplicate = duplicateMedicationChecker.findDuplicate(draft.toMedication())
            if (duplicate != null && !state.duplicateAcknowledged) {
                _uiState.update { it.copy(duplicateOf = duplicate.name) }
                return@launch
            }
            _uiState.update { it.copy(saving = true) }
            val medication = if (state.deletePhotoAfterSave) {
                draft.toMedication().copy(photoUri = null)
            } else {
                draft.toMedication()
            }
            when (val result = addMedicationUseCase(medication)) {
                is AddMedicationUseCase.Result.Success -> {
                    if (state.deletePhotoAfterSave) {
                        draft.photoUri?.let { uri ->
                            runCatching { File(uri.removePrefix("file:")).delete() }
                        }
                    }
                    _uiState.update { it.copy(saving = false, saved = true, duplicateOf = null) }
                }
                is AddMedicationUseCase.Result.Invalid -> {
                    _uiState.update { it.copy(saving = false) }
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
