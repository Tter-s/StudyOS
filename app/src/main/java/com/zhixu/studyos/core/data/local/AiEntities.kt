package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.zhixu.studyos.core.model.AiCachedAnalysis
import com.zhixu.studyos.core.model.AiPlanRecord
import com.zhixu.studyos.core.model.AiUsage
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ai_usage_daily")
data class AiUsageDailyEntity(
    @PrimaryKey val dateKey: String,
    val requestCount: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostMicros: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "ai_analysis_cache")
data class AiAnalysisCacheEntity(
    @PrimaryKey val cacheKey: String,
    val model: String,
    val planJson: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val costMicros: Long,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "ai_plan_records")
data class AiPlanRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMillis: Long,
    val model: String,
    val usedCache: Boolean,
    val planJson: String,
    val appliedTaskCount: Int,
    val costMicros: Long,
)

fun AiUsageDailyEntity.toDomainModel() = AiUsage(
    date = LocalDate.parse(dateKey),
    requestCount = requestCount,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    estimatedCostMicros = estimatedCostMicros,
)

fun AiAnalysisCacheEntity.toDomainModel() = AiCachedAnalysis(
    cacheKey = cacheKey,
    model = model,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    planJson = planJson,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    costMicros = costMicros,
)

fun AiPlanRecordEntity.toDomainModel() = AiPlanRecord(
    id = id,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    model = model,
    usedCache = usedCache,
    planJson = planJson,
    appliedTaskCount = appliedTaskCount,
    costMicros = costMicros,
)

@Dao
interface AiDao {
    @Query("SELECT * FROM ai_usage_daily WHERE dateKey = :dateKey")
    suspend fun getUsage(dateKey: String): AiUsageDailyEntity?

    @Query("SELECT * FROM ai_usage_daily ORDER BY dateKey DESC LIMIT :limit")
    fun observeUsage(limit: Int): Flow<List<AiUsageDailyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(entity: AiUsageDailyEntity)

    @Query("SELECT * FROM ai_analysis_cache WHERE cacheKey = :cacheKey")
    suspend fun getCache(cacheKey: String): AiAnalysisCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(entity: AiAnalysisCacheEntity)

    @Query("DELETE FROM ai_analysis_cache WHERE createdAtEpochMillis < :cutoffEpochMillis")
    suspend fun pruneCache(cutoffEpochMillis: Long)

    @Insert
    suspend fun insertPlanRecord(entity: AiPlanRecordEntity): Long

    @Query("SELECT * FROM ai_plan_records ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    fun observePlanRecords(limit: Int): Flow<List<AiPlanRecordEntity>>
}
