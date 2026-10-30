@file:OptIn(ExperimentalMaterial3Api::class)

package com.mediminder.app.ui.addmedication

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediminder.app.R
import com.mediminder.app.domain.model.FieldConfidence
import com.mediminder.app.domain.model.LabelField
import com.mediminder.app.domain.model.MedicationForm
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.BigOutlinedButton
import com.mediminder.app.ui.components.ConfirmDialog
import com.mediminder.app.ui.components.InfoBanner
import com.mediminder.app.ui.components.LabeledField
import com.mediminder.app.ui.components.SectionCard
import com.mediminder.app.ui.components.Stepper
import com.mediminder.app.ui.components.WarningBanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Add or edit a medicine in 3 linear steps: Medicine -> Schedule -> Finish.
 * No nested menus, no gestures; every control is a large card or button.
 */
@Composable
fun AddMedicationScreen(
    medicationId: Long,
    photoUri: String?,
    onDone: () -> Unit,
    viewModel: AddMedicationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = state.step > 0) { viewModel.previousStep() }

    if (state.saved) {
        onDone()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = if (state.step > 0) viewModel::previousStep else onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Text(
                        stringResource(
                            if (state.draft.isEditing) R.string.edit_title else R.string.add_title,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text(
                    stringResource(R.string.step_of, state.step + 1, 3),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (state.step) {
            0 -> item(key = "step1") {
                StepMedicine(state = state, viewModel = viewModel)
            }
            1 -> item(key = "step2") {
                StepSchedule(state = state, viewModel = viewModel)
            }
            else -> item(key = "step3") {
                StepFinish(state = state, viewModel = viewModel)
            }
        }

        item(key = "nav") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.step < 2) {
                    BigButton(
                        text = stringResource(R.string.next),
                        onClick = viewModel::nextStep,
                        enabled = state.step != 0 || state.draft.name.isNotBlank(),
                    )
                } else {
                    BigButton(
                        text = if (state.saving) {
                            stringResource(R.string.saving)
                        } else {
                            stringResource(R.string.save_medicine)
                        },
                        onClick = viewModel::save,
                        enabled = !state.saving,
                    )
                }
                if (state.step > 0) {
                    BigOutlinedButton(text = stringResource(R.string.back), onClick = viewModel::previousStep)
                }
            }
        }
    }

    if (state.duplicateOf != null) {
        ConfirmDialog(
            title = stringResource(R.string.duplicate_warning_title),
            text = stringResource(R.string.duplicate_warning_text, state.duplicateOf!!),
            confirmLabel = stringResource(R.string.save_anyway),
            onConfirm = viewModel::confirmDuplicate,
            onDismiss = viewModel::dismissDuplicate,
        )
    }

    state.invalidReason?.let { reason ->
        val message = when (reason) {
            com.mediminder.app.domain.usecase.AddMedicationUseCase.InvalidReason.EMPTY_NAME ->
                stringResource(R.string.invalid_name)
            com.mediminder.app.domain.usecase.AddMedicationUseCase.InvalidReason.NO_SCHEDULE ->
                stringResource(R.string.invalid_no_schedule)
            com.mediminder.app.domain.usecase.AddMedicationUseCase.InvalidReason.NO_TIMES ->
                stringResource(R.string.invalid_no_times)
        }
        InfoBanner(text = message)
    }
}

// ---------------------------------------------------------------------------
// Step 1 — the medicine itself
// ---------------------------------------------------------------------------

