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

    @TypeConverter
    fun stringToDayOfWeekSet(value: String?): Set<DayOfWeek>? =
        value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { DayOfWeek.valueOf(it.trim()) }
            ?.toSet()

    @TypeConverter
    fun localTimeListToString(value: List<LocalTime>?): String? =
        value?.joinToString(",") { it.toString() }

    @TypeConverter
    fun stringToLocalTimeList(value: String?): List<LocalTime>? =
        value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { LocalTime.parse(it.trim()) }
}
