package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyLogDao {
    // ---------- 每日待办 ----------

    @Query("SELECT * FROM daily_tasks WHERE dateEpochDay = :epochDay ORDER BY sortOrder, id")
    fun observeTasks(epochDay: Long): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_tasks WHERE dateEpochDay = :epochDay ORDER BY sortOrder, id")
    suspend fun tasks(epochDay: Long): List<DailyTaskEntity>

    @Query(
        "SELECT * FROM daily_tasks WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY dateEpochDay DESC, sortOrder, id",
    )
    fun observeTasksBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_tasks ORDER BY dateEpochDay DESC, sortOrder, id")
    fun observeAllTasks(): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_tasks WHERE id = :taskId LIMIT 1")
    suspend fun task(taskId: Long): DailyTaskEntity?

    @Insert
    suspend fun insertTask(task: DailyTaskEntity): Long

    @Update
    suspend fun updateTask(task: DailyTaskEntity)

    @Query("DELETE FROM daily_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM daily_tasks WHERE dateEpochDay = :epochDay")
    suspend fun maxSortOrder(epochDay: Long): Int

    // ---------- 每日总结 ----------

    @Query("SELECT * FROM daily_summaries WHERE dateEpochDay = :epochDay LIMIT 1")
    fun observeSummary(epochDay: Long): Flow<DailySummaryEntity?>

    @Query("SELECT * FROM daily_summaries WHERE dateEpochDay = :epochDay LIMIT 1")
    suspend fun summary(epochDay: Long): DailySummaryEntity?

    @Query(
        "SELECT * FROM daily_summaries WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY dateEpochDay DESC",
    )
    fun observeSummariesBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries ORDER BY dateEpochDay DESC")
    fun observeAllSummaries(): Flow<List<DailySummaryEntity>>

    @Insert
    suspend fun insertSummary(summary: DailySummaryEntity)

    @Update
    suspend fun updateSummary(summary: DailySummaryEntity)

    // ---------- 娱乐时间 ----------

    @Query("SELECT * FROM entertainment_records WHERE dateEpochDay = :epochDay LIMIT 1")
    fun observeEntertainment(epochDay: Long): Flow<EntertainmentRecordEntity?>

    @Query("SELECT * FROM entertainment_records WHERE dateEpochDay = :epochDay LIMIT 1")
    suspend fun entertainment(epochDay: Long): EntertainmentRecordEntity?

    @Query(
        "SELECT * FROM entertainment_records WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY dateEpochDay DESC",
    )
    fun observeEntertainmentBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<EntertainmentRecordEntity>>

    @Query("SELECT * FROM entertainment_records ORDER BY dateEpochDay DESC")
    fun observeAllEntertainment(): Flow<List<EntertainmentRecordEntity>>

    @Insert
    suspend fun insertEntertainment(record: EntertainmentRecordEntity)

    @Update
    suspend fun updateEntertainment(record: EntertainmentRecordEntity)

    @Query("DELETE FROM entertainment_records WHERE dateEpochDay = :epochDay")
    suspend fun deleteEntertainment(epochDay: Long): Int
}