@Composable
private fun StepMedicine(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    val draft = state.draft
    val confidence = draft.confidence
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (draft.photoUri != null) {
            SectionCard(title = stringResource(R.string.scanned_photo)) {
                Text(
                    stringResource(R.string.scanned_photo_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LabeledField(
            label = stringResource(R.string.field_medication_name),
            value = draft.name,
            onValueChange = { value -> viewModel.updateDraft { it.copy(name = value) } },
            confidence = confidence?.get(LabelField.NAME),
        )

        if (confidence != null && confidence[LabelField.NAME] == FieldConfidence.LOW) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigOutlinedButton(
                    text = if (state.checkingNameOnline) {
                        stringResource(R.string.checking_online)
                    } else {
                        stringResource(R.string.check_name_online)
                    },
                    onClick = viewModel::checkNameOnline,
                    enabled = !state.checkingNameOnline,
                    modifier = Modifier.weight(1f),
                )
            }
            state.nameSuggestions.forEach { suggestion ->
                TextButton(onClick = { viewModel.applySuggestion(suggestion) }) {
                    Text(suggestion, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledField(
                label = stringResource(R.string.field_dosage_amount),
                value = draft.dosageValue,
                onValueChange = { value -> viewModel.updateDraft { it.copy(dosageValue = value) } },
                confidence = confidence?.get(LabelField.DOSAGE),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            LabeledField(
                label = stringResource(R.string.field_dosage_unit),
                value = draft.dosageUnit,
                onValueChange = { value -> viewModel.updateDraft { it.copy(dosageUnit = value) } },
                confidence = confidence?.get(LabelField.DOSAGE),
                modifier = Modifier.weight(1f),
            )
        }

        Column {
            Text(stringResource(R.string.field_form), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                formOptions().forEach { (form, label) ->
                    FilterChip(
                        selected = draft.form == form,
                        onClick = { viewModel.updateDraft { it.copy(form = form) } },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }

        LabeledField(
            label = stringResource(R.string.field_instructions),
            value = draft.instructions,
            onValueChange = { value -> viewModel.updateDraft { it.copy(instructions = value) } },
            confidence = confidence?.get(LabelField.INSTRUCTIONS),
            singleLine = false,
        )
    }
}

@Composable
private fun formOptions(): List<Pair<MedicationForm, String>> = listOf(
    MedicationForm.TABLET to stringResource(R.string.form_tablet),
    MedicationForm.CAPSULE to stringResource(R.string.form_capsule),
    MedicationForm.LIQUID to stringResource(R.string.form_liquid),
    MedicationForm.DROPS to stringResource(R.string.form_drops),
    MedicationForm.INJECTION to stringResource(R.string.form_injection),
)

// ---------------------------------------------------------------------------
// Step 2 — schedule
// ---------------------------------------------------------------------------

@Composable
private fun StepSchedule(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    ScheduleEditor(
        draft = state.draft,
        onDraftChange = { draft -> viewModel.updateDraft { draft } },
    )
}

// ---------------------------------------------------------------------------
// Step 3 — review, stock and save
// ---------------------------------------------------------------------------

@Composable
private fun StepFinish(state: AddMedicationUiState, viewModel: AddMedicationViewModel) {
    val draft = state.draft
    val dateDisplay = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(R.string.review_title)) {
            SummaryRow(stringResource(R.string.field_medication_name), draft.name.ifBlank { "—" })
            SummaryRow(
                stringResource(R.string.field_dosage_amount),
                listOf(draft.dosageValue, draft.dosageUnit).filter { it.isNotBlank() }
                    .joinToString(" ").ifBlank { "—" },
            )
            SummaryRow(
                stringResource(R.string.field_form),
                stringResource(
                    when (draft.form) {
                        MedicationForm.TABLET -> R.string.form_tablet
                        MedicationForm.CAPSULE -> R.string.form_capsule
                        MedicationForm.LIQUID -> R.string.form_liquid
                        MedicationForm.DROPS -> R.string.form_drops
                        MedicationForm.INJECTION -> R.string.form_injection
                    },
                ),
            )
            SummaryRow(
                stringResource(R.string.field_instructions),
                draft.instructions.ifBlank { "—" },
            )
        }

        SectionCard(title = stringResource(R.string.dates_title)) {
            DateRow(
                label = stringResource(R.string.start_date),
                value = draft.startDate.format(dateDisplay),
                onClick = { showStartDatePicker = true },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.no_end_date),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                val endDateSwitchLabel = stringResource(R.string.no_end_date)
                Switch(
                    checked = draft.hasEndDate,
                    onCheckedChange = { checked ->
                        viewModel.updateDraft {
                            it.copy(
                                hasEndDate = checked,
                                endDate = if (checked) {
                                    it.endDate ?: it.startDate.plusYears(1)
                                } else {
                                    null
                                },
                            )
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = endDateSwitchLabel
                    },
                )
            }
            if (draft.hasEndDate) {
                DateRow(
                    label = stringResource(R.string.end_date),
                    value = (draft.endDate ?: draft.startDate).format(dateDisplay),
                    onClick = { showEndDatePicker = true },
                )
            }
        }

        SectionCard(title = stringResource(R.string.stock_title)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.track_stock_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.track_stock_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.trackStock,
                    onCheckedChange = { checked ->
                        viewModel.updateDraft { it.copy(trackStock = checked) }
                    },
                )
            }
            if (draft.trackStock) {
                Stepper(
                    value = draft.pillsRemaining,
                    onValueChange = { pills -> viewModel.updateDraft { it.copy(pillsRemaining = pills) } },
                    range = 0..999,
                    label = stringResource(R.string.pills_remaining),
                )
            }
        }

        if (draft.photoUri != null && !draft.isEditing) {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.delete_photo_after_save),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.deletePhotoAfterSave,
                        onCheckedChange = viewModel::setDeletePhotoAfterSave,
                    )
                }
            }
        }

        Text(
            stringResource(R.string.disclaimer_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showStartDatePicker) {
        DatePickerModal(
            initial = draft.startDate,
            onDismiss = { showStartDatePicker = false },
            onConfirm = { date ->
                viewModel.updateDraft { it.copy(startDate = date) }
                showStartDatePicker = false
            },
        )
    }
    if (showEndDatePicker) {
        DatePickerModal(
            initial = draft.endDate ?: draft.startDate,
            onDismiss = { showEndDatePicker = false },
            onConfirm = { date ->
                viewModel.updateDraft { it.copy(hasEndDate = true, endDate = date) }
                showEndDatePicker = false
            },
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DateRow(label: String, value: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerModal(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        onConfirm(
                            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(),
                        )
                    } else {
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}
