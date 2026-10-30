package com.mediminder.app.domain.parser

import com.mediminder.app.domain.model.FieldConfidence
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.LabelField
import com.mediminder.app.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Unit tests for the OCR-to-structured-data parser, covering common label and
 * prescription formats (including the two formats named in the spec).
 */
class MedicationLabelParserTest {

    private val parser = MedicationLabelParser()

    // ----- Spec examples ---------------------------------------------------

    @Test
    fun `Take 1 tablet by mouth twice daily`() {
        val result = parser.parse("Take 1 tablet by mouth twice daily")
        assertEquals("1", result.dosageAmount)
        assertEquals("tablet", result.dosageUnit)
        assertEquals(MedicationForm.TABLET, result.form)
        val frequency = result.frequency
        assertNotNull(frequency)
        assertEquals(FrequencyType.DAILY, frequency!!.type)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), frequency.times)
    }

    @Test
    fun `500mg every 8 hours`() {
        val result = parser.parse("500mg every 8 hours")
        assertEquals("500", result.strengthValue)
        assertEquals("mg", result.strengthUnit)
        val frequency = result.frequency
        assertNotNull(frequency)
        assertEquals(FrequencyType.EVERY_X_HOURS, frequency!!.type)
        assertEquals(8, frequency.intervalHours)
        // 8h chains anchor at 06:00 so doses land 06:00/14:00/22:00.
        assertEquals(listOf(LocalTime.of(6, 0)), frequency.times)
    }

    // ----- Full pharmacy-style labels ---------------------------------------

    @Test
    fun `Metformin 500 mg take 1 tablet twice daily with food`() {
        val result = parser.parse(
            "Metformin 500 mg\nTake 1 tablet by mouth twice daily with food",
        )
        assertEquals("Metformin", result.name)
        assertEquals("500", result.strengthValue)
        assertEquals("mg", result.strengthUnit)
        assertEquals("1", result.dosageAmount)
        assertEquals("tablet", result.dosageUnit)
        assertEquals(MedicationForm.TABLET, result.form)
        assertEquals(FrequencyType.DAILY, result.frequency!!.type)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), result.frequency!!.times)
        assertEquals("With food", result.instructionText)
        assertEquals(FieldConfidence.HIGH, result.confidenceOf(LabelField.NAME))
        assertEquals(FieldConfidence.HIGH, result.confidenceOf(LabelField.STRENGTH))
        assertEquals(FieldConfidence.HIGH, result.confidenceOf(LabelField.DOSAGE))
        assertEquals(FieldConfidence.HIGH, result.confidenceOf(LabelField.FREQUENCY))
    }

    @Test
    fun `Lisinopril 10 mg once daily in the morning`() {
        val result = parser.parse("Lisinopril 10 mg once daily in the morning")
        assertEquals("Lisinopril", result.name)
        assertEquals("10", result.strengthValue)
        assertEquals(listOf(LocalTime.of(8, 0)), result.frequency!!.times)
    }

    @Test
    fun `once daily in the evening honors the evening moment`() {
        val result = parser.parse("Warfarin 5 mg, take one tablet by mouth once daily in the evening")
        assertEquals("1", result.dosageAmount)
        assertEquals(listOf(LocalTime.of(18, 0)), result.frequency!!.times)
    }

    @Test
    fun `Atorvastatin 20 mg at bedtime`() {
        val result = parser.parse("Atorvastatin 20 mg at bedtime")
        assertEquals("Atorvastatin", result.name)
        assertEquals("20", result.strengthValue)
        assertEquals(listOf(LocalTime.of(22, 0)), result.frequency!!.times)
    }

    @Test
    fun `as needed prevents scheduled reminders`() {
        val result = parser.parse("Ibuprofen 200 mg every 4-6 hours as needed for pain")
        val frequency = result.frequency!!
        assertEquals(FrequencyType.AS_NEEDED, frequency.type)
        assertTrue(frequency.asNeeded)
        assertEquals("Ibuprofen", result.name)
        assertEquals("200", result.strengthValue)
    }

    @Test
    fun `capsule form is detected`() {
        val result = parser.parse("Amoxicillin 250 mg, 1 capsule every 8 hours")
        assertEquals("1", result.dosageAmount)
        assertEquals("capsule", result.dosageUnit)
        assertEquals(MedicationForm.CAPSULE, result.form)
        assertEquals(8, result.frequency!!.intervalHours)
    }

    @Test
    fun `sig line with every morning`() {
        val result = parser.parse("Glyburide 5mg tablets\nSig: 1 tablet by mouth every morning")
        assertEquals("Glyburide", result.name)
        assertEquals(MedicationForm.TABLET, result.form)
        assertEquals(listOf(LocalTime.of(8, 0)), result.frequency!!.times)
    }

    @Test
    fun `every other day becomes a two-day interval`() {
        val result = parser.parse("Prednisone 10 mg every other day")
        assertEquals(FrequencyType.DAILY, result.frequency!!.type)
        assertEquals(2, result.frequency!!.intervalDays)
        assertEquals(listOf(LocalTime.of(8, 0)), result.frequency!!.times)
    }

    @Test
    fun `abbreviations bid tid qid`() {
        assertEquals(2, timesPerDayFrom("Metoprolol 50 mg 1 tab PO bid"))
        assertEquals(3, timesPerDayFrom("Metoprolol 50 mg 1 tab PO t.i.d."))
        assertEquals(4, timesPerDayFrom("Metoprolol 50 mg 1 tab PO qid"))
    }

    @Test
    fun `q8h abbreviation parses`() {
        val result = parser.parse("Cephalexin 500 mg 1 capsule q8h")
        assertEquals(FrequencyType.EVERY_X_HOURS, result.frequency!!.type)
        assertEquals(8, result.frequency!!.intervalHours)
    }

    @Test
    fun `liquid volume dose is not confused with bottle size`() {
        val result = parser.parse(
            "Cough Syrup 100 ml bottle\nTake 5 ml by mouth twice daily",
        )
        assertEquals("100", result.strengthValue)
        assertEquals("5", result.dosageAmount)
        assertEquals(MedicationForm.LIQUID, result.form)
    }

    @Test
    fun `dosage equal to strength is not duplicated`() {
        val result = parser.parse("Vitamin D 1000 units, take 1000 units once daily")
        // "1000 units" appears once as the dose; strength is dropped, not doubled.
        assertEquals("1000", result.dosageAmount)
        assertEquals("units", result.dosageUnit)
        assertNull(result.strengthValue)
    }

    @Test
    fun `word numbers map to digits`() {
        val result = parser.parse("Paracetamol 500 mg, take two tablets three times a day")
        assertEquals("2", result.dosageAmount)
        assertEquals("tablet", result.dosageUnit)
        assertEquals(3, result.frequency!!.times.size)
    }

    @Test
    fun `instruction phrases are deduplicated`() {
        val result = parser.parse("Omeprazole 20 mg once daily on an empty stomach")
        assertEquals("On an empty stomach", result.instructionText)
    }

    @Test
    fun `empty and garbage text yield a low-confidence empty result`() {
        val empty = parser.parse("")
        assertTrue(empty.isEmpty)

        val garbage = parser.parse("!!! 12345 ???")
        assertEquals(FieldConfidence.LOW, garbage.confidenceOf(LabelField.NAME))
        assertNull(garbage.frequency)
    }

    @Test
    fun `name without strength is medium confidence`() {
        val result = parser.parse("Aspirin\nTake 1 tablet daily")
        assertEquals("Aspirin", result.name)
        assertEquals(FieldConfidence.MEDIUM, result.confidenceOf(LabelField.NAME))
    }

    @Test
    fun `pharmacy header lines are skipped for the name`() {
        val result = parser.parse(
            "2464 Main Street Pharmacy\nTel: 555-0100\nMetformin HCL 500 mg\n1 tablet by mouth twice daily",
        )
        assertEquals("Metformin HCL", result.name)
    }

    // -----------------------------------------------------------------------

    private fun timesPerDayFrom(label: String): Int {
        val frequency = parser.parse(label).frequency ?: return 0
        return when (frequency.type) {
            FrequencyType.DAILY -> frequency.times.size
            else -> -1
        }
    }
}
