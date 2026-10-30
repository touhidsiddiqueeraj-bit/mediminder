package com.mediminder.app.domain.repository

/**
 * Optional online drug-name lookup (RxNorm). Used only on the scan confirmation
 * screen to correct OCR mistakes. Must degrade gracefully: return an empty list
 * when offline or when the service fails — never block or crash.
 */
interface DrugNameRepository {

    /** Candidate standardized names for [query], best match first. Empty when offline. */
    suspend fun suggestNames(query: String): List<String>
}
