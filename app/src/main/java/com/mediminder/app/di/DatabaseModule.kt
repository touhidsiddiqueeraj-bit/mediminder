package com.mediminder.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mediminder.app.data.local.MediMinderDatabase
import com.mediminder.app.data.local.MedicationDao
import com.mediminder.app.data.local.DoseLogDao
import com.mediminder.app.data.local.DrugNameDao
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

    /**
     * v1 -> v2: adds the offline drug-name dictionary table used to correct
     * OCR'd medicine names. User data is untouched.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `drug_names` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`genericName` TEXT, " +
                    "`commonStrengths` TEXT)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_drug_names_name` ON `drug_names` (`name`)",
            )
        }
    }

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
            .addMigrations(MIGRATION_1_2)
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

    @Provides
    fun provideDrugNameDao(database: MediMinderDatabase): DrugNameDao =
        database.drugNameDao()
}
