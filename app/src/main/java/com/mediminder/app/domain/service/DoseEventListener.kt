package com.mediminder.app.domain.service

import com.mediminder.app.domain.model.DoseLog
import com.mediminder.app.domain.model.Medication
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extension point for caregiver support (phase 2).
 *
 * The app already calls this listener whenever a dose is taken or missed.
 * A future "notify my family" feature only needs a new implementation (SMS
 * gateway / push) bound in the DI module — no other layer changes.
 */
interface DoseEventListener {

    suspend fun onDoseTaken(dose: DoseLog, medication: Medication)

    suspend fun onDoseMissed(dose: DoseLog, medication: Medication)
}

/** Default no-op implementation: no caregiver feature is enabled yet. */
class NoopDoseEventListener @Inject constructor() : DoseEventListener {
    override suspend fun onDoseTaken(dose: DoseLog, medication: Medication) = Unit
    override suspend fun onDoseMissed(dose: DoseLog, medication: Medication) = Unit
}
