package com.mediminder.app.domain.parser

/**
 * Splits the OCR lines of a prescription or label into clusters — one per
 * medication — and filters out the noise around them (doctor names,
 * addresses, dates, headers, warnings).
 *
 * Pure Kotlin, no Android or dictionary dependency: clustering runs on cheap
 * textual cues only (form words like "Tab."/"Cap.", strength patterns like
 * "500 mg", dose-slot notation like "1-0-1"). The dictionary is consulted
 * afterwards, per cluster, by the extractor.
 *
 * Line handling, in order:
 *  1. A line with a name AND a cue (form word, strength, or that is an
 *     enumerated item) starts a new cluster.
 *  2. A line that looks like dosage instructions ("Sig:", "1-1-1",
 *     "twice daily", "take …") is absorbed into the current cluster.
 *  3. A noise line (doctor, address, date, contact, warnings…) closes the
 *     current cluster — nothing after it belongs to the previous medication.
 *  4. Other lines (e.g. a bare "500 mg" wrapped from the next print column)
 *     are absorbed while a cluster is open; ignored otherwise.
 *  5. If no cue line exists at all (a plain pharmacy label), everything
 *     non-noise falls back into one single cluster — the pre-existing
 *     single-medication behaviour.
 */
class PrescriptionSegmenter {

    /** One medication candidate: its primary line plus absorbed follow-ups. */
    data class Cluster(
        val primaryLine: String,
        val lines: List<String>,
    )

    fun segment(rawLines: List<String>): List<Cluster> {
        val clusters = mutableListOf<MutableCluster>()
        var open: MutableCluster? = null

        for (rawLine in rawLines) {
            val line = stripEnumeration(rawLine.trim())
            if (line.isEmpty()) continue

            when {
                isMedicationStart(line) -> {
                    open = MutableCluster(primaryLine = line, lines = mutableListOf(line))
                    clusters.add(open)
                }
                isDosageInstruction(line) -> open?.lines?.add(line)
                isNoise(line) -> open = null
                open != null -> open.lines.add(line)
                // Anything else before the first medication (clinic headers
                // without known keywords) is ignored.
            }
        }

        val capped = clusters.take(MAX_MEDICATIONS)
        if (capped.isNotEmpty()) {
            return dedupe(capped.map { Cluster(it.primaryLine, it.lines.toList()) })
        }

        // Fallback: a single label with no strong cues — keep everything.
        val fallback = rawLines.map { stripEnumeration(it.trim()) }
            .filter { it.isNotEmpty() && !isNoise(it) }
        if (fallback.isEmpty()) return emptyList()
        return listOf(Cluster(primaryLine = fallback.first(), lines = fallback))
    }

    // -----------------------------------------------------------------
    // Line classification
    // -----------------------------------------------------------------

    private fun isMedicationStart(line: String): Boolean {
        val lower = line.lowercase()
        if (!hasNameWords(line)) return false
        // Dosage instructions ("Take 1 tablet twice daily") contain form cue
        // words too — they must never start their own cluster.
        if (LEADING_VERB_PATTERN.containsMatchIn(lower)) return false
        if (NOISE_LINE_PATTERN.containsMatchIn(lower)) return false
        // A date with slots ("15/03/2026 1-1-1") is an instruction, not a med.
        if (DATE_PATTERN.containsMatchIn(line) &&
            !FORM_CUE_PATTERN.containsMatchIn(lower) &&
            !STRENGTH_CUE_PATTERN.containsMatchIn(lower)
        ) {
            return false
        }
        return FORM_CUE_PATTERN.containsMatchIn(lower) ||
            STRENGTH_CUE_PATTERN.containsMatchIn(lower) ||
            DOSAGE_SLOT_PATTERN.containsMatchIn(lower)
    }

