package com.mediminder.app.ui.addmedication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediminder.app.R
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.parser.LabelDefaults
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.SelectionCard
import com.mediminder.app.ui.components.Stepper
import com.mediminder.app.ui.components.TimeField
import com.mediminder.app.ui.model.MedicationDraft
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import com.mediminder.app.util.appLocale

/**
 * Frequency + reminder times editor, shared by the manual wizard (step 2)
 * and the scan confirmation screen. Big cards, big buttons, no dropdowns.
 */
@Composable
fun ScheduleEditor(
    draft: MedicationDraft,
    onDraftChange: (MedicationDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SelectionCard(
            selected = draft.frequency == FrequencyType.DAILY && draft.intervalDays == 1,
            onClick = { onDraftChange(draft.copy(frequency = FrequencyType.DAILY, intervalDays = 1)) },
            title = stringResource(R.string.freq_daily_title),
            subtitle = stringResource(R.string.freq_daily_subtitle),
        )
        SelectionCard(
            selected = draft.frequency == FrequencyType.SPECIFIC_DAYS,
            onClick = { onDraftChange(draft.copy(frequency = FrequencyType.SPECIFIC_DAYS)) },
            title = stringResource(R.string.freq_specific_days_title),
            subtitle = stringResource(R.string.freq_specific_days_subtitle),
        )
        SelectionCard(
            selected = draft.frequency == FrequencyType.EVERY_X_HOURS,
            onClick = { onDraftChange(draft.copy(frequency = FrequencyType.EVERY_X_HOURS)) },
            title = stringResource(R.string.freq_interval_title),
            subtitle = stringResource(R.string.freq_interval_subtitle),
        )
        SelectionCard(
            selected = draft.frequency == FrequencyType.AS_NEEDED,
            onClick = { onDraftChange(draft.copy(frequency = FrequencyType.AS_NEEDED)) },
            title = stringResource(R.string.freq_as_needed_title),
            subtitle = stringResource(R.string.freq_as_needed_subtitle),
        )

        when (draft.frequency) {
            FrequencyType.AS_NEEDED -> Text(
                stringResource(R.string.freq_as_needed_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FrequencyType.SPECIFIC_DAYS -> {
                Text(stringResource(R.string.freq_pick_days), style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        val short = day.getDisplayName(TextStyle.SHORT, appLocale())
                        FilterChip(
                            selected = day in draft.selectedDays,
                            onClick = {
                                val days = draft.selectedDays.toMutableSet()
                                if (day in days) days.remove(day) else days.add(day)
                                onDraftChange(draft.copy(selectedDays = days))
                            },
                            label = { Text(short) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                if (draft.selectedDays.isEmpty()) {
                    Text(
                        stringResource(R.string.freq_pick_days_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ReminderTimesEditor(draft, onDraftChange)
            }
            FrequencyType.EVERY_X_HOURS -> {
                Stepper(
                    value = draft.intervalHours,
                    onValueChange = { hours ->
                        onDraftChange(
                            draft.copy(
                                intervalHours = hours,
                                times = listOf(LabelDefaults.anchorForInterval(hours)),
                            ),
                        )
                    },
                    range = 2..24,
                    label = stringResource(R.string.freq_every_n_hours, draft.intervalHours),
                )
                Text(
                    stringResource(R.string.freq_first_dose_at, formatTime(draft.times.firstOrNull())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TimeField(
                    time = draft.times.firstOrNull() ?: LabelDefaults.MORNING,
                    onTimeChange = { time -> onDraftChange(draft.copy(times = listOf(time))) },
                )
            }
            FrequencyType.DAILY -> {
                Stepper(
                    value = draft.intervalDays,
                    onValueChange = { days ->
                        onDraftChange(draft.copy(intervalDays = days))
                    },
                    range = 1..7,
                    label = if (draft.intervalDays == 1) {
                        stringResource(R.string.freq_every_day)
                    } else {
                        stringResource(R.string.freq_every_n_days, draft.intervalDays)
                    },
                )
                ReminderTimesEditor(draft, onDraftChange)
            }
        }
    }
}

@Composable
private fun ReminderTimesEditor(
    draft: MedicationDraft,
    onDraftChange: (MedicationDraft) -> Unit,
) {
    Text(stringResource(R.string.freq_reminder_times), style = MaterialTheme.typography.titleSmall)
    draft.times.forEachIndexed { index, time ->
        TimeField(
            time = time,
            onTimeChange = { newTime ->
                val times = draft.times.toMutableList()
                times[index] = newTime
                onDraftChange(draft.copy(times = times))
            },
            onRemove = if (draft.times.size > 1) {
                {
                    val times = draft.times.toMutableList()
                    times.removeAt(index)
                    onDraftChange(draft.copy(times = times))
                }
            } else {
                null
            },
        )
    }
    BigButton(
        text = stringResource(R.string.add_time),
        onClick = { onDraftChange(draft.copy(times = draft.times + LocalTime.of(12, 0))) },
    )
}

@Composable
private fun formatTime(time: LocalTime?): String =
    time?.let {
        java.time.format.DateTimeFormatter.ofPattern("h:mm a").withLocale(appLocale()).format(it)
    } ?: ""
