package com.mediminder.app.domain.parser

import com.mediminder.app.domain.model.FieldConfidence
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.LabelField
import com.mediminder.app.domain.model.MedicationForm
import com.mediminder.app.domain.model.ParsedFrequency
import com.mediminder.app.domain.model.ParsedLabel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns raw OCR text from a medicine label, box or prescription into structured
 * fields: name, strength, dosage, form, frequency and instructions.
 *
 * Design notes
 * - Pure Kotlin, no Android dependencies: fully unit-testable on the JVM.
 * - Regex rules are intentionally ordered (most specific first).
 * - Every result carries per-field confidence; the confirmation screen
 *   highlights low-confidence fields and the user always has the final say.
 * - Nothing here is saved or scheduled — parsing only suggests values.
 */
@Singleton
class MedicationLabelParser @Inject constructor() {

    fun parse(rawText: String): ParsedLabel {
        val text = rawText.trim()
        if (text.isEmpty()) return ParsedLabel(rawText = rawText)

        val lower = text.lowercase()
        val confidence = mutableMapOf<LabelField, FieldConfidence>()

        // ---- 1. Strength (e.g. "500 mg", "10 mcg", "100 units/mL" -> 100 units) ----
        val strengthMatch = STRENGTH_PATTERN.find(text)
        val strengthValue = strengthMatch?.groupValues?.get(1)?.replace(",", ".")
        val strengthUnit = strengthMatch?.groupValues?.get(2)?.let(::normalizeStrengthUnit)
        if (strengthValue != null) confidence[LabelField.STRENGTH] = FieldConfidence.HIGH

        // ---- 2. Frequency ----
        val frequency = detectFrequency(lower, confidence)

        // ---- 3. Dosage (e.g. "1 tablet", "two capsules", "10 drops") ----
        val dosage = detectDosage(lower)
        if (dosage != null) {
            confidence[LabelField.DOSAGE] = FieldConfidence.HIGH
            if (dosage.form != null) confidence[LabelField.FORM] = FieldConfidence.HIGH
        }

        // A strength that is identical to the dosage (e.g. "take 5 ml" of a liquid)
        // is a dosage, not a separate strength.
        val adjustedStrengthValue: String?
        val adjustedStrengthUnit: String?
        if (dosage != null && dosage.amount == strengthValue && dosage.unit == strengthUnit) {
            adjustedStrengthValue = null
            adjustedStrengthUnit = null
        } else {
            adjustedStrengthValue = strengthValue
            adjustedStrengthUnit = strengthUnit
        }
        confidence.putIfAbsent(LabelField.DOSAGE, FieldConfidence.LOW)
        confidence.putIfAbsent(LabelField.FORM, FieldConfidence.LOW)

        // ---- 4. Instructions ("with food", "at bedtime" ...) ----
        val instructions = detectInstructions(lower)
        if (instructions != null) confidence[LabelField.INSTRUCTIONS] = FieldConfidence.MEDIUM

        // ---- 5. Name (heuristic: the first plausible product line) ----
        val name = extractName(text, hadStrength = adjustedStrengthValue != null)
        confidence[LabelField.NAME] = when {
            name != null && adjustedStrengthValue != null -> FieldConfidence.HIGH
            name != null -> FieldConfidence.MEDIUM
            else -> FieldConfidence.LOW
        }
        confidence.putIfAbsent(LabelField.FREQUENCY, FieldConfidence.LOW)

        return ParsedLabel(
            rawText = rawText,
            name = name,
            strengthValue = adjustedStrengthValue,
            strengthUnit = adjustedStrengthUnit,
            dosageAmount = dosage?.amount,
            dosageUnit = dosage?.unit,
            form = dosage?.form,
            frequency = frequency,
            instructionText = instructions,
            confidence = confidence.toMap(),
        )
    }

    // ---------------------------------------------------------------------
    // Frequency
    // ---------------------------------------------------------------------

