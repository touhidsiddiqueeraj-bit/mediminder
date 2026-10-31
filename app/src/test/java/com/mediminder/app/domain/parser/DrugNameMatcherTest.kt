package com.mediminder.app.domain.parser

import com.mediminder.app.domain.model.DrugNameEntry
import com.mediminder.app.domain.model.DrugNameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzy drug-name matching against a small fixed dictionary, using the noisy
 * OCR examples from the scanner specification.
 */
class DrugNameMatcherTest {

    private val matcher = DrugNameMatcher(
        listOf(
            entry("Metformin"),
            entry("Amoxicillin"),
            entry("Paracetamol"),
            entry("Atorvastatin"),
            entry("Azithromycin"),
            entry("Ibuprofen"),
            entry("Omeprazole"),
            entry("Aspirin"),
            entry("Lipitor", type = DrugNameType.BRAND, generic = "Atorvastatin"),
            entry("Glucophage", type = DrugNameType.BRAND, generic = "Metformin"),
            entry("Augmentin", type = DrugNameType.BRAND, generic = "Amoxicillin and Clavulanate Potassium"),
            entry("Metformin HCl ER"),
            entry("Vitamin B12"),
        ),
    )

    // ------------------------------------------------------------------
    // Spec examples: noisy OCR strings must match the right dictionary name
    // ------------------------------------------------------------------

    @Test
    fun zeroInsteadOfOIsExactAfterCanonicalization() {
        val matches = matcher.bestMatches("Metf0rmin")
        assertEquals("Metformin", matches.first().name)
        assertTrue(matches.first().score >= 0.85f)
    }

    @Test
    fun singleLetterTypoMatchesConfidently() {
        val matches = matcher.bestMatches("Amoxicilin")
        assertEquals("Amoxicillin", matches.first().name)
        assertTrue(matches.first().score >= 0.85f)
    }

    @Test
    fun trailingIMistakenForL() {
        val matches = matcher.bestMatches("Paracetamoi")
        assertEquals("Paracetamol", matches.first().name)
        assertTrue(matches.first().score >= 0.85f)
    }

    @Test
    fun oneInsteadOfLInsideWord() {
        val matches = matcher.bestMatches("Atorvastatln")
        assertEquals("Atorvastatin", matches.first().name)
        assertTrue(matches.first().score >= 0.85f)
    }

    @Test
    fun multiWordQueryFindsEmbeddedName() {
        // The whole OCR line is given; the name is matched as an n-gram.
        val matches = matcher.bestMatches("Tab. Atorvastatln 10mg")
        assertEquals("Atorvastatin", matches.first().name)
    }

    @Test
    fun strengthAndJunkNeverDegradeTheNameMatch() {
        val matches = matcher.bestMatches("Metf0rmin 500mg twice daily")
        assertEquals("Metformin", matches.first().name)
    }

    // ------------------------------------------------------------------
    // Brand resolution
    // ------------------------------------------------------------------

    @Test
    fun brandMatchCarriesTheGenericName() {
        val matches = matcher.bestMatches("Lipitor")
        val lipitor = matches.first { it.name == "Lipitor" }
        assertEquals(DrugNameType.BRAND, lipitor.type)
        assertEquals("Atorvastatin", lipitor.genericName)
    }

    @Test
    fun brandWithTypoStillResolves() {
        val matches = matcher.bestMatches("Lipit0r")
        assertEquals("Lipitor", matches.first().name)
    }

    // ------------------------------------------------------------------
    // Non-drug text must not match
    // ------------------------------------------------------------------

    @Test
    fun doctorNamesDoNotMatch() {
        assertTrue(matcher.bestMatches("Dr. Sarah Mitchell").all { it.score < 0.60f })
    }

    @Test
    fun addressesDoNotMatch() {
        assertTrue(matcher.bestMatches("1234 Elm Street Springfield").all { it.score < 0.60f })
    }

    @Test
    fun plainNumbersDoNotMatch() {
        assertTrue(matcher.bestMatches("15/03/2026").isEmpty())
    }

    @Test
    fun shortGarbageDoesNotMatch() {
        assertTrue(matcher.bestMatches("xx q0").all { it.score < 0.60f })
    }

    // ------------------------------------------------------------------
    // Ranking and suggestions
    // ------------------------------------------------------------------

    @Test
    fun resultsAreSortedBestFirst() {
        val matches = matcher.bestMatches("amox")
        assertTrue(matches.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun prefixSuggestionsFindWordsWhileTyping() {
        val suggestions = matcher.prefixSuggestions("amox")
        assertTrue(suggestions.any { it.name == "Amoxicillin" })
    }

    @Test
    fun prefixSuggestionsNeedAtLeastTwoCharacters() {
        assertTrue(matcher.prefixSuggestions("a").isEmpty())
    }

    @Test
    fun digitsInNamesStillMatchExactly() {
        val matches = matcher.bestMatches("Vitamin B12")
        assertEquals("Vitamin B12", matches.first().name)
        assertTrue(matches.first().score >= 0.99f)
    }
}

private fun entry(
    name: String,
    type: DrugNameType = DrugNameType.GENERIC,
    generic: String? = null,
): DrugNameEntry = DrugNameEntry(
    name = name,
    type = type,
    genericName = generic,
    commonStrengths = emptyList(),
)
