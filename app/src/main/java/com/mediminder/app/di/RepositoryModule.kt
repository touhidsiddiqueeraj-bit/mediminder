package com.mediminder.app.di

import com.mediminder.app.data.repository.DoseLogRepositoryImpl
import com.mediminder.app.data.repository.MedicationRepositoryImpl
import com.mediminder.app.data.repository.SettingsRepositoryImpl
import com.mediminder.app.data.report.PdfReportGenerator
import com.mediminder.app.data.scanner.LocalDrugNameIndex
import com.mediminder.app.data.scanner.MlKitOcrProvider
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.service.AdherenceReportGenerator
import com.mediminder.app.domain.service.DoseEventListener
import com.mediminder.app.domain.service.DrugNameIndex
import com.mediminder.app.domain.service.NoopDoseEventListener
import com.mediminder.app.domain.service.OcrProvider
import com.mediminder.app.domain.service.ReminderScheduler
import com.mediminder.app.scheduling.ReminderPlanner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMedicationRepository(impl: MedicationRepositoryImpl): MedicationRepository

    @Binds
    @Singleton
    abstract fun bindDoseLogRepository(impl: DoseLogRepositoryImpl): DoseLogRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** Offline drug-name dictionary (asset -> Room -> in-memory fuzzy index). */
    @Binds
    @Singleton
    abstract fun bindDrugNameIndex(impl: LocalDrugNameIndex): DrugNameIndex

    @Binds
    @Singleton
    abstract fun bindReminderScheduler(impl: ReminderPlanner): ReminderScheduler

    /** Phase-2 caregiver support: swap this binding for an SMS/push listener. */
    @Binds
    @Singleton
    abstract fun bindDoseEventListener(impl: NoopDoseEventListener): DoseEventListener

    /** On-device OCR with ML Kit (bundled, Latin) behind a testable interface. */
    @Binds
    @Singleton
    abstract fun bindOcrProvider(impl: MlKitOcrProvider): OcrProvider

    @Binds
    @Singleton
    abstract fun bindAdherenceReportGenerator(impl: PdfReportGenerator): AdherenceReportGenerator
}
