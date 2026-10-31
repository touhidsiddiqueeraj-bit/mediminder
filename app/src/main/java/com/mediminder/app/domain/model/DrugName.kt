package com.mediminder.app.domain.model

/** Where a dictionary name comes from. */
enum class DrugNameType {
    GENERIC,
    BRAND,
}

/**
 * One row of the offline drug-name dictionary (packaged as an asset and copied
 * into Room on first use; rebuilt from the openFDA NDC Directory).
 */
data class DrugNameEntry(
    val name: String,
    val type: DrugNameType,
    /** For brands: the generic ingredient(s) it is marketed under. */
    val genericName: String? = null,
    /** Strengths commonly sold for this name, e.g. ["500 mg", "850 mg"]. */
    val commonStrengths: List<String> = emptyList(),
)

/** A dictionary candidate with its fuzzy-match score (1 = certain). */
data class ScoredDrugName(
    val name: String,
    val type: DrugNameType,
    val genericName: String? = null,
    val commonStrengths: List<String> = emptyList(),
    /** 0..1; 1 = exact match after OCR-confusion normalization. */
    val score: Float,
)
