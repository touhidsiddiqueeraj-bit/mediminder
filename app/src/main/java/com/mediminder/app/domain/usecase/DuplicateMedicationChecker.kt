package com.mediminder.app.domain.usecase

import com.mediminder.app.domain.model.Medication
import com.mediminder.app.domain.repository.MedicationRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warns when a new entry looks like a duplicate of an existing one — the same
 * active ingredient taken twice is a real danger for elderly patients.
 *
 * Comparison is conservative: names are normalized (lowercase, punctuation and
 * dose-form noise removed) and a duplicate is flagged only when one token set
 * fully contains the other, e.g. "Metformin" vs "Metformin ER 500".
 */
@Singleton
class DuplicateMedicationChecker @Inject constructor(
    private val medicationRepository: MedicationRepository,
) {
    private val noiseTokens = setOf(
        "hcl", "hr", "er", "xr", "sr", "cr", "la", "dr",
        "tablet", "tablets", "tab", "tabs", "capsule", "capsules", "cap", "caps",
        "pill", "pills", "mg", "mcg", "g", "ml", "and", "plus", "with",
    )

    /** An active medication that may share the active ingredient with [candidate], or null. */
    suspend fun findDuplicate(candidate: Medication): Medication? {
        val candidateTokens = tokens(candidate.genericName ?: candidate.name)
        if (candidateTokens.isEmpty()) return null
        return medicationRepository.getActiveMedications().firstOrNull { other ->
            if (other.id != 0L && other.id == candidate.id) return@firstOrNull false
            val otherTokens = tokens(other.genericName ?: other.name)
            otherTokens.isNotEmpty() && (
                otherTokens.containsAll(candidateTokens) ||
                    candidateTokens.containsAll(otherTokens)
                )
        }
    }

    private fun tokens(raw: String): Set<String> =
        raw.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in noiseTokens }
            .toSet()
}
