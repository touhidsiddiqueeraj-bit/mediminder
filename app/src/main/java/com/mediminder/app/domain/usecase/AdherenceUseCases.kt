package com.mediminder.app.domain.usecase

import com.mediminder.app.domain.engine.AdherenceCalculator
import com.mediminder.app.domain.engine.LowStockCalculator
import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.LowStockInfo
import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.service.AdherenceReportGenerator
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReportFile
import java.time.LocalDate
import javax.inject.Inject

/**
 * Read-side queries for history, the calendar and the PDF report. All pure
 * math is delegated to [AdherenceCalculator], which is unit-tested.
 */
class ComputeAdherenceUseCase @Inject constructor(
    private val doseLogRepository: DoseLogRepository,
    private val medicationRepository: MedicationRepository,
    private val clockProvider: ClockProvider,
) {
    suspend fun logsForPeriod(days: Int): List<DoseLog> {
        val today = LocalDate.now(clockProvider.clock)
        return doseLogRepository.getLogsBetween(
            today.minusDays((days - 1).toLong()).atStartOfDay(),
            today.plusDays(1).atStartOfDay().minusNanos(1),
        )
    }

    suspend fun logsBetween(from: LocalDate, to: LocalDate): List<DoseLog> =
        doseLogRepository.getLogsBetween(
            from.atStartOfDay(),
            to.plusDays(1).atStartOfDay().minusNanos(1),
        )

    suspend fun medications(): List<Medication> = medicationRepository.getAllMedications()
}

class ExportAdherenceReportUseCase @Inject constructor(
    private val generator: AdherenceReportGenerator,
) {
    suspend operator fun invoke(): ReportFile? = generator.generate(REPORT_DAYS)

    companion object {
        const val REPORT_DAYS = 30
    }
}

class CheckLowStockUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val lowStockCalculator: LowStockCalculator,
) {
    suspend operator fun invoke(): List<LowStockInfo> =
        lowStockCalculator.lowStockMedications(medicationRepository.getActiveMedications())
}
