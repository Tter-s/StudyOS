package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyTaskDao {
    @Transaction
    @Query("SELECT * FROM study_tasks ORDER BY sortOrder, id")
    fun observeAllWithSessions(): Flow<List<TaskWithSessionsEntity>>

    @Transaction
    @Query(
        """
        SELECT DISTINCT study_tasks.* FROM study_tasks
        INNER JOIN task_sessions ON task_sessions.taskId = study_tasks.id
        WHERE task_sessions.dateEpochDay = :epochDay
           OR (task_sessions.dateEpochDay = :epochDay - 1 AND task_sessions.endMinuteExclusive > 1440)
        ORDER BY study_tasks.sortOrder, study_tasks.id
        """,
    )
    fun observeByDate(epochDay: Long): Flow<List<TaskWithSessionsEntity>>

    @Transaction
    @Query("SELECT * FROM study_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getWithSessions(taskId: Long): TaskWithSessionsEntity?

    @Transaction
    @Query("SELECT * FROM study_tasks ORDER BY sortOrder, id")
    suspend fun getAllWithSessions(): List<TaskWithSessionsEntity>

    @Query("SELECT * FROM study_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTask(taskId: Long): StudyTaskEntity?

    @Insert
    suspend fun insert(task: StudyTaskEntity): Long

    @Update
    suspend fun update(task: StudyTaskEntity)

    @Query("DELETE FROM study_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    @Query("DELETE FROM study_tasks WHERE seriesId = :seriesId")
    suspend fun deleteSeries(seriesId: String)

    @Query("SELECT * FROM study_tasks WHERE seriesId = :seriesId ORDER BY sortOrder, id")
    suspend fun getSeries(seriesId: String): List<StudyTaskEntity>

    @Insert
    suspend fun insertSessions(sessions: List<TaskSessionEntity>)

    @Query("DELETE FROM task_sessions WHERE taskId = :taskId")
    suspend fun deleteSessionsForTask(taskId: Long)

    @Query("DELETE FROM task_sessions WHERE taskId IN (:taskIds) AND isLocked = 0")
    suspend fun deleteUnlockedSessions(taskIds: List<Long>)

    @Query(
        "UPDATE task_sessions SET isLocked = :isLocked, updatedAtEpochMillis = :updatedAt " +
            "WHERE id = :sessionId AND taskId = :taskId",
    )
    suspend fun updateSessionLocked(
        taskId: Long,
        sessionId: Long,
        isLocked: Boolean,
        updatedAt: Long,
    ): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM study_tasks")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE study_tasks SET sortOrder = :sortOrder, updatedAtEpochMillis = :updatedAt WHERE id = :taskId")
    suspend fun updateSortOrder(taskId: Long, sortOrder: Int, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM study_tasks")
    suspend fun count(): Int

    @Query("SELECT * FROM study_tasks WHERE sourceType = :sourceType AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(sourceType: String, sourceId: String): StudyTaskEntity?
}
