package com.mediminder.app.domain.service

import java.io.File

/** A generated adherence report ready to share. */
data class ReportFile(
    val file: File,
    val displayName: String,
)

/** Builds the PDF report the user can share with their doctor. */
interface AdherenceReportGenerator {

    /** Report covering the last [days] days, or null when there is no data yet. */
    suspend fun generate(days: Int = 30): ReportFile?
}