    private fun detectFrequency(
        lower: String,
        confidence: MutableMap<LabelField, FieldConfidence>,
    ): ParsedFrequency? {
        // "As needed" wins: no scheduled reminders for PRN medicines.
        if (AS_NEEDED_PATTERN.containsMatchIn(lower)) {
            confidence[LabelField.FREQUENCY] = FieldConfidence.HIGH
            return ParsedFrequency(type = FrequencyType.AS_NEEDED, asNeeded = true)
        }

        // every N hours / every N-M hours / every four hours
        EVERY_N_HOURS_PATTERN.find(lower)?.let { match ->
            val from = match.groupValues[1].toIntOrNull()
            val to = match.groupValues[2].toIntOrNull()
            val hours = to ?: from
            if (hours != null && hours in 1..24) return intervalFrequency(hours, confidence)
        }
        EVERY_WORD_HOURS_PATTERN.find(lower)?.let { match ->
            val hours = WORD_HOURS[match.groupValues[1]]
            if (hours != null) return intervalFrequency(hours, confidence)
        }

        // q8h, q12h …
        QNH_PATTERN.find(lower)?.let { match ->
            val hours = match.groupValues[1].toIntOrNull()
            if (hours != null && hours in 1..24) return intervalFrequency(hours, confidence)
        }

        // "at bedtime", "at night", "hs"
        if (BEDTIME_PATTERN.containsMatchIn(lower)) {
            confidence[LabelField.FREQUENCY] = FieldConfidence.HIGH
            return ParsedFrequency(
                type = FrequencyType.DAILY,
                times = listOf(LabelDefaults.BEDTIME),
            )
        }

        // twice daily / 3 times a day / b.i.d. / tid / daily …
        // A single moment mentioned alongside "once daily" wins ("once daily
        // in the evening" -> 18:00 rather than the generic 08:00).
        val perDay = detectTimesPerDay(lower)
        if (perDay != null) {
            confidence[LabelField.FREQUENCY] = FieldConfidence.HIGH
            val moment = when {
                MORNING_PATTERN.containsMatchIn(lower) -> LabelDefaults.MORNING
                AFTERNOON_PATTERN.containsMatchIn(lower) -> LabelDefaults.AFTERNOON
                EVENING_PATTERN.containsMatchIn(lower) -> LabelDefaults.EVENING
                else -> null
            }
            val times = if (perDay == 1 && moment != null) {
                listOf(moment)
            } else {
                LabelDefaults.timesPerDay(perDay)
            }
            return ParsedFrequency(type = FrequencyType.DAILY, times = times)
        }

        // "with meals" / "after food"
        if (MEALS_PATTERN.containsMatchIn(lower)) {
            confidence[LabelField.FREQUENCY] = FieldConfidence.MEDIUM
            return ParsedFrequency(type = FrequencyType.DAILY, times = LabelDefaults.MEAL_TIMES)
        }

        // "every other day"
        if (EVERY_OTHER_DAY_PATTERN.containsMatchIn(lower)) {
            confidence[LabelField.FREQUENCY] = FieldConfidence.HIGH
            return ParsedFrequency(
                type = FrequencyType.DAILY,
                times = listOf(LabelDefaults.MORNING),
                intervalDays = 2,
            )
        }

        // single moments of the day
        val moment = when {
            MORNING_PATTERN.containsMatchIn(lower) -> LabelDefaults.MORNING
            AFTERNOON_PATTERN.containsMatchIn(lower) -> LabelDefaults.AFTERNOON
            EVENING_PATTERN.containsMatchIn(lower) -> LabelDefaults.EVENING
            else -> null
        }
        if (moment != null) {
            confidence[LabelField.FREQUENCY] = FieldConfidence.MEDIUM
            return ParsedFrequency(type = FrequencyType.DAILY, times = listOf(moment))
        }

        return null
    }

    private fun intervalFrequency(
        hours: Int,
        confidence: MutableMap<LabelField, FieldConfidence>,
    ): ParsedFrequency {
        confidence[LabelField.FREQUENCY] = FieldConfidence.HIGH
        return ParsedFrequency(
            type = FrequencyType.EVERY_X_HOURS,
            times = listOf(LabelDefaults.anchorForInterval(hours)),
            intervalHours = hours,
        )
    }

    private fun detectTimesPerDay(lower: String): Int? = when {
        BID_PATTERN.containsMatchIn(lower) || containsAnyWord(lower, "twice", "two times", "2 times", "2x") -> 2
        TID_PATTERN.containsMatchIn(lower) || containsAnyWord(lower, "three times", "3 times", "thrice", "3x") -> 3
        QID_PATTERN.containsMatchIn(lower) || containsAnyWord(lower, "four times", "4 times", "4x") -> 4
        DAILY_BARE_PATTERN.containsMatchIn(lower) -> 1
        else -> null
    }

    private fun containsAnyWord(lower: String, vararg phrases: String): Boolean =
        phrases.any { lower.contains(it) }

    // ---------------------------------------------------------------------
    // Dosage
    // ---------------------------------------------------------------------

    private data class DosageParse(
        val amount: String,
        val unit: String,
        val form: MedicationForm?,
    )

