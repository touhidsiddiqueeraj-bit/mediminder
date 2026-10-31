package com.mediminder.app.data.scanner

import com.mediminder.app.data.local.DrugNameDao
import com.mediminder.app.data.local.DrugNameSeeder
import com.mediminder.app.domain.model.DrugNameEntry
import com.mediminder.app.domain.model.ScoredDrugName
import com.mediminder.app.domain.parser.DrugNameMatcher
import com.mediminder.app.domain.service.DrugNameIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DrugNameIndex] backed by the Room dictionary table: seeds it from the
 * packaged asset on first use, then loads every row once into an in-memory
 * matcher (a few thousand short strings — trivially small) with a prefix and
 * trigram index for fast fuzzy lookups.
 */
@Singleton
class LocalDrugNameIndex @Inject constructor(
    private val dao: DrugNameDao,
    private val seeder: DrugNameSeeder,
) : DrugNameIndex {

    private val mutex = Mutex()
    private var matcher: DrugNameMatcher? = null

    override suspend fun bestMatches(query: String, maxResults: Int): List<ScoredDrugName> =
        loaded().bestMatches(query, maxResults = maxResults)

    override suspend fun suggestions(query: String, limit: Int): List<ScoredDrugName> =
        loaded().prefixSuggestions(query, limit = limit)

    private suspend fun loaded(): DrugNameMatcher = matcher ?: mutex.withLock {
        matcher ?: run {
            seeder.ensureSeeded()
            val entries = try {
                dao.getAll().map { entity ->
                    DrugNameEntry(
                        name = entity.name,
                        type = entity.type,
                        genericName = entity.genericName,
                        commonStrengths = entity.commonStrengths
                            ?.split(",")
                            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                            ?: emptyList(),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
            DrugNameMatcher(entries).also { matcher = it }
        }
    }
}
