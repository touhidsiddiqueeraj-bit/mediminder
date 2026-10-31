package com.mediminder.app.domain.usecase

import com.mediminder.app.domain.model.DrugNameEntry
import com.mediminder.app.domain.model.DrugNameType
import com.mediminder.app.domain.model.FieldConfidence
import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.LabelField
import com.mediminder.app.domain.model.MedicationForm
import com.mediminder.app.domain.parser.MedicationLabelParser
import com.mediminder.app.domain.service.OcrLine
import com.mediminder.app.domain.service.OcrProvider
import com.mediminder.app.domain.service.OcrResult
import com.mediminder.app.domain.service.drugNameIndexOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * End-to-end extraction from OCR lines with a fake OCR provider and a small
 * fixed dictionary: one photo -> several medication candidates, each with a
 * dictionary-resolved name, strength, form and schedule.
 */
class ExtractMedicationsTest {

    private val dictionary = drugNameIndexOf(
        listOf(
            DrugNameEntry("Metformin", DrugNameType.GENERIC),
            DrugNameEntry("Amoxicillin", DrugNameType.GENERIC, commonStrengths = listOf("250 mg", "500 mg")),
            DrugNameEntry("Paracetamol", DrugNameType.GENERIC),
            DrugNameEntry("Atorvastatin", DrugNameType.GENERIC),
            DrugNameEntry("Amlodipine", DrugNameType.GENERIC),
            DrugNameEntry("Lipitor", DrugNameType.BRAND, genericName = "Atorvastatin"),
        ),
    )

    private fun extractor(lines: List<String>): ExtractMedicationsFromPhoto =
        ExtractMedicationsFromPhoto(
            ocrProvider = FakeOcrProvider(OcrResult(lines = lines.map { OcrLine(text = it) })),
            parser = MedicationLabelParser(),
            drugNameIndex = dictionary,
            ioDispatcher = Dispatchers.Unconfined,
        )

    // ------------------------------------------------------------------
    // Spec: noisy OCR strings
    // ------------------------------------------------------------------

    @Test
    fun metf0rmin500mgIsResolvedWithHighConfidence() = runTest {
        val result = extractor(listOf("Metf0rmin 500mg")).invoke("ignored")
        val item = result.items.single()
        assertEquals("Metformin", item.label.name)
        assertEquals("500", item.label.strengthValue)
        assertEquals("mg", item.label.strengthUnit)
        assertTrue(item.nameScore >= 0.85f)
        assertEquals(FieldConfidence.HIGH, item.label.confidence[LabelField.NAME])
        // The raw OCR name is kept so the UI can show "we read / did you mean".
        assertEquals("Metf0rmin", item.rawNameText)
    }

    @Test
    fun amoxicilin250mgIsResolved() = runTest {
        val item = extractor(listOf("Amoxicilin 250 mg")).invoke("ignored").items.single()
        assertEquals("Amoxicillin", item.label.name)
        assertEquals("250", item.label.strengthValue)
        assertTrue(item.nameScore >= 0.85f)
    }

    @Test
    fun paracetamoiWithoutUnitMatchesNameButNotStrength() = runTest {
        val item = extractor(listOf("Paracetamoi 500")).invoke("ignored").items.single()
        assertEquals("Paracetamol", item.label.name)
        assertTrue(item.nameScore >= 0.85f)
        // "500" without a unit is never guessed as a strength.
        assertEquals(null, item.label.strengthValue)
    }

    @Test
    fun tabAtorvastatln10mgOdIsFullyResolved() = runTest {
        val item = extractor(listOf("Tab. Atorvastatln 10mg OD")).invoke("ignored").items.single()
        assertEquals("Atorvastatin", item.label.name)
        assertEquals("10", item.label.strengthValue)
        assertEquals(MedicationForm.TABLET, item.label.form)
        assertEquals(FieldConfidence.MEDIUM, item.label.confidence[LabelField.FORM])
        val frequency = item.label.frequency
        assertEquals(FrequencyType.DAILY, frequency?.type)
        assertEquals(listOf(LocalTime.of(8, 0)), frequency?.times)
    }

    // ------------------------------------------------------------------
    // Multiple medications from a single photo
    // ------------------------------------------------------------------

    @Test
    fun prescriptionWithTwoMedicinesYieldsTwoItems() = runTest {
        val result = extractor(
            listOf(
                "Rx",
                "1. Tab. Amoxicillin 500mg",
                "Sig: 1-1-1",
                "2. Tab. Paracetamol 500mg",
                "SOS",
                "Dr. A. Rahman",
                "15/03/2026",
            ),
        ).invoke("ignored")
        assertEquals(2, result.items.size)
        assertEquals("Amoxicillin", result.items[0].label.name)
        assertEquals("Paracetamol", result.items[1].label.name)
    }

