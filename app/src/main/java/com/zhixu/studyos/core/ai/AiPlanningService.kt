package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.ai.network.AiHttpTransport
import com.zhixu.studyos.core.ai.network.OpenAiCompatibleProvider
import com.zhixu.studyos.core.data.repository.AiRepository
import com.zhixu.studyos.core.model.AiCachedAnalysis
import com.zhixu.studyos.core.model.AiCompletionRequest
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiPlanRecord
import com.zhixu.studyos.core.model.AiPlanReview
import com.zhixu.studyos.core.model.AiProvider
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiProviderException
import com.zhixu.studyos.core.model.AiStudySnapshot
import com.zhixu.studyos.core.model.AiUsage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一次 AI 分析的结果：新调用或来自缓存。 */
sealed interface AiAnalysisOutcome {
    val review: AiPlanReview
    val planJson: String
    val model: String
    val costMicros: Long
    val usedCache: Boolean

    data class Fresh(
        override val review: AiPlanReview,
        override val planJson: String,
        override val model: String,
        override val costMicros: Long,
        val promptTokens: Int,
        val completionTokens: Int,
        val tokensEstimated: Boolean,
        val latencyMillis: Long,
        val usageToday: AiUsage,
    ) : AiAnalysisOutcome {
        override val usedCache: Boolean = false
    }

    data class Cached(
        override val review: AiPlanReview,
        override val planJson: String,
        override val model: String,
        override val costMicros: Long,
        val cachedAt: Instant,
    ) : AiAnalysisOutcome {
        override val usedCache: Boolean = true
    }
}

/** AI 规划服务的接口，便于 ViewModel 测试替换。 */
interface AiPlanCoordinator {
    suspend fun prepare(
        config: AiProviderConfig,
        snapshot: AiStudySnapshot,
        forceRefresh: Boolean = false,
    ): AiAnalysisOutcome

    suspend fun useCache(
        cacheKey: String,
        snapshot: AiStudySnapshot,
        config: AiProviderConfig,
    ): AiAnalysisOutcome.Cached

    fun cacheKeyFor(config: AiProviderConfig, snapshot: AiStudySnapshot): String

    suspend fun findCached(config: AiProviderConfig, snapshot: AiStudySnapshot): AiCachedAnalysis?

    suspend fun recordConfirmedPlan(
        outcome: AiAnalysisOutcome,
        appliedTaskCount: Int,
        usedCache: Boolean,
    ): Long
}

/**
 * AI 规划服务：把本地快照变成经过 Schema 校验和本地约束检查的可预览计划。
 *
 * 关键约定（V0.9 第三节/第四节）：
 * - 只有用户手动触发才会真正调用付费接口；默认不自动调用。
 * - 相同输入（模型 + 提示词版本 + 数据快照）在缓存有效期内直接返回缓存，不重复付费。
 * - 每日调用次数与估算费用上限在调用前检查。
 * - 离线、未配置、未启用、额度用尽都有明确错误分类，核心离线功能不受影响。
 * - 即使 AI 返回格式错误，已产生的 token 用量仍会被记录（费用不会凭空消失）。
 * - 任何 AI 输出都不会直接写业务数据库；本服务只记录用量、缓存与用户确认后的计划记录。
 */
