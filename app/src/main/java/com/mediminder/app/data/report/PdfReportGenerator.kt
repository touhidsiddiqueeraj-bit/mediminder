package com.mediminder.app.data.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.mediminder.app.R
import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.engine.AdherenceCalculator
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.service.AdherenceReportGenerator
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReportFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a simple, printer-friendly PDF adherence report the user can share
 * with their doctor (via the system share sheet).
 */
@Singleton
class PdfReportGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val doseLogRepository: DoseLogRepository,
    private val medicationRepository: MedicationRepository,
    private val calculator: AdherenceCalculator,
    private val clockProvider: ClockProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AdherenceReportGenerator {

    private object Styles {
        val title = paint(size = 20f, bold = true, color = ACCENT)
        val summary = paint(size = 13f, bold = true, color = Color.BLACK)
        val header = paint(size = 13f, bold = true, color = ACCENT)
        val columnHeader = paint(size = 10f, bold = true, color = Color.DKGRAY)
        val body = paint(size = 11f, bold = false, color = Color.BLACK)
        val muted = paint(size = 10f, bold = false, color = Color.GRAY)
        val divider = Paint().apply { color = 0xFFCCCCCC.toInt() }

        private fun paint(size: Float, bold: Boolean, color: Int) = Paint().apply {
            textSize = size
            isAntiAlias = true
            this.color = color
            isFakeBoldText = bold
        }
    }

    /** Draws onto pages, creating a new page automatically near the bottom. */
    private inner class PdfWriter {
        private val document = PdfDocument()
        private var pageCount = 1
        private var page: PdfDocument.Page =
            document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        private var canvas: Canvas = page.canvas
        private var y = MARGIN.toFloat()

        fun drawText(text: String, paint: Paint, x: Float = MARGIN.toFloat()) {
            newPageIfNeeded(paint.textSize + 5f)
            canvas.drawText(text, x, y, paint)
            y += paint.textSize + 5f
        }

        fun drawDivider() {
            newPageIfNeeded(6f)
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, Styles.divider)
            y += 14f
        }

        fun drawWrapped(text: String, paint: Paint) {
            val maxWidth = (PAGE_W - 2 * MARGIN).toFloat()
            val words = text.split(' ')
            val line = StringBuilder()
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    drawText(line.toString(), paint)
                    line.clear()
                }
                if (line.isEmpty()) line.append(word) else line.append(' ').append(word)
            }
            if (line.isNotEmpty()) drawText(line.toString(), paint)
        }

        fun drawSpacer(height: Float) {
            y += height
        }

        fun finish(now: LocalDateTime): File {
            document.finishPage(page)
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(
                reportsDir,
                "MediMinder_Report_${now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))}.pdf",
            )
            FileOutputStream(file).use { output -> document.writeTo(output) }
            document.close()
            return file
        }

        private fun newPageIfNeeded(needed: Float) {
            if (y + needed > PAGE_H - MARGIN) {
                document.finishPage(page)
                pageCount += 1
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageCount).create(),
                )
                canvas = page.canvas
                y = MARGIN.toFloat()
            }
        }
    }

    override suspend fun generate(days: Int): ReportFile? = withContext(ioDispatcher) {
        val now = LocalDateTime.now(clockProvider.clock)
        val today = now.toLocalDate()
        val fromDate = today.minusDays((days - 1).toLong())
        val logs = doseLogRepository.getLogsBetween(
            fromDate.atStartOfDay(),
            today.plusDays(1).atStartOfDay().minusNanos(1),
        )
        if (logs.isEmpty()) return@withContext null

        val medications = medicationRepository.getAllMedications()
        val perMedication = calculator.perMedication(logs, medications)
            .filter { it.taken + it.missed + it.skipped > 0 }
        val last14Days = calculator.perDay(
            logs,
            (13 downTo 0).map { today.minusDays(it.toLong()) },
        )
        val takenTotal = logs.count { it.status == DoseStatus.TAKEN }
        val missedTotal = logs.count { it.status == DoseStatus.MISSED }
        val skippedTotal = logs.count { it.status == DoseStatus.SKIPPED }
        val adherencePercent: Int? =
            if (takenTotal + missedTotal > 0) takenTotal * 100 / (takenTotal + missedTotal) else null

        val writer = PdfWriter()
        writer.drawText(context.getString(R.string.report_title), Styles.title)
        writer.drawSpacer(4f)
        writer.drawText(
            context.getString(R.string.report_generated, now.format(dateFormat)),
            Styles.muted,
        )
        writer.drawText(
            context.getString(
                R.string.report_period,
                fromDate.format(dateFormat),
                today.format(dateFormat),
            ),
            Styles.muted,
        )
        writer.drawDivider()
        writer.drawText(
            context.getString(
                R.string.report_summary,
                takenTotal,
                missedTotal,
                skippedTotal,
                adherencePercent?.let { "$it%" } ?: "-",
            ),
            Styles.summary,
        )
        writer.drawSpacer(12f)

        if (perMedication.isNotEmpty()) {
            writer.drawText(context.getString(R.string.report_by_medicine), Styles.header)
            writer.drawDivider()
            writer.drawText(context.getString(R.string.report_col_medicine), Styles.columnHeader)
            writer.drawText(context.getString(R.string.report_col_taken), Styles.columnHeader, x = 340f)
            writer.drawText(context.getString(R.string.report_col_missed), Styles.columnHeader, x = 410f)
            writer.drawText(context.getString(R.string.report_col_skipped), Styles.columnHeader, x = 480f)
            writer.drawText(context.getString(R.string.report_col_adherence), Styles.columnHeader, x = 540f)
            writer.drawSpacer(2f)
            for (row in perMedication) {
                writer.drawText(ellipsize(row.name, 40), Styles.body)
                writer.drawText(row.taken.toString(), Styles.body, x = 344f)
                writer.drawText(row.missed.toString(), Styles.body, x = 414f)
                writer.drawText(row.skipped.toString(), Styles.body, x = 484f)
                writer.drawText(row.adherencePercent?.let { "$it%" } ?: "-", Styles.body, x = 540f)
                writer.drawSpacer(2f)
            }
            writer.drawSpacer(12f)
        }

        writer.drawText(context.getString(R.string.report_by_day), Styles.header)
        writer.drawDivider()
        val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
        for (day in last14Days.asReversed()) {
            writer.drawText(day.date.format(dayFormatter), Styles.body)
            writer.drawText(
                context.getString(
                    R.string.report_day_counts,
                    day.taken,
                    day.missed,
                    day.skipped,
                    day.adherencePercent?.let { "$it%" } ?: "-",
                ),
                Styles.body,
                x = 170f,
            )
            writer.drawSpacer(2f)
        }
        writer.drawSpacer(14f)
        writer.drawWrapped(context.getString(R.string.report_disclaimer), Styles.muted)

        val file = writer.finish(now)
        ReportFile(file, file.name)
    }

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private val dateFormat = DateTimeFormatter.ofPattern("d MMM uuuu")

    companion object {
        // A4 at 72 dpi
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 40
        const val ACCENT = 0xFF00509D.toInt()
    }
}
