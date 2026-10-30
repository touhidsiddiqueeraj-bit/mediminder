package com.mediminder.app.domain.engine

import com.mediminder.app.domain.model.FrequencyType
import com.mediminder.app.domain.model.LowStockInfo
import com.mediminder.app.domain.model.Medication
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estimates how many days of supply are left and warns early enough to refill.
 *
 * Simplification (documented in README): one "pill" equals one dose, and the
 * estimate uses the busiest day of the week — better to warn a little early
 * than too late.
 */
@Singleton
class LowStockCalculator @Inject constructor() {

    /** Warn when fewer than [thresholdDays] days of supply remain. */
    var thresholdDays: Int = 3

    fun lowStockMedications(medications: List<Medication>): List<LowStockInfo> =
        medications.mapNotNull { medication ->
            val pills = medication.pillsRemaining ?: return@mapNotNull null
            if (!medication.isActive || pills <= 0) return@mapNotNull null
            val dailyDoses = dailyDoseCount(medication)
            if (dailyDoses <= 0) return@mapNotNull null
            val daysRemaining = pills / dailyDoses
            if (daysRemaining <= thresholdDays) {
                LowStockInfo(
                    medicationId = medication.id,
                    medicationName = medication.name,
                    daysRemaining = daysRemaining,
                )
            } else {
                null
            }
        }

    /** Maximum number of doses on any single day (conservative). */
    fun dailyDoseCount(medication: Medication): Int = medication.schedules
        .filter { it.type != FrequencyType.AS_NEEDED }
        .sumOf { schedule ->
            when (schedule.type) {
                FrequencyType.EVERY_X_HOURS -> {
                    val interval = schedule.intervalHours ?: 24
                    if (interval <= 0) 1 else (24 + interval - 1) / interval
                }
                else -> schedule.times.size
            }
        }
}
