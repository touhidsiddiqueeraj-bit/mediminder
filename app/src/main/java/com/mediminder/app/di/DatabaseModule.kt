package com.mediminder.app.di

import android.content.Context
import androidx.room.Room
import com.mediminder.app.data.local.MediMinderDatabase
import com.mediminder.app.data.local.MedicationDao
import com.mediminder.app.data.local.DoseLogDao
import com.mediminder.app.data.local.ScheduleDao
import com.mediminder.app.data.local.SecurePassphraseProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

/**
 * Room database encrypted at rest with SQLCipher. The passphrase is 32 random
 * bytes, encrypted with an AES/GCM key that lives in the Android Keystore.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: SecurePassphraseProvider,
    ): MediMinderDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportFactory(passphraseProvider.getOrCreate())
        return Room.databaseBuilder(context, MediMinderDatabase::class.java, MediMinderDatabase.NAME)
            .openHelperFactory(factory)
            .build()
    }

    @Provides
    fun provideMedicationDao(database: MediMinderDatabase): MedicationDao =
        database.medicationDao()

    @Provides
    fun provideScheduleDao(database: MediMinderDatabase): ScheduleDao =
        database.scheduleDao()

    @Provides
    fun provideDoseLogDao(database: MediMinderDatabase): DoseLogDao =
        database.doseLogDao()
}
