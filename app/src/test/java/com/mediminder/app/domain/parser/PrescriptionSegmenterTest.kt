package com.mediminder.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-medication segmentation of prescription OCR text, plus the noise
 * filters (doctor names, addresses, dates must not become medicines).
 */
class PrescriptionSegmenterTest {

    private val segmenter = PrescriptionSegmenter()

    @Test
    fun twoMedicationPrescriptionIsSplitIntoTwoClusters() {
        val clusters = segmenter.segment(
            listOf(
                "City Care Hospital",
                "Rx",
                "1. Tab. Amoxicillin 500mg",
                "Sig: 1-1-1",
                "2. Tab. Paracetamol 500mg",
                "SOS",
                "Dr. A. Rahman",
                "15/03/2026",
            ),
        )
        assertEquals(2, clusters.size)
        assertEquals("Tab. Amoxicillin 500mg", clusters[0].primaryLine)
        assertTrue(clusters[0].lines.any { it.startsWith("Sig") })
        assertEquals("Tab. Paracetamol 500mg", clusters[1].primaryLine)
        assertTrue(clusters[1].lines.any { it.equals("SOS", ignoreCase = true) })
    }

    @Test
    fun doctorAndAddressNeverBecomeClusters() {
        val clusters = segmenter.segment(
            listOf(
                "Dr. Sarah Mitchell, MD",
                "1234 Elm Street, Springfield",
                "Phone: 555-0100",
            ),
        )
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun datesAreIgnored() {
        val clusters = segmenter.segment(listOf("15/03/2026", "Date: 2026-03-15"))
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun plainLabelFallsBackToSingleCluster() {
        val clusters = segmenter.segment(
            listOf("Metformin", "500 mg", "Take one tablet twice daily"),
        )
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].lines.size)
    }

    @Test
    fun strengthLinesWithoutWordsAreNotClusterStarts() {
        // The wrapped "500 mg" print column belongs to the medicine above it.
        val clusters = segmenter.segment(
            listOf("Metformin 500mg", "500 mg", "twice daily"),
        )
        assertEquals(1, clusters.size)
    }

    @Test
    fun leadingItemNumbersAreStripped() {
        val clusters = segmenter.segment(listOf("1) Amoxicillin 250mg"))
        assertEquals(1, clusters.size)
        assertEquals("Amoxicillin 250mg", clusters[0].primaryLine)
    }

    @Test
    fun slotNotationIsNotStrippedAsEnumeration() {
        val clusters = segmenter.segment(
            listOf("Amoxicillin 250mg", "1 - 0 - 1"),
        )
        assertEquals(1, clusters.size)
        assertTrue(clusters[0].lines.any { it.contains("0") })
    }

    @Test
    fun instructionLineBeforeAnyMedicineIsIgnored() {
        val clusters = segmenter.segment(
            listOf("twice daily", "Amoxicillin 250mg"),
        )
        assertEquals(1, clusters.size)
        assertEquals("Amoxicillin 250mg", clusters[0].primaryLine)
    }

    @Test
    fun repeatedLinesAreMerged() {
        val clusters = segmenter.segment(
            listOf("Amoxicillin 250mg", "Amoxicillin 250mg", "1-1-1"),
        )
        assertEquals(1, clusters.size)
    }

    @Test
    fun atMostEightMedications() {
        val lines = (1..12).map { "Tab. Medicine$it 10mg" }
        val clusters = segmenter.segment(lines)
        assertEquals(8, clusters.size)
    }

    @Test
    fun odLineIsAnInstructionForThePreviousMedicine() {
        val clusters = segmenter.segment(
            listOf("Tab. Atorvastatin 10mg", "OD", "Tab. Amlodipine 5mg", "HS"),
        )
        assertEquals(2, clusters.size)
        assertTrue(clusters[0].lines.any { it.equals("OD", ignoreCase = true) })
        assertTrue(clusters[1].lines.any { it.equals("HS", ignoreCase = true) })
    }
}