    private fun detectDosage(lower: String): DosageParse? {
        // Prefer unit-count forms: "1 tablet", "two capsules", "2 drops".
        DOSAGE_FORM_PATTERN.find(lower)?.let { match ->
            val amount = normalizeAmount(match.groupValues[1], match.groupValues[2])
            val unit = normalizeDosageUnit(match.groupValues[3])
            if (amount != null) return DosageParse(amount, unit, formFor(unit))
        }
        // Volume only counts when preceded by an intake verb, so the bottle
        // size ("100 ml") is not mistaken for the dose ("take 5 ml").
        DOSAGE_VOLUME_PATTERN.find(lower)?.let { match ->
            val amount = normalizeAmount(match.groupValues[2], match.groupValues[3])
            val unit = "ml"
            if (amount != null) return DosageParse(amount, unit, MedicationForm.LIQUID)
        }
        return null
    }

    private fun normalizeAmount(first: String, second: String): String? {
        val base = WORD_NUMBERS[first] ?: first
        if (base.isBlank()) return null
        if (second.isBlank()) return base
        val rangeEnd = WORD_NUMBERS[second] ?: second
        return "$base-$rangeEnd"
    }

    private fun normalizeStrengthUnit(unit: String): String = when (unit.lowercase()) {
        "gram", "grams", "gm" -> "g"
        "units" -> "units"
        else -> unit.lowercase()
    }

    private fun normalizeDosageUnit(unit: String): String = when (unit.lowercase()) {
        "tablets", "tabs", "tab" -> "tablet"
        "pills" -> "pill"
        "capsules", "caps" -> "capsule"
        "gtt" -> "drops"
        "drops" -> "drops"
        "puffs" -> "puff"
        "sprays" -> "spray"
        "injections", "syringe" -> "injection"
        "lozenges" -> "lozenge"
        "patches" -> "patch"
        else -> unit.lowercase()
    }

    private fun formFor(unit: String): MedicationForm? = when (unit) {
        "tablet", "pill", "lozenge" -> MedicationForm.TABLET
        "capsule" -> MedicationForm.CAPSULE
        "drops" -> MedicationForm.DROPS
        "injection" -> MedicationForm.INJECTION
        "ml" -> MedicationForm.LIQUID
        else -> null
    }

    // ---------------------------------------------------------------------
    // Instructions
    // ---------------------------------------------------------------------

    private fun detectInstructions(lower: String): String? {
        val matched = INSTRUCTION_PHRASES.filter { lower.contains(it) }
        // Drop phrases fully contained in a longer matched phrase
        // ("empty stomach" inside "on an empty stomach").
        val cleaned = matched.filter { phrase ->
            matched.none { other -> other != phrase && other.contains(phrase) }
        }
        if (cleaned.isEmpty()) return null
        return cleaned.joinToString(", ") { phrase ->
            phrase.replaceFirstChar { it.uppercase() }
        }
    }

    // ---------------------------------------------------------------------
    // Name
    // ---------------------------------------------------------------------

    private fun extractName(text: String, hadStrength: Boolean): String? {
        val lines = text.lines().map(String::trim).filter { it.isNotEmpty() }
        for (line in lines) {
            val lowerLine = line.lowercase()
            if (NOISE_LINE_PATTERN.containsMatchIn(lowerLine)) continue

            // Real labels are often a single line: "Atorvastatin 20 mg at bedtime".
            // The name is the part BEFORE the first instruction wording.
            val cut = INSTRUCTION_WORDS.asSequence()
                .mapNotNull { lowerLine.indexOf(it).takeIf { index -> index > 0 } }
                .minOrNull()
            var name = (if (cut != null) line.substring(0, cut) else line).trim()
            if (name.isEmpty()) continue

            // Remove the strength ("Metformin 500 mg" -> "Metformin")
            STRENGTH_PATTERN.findAll(name).forEach { match ->
                name = name.replaceRange(match.range, " ")
            }
            // Remove trailing form words ("Metformin Tablets" -> "Metformin")
            name = TRAILING_FORM_PATTERN.replace(name, "")
            // Drop leftover connector words at the end ("Atorvastatin at" -> "Atorvastatin")
            while (true) {
                val trimmed = TRAILING_CONNECTOR_PATTERN.replace(name, "")
                if (trimmed == name) break
                name = trimmed
            }
            name = name.replace(Regex("\\s{2,}"), " ").trim(' ', '\t', ',', '.', '-', '–', ';', ':')

            val words = name.split(' ').filter { it.isNotBlank() }
            val letters = name.count { it.isLetter() }
            val looksLikeName = words.isNotEmpty() &&
                words.size <= MAX_NAME_WORDS &&
                letters >= MIN_NAME_LETTERS &&
                !name.first().isDigit()
            if (looksLikeName) return name
        }
        return null
    }

    private companion object {
        const val MAX_NAME_WORDS = 6
        const val MIN_NAME_LETTERS = 3

        val WORD_NUMBERS = mapOf(
            "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "half" to "0.5",
        )

        val WORD_HOURS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "six" to 6, "eight" to 8, "twelve" to 12, "twenty-four" to 24,
            "twenty four" to 24,
        )

