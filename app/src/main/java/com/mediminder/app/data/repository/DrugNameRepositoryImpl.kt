package com.mediminder.app.data.repository

import com.mediminder.app.data.remote.RxNormApi
import com.mediminder.app.domain.repository.DrugNameRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online drug-name suggestions from RxNorm. Every failure mode (offline,
 * timeout, malformed body) resolves to an empty list — this feature must never
 * get in the way of adding a medicine.
 */
@Singleton
class DrugNameRepositoryImpl @Inject constructor(
    private val api: RxNormApi,
) : DrugNameRepository {

    override suspend fun suggestNames(query: String): List<String> {
        val cleaned = query.trim().take(MAX_QUERY_LENGTH)
        if (cleaned.length < MIN_QUERY_LENGTH) return emptyList()
        return try {
            api.findDrugs(cleaned).drugGroup
                ?.conceptGroup
                .orEmpty()
                .flatMap { group -> group.conceptProperties.orEmpty() }
                .mapNotNull { it.name }
                .filter { it.length in 2..60 }
                .distinct()
                .take(MAX_RESULTS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 3
        const val MAX_QUERY_LENGTH = 80
        const val MAX_RESULTS = 5
    }
}
