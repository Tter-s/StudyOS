package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.ai.network.AiHttpTransport
import com.zhixu.studyos.core.ai.network.OpenAiCompatibleProvider
import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.data.local.AiAnalysisRecordEntity
import com.zhixu.studyos.core.data.local.V2AiDao
import com.zhixu.studyos.core.data.preferences.AiConfigStore
import com.zhixu.studyos.core.data.repository.AiRepository
import com.zhixu.studyos.core.model.AiCachedAnalysis
import com.zhixu.studyos.core.model.AiCompletionRequest
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiProvider
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiProviderException
import com.zhixu.studyos.core.model.AiUsage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/** 统一的 AI 任务类型。不同任务使用独立提示模板与输出 Schema，避免各模块自行随意调用。 */
enum class AiTaskType(val label: String) {
    SCHEDULE_OCR("课表识别"),
    EXERCISE_OCR("习题识别"),
    MATH_HINT("数学分级提示"),
    PROOF_CHECK("证明检查"),
    STUDY_PLAN("学习规划"),
    KNOWLEDGE_LINK("知识点关联建议"),
    WEEKLY_REPORT("周报分析"),
    GROWTH_REPORT("成长报告"),
}

data class AiCapabilities(
    val textGeneration: Boolean = true,
    val structuredJson: Boolean = true,
    val imageUnderstanding: Boolean = false,
    val note: String = "当前只接入 OpenAI 兼容的文本接口；图片识别未启用，界面不会提供伪造的图片 AI 按钮。",
)

data class AiTaskRequest(
    val taskType: AiTaskType,
    val promptVersion: String,
    val systemPrompt: String,
    val userPrompt: String,
    val cacheKey: String,
    val objectType: String = "NONE",
    val objectId: Long? = null,
    val maxOutputTokens: Int = 1600,
    val note: String = "",
)

data class AiTaskResult(
    val taskType: AiTaskType,
    val content: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val costMicros: Long,
    val usedCache: Boolean,
    val cachedAt: Instant? = null,
    val tokensEstimated: Boolean = false,
    val latencyMillis: Long = 0L,
    val usageToday: AiUsage,
    val analysisRecordId: Long? = null,
)

/**
 * V2.0 统一 AI 服务层。
 *
 * - 只有用户主动触发才会发起付费调用；
 * - 统一检查启用状态、配置、离线、每日次数/费用上限；
 * - 记录 token 用量与估算费用；相同输入在 24 小时内复用缓存；
 * - 每次输出都写入 ai_analysis_records，保留任务类型、模型与提示版本；
 * - 不直接写业务数据库，调用方必须自行校验并让用户确认；
 * - 对可重试错误做一次短暂重试，取消请求时由调用方取消协程。
 */
