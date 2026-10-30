package com.mediminder.app.ui.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediminder.app.domain.engine.AdherenceCalculator
import com.mediminder.app.domain.model.DayAdherence
import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.model.WeeklyAdherence
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReportFile
import com.mediminder.app.domain.usecase.ComputeAdherenceUseCase
import com.mediminder.app.domain.usecase.ExportAdherenceReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val daySummaries: Map<LocalDate, DayAdherence> = emptyMap(),
    val selectedDay: LocalDate? = null,
    val selectedDayRows: List<DoseLog> = emptyList(),
    val medicationNames: Map<Long, String> = emptyMap(),
    val weekly: WeeklyAdherence? = null,
    val exporting: Boolean = false,
    val reportFile: ReportFile? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val computeAdherenceUseCase: ComputeAdherenceUseCase,
    private val exportAdherenceReportUseCase: ExportAdherenceReportUseCase,
    private val calculator: AdherenceCalculator,
    private val clockProvider: ClockProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val today = LocalDate.now(clockProvider.clock)
            val monthStart = today.withDayOfMonth(1)
            val logs = computeAdherenceUseCase.logsBetween(monthStart, today)
            val summaries = calculator.perDay(logs, (0 until today.dayOfMonth).map { monthStart.plusDays(it.toLong()) })
                .associateBy { it.date }
            val weekly = calculator.weekly(computeAdherenceUseCase.logsForPeriod(7), today)
            _uiState.update {
                it.copy(
                    daySummaries = summaries,
                    selectedDay = today,
                    weekly = weekly,
                )
            }
            loadSelectedDay(today)
        }
    }

    fun selectDay(day: LocalDate) {
        _uiState.update { it.copy(selectedDay = day) }
        loadSelectedDay(day)
    }

    private fun loadSelectedDay(day: LocalDate) {
        viewModelScope.launch {
            val logs = computeAdherenceUseCase.logsBetween(day, day)
            val meds: List<Medication> = computeAdherenceUseCase.medications()
            _uiState.update {
                it.copy(
                    selectedDayRows = logs,
                    medicationNames = meds.associate { med -> med.id to med.name },
                )
            }
        }
    }

    fun exportReport() {
        if (_uiState.value.exporting) return
        _uiState.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val report = exportAdherenceReportUseCase()
            _uiState.update { it.copy(exporting = false, reportFile = report) }
        }
    }

    fun reportConsumed() {
        _uiState.update { it.copy(reportFile = null) }
    }
}

/** Opens the system share sheet for the exported PDF (doctor, email, print…). */
fun shareReport(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
