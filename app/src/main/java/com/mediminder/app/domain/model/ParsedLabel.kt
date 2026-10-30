package com.mediminder.app.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

/** Which part of the extraction an entry belongs to (used for confidence). */
enum class LabelField {
    NAME,
    STRENGTH,
    DOSAGE,
    FORM,
    FREQUENCY,
    INSTRUCTIONS,
}

/** How sure the parser is about a field; low-confidence fields are highlighted. */
enum class FieldConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/** Frequency extracted from label text, before the user confirms it. */
data class ParsedFrequency(
    val type: FrequencyType,
    val times: List<LocalTime> = emptyList(),
    val intervalHours: Int? = null,
    val intervalDays: Int? = null,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val asNeeded: Boolean = false,
)

/**
 * Result of parsing OCR text from a medicine label. Every field may be null —
 * the user always confirms (and can correct) before anything is saved.
 */
data class ParsedLabel(
    val rawText: String,
    val name: String? = null,
    val strengthValue: String? = null,
    val strengthUnit: String? = null,
    /** "1", "2", "1-2" … the number of units per dose. */
    val dosageAmount: String? = null,
    /** "tablet", "capsule", "mL", "drops" … */
    val dosageUnit: String? = null,
    val form: MedicationForm? = null,
    val frequency: ParsedFrequency? = null,
    /** Human-readable instruction snippets ("with food", "at bedtime" …). */
    val instructionText: String? = null,
    val confidence: Map<LabelField, FieldConfidence> = emptyMap(),
) {
    val strengthText: String?
        get() = if (strengthValue.isNullOrBlank()) null else "$strengthValue $strengthUnit".trim()

    fun confidenceOf(field: LabelField): FieldConfidence = confidence[field] ?: FieldConfidence.LOW

    /** True when nothing meaningful could be extracted and we should fall back to the manual form. */
    val isEmpty: Boolean
        get() = name == null && strengthValue == null && dosageAmount == null && frequency == null
}
