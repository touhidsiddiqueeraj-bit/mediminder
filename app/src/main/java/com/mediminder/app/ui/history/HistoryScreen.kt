package com.mediminder.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediminder.app.R
import com.mediminder.app.domain.model.DayAdherence
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.DoseStatusChip
import com.mediminder.app.ui.components.EmptyStateCard
import com.mediminder.app.ui.components.SectionCard
import com.mediminder.app.ui.components.timeFormatter
import com.mediminder.app.ui.theme.LocalStatusColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import com.mediminder.app.util.appLocale

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormatter = remember { timeFormatter() }
    val today = remember { LocalDate.now() }

    LaunchedEffect(state.reportFile) {
        state.reportFile?.let { report ->
            shareReport(context, report.file)
            viewModel.reportConsumed()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium)
        }

        state.weekly?.let { weekly ->
            item(key = "weekly") {
                SectionCard(title = stringResource(R.string.weekly_summary_title)) {
                    val percent = weekly.percent
                    Text(
                        text = percent?.let {
                            stringResource(R.string.weekly_percent, it)
                        } ?: stringResource(R.string.weekly_no_data),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.weekly_taken_of, weekly.takenTotal, weekly.takenTotal + weekly.missedTotal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    // One bar per day; tall enough to read, short enough to fit.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        weekly.days.forEach { day ->
                            val fraction = day.adherencePercent?.let { it / 100f } ?: 0f
                            val barDescription = if (day.adherencePercent == null) {
                                stringResource(
                                    R.string.calendar_day_no_doses,
                                    day.date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, appLocale()),
                                )
                            } else {
                                stringResource(
                                    R.string.calendar_day_a11y,
                                    day.date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, appLocale()),
                                    day.taken,
                                    day.missed,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((90 * fraction).dp)
                                        .semantics {
                                            contentDescription = barDescription
                                        },
                                ) {
                                    Surface(
                                        color = if (day.adherencePercent == null) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            LocalStatusColors.current.taken
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {}
                                }
                                Text(
                                    day.date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, appLocale()),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "calendar") {
            SectionCard(title = state.month.month.getDisplayName(JavaTextStyle.FULL, appLocale())) {
                MonthCalendar(
                    month = state.month,
                    daySummaries = state.daySummaries,
                    selected = state.selectedDay,
                    today = today,
                    onSelect = viewModel::selectDay,
                )
            }
        }

        state.selectedDay?.let { day ->
            item(key = "day_header") {
                Text(
                    day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM").withLocale(appLocale())),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.selectedDayRows.isEmpty()) {
                item(key = "day_empty") {
                    EmptyStateCard(
                        title = stringResource(R.string.day_empty_title),
                        message = stringResource(R.string.day_empty_message),
                    )
                }
            } else {
                items(state.selectedDayRows.size, key = { state.selectedDayRows[it].id }) { index ->
                    val log = state.selectedDayRows[index]
                    DayLogRow(
                        log = log,
                        medicationName = state.medicationNames[log.medicationId] ?: "",
                        timeFormatter = timeFormatter,
                    )
                }
            }
        }

        item(key = "export") {
            BigButton(
                text = if (state.exporting) {
                    stringResource(R.string.saving)
                } else {
                    stringResource(R.string.export_report)
                },
                onClick = viewModel::exportReport,
                enabled = !state.exporting,
                leadingIcon = Icons.Filled.PictureAsPdf,
            )
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
private fun DayLogRow(
    log: com.mediminder.app.domain.model.DoseLog,
    medicationName: String,
    timeFormatter: DateTimeFormatter,
) {
    val displayStatus = when (log.status) {
        DoseStatus.TAKEN -> com.mediminder.app.domain.model.DoseDisplayStatus.TAKEN
        DoseStatus.MISSED -> com.mediminder.app.domain.model.DoseDisplayStatus.MISSED
        DoseStatus.SKIPPED -> com.mediminder.app.domain.model.DoseDisplayStatus.SKIPPED
        DoseStatus.SNOOZED -> com.mediminder.app.domain.model.DoseDisplayStatus.SNOOZED
        DoseStatus.SCHEDULED -> if (log.scheduledTime.isBefore(java.time.LocalDateTime.now())) {
            com.mediminder.app.domain.model.DoseDisplayStatus.MISSED
        } else {
            com.mediminder.app.domain.model.DoseDisplayStatus.UPCOMING
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(log.scheduledTime.format(timeFormatter), style = MaterialTheme.typography.titleSmall)
            }
            Column(Modifier.weight(1f)) {
                Text(medicationName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            DoseStatusChip(status = displayStatus)
        }
    }
}

/** Simple month grid: day cells colored by adherence; tap to see that day. */
@Composable
private fun MonthCalendar(
    month: YearMonth,
    daySummaries: Map<LocalDate, DayAdherence>,
    selected: LocalDate?,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = LocalStatusColors.current
    val weekDayLabels = remember {
        java.time.DayOfWeek.entries.map { it.getDisplayName(JavaTextStyle.NARROW, appLocale()) }
    }
    val firstDayOffset = month.atDay(1).dayOfWeek.value - 1 // Monday-first grid
    val daysInMonth = month.lengthOfMonth()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekDayLabels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        var dayNumber = 1 - firstDayOffset
        while (dayNumber <= daysInMonth) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val dayIsValid = dayNumber in 1..daysInMonth
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayIsValid) {
                            val date = month.atDay(dayNumber)
                            val summary = daySummaries[date]
                            val inFuture = date.isAfter(today)
                            val background = when {
                                summary == null || inFuture -> MaterialTheme.colorScheme.surfaceVariant
                                summary.missed > 0 -> colors.missedContainer
                                summary.taken > 0 -> colors.takenContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            val isSelected = selected == date
                            val dayLabel = date.dayOfMonth.toString()
                            Surface(
                                onClick = { if (!inFuture) onSelect(date) },
                                shape = CircleShape,
                                color = background,
                                border = if (isSelected) {
                                    BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .semantics { contentDescription = dayLabel },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        dayLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    dayNumber++
                }
            }
        }
    }
}
