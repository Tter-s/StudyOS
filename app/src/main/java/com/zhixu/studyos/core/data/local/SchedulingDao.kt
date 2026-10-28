package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SchedulingDao {
    @Query("SELECT * FROM scheduling_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<SchedulingSettingsEntity?>

    @Query("SELECT * FROM scheduling_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): SchedulingSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: SchedulingSettingsEntity)

    @Query("SELECT * FROM scheduling_subject_preferences ORDER BY subjectName")
    fun observeSubjectPreferences(): Flow<List<SchedulingSubjectPreferenceEntity>>

    @Query("SELECT * FROM scheduling_subject_preferences ORDER BY subjectName")
    suspend fun getSubjectPreferences(): List<SchedulingSubjectPreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubjectPreferences(preferences: List<SchedulingSubjectPreferenceEntity>)

    @Query("DELETE FROM scheduling_subject_preferences")
    suspend fun deleteAllSubjectPreferences()
}
