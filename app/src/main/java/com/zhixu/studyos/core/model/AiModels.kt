package com.zhixu.studyos.core.model

import java.time.Instant
import java.time.LocalDate

/** AI 服务配置。密钥不在此模型内，单独存放在 Android Keystore 加密的存储中。 */
data class AiProviderConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val timeoutSeconds: Int = 60,
    /** 每百万输入 token 的价格，单位：微元（1 元 = 1,000,000 微元）。 */
    val inputPriceMicrosPerMillionTokens: Long = 0,
    val outputPriceMicrosPerMillionTokens: Long = 0,
    val dailyCallLimit: Int = 10,
    /** 每日费用上限，单位：微元。0 表示不限制费用。 */
    val dailyCostLimitMicros: Long = 2_000_000,
    val includeTaskTitles: Boolean = true,
    val includeSubjectNames: Boolean = true,
    val includeProblemStatements: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun validationError(): String? = when {
        baseUrl.isNotBlank() && !baseUrl.trim().startsWith("https://") &&
            !baseUrl.trim().startsWith("http://") -> "服务地址必须以 http:// 或 https:// 开头"
        enabled && baseUrl.isBlank() -> "启用 AI 前请填写服务地址"
        enabled && model.isBlank() -> "启用 AI 前请填写模型名称"
        timeoutSeconds !in 10..180 -> "请求超时应在 10–180 秒之间"
        dailyCallLimit !in 1..200 -> "每日调用次数上限应在 1–200 之间"
        dailyCostLimitMicros < 0 -> "每日费用上限不能为负"
        inputPriceMicrosPerMillionTokens < 0 || outputPriceMicrosPerMillionTokens < 0 ->
            "Token 价格不能为负"
        else -> null
    }

    fun requireValid() {
        require(validationError() == null) { validationError() ?: "AI 设置无效" }
    }

    companion object {
        /** 未配置时的空白基线（测试/内部判断用，不代表产品默认服务）。 */
        val Default = AiProviderConfig()

        /** 产品默认接入 DeepSeek；用户仍需自行填写 API Key 并手动启用。 */
        val DeepSeekDefault = AiProviderConfig(
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        )
    }
}

/** AI 调用失败分类，UI 与错误处理共用。 */
enum class AiFailureKind {
    NOT_CONFIGURED,
    DISABLED,
    OFFLINE,
    DAILY_CALL_LIMIT,
    DAILY_COST_LIMIT,
    UNAUTHORIZED,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    REQUEST_REJECTED,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    SCHEMA_INVALID,
    CANCELLED,
    UNKNOWN,
    ;
    /** 只有这些失败值得用户重试；其余需要改配置或等额度恢复。 */
    val isRetryable: Boolean
        get() = this in setOf(RATE_LIMITED, SERVER_ERROR, TIMEOUT, NETWORK, INVALID_RESPONSE, UNKNOWN)
}

class AiProviderException(
    val kind: AiFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 一次补全请求。 */
data class AiCompletionRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxOutputTokens: Int = 1500,
)

data class AiProviderResult(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val model: String,
    val latencyMillis: Long,
    /** 服务端未返回 usage 时，token 数为字符估算，费用也按估算计算。 */
    val tokensEstimated: Boolean = false,
)

/**
 * AI 服务提供方的唯一接口。V0.9 只实现 OpenAI 兼容的 Chat Completions；
 * 任何其他服务只要实现该接口即可替换，核心规划流程不感知具体厂商 SDK。
 */
interface AiProvider {
    val providerName: String

    suspend fun complete(request: AiCompletionRequest): AiProviderResult
}