class AiPlanningService(
    private val repository: AiRepository,
    private val keyStore: ApiKeyStore,
    private val connectivity: AiConnectivity,
    private val transport: AiHttpTransport,
    private val snapshotBuilder: AiSnapshotBuilder,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val providerFactory: (AiProviderConfig, String) -> AiProvider = { config, key ->
        OpenAiCompatibleProvider(
            transport = transport,
            apiKey = key,
            baseUrl = config.baseUrl,
            model = config.model,
            timeoutSeconds = config.timeoutSeconds,
        )
    },
) : AiPlanCoordinator {
    companion object {
        const val CACHE_MAX_AGE_HOURS = 24L
        const val CACHE_KEEP_DAYS = 7L
    }

    /** 生成（或复用缓存）一次分析。forceRefresh 必须由用户显式选择。 */
    override suspend fun prepare(
        config: AiProviderConfig,
        snapshot: AiStudySnapshot,
        forceRefresh: Boolean,
    ): AiAnalysisOutcome = AiRequestGate.run {
        prepareLocked(config, snapshot, forceRefresh)
    }

    private suspend fun prepareLocked(
        config: AiProviderConfig,
        snapshot: AiStudySnapshot,
        forceRefresh: Boolean,
    ): AiAnalysisOutcome {
        config.validationError()?.let { message ->
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, message)
        }
        if (!config.enabled) {
            throw AiProviderException(AiFailureKind.DISABLED, "AI 规划功能未启用，请先在「我的 → AI 设置」中开启")
        }
        if (!config.isConfigured) {
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, "请先配置 AI 服务地址与模型")
        }
        val apiKey = keyStore.load()
        if (apiKey.isNullOrBlank()) {
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, "请先保存 API Key")
        }

        val payloadJson = snapshotBuilder.toJson(snapshot, config, zoneId)
        val cacheKey = AiRequestHasher.cacheKey(
            promptVersion = AiPromptBuilder.PROMPT_VERSION,
            model = config.model,
            payloadJson = payloadJson,
        )

        if (!forceRefresh) {
            repository.findCache(cacheKey, CACHE_MAX_AGE_HOURS)?.let { cached ->
                return cachedOutcome(cached, snapshot, config)
            }
        }

        val today = LocalDate.now(clock.withZone(zoneId))
        val usage = repository.getUsage(today)
        if (usage.requestCount >= config.dailyCallLimit) {
            throw AiProviderException(
                AiFailureKind.DAILY_CALL_LIMIT,
                "已达到今日调用上限（${config.dailyCallLimit} 次），明天再试或调整上限",
            )
        }
        if (config.dailyCostLimitMicros > 0 && usage.estimatedCostMicros >= config.dailyCostLimitMicros) {
            throw AiProviderException(
                AiFailureKind.DAILY_COST_LIMIT,
                "已达到今日费用上限（${AiCostCalculator.formatYuan(config.dailyCostLimitMicros)}）",
            )
        }
        if (!connectivity.isOnline()) {
            throw AiProviderException(AiFailureKind.OFFLINE, "当前处于离线状态；本地功能不受影响，联网后可手动重试")
        }

        val prompts = AiPromptBuilder.build(payloadJson, snapshot)
        val provider = providerFactory(config, apiKey)
        val result = provider.complete(
            AiCompletionRequest(
                systemPrompt = prompts.systemPrompt,
                userPrompt = prompts.userPrompt,
            ),
        )
        val costMicros = AiCostCalculator.estimateMicros(
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            config = config,
        )
        // 先记账：即使随后解析失败，token 已实际消耗
        val updatedUsage = repository.recordUsage(
            date = today,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            costMicros = costMicros,
        )
        val planJson = AiPlanParser.extractJson(result.content)
        val plan = AiPlanParser.parse(planJson)
        val review = checkAgainstLocalConstraints(plan, snapshot, config)
        val now = Instant.now(clock)
        repository.saveCache(
            AiCachedAnalysis(
                cacheKey = cacheKey,
                model = result.model.ifBlank { config.model },
                createdAt = now,
                planJson = planJson,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                costMicros = costMicros,
            ),
        )
        repository.pruneCache(CACHE_KEEP_DAYS)
        return AiAnalysisOutcome.Fresh(
            review = review,
            planJson = planJson,
            model = result.model.ifBlank { config.model },
            costMicros = costMicros,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            tokensEstimated = result.tokensEstimated,
            latencyMillis = result.latencyMillis,
            usageToday = updatedUsage,
        )
    }

    /** 使用缓存结果（不产生调用与费用）。 */
    override suspend fun useCache(
        cacheKey: String,
        snapshot: AiStudySnapshot,
        config: AiProviderConfig,
    ): AiAnalysisOutcome.Cached {
        val cached = repository.findCache(cacheKey, CACHE_KEEP_DAYS * 24L)
            ?: throw AiProviderException(AiFailureKind.UNKNOWN, "缓存已过期，请重新生成")
        return cachedOutcome(cached, snapshot, config)
    }

    /** 计算当前快照的缓存键，用于在界面判断「输入未变化」。 */
    override fun cacheKeyFor(config: AiProviderConfig, snapshot: AiStudySnapshot): String {
        val payload = snapshotBuilder.toJson(snapshot, config, zoneId)
        return AiRequestHasher.cacheKey(AiPromptBuilder.PROMPT_VERSION, config.model, payload)
    }

    override suspend fun findCached(config: AiProviderConfig, snapshot: AiStudySnapshot): AiCachedAnalysis? =
        repository.findCache(cacheKeyFor(config, snapshot), CACHE_MAX_AGE_HOURS)

    /** 用户确认后记录本次计划（只记录，不修改业务数据）。 */
    override suspend fun recordConfirmedPlan(
        outcome: AiAnalysisOutcome,
        appliedTaskCount: Int,
        usedCache: Boolean,
    ): Long = repository.savePlanRecord(
        AiPlanRecord(
            createdAt = Instant.now(clock),
            model = outcome.model,
            usedCache = usedCache,
            planJson = outcome.planJson,
            appliedTaskCount = appliedTaskCount,
            costMicros = if (usedCache) 0L else outcome.costMicros,
        ),
    )

    /**
     * 测试连接：发起一次极小的付费调用以验证地址、密钥与模型。
     * 只有用户主动点击才会执行，并计入当天用量。
     */
    suspend fun testConnection(config: AiProviderConfig, apiKey: String): String =
        AiRequestGate.run { testConnectionLocked(config, apiKey) }

    private suspend fun testConnectionLocked(config: AiProviderConfig, apiKey: String): String {
        config.validationError()?.let { throw AiProviderException(AiFailureKind.NOT_CONFIGURED, it) }
        if (config.baseUrl.isBlank() || config.model.isBlank()) {
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, "请先填写服务地址与模型")
        }
        if (apiKey.isBlank()) {
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, "请先填写 API Key")
        }
        if (!connectivity.isOnline()) {
            throw AiProviderException(AiFailureKind.OFFLINE, "当前处于离线状态，无法测试连接")
        }
        val today = LocalDate.now(clock.withZone(zoneId))
        val usage = repository.getUsage(today)
        if (usage.requestCount >= config.dailyCallLimit) {
            throw AiProviderException(AiFailureKind.DAILY_CALL_LIMIT, "已达到今日调用上限")
        }
        val provider = providerFactory(config, apiKey)
        val result = provider.complete(
            AiCompletionRequest(
                systemPrompt = "你是一个连接测试助手，只回复 OK。",
                userPrompt = "只回复：OK",
                maxOutputTokens = 16,
            ),
        )
        val cost = AiCostCalculator.estimateMicros(result.promptTokens, result.completionTokens, config)
        repository.recordUsage(today, result.promptTokens, result.completionTokens, cost)
        return "连接成功：${result.model.ifBlank { config.model }} 返回「${result.content.take(40)}」"
    }

    private fun cachedOutcome(
        cached: AiCachedAnalysis,
        snapshot: AiStudySnapshot,
        config: AiProviderConfig,
    ): AiAnalysisOutcome.Cached {
        val plan = AiPlanParser.parse(cached.planJson)
        val review = checkAgainstLocalConstraints(plan, snapshot, config)
        return AiAnalysisOutcome.Cached(
            review = review,
            planJson = cached.planJson,
            model = cached.model,
            // 本次使用缓存不产生新费用；原始估算费用保留在缓存记录中
            costMicros = 0L,
            cachedAt = cached.createdAt,
        )
    }

    private fun checkAgainstLocalConstraints(
        plan: com.zhixu.studyos.core.model.AiPlan,
        snapshot: AiStudySnapshot,
        config: AiProviderConfig,
    ): AiPlanReview = AiPlanConstraintChecker.check(
        plan = plan,
        upcomingTasks = snapshot.upcomingTasks,
        knownSubjects = snapshot.subjects.map { it.subjectName }.toSet(),
        dailyLimitMinutes = snapshot.scheduling.dailyLimitMinutes,
        subjectNamesAvailable = config.includeSubjectNames,
    )
}
