package com.mediminder.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Regression tests for the schedule crash:
 * "Expected NON-NULL 'java.util.Set<java.time.DayOfWeek>', but it was NULL".
 *
 * Saving a DAILY / EVERY_X_HOURS / AS_NEEDED schedule stores an empty string
 * for `daysOfWeek` (and AS_NEEDED for `times` too); reading it back must give
 * an empty collection, never null.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun emptyDayOfWeekSetRoundTripsAsEmptySet() {
        val stored = converters.dayOfWeekSetToString(emptySet())
        assertEquals("", stored)
        val restored = converters.stringToDayOfWeekSet(stored)
        assertTrue(restored != null)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun nullColumnReadsAsEmptyDaySet() {
        assertTrue(converters.stringToDayOfWeekSet(null).isEmpty())
    }

    @Test
    fun daySetRoundTrip() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val restored = converters.stringToDayOfWeekSet(
            converters.dayOfWeekSetToString(days),
        )
        assertEquals(days, restored)
    }

    @Test
    fun unknownDayTokensAreSkippedNotFatal() {
        val restored = converters.stringToDayOfWeekSet("MONDAY, someday, WEDNESDAY")
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), restored)
    }

    @Test
    fun emptyTimeListRoundTripsAsEmptyList() {
        val stored = converters.localTimeListToString(emptyList())
        assertEquals("", stored)
        val restored = converters.stringToLocalTimeList(stored)
        assertTrue(restored != null)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun nullColumnReadsAsEmptyTimeList() {
        assertTrue(converters.stringToLocalTimeList(null).isEmpty())
    }

    @Test
    fun timeListRoundTrip() {
        val times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 30))
        val restored = converters.stringToLocalTimeList(
            converters.localTimeListToString(times),
        )
        assertEquals(times, restored)
    }

    @Test
    fun malformedTimeTokensAreSkippedNotFatal() {
        val restored = converters.stringToLocalTimeList("08:00, not-a-time, 22:00")
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(22, 0)), restored)
    }
}
