package com.mediminder.app.di

import com.mediminder.app.data.repository.DoseLogRepositoryImpl
import com.mediminder.app.data.repository.DrugNameRepositoryImpl
import com.mediminder.app.data.repository.MedicationRepositoryImpl
import com.mediminder.app.data.repository.SettingsRepositoryImpl
import com.mediminder.app.data.report.PdfReportGenerator
import com.mediminder.app.data.scanner.MlKitLabelScanner
import com.mediminder.app.domain.repository.DoseLogRepository
import com.mediminder.app.domain.repository.DrugNameRepository
import com.mediminder.app.domain.repository.MedicationRepository
import com.mediminder.app.domain.repository.SettingsRepository
import com.mediminder.app.domain.service.AdherenceReportGenerator
import com.mediminder.app.domain.service.DoseEventListener
import com.mediminder.app.domain.service.LabelScanner
import com.mediminder.app.domain.service.NoopDoseEventListener
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

    @Binds
    @Singleton
    abstract fun bindDrugNameRepository(impl: DrugNameRepositoryImpl): DrugNameRepository

    @Binds
    @Singleton
    abstract fun bindReminderScheduler(impl: ReminderPlanner): ReminderScheduler

    /** Phase-2 caregiver support: swap this binding for an SMS/push listener. */
    @Binds
    @Singleton
    abstract fun bindDoseEventListener(impl: NoopDoseEventListener): DoseEventListener

    @Binds
    @Singleton
    abstract fun bindLabelScanner(impl: MlKitLabelScanner): LabelScanner

    @Binds
    @Singleton
    abstract fun bindAdherenceReportGenerator(impl: PdfReportGenerator): AdherenceReportGenerator
}