class AiTaskService(
    private val repository: AiRepository,
    private val configStore: AiConfigStore,
    private val keyStore: ApiKeyStore,
    private val connectivity: AiConnectivity,
    private val transport: AiHttpTransport,
    private val aiDao: V2AiDao,
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
) {
    companion object {
        const val CACHE_MAX_AGE_HOURS = 24L
        const val CACHE_KEEP_DAYS = 7L
        private const val RETRY_DELAY_MILLIS = 600L
    }

    val capabilities: AiCapabilities = AiCapabilities()

    fun cacheKey(taskType: AiTaskType, promptVersion: String, model: String, payload: String): String =
        AiRequestHasher.cacheKey(
            promptVersion = "${taskType.name}:$promptVersion",
            model = model,
            payloadJson = payload,
        )

    suspend fun run(
        request: AiTaskRequest,
        forceRefresh: Boolean = false,
        recordUserAccepted: Boolean? = null,
        validateContent: ((String) -> Unit)? = null,
    ): AiTaskResult = AiRequestGate.run {
        runLocked(
            request = request,
            forceRefresh = forceRefresh,
            recordUserAccepted = recordUserAccepted,
            validateContent = validateContent,
        )
    }

    private suspend fun runLocked(
        request: AiTaskRequest,
        forceRefresh: Boolean,
        recordUserAccepted: Boolean?,
        validateContent: ((String) -> Unit)?,
    ): AiTaskResult {
        val config = configStore.getConfig()
        config.validationError()?.let { message ->
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, message)
        }
        if (!config.enabled) {
            throw AiProviderException(AiFailureKind.DISABLED, "AI 功能未启用，请先在「我的 → AI 设置」中开启")
        }
        if (!config.isConfigured) {
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, "请先配置 AI 服务地址与模型")
        }
        val apiKey = keyStore.load()
        if (apiKey.isNullOrBlank()) {
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, "请先保存 API Key")
        }
        if (!connectivity.isOnline()) {
            throw AiProviderException(AiFailureKind.OFFLINE, "当前处于离线状态；本地功能不受影响，联网后可手动重试")
        }
        val today = LocalDate.now(clock.withZone(zoneId))
        var usage = repository.getUsage(today)
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
        if (!forceRefresh) {
            repository.findCache(request.cacheKey, CACHE_MAX_AGE_HOURS)?.let { cached ->
                validateContent?.invoke(cached.planJson)
                val recordId = aiDao.insertAnalysis(
                    AiAnalysisRecordEntity(
                        taskType = request.taskType.name,
                        objectType = request.objectType,
                        objectId = request.objectId,
                        createdAtEpochMillis = clock.millis(),
                        model = cached.model,
                        promptVersion = request.promptVersion,
                        resultJson = cached.planJson,
                        costMicros = 0,
                        usedCache = true,
                        userAccepted = recordUserAccepted,
                        note = "使用缓存，未产生新费用。${request.note}".trim(),
                    ),
                )
                return AiTaskResult(
                    taskType = request.taskType,
                    content = cached.planJson,
                    model = cached.model,
                    promptTokens = cached.promptTokens,
                    completionTokens = cached.completionTokens,
                    costMicros = 0,
                    usedCache = true,
                    cachedAt = cached.createdAt,
                    usageToday = usage,
                    analysisRecordId = recordId,
                )
            }
        }
        val provider = providerFactory(config, apiKey)
        var attempt = 0
        var lastError: AiProviderException? = null
        while (attempt < 2) {
            attempt += 1
            try {
                val result = provider.complete(
                    AiCompletionRequest(
                        systemPrompt = request.systemPrompt,
                        userPrompt = request.userPrompt,
                        maxOutputTokens = request.maxOutputTokens,
                    ),
                )
                // 在记录费用/写缓存前验证格式；无效输出会抛错，但仍会先记账，因为 token 已消耗。
                val costMicros = AiCostCalculator.estimateMicros(
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    config = config,
                )
                usage = repository.recordUsage(
                    date = today,
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    costMicros = costMicros,
                )
                try {
                    validateContent?.invoke(result.content)
                } catch (error: Exception) {
                    throw AiProviderException(
                        AiFailureKind.SCHEMA_INVALID,
                        "AI 输出格式不符合要求：${error.message ?: "无法解析"}",
                        error,
                    )
                }
                val model = result.model.ifBlank { config.model }
                repository.saveCache(
                    AiCachedAnalysis(
                        cacheKey = request.cacheKey,
                        model = model,
                        createdAt = Instant.now(clock),
                        planJson = result.content,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        costMicros = costMicros,
                    ),
                )
                repository.pruneCache(CACHE_KEEP_DAYS)
                val recordId = aiDao.insertAnalysis(
                    AiAnalysisRecordEntity(
                        taskType = request.taskType.name,
                        objectType = request.objectType,
                        objectId = request.objectId,
                        createdAtEpochMillis = clock.millis(),
                        model = model,
                        promptVersion = request.promptVersion,
                        resultJson = result.content,
                        costMicros = costMicros,
                        usedCache = false,
                        userAccepted = recordUserAccepted,
                        note = request.note,
                    ),
                )
                return AiTaskResult(
                    taskType = request.taskType,
                    content = result.content,
                    model = model,
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    costMicros = costMicros,
                    usedCache = false,
                    tokensEstimated = result.tokensEstimated,
                    latencyMillis = result.latencyMillis,
                    usageToday = usage,
                    analysisRecordId = recordId,
                )
            } catch (error: AiProviderException) {
                lastError = error
                val retryable = error.kind in setOf(
                    AiFailureKind.SERVER_ERROR,
                    AiFailureKind.TIMEOUT,
                    AiFailureKind.NETWORK,
                )
                if (!retryable || attempt >= 2) throw error
                delay(RETRY_DELAY_MILLIS)
            }
        }
        throw lastError ?: AiProviderException(AiFailureKind.UNKNOWN, "AI 请求失败")
    }

    suspend fun recordAcceptance(recordId: Long?, accepted: Boolean) {
        if (recordId == null) return
        aiDao.setUserAccepted(recordId, accepted)
    }
}
