package com.mediminder.app.domain.service

import com.mediminder.app.domain.model.DrugNameEntry
import com.mediminder.app.domain.model.ScoredDrugName
import com.mediminder.app.domain.parser.DrugNameMatcher

/**
 * Offline drug-name dictionary: generic and brand names packaged with the app
 * (rebuilt from the openFDA NDC Directory) with fuzzy matching that tolerates
 * typical OCR mistakes (Metf0rmin -> Metformin). Fully on-device.
 */
interface DrugNameIndex {

    /**
     * Best fuzzy matches for an OCR'd or typed name, best first.
     * Returns an empty list when nothing scores high enough.
     */
    suspend fun bestMatches(query: String, maxResults: Int = 3): List<ScoredDrugName>

    /** Prefix suggestions while the user types. */
    suspend fun suggestions(query: String, limit: Int = 5): List<ScoredDrugName>
}

/** In-memory [DrugNameIndex] over a fixed entry list — tests and previews. */
fun drugNameIndexOf(entries: List<DrugNameEntry>): DrugNameIndex {
    val matcher = DrugNameMatcher(entries)
    return object : DrugNameIndex {
        override suspend fun bestMatches(query: String, maxResults: Int): List<ScoredDrugName> =
            matcher.bestMatches(query, maxResults = maxResults)

        override suspend fun suggestions(query: String, limit: Int): List<ScoredDrugName> =
            matcher.prefixSuggestions(query, limit = limit)
    }
}
