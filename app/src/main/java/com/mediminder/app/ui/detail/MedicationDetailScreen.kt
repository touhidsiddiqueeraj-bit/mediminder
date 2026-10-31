package com.mediminder.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediminder.app.R
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.ConfirmDialog
import com.mediminder.app.ui.components.SectionCard
import com.mediminder.app.ui.components.Stepper
import com.mediminder.app.ui.components.timeFormatter
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.mediminder.app.util.appLocale

@Composable
fun MedicationDetailScreen(
    medicationId: Long,
    onEdit: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: MedicationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    state.medication?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }

        state.medication?.let { med ->
            item(key = "info") {
                SectionCard(title = stringResource(R.string.detail_info_title)) {
                    med.genericName?.let {
                        DetailRow(stringResource(R.string.detail_generic_name), it)
                    }
                    med.dosageText?.let { DetailRow(stringResource(R.string.detail_dosage), it) }
                    DetailRow(
                        stringResource(R.string.detail_form),
                        stringResource(
                            when (med.form) {
                                com.mediminder.app.domain.model.MedicationForm.TABLET -> R.string.form_tablet
                                com.mediminder.app.domain.model.MedicationForm.CAPSULE -> R.string.form_capsule
                                com.mediminder.app.domain.model.MedicationForm.LIQUID -> R.string.form_liquid
                                com.mediminder.app.domain.model.MedicationForm.DROPS -> R.string.form_drops
                                com.mediminder.app.domain.model.MedicationForm.INJECTION -> R.string.form_injection
                            },
                        ),
                    )
                    med.instructions?.let { DetailRow(stringResource(R.string.detail_instructions), it) }
                    DetailRow(
                        stringResource(R.string.detail_active),
                        stringResource(if (med.isActive) R.string.yes else R.string.no),
                    )
                }
            }

            item(key = "schedule") {
                SectionCard(title = stringResource(R.string.detail_schedule_title)) {
                    med.schedules.forEach { schedule ->
                        val description = when (schedule.type) {
                            FrequencyType.DAILY ->
                                if ((schedule.intervalDays ?: 1) > 1) {
                                    stringResource(
                                        R.string.schedule_every_n_days,
                                        schedule.intervalDays ?: 1,
                                        formatTimes(schedule),
                                    )
                                } else {
                                    stringResource(R.string.schedule_daily, formatTimes(schedule))
                                }
                            FrequencyType.SPECIFIC_DAYS ->
                                stringResource(R.string.schedule_specific_days, formatTimes(schedule))
                            FrequencyType.EVERY_X_HOURS ->
                                stringResource(
                                    R.string.schedule_interval,
                                    schedule.intervalHours ?: 8,
                                    formatTimes(schedule),
                                )
                            FrequencyType.AS_NEEDED -> stringResource(R.string.freq_as_needed_title)
                        }
                        Text(description, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            item(key = "adherence") {
                SectionCard(title = stringResource(R.string.detail_adherence_title)) {
                    state.adherence?.let { adherence ->
                        val percent = adherence.adherencePercent
                        Text(
                            percent?.let { stringResource(R.string.detail_adherence_value, it) }
                                ?: stringResource(R.string.weekly_no_data),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(
                                R.string.detail_adherence_counts,
                                adherence.taken,
                                adherence.missed,
                                adherence.skipped,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } ?: Text(stringResource(R.string.weekly_no_data))
                }
            }

            item(key = "stock") {
                SectionCard(title = stringResource(R.string.stock_title)) {
                    Stepper(
                        value = med.pillsRemaining ?: 0,
                        onValueChange = { pills -> viewModel.setPills(pills) },
                        range = 0..999,
                        label = stringResource(R.string.pills_remaining),
                    )
                    if (med.pillsRemaining == null) {
                        Text(
                            stringResource(R.string.stock_not_tracked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(
                        text = stringResource(R.string.detail_edit),
                        onClick = { onEdit(med.id) },
                        leadingIcon = Icons.Filled.Edit,
                    )
                    if (med.isActive) {
                        BigButton(
                            text = stringResource(R.string.detail_stop),
                            onClick = { viewModel.activate(false) },
                            leadingIcon = Icons.Filled.Block,
                        )
                    } else {
                        BigButton(
                            text = stringResource(R.string.detail_restart),
                            onClick = { viewModel.activate(true) },
                            leadingIcon = Icons.Filled.PlayCircle,
                        )
                    }
                    BigButton(
                        text = stringResource(R.string.detail_delete),
                        onClick = viewModel::requestDelete,
                        leadingIcon = Icons.Filled.DeleteForever,
                    )
                }
            }
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.detail_delete_confirm_title),
            text = stringResource(R.string.detail_delete_confirm_text),
            confirmLabel = stringResource(R.string.delete_all_confirm_button),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun formatTimes(schedule: com.mediminder.app.domain.model.Schedule): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", appLocale())
    return schedule.times.joinToString(", ") { it.format(formatter) }
}
