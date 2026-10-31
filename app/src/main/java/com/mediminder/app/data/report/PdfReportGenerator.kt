package com.mediminder.app.data.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mediminder.app.R
import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.engine.AdherenceCalculator
import com.mediminder.app.domain.model.DoseStatus
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.service.AdherenceReportGenerator
import com.mediminder.app.domain.service.ClockProvider
import com.mediminder.app.domain.service.ReportFile
import com.mediminder.app.util.withAppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a simple, printer-friendly PDF adherence report the user can share
 * with their doctor (via the system share sheet).
 *
 * All text is drawn through [StaticLayout] rather than plain Canvas.drawText:
 * Bangla, Hindi and Arabic need proper text shaping (conjuncts, ligatures),
 * which drawText cannot do.
 */
@Singleton
class PdfReportGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val doseLogRepository: DoseLogRepository,
    private val medicationRepository: MedicationRepository,
    private val settingsRepository: SettingsRepository,
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
    private inner class PdfWriter(private val strings: Context) {
        private val document = PdfDocument()
        private var pageCount = 1
        private var page: PdfDocument.Page =
            document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        private var canvas: Canvas = page.canvas
        private var y = MARGIN.toFloat()

        fun drawText(text: String, paint: Paint, x: Float = MARGIN.toFloat()) {
            newPageIfNeeded(paint.textSize + 5f)
            y += drawStaticLine(text, paint, x)
            y += 5f
        }

        /** Draws several cells of one table row on the same baseline. */
        fun drawRow(cells: List<Pair<Float, String>>, paint: Paint) {
            newPageIfNeeded(paint.textSize + 5f)
            var height = 0f
            for ((x, text) in cells) {
                height = maxOf(height, drawStaticLine(text, paint, x))
            }
            y += height + 5f
        }

        fun drawDivider() {
            newPageIfNeeded(6f)
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, Styles.divider)
            y += 14f
        }

        /** Word-wrapping paragraph (StaticLayout wraps and shapes correctly). */
        fun drawWrapped(text: String, paint: Paint) {
            val width = (PAGE_W - 2 * MARGIN).toFloat()
            val layout = staticLayout(text, paint, width)
            newPageIfNeeded(layout.height + 2f)
            canvas.save()
            canvas.translate(MARGIN.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 6f
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

        /** Draws a single shaped line starting at (x, y); returns its height. */
        private fun drawStaticLine(text: String, paint: Paint, x: Float): Float {
            val width = ((PAGE_W - MARGIN) - x).coerceAtLeast(1f)
            val layout = staticLayout(text, paint, width)
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            return layout.height.toFloat()
        }

        private fun staticLayout(text: String, paint: Paint, width: Float): StaticLayout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, TextPaint(paint), width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()

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

        // The report runs on the application context, which on Android 8–12
        // does not follow the per-app language — re-apply it for every string.
        val languageTag = settingsRepository.current().languageTag
        val strings = context.withAppLanguage(languageTag)
        val locale = if (languageTag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(languageTag)
        val dateFormat = DateTimeFormatter.ofPattern("d MMM uuuu", locale)

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

        val writer = PdfWriter(strings)
        writer.drawText(strings.getString(R.string.report_title), Styles.title)
        writer.drawSpacer(4f)
        writer.drawText(
            strings.getString(R.string.report_generated, now.format(dateFormat)),
            Styles.muted,
        )
        writer.drawText(
            strings.getString(
                R.string.report_period,
                fromDate.format(dateFormat),
                today.format(dateFormat),
            ),
            Styles.muted,
        )
        writer.drawDivider()
        writer.drawText(
            strings.getString(
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
            writer.drawText(strings.getString(R.string.report_by_medicine), Styles.header)
            writer.drawDivider()
            writer.drawRow(
                listOf(
                    MARGIN.toFloat() to strings.getString(R.string.report_col_medicine),
                    340f to strings.getString(R.string.report_col_taken),
                    410f to strings.getString(R.string.report_col_missed),
                    480f to strings.getString(R.string.report_col_skipped),
                    540f to strings.getString(R.string.report_col_adherence),
                ),
                Styles.columnHeader,
            )
            writer.drawSpacer(2f)
            for (row in perMedication) {
                writer.drawRow(
                    listOf(
                        MARGIN.toFloat() to ellipsizeToWidth(
                            row.name,
                            Styles.body,
                            340f - MARGIN - 8f,
                        ),
                        344f to row.taken.toString(),
                        414f to row.missed.toString(),
                        484f to row.skipped.toString(),
                        540f to (row.adherencePercent?.let { "$it%" } ?: "-"),
                    ),
                    Styles.body,
                )
                writer.drawSpacer(2f)
            }
            writer.drawSpacer(12f)
        }

        writer.drawText(strings.getString(R.string.report_by_day), Styles.header)
        writer.drawDivider()
        val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)
        for (day in last14Days.asReversed()) {
            writer.drawRow(
                listOf(
                    MARGIN.toFloat() to day.date.format(dayFormatter),
                    170f to strings.getString(
                        R.string.report_day_counts,
                        day.taken,
                        day.missed,
                        day.skipped,
                        day.adherencePercent?.let { "$it%" } ?: "-",
                    ),
                ),
                Styles.body,
            )
            writer.drawSpacer(2f)
        }
        writer.drawSpacer(14f)
        writer.drawWrapped(strings.getString(R.string.report_disclaimer), Styles.muted)

        val file = writer.finish(now)
        ReportFile(file, file.name)
    }

    /** Truncates with an ellipsis so the text fits [maxWidth] at the paint's size. */
    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.isNotEmpty() && paint.measureText(trimmed + "…") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return trimmed + "…"
    }

    companion object {
        // A4 at 72 dpi
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 40
        const val ACCENT = 0xFF00509D.toInt()
    }
}
