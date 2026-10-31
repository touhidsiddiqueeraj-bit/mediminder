package com.mediminder.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DrugNameDao {

    @Query("SELECT COUNT(*) FROM drug_names")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(entries: List<DrugNameEntity>)

    @Query("SELECT * FROM drug_names")
    suspend fun getAll(): List<DrugNameEntity>
}
