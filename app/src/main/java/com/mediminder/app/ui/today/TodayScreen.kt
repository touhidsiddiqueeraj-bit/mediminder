package com.mediminder.app.ui.today

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediminder.app.R
import com.mediminder.app.domain.model.DoseDisplayStatus
import com.mediminder.app.domain.model.TimeOfDay
import com.mediminder.app.domain.model.UpcomingDose
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.BigOutlinedButton
import com.mediminder.app.ui.components.DoseStatusChip
import com.mediminder.app.ui.components.EmptyStateCard
import com.mediminder.app.ui.components.InfoBanner
import com.mediminder.app.ui.components.timeFormatter
import com.mediminder.app.tts.ReminderSpeaker
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun TodayScreen(
    onAddManually: () -> Unit,
    onScan: () -> Unit,
    onOpenMedication: (Long) -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("EEEE, d MMMM").withLocale(Locale.getDefault())
    }
    val timeFormatter = remember { timeFormatter() }
    val speaker = remember(context) { ReminderSpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    java.time.LocalDate.now().format(dateFormatter),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.today_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                state.weekly?.percent?.let { percent ->
                    Text(
                        stringResource(R.string.this_week_adherence, percent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        state.lowStock.forEach { info ->
            item(key = "low_stock_${info.medicationId}") {
                InfoBanner(
                    text = stringResource(
                        R.string.low_stock_banner,
                        info.medicationName,
                        info.daysRemaining,
                    ),
                )
            }
        }

        item(key = "add_buttons") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(
                    text = stringResource(R.string.scan_label_button),
                    onClick = onScan,
                    leadingIcon = Icons.Filled.PhotoCamera,
                )
                BigOutlinedButton(
                    text = stringResource(R.string.type_manually_button),
                    onClick = onAddManually,
                    leadingIcon = Icons.Filled.Edit,
                )
            }
        }

        val grouped = state.doses.groupBy { TimeOfDay.ofHour(it.dose.scheduledTime.hour) }
        var hasAnyDose = false
        TimeOfDay.entries.forEach { bucket ->
            val bucketDoses = grouped[bucket].orEmpty()
            if (bucketDoses.isNotEmpty()) {
                hasAnyDose = true
                item(key = "header_${bucket.name}") {
                    SectionHeader(
                        title = sectionTitle(bucket),
                        count = bucketDoses.size,
                        icon = sectionIcon(bucket),
                    )
                }
                items(bucketDoses, key = { it.dose.id }) { dose ->
                    DoseCard(
                        dose = dose,
                        timeFormatter = timeFormatter,
                        onOpenMedication = onOpenMedication,
                        onTaken = { viewModel.markTaken(dose.dose.id) },
                        onSnooze = { viewModel.snooze(dose.dose.id) },
                        onSkip = { viewModel.markSkipped(dose.dose.id) },
                    )
                }
            }
        }

        if (!hasAnyDose) {
            item(key = "empty") {
                EmptyStateCard(
                    title = stringResource(R.string.no_medicines_today),
                    message = stringResource(R.string.no_medicines_today_hint),
                )
            }
        }

        item(key = "read_aloud") {
            if (state.doses.isNotEmpty()) {
                BigOutlinedButton(
                    text = stringResource(R.string.read_aloud_button),
                    leadingIcon = Icons.Filled.VolumeUp,
                    onClick = {
                        val text = buildString {
                            append(context.getString(R.string.tts_today_intro))
                            state.doses
                                .filter { it.displayStatus == DoseDisplayStatus.UPCOMING }
                                .forEach { dose ->
                                    append(" ")
                                    append(
                                        context.getString(
                                            R.string.tts_dose_line,
                                            dose.medication.name,
                                            dose.dose.scheduledTime.format(timeFormatter),
                                        ),
                                    )
                                }
                        }
                        speaker.init()
                        speaker.speak(text)
                    },
                )
            }
        }
        item(key = "disclaimer") {
            Text(
                stringResource(R.string.disclaimer_short),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.section_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun sectionTitle(bucket: TimeOfDay): String = when (bucket) {
    TimeOfDay.MORNING -> stringResource(R.string.time_morning)
    TimeOfDay.AFTERNOON -> stringResource(R.string.time_afternoon)
    TimeOfDay.EVENING -> stringResource(R.string.time_evening)
    TimeOfDay.NIGHT -> stringResource(R.string.time_night)
}

@Composable
private fun sectionIcon(bucket: TimeOfDay): ImageVector = when (bucket) {
    TimeOfDay.MORNING -> Icons.Filled.WbSunny
    TimeOfDay.AFTERNOON -> Icons.Filled.LightMode
    TimeOfDay.EVENING -> Icons.Filled.NightsStay
    TimeOfDay.NIGHT -> Icons.Filled.Bedtime
}

@Composable
private fun DoseCard(
    dose: UpcomingDose,
    timeFormatter: DateTimeFormatter,
    onOpenMedication: (Long) -> Unit,
    onTaken: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
) {
    val medication = dose.medication
    val openLabel = stringResource(R.string.open_details, medication.name)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel) { onOpenMedication(medication.id) }
            .semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(
                        dose.dose.scheduledTime.format(timeFormatter),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    DoseStatusChip(status = dose.displayStatus)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        medication.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    medication.dosageText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    medication.instructions?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dose.displayStatus == DoseDisplayStatus.TAKEN && dose.dose.actualTime != null) {
                        Text(
                            stringResource(
                                R.string.taken_at,
                                dose.dose.actualTime!!.format(timeFormatter),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (dose.displayStatus) {
                DoseDisplayStatus.TAKEN, DoseDisplayStatus.SKIPPED -> Unit
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onTaken,
                            modifier = Modifier
                                .weight(1.6f)
                                .heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_taken))
                        }
                        OutlinedButton(
                            onClick = onSnooze,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp),
                        ) {
                            Text(stringResource(R.string.action_snooze_short))
                        }
                        TextButton(
                            onClick = onSkip,
                            modifier = Modifier
                                .weight(0.8f)
                                .heightIn(min = 56.dp),
                        ) {
                            Text(stringResource(R.string.action_skip))
                        }
                    }
                }
            }
        }
    }
}
