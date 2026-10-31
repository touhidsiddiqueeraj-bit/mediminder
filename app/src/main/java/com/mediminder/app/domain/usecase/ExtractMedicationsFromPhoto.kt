package com.mediminder.app.domain.usecase

import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.model.FieldConfidence
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.LabelField
import com.mediminder.app.domain.model.MedicationForm
import com.mediminder.app.domain.model.ParsedFrequency
import com.mediminder.app.domain.model.ParsedLabel
import com.mediminder.app.domain.model.ScoredDrugName
import com.mediminder.app.domain.parser.LabelDefaults
import com.mediminder.app.domain.parser.MedicationLabelParser
import com.mediminder.app.domain.parser.PrescriptionSegmenter
import com.mediminder.app.domain.service.DrugNameIndex
import com.mediminder.app.domain.service.OcrProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** One medication found on the photo, ready for the user to confirm. */
data class ExtractedMedication(
    /** Pre-fill fields; [ParsedLabel.name] is the dictionary-resolved name. */
    val label: ParsedLabel,
    /** Dictionary score of the resolved name: 1 = exact, 0 = no match. */
    val nameScore: Float,
    /** The name exactly as the OCR read it, for the "please double-check" banner. */
    val rawNameText: String,
    /** Top dictionary alternatives for the name. */
    val candidates: List<ScoredDrugName>,
)

data class ExtractionResult(
    /** Medication candidates in document order (top of the photo first). */
    val items: List<ExtractedMedication>,
    /** Pre-processing corrections that were applied to the photo (diagnostics). */
    val ocrCorrections: List<String> = emptyList(),
    /** False when the OCR could not read any text at all. */
    val ocrSawText: Boolean = true,
)

/**
 * Photo -> on-device OCR -> one medication candidate per line cluster ->
 * dictionary fuzzy-matching with confidence scores.
 *
 * Prescriptions often contain several medications; every extracted item is
 * shown to the user one by one, every field is editable, and nothing is
 * saved or scheduled until the user explicitly confirms.
 *
 * Name resolution bands (from the offline dictionary):
 *  - score >= 0.85: pre-filled with the dictionary name, marked HIGH.
 *  - score >= 0.60: pre-filled but highlighted "please double-check",
 *    with the top candidates offered as one-tap chips.
 *  - score <  0.60: the raw OCR text is kept, marked LOW, suggestions shown.
 */
