package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.AiAnalysisCacheEntity
import com.zhixu.studyos.core.data.local.AiDao
import com.zhixu.studyos.core.data.local.AiPlanRecordEntity
import com.zhixu.studyos.core.data.local.AiUsageDailyEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.model.AiCachedAnalysis
import com.zhixu.studyos.core.model.AiPlanRecord
import com.zhixu.studyos.core.model.AiUsage
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** AI 用量、缓存与已确认计划记录的持久化。 */
interface AiRepository {
    suspend fun getUsage(date: LocalDate): AiUsage
    fun observeUsage(limit: Int): Flow<List<AiUsage>>

    /** 记录一次实际发生的调用（含 token 与估算费用），返回累加后的当天用量。 */
    suspend fun recordUsage(
        date: LocalDate,
        promptTokens: Int,
        completionTokens: Int,
        costMicros: Long,
    ): AiUsage

    suspend fun findCache(cacheKey: String, maxAgeHours: Long): AiCachedAnalysis?
    suspend fun saveCache(cached: AiCachedAnalysis)
    suspend fun pruneCache(olderThanDays: Long)

    fun observePlanRecords(limit: Int): Flow<List<AiPlanRecord>>
    suspend fun savePlanRecord(record: AiPlanRecord): Long
}

class OfflineAiRepository(
    private val database: StudyOSDatabase,
    private val dao: AiDao,
    private val clock: Clock,
) : AiRepository {

    override suspend fun getUsage(date: LocalDate): AiUsage =
        dao.getUsage(date.toString())?.toDomainModel() ?: AiUsage(date = date)

    override fun observeUsage(limit: Int): Flow<List<AiUsage>> =
        dao.observeUsage(limit).map { rows -> rows.map { it.toDomainModel() } }

    override suspend fun recordUsage(
        date: LocalDate,
        promptTokens: Int,
        completionTokens: Int,
        costMicros: Long,
    ): AiUsage = database.withTransaction {
        val key = date.toString()
        val existing = dao.getUsage(key)
        val updated = AiUsageDailyEntity(
            dateKey = key,
            requestCount = (existing?.requestCount ?: 0) + 1,
            promptTokens = (existing?.promptTokens ?: 0) + promptTokens.coerceAtLeast(0),
            completionTokens = (existing?.completionTokens ?: 0) + completionTokens.coerceAtLeast(0),
            estimatedCostMicros = (existing?.estimatedCostMicros ?: 0L) + costMicros.coerceAtLeast(0L),
            updatedAtEpochMillis = clock.millis(),
        )
        dao.upsertUsage(updated)
        updated.toDomainModel()
    }

    override suspend fun findCache(cacheKey: String, maxAgeHours: Long): AiCachedAnalysis? {
        val cached = dao.getCache(cacheKey)?.toDomainModel() ?: return null
        val maxAgeMillis = maxAgeHours * 60L * 60L * 1000L
        val age = clock.millis() - cached.createdAt.toEpochMilli()
        return if (age in 0..maxAgeMillis) cached else null
    }

    override suspend fun saveCache(cached: AiCachedAnalysis) {
        dao.upsertCache(
            AiAnalysisCacheEntity(
                cacheKey = cached.cacheKey,
                model = cached.model,
                planJson = cached.planJson,
                promptTokens = cached.promptTokens,
                completionTokens = cached.completionTokens,
                costMicros = cached.costMicros,
                createdAtEpochMillis = cached.createdAt.toEpochMilli(),
            ),
        )
    }

    override suspend fun pruneCache(olderThanDays: Long) {
        val cutoff = clock.millis() - olderThanDays * 24L * 60L * 60L * 1000L
        dao.pruneCache(cutoff)
    }

    override fun observePlanRecords(limit: Int): Flow<List<AiPlanRecord>> =
        dao.observePlanRecords(limit).map { rows -> rows.map { it.toDomainModel() } }

    override suspend fun savePlanRecord(record: AiPlanRecord): Long =
        dao.insertPlanRecord(
            AiPlanRecordEntity(
                createdAtEpochMillis = record.createdAt.toEpochMilli(),
                model = record.model,
                usedCache = record.usedCache,
                planJson = record.planJson,
                appliedTaskCount = record.appliedTaskCount,
                costMicros = record.costMicros,
            ),
        )
}
