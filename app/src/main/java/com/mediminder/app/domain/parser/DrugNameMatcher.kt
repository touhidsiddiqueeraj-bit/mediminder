package com.mediminder.app.domain.parser

import com.mediminder.app.domain.model.DrugNameEntry
import com.mediminder.app.domain.model.DrugNameType
import com.mediminder.app.domain.model.ScoredDrugName

/**
 * Fuzzy matcher for drug names against the offline dictionary.
 *
 * Pure Kotlin, no Android dependencies — fully unit-testable on the JVM.
 *
 * Matching pipeline:
 * 1. Both the query and every dictionary name are canonicalized:
 *    lowercase, punctuation stripped, and the classic OCR confusions folded
 *    together (0/o, 1/l, 5/s, rn/m) so "Metf0rmin" equals "metformin".
 * 2. Exact canonical hit → score 1.0.
 * 3. Otherwise candidate recall through a trigram index (fast: only a few
 *    dozen names get distance-checked per query), then a blend of
 *    normalized Levenshtein similarity and trigram (Dice) similarity, with a
 *    bonus for prefix relationships ("amoxicil" → "amoxicillin").
 * 4. Multi-word queries are matched as 1–4 word n-grams, so "Tab. Atorvastatin
 *    10mg" still finds "Atorvastatin".
 *
 * Scores are suggestions only — the user always confirms the final name.
 */
class DrugNameMatcher(entries: List<DrugNameEntry>) {

    private data class IndexedEntry(
        val entry: DrugNameEntry,
        val canonical: String,
        val trigrams: Set<String>,
    )

    private val entries: List<IndexedEntry>
    private val byCanonical = HashMap<String, MutableList<Int>>()
    private val trigramIndex = HashMap<String, MutableList<Int>>()

    init {
        this.entries = entries.map { entry ->
            val canonical = canonicalize(entry.name)
            IndexedEntry(entry = entry, canonical = canonical, trigrams = trigrams(canonical))
        }
        this.entries.forEachIndexed { index, item ->
            if (item.canonical.isNotEmpty()) {
                byCanonical.getOrPut(item.canonical) { mutableListOf() }.add(index)
                item.trigrams.forEach { gram ->
                    trigramIndex.getOrPut(gram) { mutableListOf() }.add(index)
                }
            }
        }
    }

    /** Canonical comparison form: folds OCR confusions and punctuation. */
    fun canonicalize(text: String): String {
        if (text.isBlank()) return ""
        var out = text.lowercase()
        out = NON_ALNUM.replace(out, " ")
        // OCR confusions: apply symmetrically so exact text still matches.
        out = out.replace("rn", "m")
        out = out.replace('0', 'o').replace('1', 'l').replace('5', 's')
        return SPACES.replace(out, " ").trim()
    }

    /**
     * Best matches for a (possibly noisy, multi-word) OCR name.
     * Only entries scoring at least [minScore] are returned.
     */
    fun bestMatches(
        query: String,
        maxResults: Int = 3,
        minScore: Float = MIN_USEFUL_SCORE,
    ): List<ScoredDrugName> {
        val queryCanonical = canonicalize(query)
        if (queryCanonical.isEmpty()) return emptyList()

        val best = HashMap<Int, Float>() // entry index -> best score
        for (gram in wordNGrams(queryCanonical)) {
            // Exact hit on a gram wins instantly for that entry.
            byCanonical[gram]?.forEach { index ->
                best[index] = 1f
            }

            // Trigram recall: rank candidate entries by gram overlap first.
            val overlap = HashMap<Int, Int>()
            trigrams(gram).forEach { trigram ->
                trigramIndex[trigram]?.forEach { index ->
                    overlap.merge(index, 1, Int::plus)
                }
            }
            if (overlap.isEmpty()) continue
            val candidates = overlap.entries
                .sortedByDescending { it.value }
                .take(MAX_CANDIDATES_PER_GRAM)
                .map { it.key }
            for (index in candidates) {
                val item = entries[index]
                val score = fuzzyScore(gram, item)
                val previous = best[index]
                if (previous == null || score > previous) {
                    best[index] = score
                }
            }
        }

        return best.entries
            .asSequence()
            .filter { it.value >= minScore }
            .sortedByDescending { it.value }
            .take(maxResults)
            .map { (index, score) ->
                val entry = entries[index].entry
                ScoredDrugName(
                    name = entry.name,
                    type = entry.type,
                    genericName = entry.genericName,
                    commonStrengths = entry.commonStrengths,
                    score = score,
                )
            }
            .toList()
    }

