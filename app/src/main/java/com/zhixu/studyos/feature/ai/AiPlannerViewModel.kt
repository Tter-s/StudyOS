package com.zhixu.studyos.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.ai.AiAnalysisOutcome
import com.zhixu.studyos.core.ai.AiCostCalculator
import com.zhixu.studyos.core.ai.AiPlanConstraintChecker
import com.zhixu.studyos.core.ai.AiPlanCoordinator
import com.zhixu.studyos.core.ai.AiPromptBuilder
import com.zhixu.studyos.core.ai.AiSnapshotBuilder
import com.zhixu.studyos.core.ai.AiStudyDataSource
import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.data.preferences.AiConfigStore
import com.zhixu.studyos.core.data.repository.AiRepository
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiPlanRecord
import com.zhixu.studyos.core.model.AiPlanReview
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiProviderException
import com.zhixu.studyos.core.model.AiStudySnapshot
import com.zhixu.studyos.core.model.AiUsage
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一次分析的结果元信息（不含完整计划，计划在 review 中）。 */
data class AiOutcomeMeta(
    val model: String,
    val fromCache: Boolean,
    val cachedAt: Instant?,
    val costMicros: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMillis: Long?,
    val tokensEstimated: Boolean,
)

data class AiPlannerUiState(
    val loading: Boolean = true,
    val config: AiProviderConfig = AiProviderConfig.DeepSeekDefault,
    val hasApiKey: Boolean = false,
    val snapshot: AiStudySnapshot? = null,
    val payloadPreview: String = "",
    val payloadBytes: Int = 0,
    val privacySummary: String = "",
    val todayUsage: AiUsage? = null,
    val recentUsage: List<AiUsage> = emptyList(),
    val records: List<AiPlanRecord> = emptyList(),
    val currentCacheKey: String? = null,
    val cachedAt: Instant? = null,
    val review: AiPlanReview? = null,
    val outcomeMeta: AiOutcomeMeta? = null,
    val selectedTaskIds: Set<Long> = emptySet(),
    val busy: Boolean = false,
    val errorKind: AiFailureKind? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
) {
    val isReadyToCall: Boolean
        get() = config.enabled && config.isConfigured && hasApiKey && !busy

    val canApply: Boolean
        get() = review != null && !busy

    val selectedEstimateCount: Int get() = selectedTaskIds.size
}

/**
 * AI 规划页面状态。
 *
 * - 只做「准备数据 → 用户手动生成 → 预览 → 确认后本地应用」；
 * - 不自动调用，不在生成时修改任何业务数据；
 * - 数据集变化（缓存键变化）会清空旧预览，避免把过期建议应用到新任务上。
 */
