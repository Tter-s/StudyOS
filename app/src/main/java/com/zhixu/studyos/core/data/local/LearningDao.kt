package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningDao {
    @Query("SELECT * FROM subjects ORDER BY isPreset DESC, id, name")
    fun observeSubjects(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects ORDER BY isPreset DESC, id, name")
    suspend fun getSubjects(): List<SubjectEntity>

    @Query("SELECT * FROM subjects WHERE id = :id LIMIT 1")
    suspend fun getSubject(id: Long): SubjectEntity?

    @Query("SELECT * FROM subjects WHERE name = :name LIMIT 1")
    suspend fun getSubjectByName(name: String): SubjectEntity?

    @Insert
    suspend fun insertSubject(subject: SubjectEntity): Long

    @Query("SELECT * FROM study_timing_sessions ORDER BY startedAtEpochMillis DESC, id DESC")
    fun observeTimingSessions(): Flow<List<TimingSessionEntity>>

    @Query("SELECT * FROM study_timing_sessions ORDER BY startedAtEpochMillis DESC, id DESC")
    suspend fun getTimingSessions(): List<TimingSessionEntity>

    @Query(
        "SELECT * FROM study_timing_sessions WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY startedAtEpochMillis DESC, id DESC",
    )
    suspend fun getTimingSessionsBetween(startEpochDay: Long, endEpochDay: Long): List<TimingSessionEntity>

    @Query("SELECT * FROM study_timing_sessions WHERE id = :id LIMIT 1")
    suspend fun getTimingSession(id: Long): TimingSessionEntity?

    @Query(
        "SELECT * FROM study_timing_sessions WHERE status IN ('RUNNING', 'PAUSED') " +
            "ORDER BY startedAtEpochMillis DESC LIMIT 1",
    )
    suspend fun getActiveTimingSession(): TimingSessionEntity?

    @Query("SELECT * FROM study_timing_sessions WHERE status = 'RUNNING'")
    suspend fun getRunningTimingSessions(): List<TimingSessionEntity>

    @Insert
    suspend fun insertTimingSession(session: TimingSessionEntity): Long

    @Update
    suspend fun updateTimingSession(session: TimingSessionEntity)

    @Query("DELETE FROM study_timing_sessions WHERE id = :id")
    suspend fun deleteTimingSession(id: Long)

    @Query("SELECT * FROM problems ORDER BY updatedAtEpochMillis DESC, id DESC")
    fun observeProblems(): Flow<List<ProblemEntity>>

    @Query("SELECT * FROM problems ORDER BY updatedAtEpochMillis DESC, id DESC")
    suspend fun getProblems(): List<ProblemEntity>

    @Query("SELECT * FROM problems WHERE id = :id LIMIT 1")
    suspend fun getProblem(id: Long): ProblemEntity?

    @Insert
    suspend fun insertProblem(problem: ProblemEntity): Long

    @Update
    suspend fun updateProblem(problem: ProblemEntity)

    @Query("DELETE FROM problems WHERE id = :id")
    suspend fun deleteProblem(id: Long)

    @Query("SELECT * FROM self_control_entries ORDER BY dateEpochDay DESC, createdAtEpochMillis DESC")
    fun observeSelfControlEntries(): Flow<List<SelfControlEntryEntity>>

    @Query("SELECT * FROM self_control_entries ORDER BY dateEpochDay DESC, createdAtEpochMillis DESC")
    suspend fun getSelfControlEntries(): List<SelfControlEntryEntity>

    @Query(
        "SELECT * FROM self_control_entries WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY dateEpochDay DESC, createdAtEpochMillis DESC",
    )
    suspend fun getSelfControlEntriesBetween(startEpochDay: Long, endEpochDay: Long): List<SelfControlEntryEntity>

    @Insert
    suspend fun insertSelfControlEntry(entry: SelfControlEntryEntity): Long

    @Query("DELETE FROM self_control_entries WHERE id = :id")
    suspend fun deleteSelfControlEntry(id: Long)

    @Query("SELECT * FROM learning_goals WHERE id = 1 LIMIT 1")
    fun observeGoals(): Flow<LearningGoalsEntity?>

    @Query("SELECT * FROM learning_goals WHERE id = 1 LIMIT 1")
    suspend fun getGoals(): LearningGoalsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoals(goals: LearningGoalsEntity)
}