/** 统计口径：自然日（本地时区）。 */
data class AiUsage(
    val date: LocalDate,
    val requestCount: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val estimatedCostMicros: Long = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/** 科目维度的近七天统计。 */
data class AiSubjectStat(
    val subjectName: String,
    val studyMinutes: Int,
    val completedTasks: Int,
    val scheduledTasks: Int,
) {
    /** 完成率百分比；没有安排时为空，不臆造 0%。 */
    val completionRatePercent: Int?
        get() = if (scheduledTasks <= 0) null else (completedTasks * 100 / scheduledTasks).coerceIn(0, 100)
}

data class AiDayStat(
    val date: LocalDate,
    val studyMinutes: Int,
    val completedTasks: Int,
)

/** 未来任务的摘要（供 AI 估算用时、识别延期）。 */
data class AiTaskStat(
    val taskId: Long,
    val subject: String,
    val title: String,
    val estimatedMinutes: Int,
    val dueDate: LocalDate?,
    val priority: String,
    val scheduledMinutes: Int,
    val postponeCount: Int,
)

/** 反复失败的习题信号：来自学习中心手工习题与个人题库。 */
data class AiProblemSignal(
    val source: String,
    val label: String,
    val subject: String,
    val chapter: String,
    val problemNumber: String,
    val retryCount: Int,
    val isMistake: Boolean,
    val note: String,
)

data class AiGoalSummary(
    val dailyStudyMinutes: Int?,
    val weeklyStudyMinutes: Int?,
    val dailyTaskTarget: Int?,
)

data class AiSchedulingSummary(
    val dailyLimitMinutes: Int,
    val studyStartMinute: Int,
    val studyEndMinuteExclusive: Int,
    val bufferMinutes: Int,
)

/** 发送给 AI 的全部学习数据。财务数据从不进入该模型。 */
data class AiStudySnapshot(
    val generatedAt: Instant,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val subjects: List<AiSubjectStat>,
    val recentDays: List<AiDayStat>,
    val upcomingTasks: List<AiTaskStat>,
    val difficultProblems: List<AiProblemSignal>,
    val goals: AiGoalSummary,
    val scheduling: AiSchedulingSummary,
) {
    val totalStudyMinutes: Int get() = recentDays.sumOf { it.studyMinutes }
    val completedTaskCount: Int get() = recentDays.sumOf { it.completedTasks }
}

/** AI 返回的结构化计划（JSON Schema 版本 1）。 */
data class AiSubjectAllocation(
    val subject: String,
    val minutesPerDay: Int,
    val reason: String,
)

data class AiTaskEstimate(
    val taskId: Long,
    val estimatedMinutes: Int,
    val reason: String,
)

data class AiRiskFlag(
    val kind: String,
    val detail: String,
)

data class AiPlan(
    val schemaVersion: Int = AI_PLAN_SCHEMA_VERSION,
    val summary: String,
    val subjectAllocations: List<AiSubjectAllocation> = emptyList(),
    val taskEstimates: List<AiTaskEstimate> = emptyList(),
    val restReminders: List<String> = emptyList(),
    val weeklySuggestions: List<String> = emptyList(),
    val riskFlags: List<AiRiskFlag> = emptyList(),
)

const val AI_PLAN_SCHEMA_VERSION = 1

enum class AiIssueSeverity { ERROR, WARNING }

data class AiPlanIssue(
    val severity: AiIssueSeverity,
    val itemLabel: String,
    val message: String,
)

/**
 * 本地约束检查后的可预览结果：只有 accepted 项允许在用户确认后写入本地数据。
 * AI 原样输出的 plan 仍完整保留，供用户查看被拒绝的原因。
 */
data class AiPlanReview(
    val plan: AiPlan,
    val acceptedTaskEstimates: List<AiTaskEstimate>,
    val rejectedTaskEstimates: List<AiTaskEstimate>,
    val acceptedAllocations: List<AiSubjectAllocation>,
    val rejectedAllocations: List<AiSubjectAllocation>,
    val issues: List<AiPlanIssue>,
) {
    val hasErrors: Boolean get() = issues.any { it.severity == AiIssueSeverity.ERROR }
}

/** 缓存的分析结果（同一输入不重复付费调用）。 */
data class AiCachedAnalysis(
    val cacheKey: String,
    val model: String,
    val createdAt: Instant,
    val planJson: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val costMicros: Long,
)

data class AiPlanRecord(
    val id: Long = 0,
    val createdAt: Instant,
    val model: String,
    val usedCache: Boolean,
    val planJson: String,
    val appliedTaskCount: Int,
    val costMicros: Long,
)