class AiPlannerViewModel(
    private val repository: AiRepository,
    private val planningService: AiPlanCoordinator,
    private val settingsRepository: AiConfigStore,
    private val keyStore: ApiKeyStore,
    private val studyDataSource: AiStudyDataSource,
    private val snapshotBuilder: AiSnapshotBuilder,
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val state = MutableStateFlow(AiPlannerUiState())
    val uiState: StateFlow<AiPlannerUiState> = state.asStateFlow()

    private var lastOutcome: AiAnalysisOutcome? = null
    private var snapshotJob: Job? = null
    private var refreshPending = false

    init {
        viewModelScope.launch {
            settingsRepository.config.collect { config ->
                state.update { it.copy(config = config, loading = false) }
                refreshSnapshot()
            }
        }
        viewModelScope.launch {
            repository.observeUsage(7).collect { usage ->
                state.update { it.copy(recentUsage = usage, todayUsage = usage.firstOrNull()) }
            }
        }
        viewModelScope.launch {
            repository.observePlanRecords(10).collect { records ->
                state.update { it.copy(records = records) }
            }
        }
        viewModelScope.launch {
            state.update { it.copy(hasApiKey = keyStore.hasKey()) }
        }
    }

    fun refreshHasKey() {
        viewModelScope.launch {
            state.update { it.copy(hasApiKey = keyStore.hasKey()) }
        }
    }

    /** 重新聚合本地数据并计算发送内容预览；不产生任何网络调用。 */
    fun refreshSnapshot() {
        if (snapshotJob?.isActive == true) {
            // 运行期间再次请求：标记待刷新，结束后补一次，避免隐私开关变化被吞掉
            refreshPending = true
            return
        }
        snapshotJob = viewModelScope.launch {
            do {
                refreshPending = false
                loadSnapshotOnce()
            } while (refreshPending)
        }
    }

    private suspend fun loadSnapshotOnce() {
            val config = state.value.config
            runCatching {
                val snapshot = studyDataSource.load(java.time.LocalDate.now(clock.withZone(zoneId)))
                val payload = snapshotBuilder.toJson(snapshot, config, zoneId)
                val cacheKey = planningService.cacheKeyFor(config, snapshot)
                val cached = planningService.findCached(config, snapshot)
                Triple(snapshot, payload, cached) to cacheKey
            }.onSuccess { (data, cacheKey) ->
                val (snapshot, payload, cached) = data
                state.update { current ->
                    val dataChanged = current.currentCacheKey != cacheKey
                    current.copy(
                        snapshot = snapshot,
                        payloadPreview = payload,
                        payloadBytes = payload.toByteArray(Charsets.UTF_8).size,
                        privacySummary = AiPromptBuilder.privacySummary(config),
                        currentCacheKey = cacheKey,
                        cachedAt = cached?.createdAt,
                        review = if (dataChanged) null else current.review,
                        outcomeMeta = if (dataChanged) null else current.outcomeMeta,
                        selectedTaskIds = if (dataChanged) emptySet() else current.selectedTaskIds,
                    )
                }
            }
    }

    fun setPrivacy(
        includeTaskTitles: Boolean = state.value.config.includeTaskTitles,
        includeSubjectNames: Boolean = state.value.config.includeSubjectNames,
        includeProblemStatements: Boolean = state.value.config.includeProblemStatements,
    ) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.updatePrivacy(
                    includeTaskTitles = includeTaskTitles,
                    includeSubjectNames = includeSubjectNames,
                    includeProblemStatements = includeProblemStatements,
                )
            }.onFailure { error -> fail(error) }
        }
    }

    fun requestAnalysis(forceRefresh: Boolean = false) {
        if (state.value.busy) return
        val current = state.value
        val snapshot = current.snapshot ?: run {
            state.update { it.copy(errorMessage = "学习数据尚未加载完成，请稍后重试", errorKind = AiFailureKind.UNKNOWN) }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(busy = true, errorKind = null, errorMessage = null, notice = null) }
            runCatching {
                planningService.prepare(
                    config = current.config,
                    snapshot = snapshot,
                    forceRefresh = forceRefresh,
                )
            }.onSuccess { outcome ->
                lastOutcome = outcome
                val meta = AiOutcomeMeta(
                    model = outcome.model,
                    fromCache = outcome.usedCache,
                    cachedAt = (outcome as? AiAnalysisOutcome.Cached)?.cachedAt,
                    costMicros = outcome.costMicros,
                    promptTokens = (outcome as? AiAnalysisOutcome.Fresh)?.promptTokens ?: 0,
                    completionTokens = (outcome as? AiAnalysisOutcome.Fresh)?.completionTokens ?: 0,
                    latencyMillis = (outcome as? AiAnalysisOutcome.Fresh)?.latencyMillis,
                    tokensEstimated = (outcome as? AiAnalysisOutcome.Fresh)?.tokensEstimated ?: false,
                )
                state.update {
                    it.copy(
                        busy = false,
                        review = outcome.review,
                        outcomeMeta = meta,
                        selectedTaskIds = outcome.review.acceptedTaskEstimates.map { e -> e.taskId }.toSet(),
                        todayUsage = (outcome as? AiAnalysisOutcome.Fresh)?.usageToday ?: it.todayUsage,
                        notice = if (outcome.usedCache) "已使用缓存结果，未产生费用" else null,
                    )
                }
            }.onFailure { error -> fail(error) }
        }
    }

    fun useCachedResult() {
        val current = state.value
        val snapshot = current.snapshot ?: return
        val cacheKey = current.currentCacheKey ?: return
        viewModelScope.launch {
            state.update { it.copy(busy = true, errorKind = null, errorMessage = null) }
            runCatching {
                planningService.useCache(cacheKey, snapshot, current.config)
            }.onSuccess { outcome ->
                lastOutcome = outcome
                state.update {
                    it.copy(
                        busy = false,
                        review = outcome.review,
                        outcomeMeta = AiOutcomeMeta(
                            model = outcome.model,
                            fromCache = true,
                            cachedAt = outcome.cachedAt,
                            costMicros = outcome.costMicros,
                            promptTokens = 0,
                            completionTokens = 0,
                            latencyMillis = null,
                            tokensEstimated = false,
                        ),
                        selectedTaskIds = outcome.review.acceptedTaskEstimates.map { e -> e.taskId }.toSet(),
                    )
                }
            }.onFailure { error -> fail(error) }
        }
    }

    fun toggleTaskEstimate(taskId: Long) {
        state.update { current ->
            val accepted = current.review?.acceptedTaskEstimates?.any { it.taskId == taskId } == true
            if (!accepted) return@update current
            val selected = current.selectedTaskIds.toMutableSet()
            if (!selected.add(taskId)) selected.remove(taskId)
            current.copy(selectedTaskIds = selected)
        }
    }

    /**
     * 用户确认：只应用勾选且仍然合法的任务用时估计，然后记录已确认的计划。
     * AI 输出本身从不直接写库；这里是用户确认后的本地写入。
     */
    fun confirmAndApply() {
        val current = state.value
        val review = current.review ?: return
        val outcome = lastOutcome ?: return
        if (current.busy) return
        viewModelScope.launch {
            state.update { it.copy(busy = true, errorKind = null, errorMessage = null) }
            runCatching {
                val applied = mutableListOf<Long>()
                var skipped = 0
                review.acceptedTaskEstimates
                    .filter { it.taskId in current.selectedTaskIds }
                    .forEach { estimate ->
                        val task = taskRepository.getTask(estimate.taskId)
                        if (task == null) {
                            skipped += 1
                            return@forEach
                        }
                        val reason = AiPlanConstraintChecker.stillApplicable(
                            estimate = estimate,
                            currentStatus = task.status,
                            currentTaskId = task.id,
                        )
                        if (reason != null) {
                            skipped += 1
                            return@forEach
                        }
                        if (task.estimatedMinutes != estimate.estimatedMinutes) {
                            taskRepository.saveTask(task.copy(estimatedMinutes = estimate.estimatedMinutes))
                            taskRepository.recordAiSuggestionEvent(
                                taskId = task.id,
                                accepted = true,
                                reason = "AI 预计用时 ${estimate.estimatedMinutes} 分钟：${estimate.reason}",
                            )
                        }
                        applied += task.id
                    }
                planningService.recordConfirmedPlan(
                    outcome = outcome,
                    appliedTaskCount = applied.size,
                    usedCache = outcome.usedCache,
                )
                applied.size to skipped
            }.onSuccess { (applied, skipped) ->
                lastOutcome = null
                state.update {
                    it.copy(
                        busy = false,
                        review = null,
                        outcomeMeta = null,
                        selectedTaskIds = emptySet(),
                        notice = buildString {
                            append("已保存建议")
                            if (applied > 0) append("，并更新 ${applied} 个任务的预计用时")
                            if (skipped > 0) append("，跳过 $skipped 个已变化或已完成的任务")
                        },
                    )
                }
                refreshSnapshot()
            }.onFailure { error -> fail(error) }
        }
    }

    fun dismissError() = state.update { it.copy(errorKind = null, errorMessage = null) }

    fun consumeNotice() = state.update { it.copy(notice = null) }

    private fun fail(error: Throwable) {
        val kind = (error as? AiProviderException)?.kind ?: AiFailureKind.UNKNOWN
        state.update {
            it.copy(
                busy = false,
                errorKind = kind,
                errorMessage = error.message ?: "AI 请求失败，请稍后重试",
            )
        }
    }
}