@Singleton
class ExtractMedicationsFromPhoto @Inject constructor(
    private val ocrProvider: OcrProvider,
    private val parser: MedicationLabelParser,
    private val drugNameIndex: DrugNameIndex,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(photoUri: String): ExtractionResult = withContext(ioDispatcher) {
        val ocr = ocrProvider.recognize(photoUri)
        if (!ocr.hasText) {
            return@withContext ExtractionResult(
                items = emptyList(),
                ocrCorrections = ocr.corrections,
                ocrSawText = false,
            )
        }

        val segmenter = PrescriptionSegmenter()
        val clusters = segmenter.segment(ocr.lines.map { it.text })
        val items = clusters
            .mapNotNull { cluster -> extractCluster(cluster) }
            .let(::dedupe)
        ExtractionResult(items = items, ocrCorrections = ocr.corrections, ocrSawText = true)
    }

    // -----------------------------------------------------------------
    // Per-cluster extraction
    // -----------------------------------------------------------------

    private suspend fun extractCluster(
        cluster: PrescriptionSegmenter.Cluster,
    ): ExtractedMedication? {
        val text = cluster.lines.joinToString("\n")
        var parsed = parser.parse(text)
        parsed = applyFrequencyShorthand(parsed, text)
        parsed = applyFormCue(parsed, text)

        val rawNameText = (parsed.name ?: cleanNameText(cluster.primaryLine)).trim()
        if (rawNameText.isEmpty() && parsed.isEmpty) return null

        val matches = if (rawNameText.isEmpty()) {
            emptyList()
        } else {
            drugNameIndex.bestMatches(rawNameText, maxResults = MAX_CANDIDATES)
        }
        val best = matches.firstOrNull()

        val name: String?
        val genericName: String?
        val score: Float
        when {
            best != null && best.score >= AUTO_NAME_SCORE -> {
                name = best.name
                genericName = best.genericName
                score = best.score
            }
            best != null && best.score >= REVIEW_NAME_SCORE -> {
                name = best.name
                genericName = best.genericName
                score = best.score
            }
            else -> {
                name = rawNameText.takeIf { it.isNotEmpty() }
                genericName = parsed.genericName
                score = best?.score ?: 0f
            }
        }

        val confidence = parsed.confidence.toMutableMap()
        confidence[LabelField.NAME] = when {
            name.isNullOrBlank() -> FieldConfidence.LOW
            score >= AUTO_NAME_SCORE -> FieldConfidence.HIGH
            score >= REVIEW_NAME_SCORE -> FieldConfidence.MEDIUM
            else -> FieldConfidence.LOW
        }

        val label = parsed.copy(
            name = name ?: rawNameText.takeIf { it.isNotEmpty() },
            genericName = genericName,
            confidence = confidence,
        )
        return ExtractedMedication(
            label = label,
            nameScore = score,
            rawNameText = rawNameText,
            candidates = matches,
        )
    }

    /** Drops repeats of the same medicine + strength from double OCR regions. */
    private fun dedupe(items: List<ExtractedMedication>): List<ExtractedMedication> {
        val seen = HashSet<String>()
        return items.filter { item ->
            val key = (item.label.name ?: item.rawNameText).lowercase() +
                "|" + (item.label.strengthValue ?: "") +
                "|" + (item.label.strengthUnit ?: "")
            seen.add(key)
        }
    }

    // -----------------------------------------------------------------
    // Overlays on top of the label parser
    // -----------------------------------------------------------------

    /**
     * Prescription shorthand the plain label parser does not know:
     * OD/QD = once daily, BD/BID = twice, TDS/TID = three times, QID = four,
     * SOS/PRN = as needed, HS = at bedtime, and slot notation "1-0-1".
     */
    private fun applyFrequencyShorthand(parsed: ParsedLabel, text: String): ParsedLabel {
        if (parsed.frequency != null) return parsed
        val shorthand = detectFrequencyShorthand(text) ?: return parsed
        return parsed.copy(
            frequency = shorthand,
            confidence = parsed.confidence +
                (LabelField.FREQUENCY to FieldConfidence.MEDIUM),
        )
    }

    private fun detectFrequencyShorthand(text: String): ParsedFrequency? {
        val lower = text.lowercase()

        if (AS_NEEDED_SHORTHAND.containsMatchIn(lower)) {
            return ParsedFrequency(type = FrequencyType.AS_NEEDED, asNeeded = true)
        }

        // Slot notation: "1-0-1" = morning and evening, "1-1-1" = three times.
        SLOT_PATTERN.find(lower)?.let { match ->
            val slots = match.value.split("-").mapNotNull { it.trim().toIntOrNull() }
            if (slots.isNotEmpty() && slots.any { it > 0 }) {
                val times = slots.withIndex()
                    .filter { (_, value) -> value > 0 }
                    .mapNotNull { (index, _) -> slotTime(index) }
                if (times.isNotEmpty()) {
                    return ParsedFrequency(type = FrequencyType.DAILY, times = times)
                }
            }
        }

        if (BEDTIME_SHORTHAND.containsMatchIn(lower)) {
            return ParsedFrequency(type = FrequencyType.DAILY, times = listOf(LabelDefaults.BEDTIME))
        }
        when {
            QID_SHORTHAND.containsMatchIn(lower) ->
                return ParsedFrequency(
                    type = FrequencyType.DAILY,
                    times = LabelDefaults.timesPerDay(4),
                )
            TDS_SHORTHAND.containsMatchIn(lower) ->
                return ParsedFrequency(
                    type = FrequencyType.DAILY,
                    times = LabelDefaults.timesPerDay(3),
                )
            BD_SHORTHAND.containsMatchIn(lower) ->
                return ParsedFrequency(
                    type = FrequencyType.DAILY,
                    times = LabelDefaults.timesPerDay(2),
                )
            OD_SHORTHAND.containsMatchIn(lower) ->
                return ParsedFrequency(
                    type = FrequencyType.DAILY,
                    times = listOf(LabelDefaults.MORNING),
                )
        }
        return null
    }

    private fun slotTime(index: Int): LocalTime? = when (index) {
        0 -> LabelDefaults.MORNING
        1 -> LabelDefaults.AFTERNOON
        2 -> LabelDefaults.EVENING
        3 -> LabelDefaults.BEDTIME
        else -> null
    }

    /** "Tab. Atenolol 50mg" -> form TABLET even without a "1 tablet" dosage. */
    private fun applyFormCue(parsed: ParsedLabel, text: String): ParsedLabel {
        if (parsed.form != null) return parsed
        val lower = text.lowercase()
        val form = when {
            FORM_TABLET_CUE.containsMatchIn(lower) -> MedicationForm.TABLET
            FORM_CAPSULE_CUE.containsMatchIn(lower) -> MedicationForm.CAPSULE
            FORM_LIQUID_CUE.containsMatchIn(lower) -> MedicationForm.LIQUID
            FORM_DROPS_CUE.containsMatchIn(lower) -> MedicationForm.DROPS
            FORM_INJECTION_CUE.containsMatchIn(lower) -> MedicationForm.INJECTION
            else -> null
        } ?: return parsed
        return parsed.copy(
            form = form,
            confidence = parsed.confidence + (LabelField.FORM to FieldConfidence.MEDIUM),
        )
    }

    /**
     * Fallback name text when the parser found none: the primary line with
     * form cues and strengths stripped ("Tab. Metformin 500mg" -> "Metformin").
     */
    private fun cleanNameText(line: String): String {
        var name = line.trim()
        // Strip strengths ("Metformin 500 mg" -> "Metformin")
        STRENGTH_PATTERN.findAll(name).forEach { match ->
            name = name.replaceRange(match.range, " ")
        }
        // Strip leading form cue words ("Tab. Metformin" -> "Metformin")
        while (true) {
            val stripped = LEADING_FORM_CUE.replace(name, "")
            if (stripped == name) break
            name = stripped
        }
        return name.replace(Regex("\\s{2,}"), " ").trim(' ', '\t', ',', '.', '-', '–', ';', ':')
    }

    private companion object {
        const val MAX_CANDIDATES = 3
        const val AUTO_NAME_SCORE = 0.85f
        const val REVIEW_NAME_SCORE = 0.60f

        val STRENGTH_PATTERN = Regex(
            """\d+(?:[.,]\d+)?\s*(?:mg|mcg|gm|g|gram|grams|ml|iu|units?)\b""",
            RegexOption.IGNORE_CASE,
        )

        val LEADING_FORM_CUE = Regex(
            """^\s*(?:tab|tabs|tab\.|tablet|tablets|cap|caps|cap\.|capsule|capsules|""" +
                """syr|syp|syrup|susp|suspension|inj|injection|drops|soln|solution|""" +
                """oint|ointment|cream|gel|spray|inhaler|sachet|supp|suppository|""" +
                """suppositories|patch|liq|liquid)\.?,?\s*""",
            RegexOption.IGNORE_CASE,
        )

        val AS_NEEDED_SHORTHAND = Regex("""\b(sos|prn|as required|as needed|when needed)\b""")
        val BEDTIME_SHORTHAND = Regex("""\b(hs|qhs|at bedtime|bedtime)\b""")
        val OD_SHORTHAND = Regex("""\b(od|qd)\b""")
        val BD_SHORTHAND = Regex("""\b(bd|bid)\b""")
        val TDS_SHORTHAND = Regex("""\b(tds|tid)\b""")
        val QID_SHORTHAND = Regex("""\b(qid)\b""")
        val SLOT_PATTERN = Regex("""\b[0-3](?:\s*-\s*[0-3]){1,3}\b""")

        val FORM_TABLET_CUE =
            Regex("""\b(tab|tabs|tablet|tablets|caplet|caplets)\b""")
        val FORM_CAPSULE_CUE =
            Regex("""\b(cap|caps|capsule|capsules)\b""")
        val FORM_LIQUID_CUE =
            Regex("""\b(syr|syp|syrup|susp|suspension|soln|solution|liq|liquid)\b""")
        val FORM_DROPS_CUE = Regex("""\b(drops?|gtt)\b""")
        val FORM_INJECTION_CUE = Regex("""\b(inj|injection|injections|amp|ampoule)\b""")
    }
}