    @Test
    fun slotNotationMapsToMorningAndEvening() = runTest {
        val result = extractor(
            listOf("Tab. Amoxicilin 250mg", "1-0-1"),
        ).invoke("ignored")
        val frequency = result.items.single().label.frequency
        assertEquals(FrequencyType.DAILY, frequency?.type)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), frequency?.times)
    }

    @Test
    fun sosMeansAsNeeded() = runTest {
        val result = extractor(
            listOf("Tab. Paracetamol 500mg", "SOS"),
        ).invoke("ignored")
        assertEquals(FrequencyType.AS_NEEDED, result.items.single().label.frequency?.type)
    }

    @Test
    fun repeatedMedicineLinesCollapseIntoOneItem() = runTest {
        val result = extractor(
            listOf("Amoxicillin 250mg", "Amoxicillin 250mg"),
        ).invoke("ignored")
        assertEquals(1, result.items.size)
    }

    // ------------------------------------------------------------------
    // Noise on the photo must not become medicines
    // ------------------------------------------------------------------

    @Test
    fun doctorAddressAndDateOnlyYieldNothing() = runTest {
        val result = extractor(
            listOf(
                "Dr. Sarah Mitchell, MD",
                "1234 Elm Street, Springfield",
                "Date: 15/03/2026",
                "Refill 3",
            ),
        ).invoke("ignored")
        assertTrue(result.items.isEmpty())
        assertTrue(result.ocrSawText)
    }

    @Test
    fun unreadablePhotoReportsNoText() = runTest {
        val result = ExtractMedicationsFromPhoto(
            ocrProvider = FakeOcrProvider(OcrResult(lines = emptyList())),
            parser = MedicationLabelParser(),
            drugNameIndex = dictionary,
            ioDispatcher = Dispatchers.Unconfined,
        ).invoke("ignored")
        assertTrue(!result.ocrSawText)
        assertTrue(result.items.isEmpty())
    }

    // ------------------------------------------------------------------
    // Frequency wording maps onto the existing schedule model
    // ------------------------------------------------------------------

    @Test
    fun twiceDailyBecomesTwoDailyTimes() = runTest {
        val item = extractor(
            listOf("Metformin 500mg", "Take 1 tablet twice daily"),
        ).invoke("ignored").items.single()
        val frequency = item.label.frequency
        assertEquals(FrequencyType.DAILY, frequency?.type)
        assertEquals(2, frequency?.times?.size)
    }

    @Test
    fun every8HoursBecomesIntervalSchedule() = runTest {
        val item = extractor(
            listOf("Amoxicilin 250 mg every 8 hours"),
        ).invoke("ignored").items.single()
        val frequency = item.label.frequency
        assertEquals(FrequencyType.EVERY_X_HOURS, frequency?.type)
        assertEquals(8, frequency?.intervalHours)
    }

    @Test
    fun afterMealsBecomesThreeMealTimes() = runTest {
        val item = extractor(
            listOf("Metformin 500mg after meals"),
        ).invoke("ignored").items.single()
        assertEquals(3, item.label.frequency?.times?.size)
    }

    @Test
    fun atBedtimeBecomesTenPmDaily() = runTest {
        val item = extractor(
            listOf("Atorvastatln 10mg at bedtime"),
        ).invoke("ignored").items.single()
        assertEquals(listOf(LocalTime.of(22, 0)), item.label.frequency?.times)
    }

    // ------------------------------------------------------------------
    // Weak matches keep the raw text and ask the user
    // ------------------------------------------------------------------

    @Test
    fun unknownNameKeepsRawTextWithLowConfidence() = runTest {
        val item = extractor(listOf("Zyzzyxum 500mg")).invoke("ignored").items.single()
        assertEquals("Zyzzyxum", item.label.name)
        assertTrue(item.nameScore < 0.60f)
        assertEquals(FieldConfidence.LOW, item.label.confidence[LabelField.NAME])
    }

    @Test
    fun brandMatchCarriesGenericName() = runTest {
        val item = extractor(listOf("Lipitor 10mg")).invoke("ignored").items.single()
        assertEquals("Lipitor", item.label.name)
        assertEquals("Atorvastatin", item.label.genericName)
        assertTrue(item.nameScore >= 0.85f)
    }

    // ------------------------------------------------------------------
    // Single plain label keeps the old behaviour
    // ------------------------------------------------------------------

    @Test
    fun plainSingleLabelStillWorks() = runTest {
        val item = extractor(
            listOf("Metformin", "500 mg", "twice daily"),
        ).invoke("ignored").items.single()
        assertEquals("Metformin", item.label.name)
        assertEquals(2, item.label.frequency?.times?.size)
    }

    private class FakeOcrProvider(private val result: OcrResult) : OcrProvider {
        override suspend fun recognize(photoUri: String): OcrResult = result
    }
}
