package com.mediminder.app.data.local

import androidx.room.TypeConverter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** java.time <-> ISO-8601 text; sets/lists are comma-joined names. */
class Converters {

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun localDateTimeToString(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)

    @TypeConverter
    fun localTimeToString(value: LocalTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)

    @TypeConverter
    fun dayOfWeekSetToString(value: Set<DayOfWeek>?): String? =
        value?.joinToString(",") { it.name }

    /**
     * Never returns null: the schedule entity declares `daysOfWeek` as a
     * non-null set (schedules like EVERY_DAY or AS_NEEDED have none). A null
     * result would crash Room's relation mapping with
     * "Expected NON-NULL 'java.util.Set<java.time.DayOfWeek>'".
     */
    @TypeConverter
    fun stringToDayOfWeekSet(value: String?): Set<DayOfWeek> =
        value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { token ->
                runCatching { DayOfWeek.valueOf(token.trim()) }.getOrNull()
            }
            ?.toSet()
            ?: emptySet()

    @TypeConverter
    fun localTimeListToString(value: List<LocalTime>?): String? =
        value?.joinToString(",") { it.toString() }

    /** Never returns null — see [stringToDayOfWeekSet]. */
    @TypeConverter
    fun stringToLocalTimeList(value: String?): List<LocalTime> =
        value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { token ->
                runCatching { LocalTime.parse(token.trim()) }.getOrNull()
            }
            ?: emptyList()
}