    private fun isDosageInstruction(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.startsWith("sig")) return true
        return DOSAGE_SLOT_PATTERN.containsMatchIn(lower) ||
            FREQUENCY_CUE_PATTERN.containsMatchIn(lower) ||
            INTAKE_VERB_PATTERN.containsMatchIn(lower)
    }

    private fun isNoise(line: String): Boolean {
        val lower = line.lowercase()
        if (DATE_PATTERN.containsMatchIn(line)) return true
        return NOISE_LINE_PATTERN.containsMatchIn(lower) ||
            CONTACT_LINE_PATTERN.containsMatchIn(lower)
    }

    private fun hasNameWords(line: String): Boolean {
        val letters = line.count { it.isLetter() }
        val words = line.split(Regex("\\s+")).count { it.any(Char::isLetter) && it.length >= 2 }
        return letters >= MIN_LINE_LETTERS && words >= 1
    }

    private fun stripEnumeration(line: String): String =
        ENUMERATION_PATTERN.replace(line, "")

    /** Merges clusters whose primary line repeats (double OCR regions). */
    private fun dedupe(clusters: List<Cluster>): List<Cluster> {
        val byKey = LinkedHashMap<String, Cluster>()
        for (cluster in clusters) {
            val key = cluster.primaryLine.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (key.isEmpty()) continue
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = cluster
            } else {
                byKey[key] = existing.copy(lines = (existing.lines + cluster.lines).distinct())
            }
        }
        return byKey.values.toList()
    }

    private class MutableCluster(
        val primaryLine: String,
        val lines: MutableList<String>,
    )

    private companion object {
        const val MAX_MEDICATIONS = 8
        const val MIN_LINE_LETTERS = 3

        /** "Tab", "Cap.", "Syr", "Inj", drops, ointment, spray, patch … */
        val FORM_CUE_PATTERN = Regex(
            """\b(tab|tabs|tablet|tablets|cap|caps|capsule|capsules|caplet|caplets|""" +
                """syr|syp|syrup|susp|suspension|inj|injection|drops|soln|solution|""" +
                """oint|ointment|cream|gel|spray|inhaler|sachet|supp|suppository|""" +
                """suppositories|patch|liq|liquid)\b""",
        )

        /** "500mg", "10 mcg", "5 ml", "1000 IU" … */
        val STRENGTH_CUE_PATTERN =
            Regex("""\d+(?:\.\d+)?\s*(?:mg|mcg|gm|g|ml|iu|units?)\b""", RegexOption.IGNORE_CASE)

        /** "1-0-1", "1-1-1", "2-0-1-1" (morning-noon-evening(-night) slots). */
        val DOSAGE_SLOT_PATTERN = Regex("""\b[0-3](?:\s*-\s*[0-3]){1,3}\b""")

        /** "1. ", "2) " … leading item numbers (not "1-0-1" slot notation). */
        val ENUMERATION_PATTERN = Regex("""^\s*\d{1,2}\s*[.)\]:]\s+""")

        val INTAKE_VERB_PATTERN =
            Regex("""\b(take|taking|apply|inhale|use|drink|swallow|inject|chew|dissolve)\b""")

        /** An instruction line that starts with an intake verb. */
        val LEADING_VERB_PATTERN =
            Regex("""^\s*(take|taking|apply|inhale|use|drink|swallow|inject|chew|dissolve)\b""")

        val FREQUENCY_CUE_PATTERN = Regex(
            """\b(daily|od|bd|tds|qid|bid|tid|hs|qhs|sos|prn|morning|noon|""" +
                """afternoon|evening|night|bedtime|meals?|food|week|weekly|hours?|hrs?|""" +
                """twice|thrice|once|alternate)\b""",
        )

        val DATE_PATTERN = Regex("""\b\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4}\b""")

        val CONTACT_LINE_PATTERN = Regex(
            """\b(phone|tel|mobile|cell|email|www\.|\.com|street|road|avenue|ave|""" +
                """suite|hospital|clinic|medical center|health centre|pharmacy|drug store|""" +
                """reg\.|registration|license|licence)\b""",
        )

        val NOISE_LINE_PATTERN = Regex(
            """^\s*(rx\b|#|refill|dr\.?\b|doctor|physician|patient|name\s*:|address|""" +
                """directions?|disp\b|dispense|quantity|qty|caution|warnings?|""" +
                """store\b|keep\b|manufactur|expired?|exp\.?\b|lot\b|batch|ndc\b|note\b|""" +
                """important|side effects?|if you|call your|questions|age\s*[:/]|""" +
                """sex\s*[:/]|weight\s*[:/]|consultant|prescriber|follow up|next visit|""" +
                """date\s*[:/]|date\b)|\b(md|mbbs|mbbch|ddv|ddr|frcp|fracs)\b\s*$""",
        )
    }
}