    /** Prefix suggestions while the user types ("amox" → "Amoxicillin"). */
    fun prefixSuggestions(query: String, limit: Int = 5): List<ScoredDrugName> {
        val queryCanonical = canonicalize(query)
        if (queryCanonical.length < MIN_SUGGESTION_LENGTH) return emptyList()
        val canonicalQuery = query.lowercase().trim()
        return entries
            .asSequence()
            .filter { it.canonical.startsWith(queryCanonical) || it.entry.name.lowercase().startsWith(canonicalQuery) }
            .sortedWith(
                compareBy<IndexedEntry> { it.canonical.length }
                    .thenBy { it.entry.type }
                    .thenBy { it.entry.name },
            )
            .take(limit)
            .map {
                ScoredDrugName(
                    name = it.entry.name,
                    type = it.entry.type,
                    genericName = it.entry.genericName,
                    commonStrengths = it.entry.commonStrengths,
                    score = 1f,
                )
            }
            .toList()
    }

    // -----------------------------------------------------------------
    // Scoring
    // -----------------------------------------------------------------

    private fun fuzzyScore(gramCanonical: String, item: IndexedEntry): Float {
        val levenshtein = normalizedLevenshtein(gramCanonical, item.canonical)
        val dice = diceCoefficient(gramCanonical, item)
        var score = LEVENSHTEIN_WEIGHT * levenshtein + TRIGRAM_WEIGHT * dice
        // A prefix relationship is a strong signal for truncated names.
        val longer = maxOf(gramCanonical.length, item.canonical.length)
        if (longer >= PREFIX_BONUS_MIN_LENGTH &&
            (item.canonical.startsWith(gramCanonical) || gramCanonical.startsWith(item.canonical))
        ) {
            score += PREFIX_BONUS
        }
        return score.coerceAtMost(0.99f)
    }

    private fun diceCoefficient(gramCanonical: String, item: IndexedEntry): Float {
        val gramTrigrams = trigrams(gramCanonical)
        if (gramTrigrams.isEmpty() || item.trigrams.isEmpty()) return 0f
        val common = gramTrigrams.count { it in item.trigrams }
        return 2f * common / (gramTrigrams.size + item.trigrams.size)
    }

    private fun wordNGrams(canonical: String, minLength: Int = 1, maxLength: Int = 4): List<String> {
        val words = canonical.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val grams = LinkedHashSet<String>()
        for (size in minLength..minOf(maxLength, words.size)) {
            for (start in 0..words.size - size) {
                grams.add(words.subList(start, start + size).joinToString(" "))
            }
        }
        return grams.toList()
    }

    private fun trigrams(canonical: String): Set<String> {
        if (canonical.isEmpty()) return emptySet()
        if (canonical.length < TRIGRAM_SIZE) return setOf(canonical)
        return (0..canonical.length - TRIGRAM_SIZE)
            .map { canonical.substring(it, it + TRIGRAM_SIZE) }
            .toSet()
    }

    private companion object {
        val NON_ALNUM = Regex("[^a-z0-9\\s]")
        val SPACES = Regex("\\s+")
        const val TRIGRAM_SIZE = 3
        const val MIN_USEFUL_SCORE = 0.5f
        const val MIN_SUGGESTION_LENGTH = 2
        const val MAX_CANDIDATES_PER_GRAM = 40
        const val LEVENSHTEIN_WEIGHT = 0.55f
        const val TRIGRAM_WEIGHT = 0.45f
        const val PREFIX_BONUS = 0.1f
        const val PREFIX_BONUS_MIN_LENGTH = 5
    }
}

/**
 * Normalized Levenshtein similarity (1 = equal, 0 = completely different).
 * Classic two-row dynamic programming; strings are short so this is cheap.
 */
fun normalizedLevenshtein(a: String, b: String): Float {
    if (a == b) return 1f
    if (a.isEmpty()) return if (b.isEmpty()) 1f else 0f
    if (b.isEmpty()) return 0f
    val maxLength = maxOf(a.length, b.length)
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                previous[j] + 1,                  // deletion
                current[j - 1] + 1,               // insertion
                previous[j - 1] + substitutionCost, // substitution
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return 1f - previous[b.length].toFloat() / maxLength
}