        val STRENGTH_PATTERN = Regex(
            """(\d+(?:[.,]\d+)?)\s*(mg|mcg|gm|g|gram|grams|ml|iu|units?)\b""",
            RegexOption.IGNORE_CASE,
        )

        val AS_NEEDED_PATTERN =
            Regex("""\b(as needed|when needed|if needed|as required|prn)\b""")

        val EVERY_N_HOURS_PATTERN =
            Regex("""\bevery\s+(\d+)\s*(?:to|-|–|or\s*)?\s*(\d+)?\s*(?:hours?|hrs?|h)\b""")

        val EVERY_WORD_HOURS_PATTERN = Regex(
            """\bevery\s+(one|two|three|four|six|eight|twelve|twenty[-\s]?four)\s*(?:hours?|hrs?|h)\b""",
        )

        val QNH_PATTERN = Regex("""\bq\s?(\d+)\s?h\b""")

        val BEDTIME_PATTERN =
            Regex("""\b(at bedtime|bedtime|before bed(time)?|at night|nightly|qhs|hs|hours? of sleep)\b""")

        val BID_PATTERN = Regex("""\bb\.?i\.?d\b""")
        val TID_PATTERN = Regex("""\bt\.?i\.?d\b""")
        val QID_PATTERN = Regex("""\bq\.?i\.?d\b""")
        val DAILY_BARE_PATTERN =
            Regex("""\b(daily|every day|each day|per day|once a day|once daily|q\.?d\b)""")

        val MEALS_PATTERN = Regex("""\b(with (each )?meals?|after meals?|after food|with food)\b""")

        val EVERY_OTHER_DAY_PATTERN = Regex("""\bevery (other|second|2nd) day\b""")

        val MORNING_PATTERN =
            Regex("""\b(in the morning|every morning|each morning|everyday morning|mornings|qam|with breakfast)\b""")
        val AFTERNOON_PATTERN = Regex("""\b(afternoon|midday|noon|with lunch)\b""")
        val EVENING_PATTERN = Regex("""\b(in the evening|every evening|each evening|evenings|at dinner|with dinner|qpm)\b""")

        val DOSAGE_FORM_PATTERN = Regex(
            """\b(one|two|three|four|five|half|\d+(?:\.\d+)?)(?:\s*(?:-|–|to)\s*(one|two|three|four|five|half|\d+(?:\.\d+)?))?\s*""" +
                """(tablets?|tabs?|pills?|capsules?|caps?|drops?|gtt|puffs?|sprays?|injections?|syringe|patches?|lozenges?|sachets?|scoops?|units?)\b""",
        )

        val DOSAGE_VOLUME_PATTERN =
            Regex("""\b(take|taking|inhale|use|drink|swallow|inject)\D{0,20}?(\d+(?:\.\d+)?)(?:\s*(?:-|–|to)\s*(\d+(?:\.\d+)?))?\s*ml\b""")

        val TRAILING_FORM_PATTERN =
            Regex("""\s*\b(tablets?|capsules?|tabs?|caps?|pills?)\b[.,;:]*\s*$""", RegexOption.IGNORE_CASE)

        val TRAILING_CONNECTOR_PATTERN =
            Regex("""\s*\b(at|by|in|with|for|of|to|and|or|po|orally|oral|mouth|take)\b[.,;:]*\s*$""")

        val NOISE_LINE_PATTERN = Regex(
            """^\s*(rx\b|#|refill|dr\.?\b|doctor|patient|name\s*:|address|phone|tel\b|date\b|directions?|sig\b|disp\b|quantity|qty|caution|warning|store\b|keep\b|manufactur|expired?|exp\.?\b|lot\b|ndc\b|pharmacy|drugstore|note\b|important|side effect|if you|call your|questions)""",
        )

        val INSTRUCTION_WORDS = listOf(
            "take ", "taking ", "twice", "three times", "four times", "once ",
            "daily", "every ", "bedtime", "before ", "after ", "as needed",
            "do not", "do not ", "warning", "store ", "keep ", "may cause", "side effect",
            "swallow", "crush", "chew",
        )

        val INSTRUCTION_PHRASES = listOf(
            "on an empty stomach",
            "empty stomach",
            "with a full glass of water",
            "with plenty of water",
            "with water",
            "with food",
            "after food",
            "with meals",
            "with each meal",
            "after meals",
            "before meals",
            "with breakfast",
            "with lunch",
            "with dinner",
            "at bedtime",
            "before bed",
            "with milk",
            "avoid alcohol",
            "do not drink alcohol",
            "avoid grapefruit",
            "avoid grapefruit juice",
            "do not crush",
            "do not chew",
            "swallow whole",
            "dissolve in water",
            "under the tongue",
        )
    }
}
