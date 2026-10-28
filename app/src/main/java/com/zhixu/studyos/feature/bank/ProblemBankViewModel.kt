package com.zhixu.studyos.feature.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.bank.ExerciseTaskPlanner
import com.zhixu.studyos.core.data.repository.ProblemBankRepository
import com.zhixu.studyos.core.data.repository.ProblemQuery
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemTaskPlan
import com.zhixu.studyos.core.model.ProblemTaskPlanRequest
import com.zhixu.studyos.core.model.ProblemTaskSelection
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 题库列表的过滤条件。 */
data class ProblemBankFilter(
    val documentId: Long? = null,
    val chapter: String? = null,
    val completionStatus: ProblemCompletionStatus? = null,
    val onlyUnfinished: Boolean = false,
    val maxDifficulty: Int? = null,
    val searchText: String = "",
    val onlyFormulaNeedsReview: Boolean = false,
) {
    fun toQuery(): ProblemQuery = ProblemQuery(
        documentId = documentId,
        chapter = chapter,
        completionStatus = completionStatus,
        onlyUnfinished = onlyUnfinished,
        maxDifficulty = maxDifficulty,
        searchText = searchText,
        onlyFormulaNeedsReview = onlyFormulaNeedsReview,
    )
}

data class ProblemBankUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val problems: List<ProblemBankEntry> = emptyList(),
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val hasMore: Boolean = false,
    val documents: List<ProblemDocument> = emptyList(),
    val documentProblemCounts: Map<Long, Int> = emptyMap(),
    val chapters: List<String> = emptyList(),
    val filter: ProblemBankFilter = ProblemBankFilter(),
    val detailProblem: ProblemBankEntry? = null,
    val taskPlan: ProblemTaskPlan? = null,
    val taskPlanBusy: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
) {
    val problemCount: Int get() = totalCount
    val loadedCount: Int get() = problems.size
}

/**
 * 个人题库主界面状态。
 *
 * V1.0 起列表改为数据库分页（每页 [PAGE_SIZE] 条），筛选在 SQL 层完成，
 * 不再一次性把所有题目读入内存；生成任务时单独查询命中的全部题目（有上限）。
 * 数据全部来自 Room；生成任务只创建「待安排」任务，排程交给既有本地排程引擎。
 */
class ProblemBankViewModel(
    private val repository: ProblemBankRepository,
    private val taskRepository: TaskRepository,
    private val clock: Clock,
) : ViewModel() {

    private val filter = MutableStateFlow(ProblemBankFilter())
    private val detailProblem = MutableStateFlow<ProblemBankEntry?>(null)
    private val extra = MutableStateFlow(ExtraState())
    private val page = MutableStateFlow(PageState())

    private var pageJob: Job? = null
    private var documentsJob: Job? = null

    private data class ExtraState(
        val documents: List<ProblemDocument> = emptyList(),
        val taskPlan: ProblemTaskPlan? = null,
        val taskPlanBusy: Boolean = false,
        val errorMessage: String? = null,
        val notice: String? = null,
    )

    private data class PageState(
        val problems: List<ProblemBankEntry> = emptyList(),
        val totalCount: Int = 0,
        val completedCount: Int = 0,
        val chapters: List<String> = emptyList(),
        val documentCounts: Map<Long, Int> = emptyMap(),
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = false,
    )

    val uiState: StateFlow<ProblemBankUiState> = combine(
        page,
        filter,
        detailProblem,
        extra,
    ) { pageValue, filterValue, detail, extraValue ->
        ProblemBankUiState(
            isLoading = pageValue.isLoading,
            isLoadingMore = pageValue.isLoadingMore,
            problems = pageValue.problems,
            totalCount = pageValue.totalCount,
            completedCount = pageValue.completedCount,
            hasMore = pageValue.hasMore,
            documents = extraValue.documents,
            documentProblemCounts = pageValue.documentCounts,
            chapters = pageValue.chapters,
            filter = filterValue,
            detailProblem = detail,
            taskPlan = extraValue.taskPlan,
            taskPlanBusy = extraValue.taskPlanBusy,
            errorMessage = extraValue.errorMessage,
            notice = extraValue.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProblemBankUiState())

    init {
        documentsJob = viewModelScope.launch {
            repository.observeDocuments().collect { documents ->
                extra.update { it.copy(documents = documents) }
            }
        }
        reload()
    }

    /** 重新加载第一页（筛选变化、编辑/删除后调用）。 */
    fun reload() {
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            loadPage(reset = true)
        }
    }

    /** 加载下一页；重复调用或没有更多时忽略。 */
    fun loadMore() {
        val current = page.value
        if (!current.hasMore || current.isLoading || current.isLoadingMore) return
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            loadPage(reset = false)
        }
    }

    private suspend fun loadPage(reset: Boolean) {
        val filterValue = filter.value
        val current = page.value
        page.update {
            if (reset) {
                it.copy(isLoading = true, isLoadingMore = false)
            } else {
                it.copy(isLoadingMore = true)
            }
        }
        val query = filterValue.toQuery()
        val offset = if (reset) 0 else current.problems.size
        runCatching {
            val result = repository.pageProblems(query, PAGE_SIZE, offset)
            val chapters = repository.distinctChapters()
            val documentCounts = repository.documentProblemCounts()
            Triple(result, chapters, documentCounts)
        }.onSuccess { (result, chapters, documentCounts) ->
            currentCoroutineContext().ensureActive()
            if (filter.value != filterValue) return@onSuccess
            val merged = if (reset) {
                result.problems
            } else {
                (page.value.problems + result.problems).distinctBy { it.id }
            }
            page.value = page.value.copy(
                problems = merged,
                totalCount = result.totalCount,
                completedCount = result.completedCount,
                chapters = chapters,
                documentCounts = documentCounts,
                isLoading = false,
                isLoadingMore = false,
                hasMore = merged.size < result.totalCount,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            page.update { it.copy(isLoading = false, isLoadingMore = false) }
            showError(error)
        }
    }

    fun setFilterDocument(documentId: Long?) {
        filter.update { it.copy(documentId = documentId) }
        reload()
    }

    fun setFilterChapter(chapter: String?) {
        filter.update { it.copy(chapter = chapter?.takeIf(String::isNotBlank)) }
        reload()
    }

    fun setFilterCompletion(status: ProblemCompletionStatus?) {
        filter.update { it.copy(completionStatus = status) }
        reload()
    }

    fun setFilterMaxDifficulty(max: Int?) {
        filter.update { it.copy(maxDifficulty = max) }
        reload()
    }

    fun setFilterOnlyUnfinished(enabled: Boolean) {
        filter.update { it.copy(onlyUnfinished = enabled) }
        reload()
    }

    fun setFilterSearch(text: String) {
        filter.update { it.copy(searchText = text) }
        reload()
    }

    fun setFilterOnlyFormulaReview(enabled: Boolean) {
        filter.update { it.copy(onlyFormulaNeedsReview = enabled) }
        reload()
    }

    fun clearFilter() {
        filter.value = ProblemBankFilter()
        reload()
    }

    fun openProblem(problemId: Long) {
        viewModelScope.launch {
            runCatching { repository.getProblem(problemId) }
                .onSuccess { problem ->
                    if (problem == null) {
                        extra.update { it.copy(errorMessage = "题目不存在，可能已被删除") }
                    } else {
                        detailProblem.value = problem
                    }
                }
                .onFailure { error -> showError(error) }
        }
    }

    fun closeProblemDetail() {
        detailProblem.value = null
    }

    /** 用户修改题目（题干/公式/元数据/完成与复习状态）。 */
    fun saveProblemEdit(entry: ProblemBankEntry) {
        viewModelScope.launch {
            runCatching {
                // 只有用户真的改了难度才把难度来源标记为 USER，避免覆盖原有来源标注
                val original = repository.getProblem(entry.id)
                val difficultyChanged = original?.estimatedDifficulty != entry.estimatedDifficulty
                val toSave = if (difficultyChanged) {
                    entry.copy(difficultySource = com.zhixu.studyos.core.model.ProblemAnalysisSource.USER)
                } else {
                    entry.copy(difficultySource = original?.difficultySource ?: entry.difficultySource)
                }
                repository.updateProblem(toSave)
            }
                .onSuccess {
                    detailProblem.value = repository.getProblem(entry.id)
                    extra.update { it.copy(notice = "题目已保存") }
                    reload()
                }
                .onFailure { error -> showError(error) }
        }
    }

    fun deleteProblem(problemId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteProblem(problemId) }
                .onSuccess {
                    if (detailProblem.value?.id == problemId) detailProblem.value = null
                    extra.update { it.copy(notice = "题目已删除") }
                    reload()
                }
                .onFailure { error -> showError(error) }
        }
    }

    fun deleteDocument(documentId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteDocument(documentId) }
                .onSuccess {
                    if (filter.value.documentId == documentId) filter.update { it.copy(documentId = null) }
                    extra.update { it.copy(notice = "来源已删除") }
                    reload()
                }
                .onFailure { error -> showError(error) }
        }
    }

    fun consumeNotice() = extra.update { it.copy(notice = null) }

    fun dismissError() = extra.update { it.copy(errorMessage = null) }

    // ---------- 任务联动（V0.8 第六节） ----------

    /** 预览：把当前筛选命中的全部未完成题目按每日题量拆分成每日习题任务。 */
    fun prepareTaskPlan(problemsPerDay: Int, startDate: LocalDate) {
        viewModelScope.launch {
            extra.update { it.copy(taskPlanBusy = true, errorMessage = null) }
            runCatching {
                val current = filter.value
                // 任务生成不使用分页结果：单独查询全部命中项（上限 1000），且只取未完成
                val selected = repository.problemsForSelection(
                    query = current.toQuery().copy(onlyUnfinished = true, completionStatus = null),
                    limit = SELECTION_LIMIT,
                )
                require(selected.isNotEmpty()) { "当前筛选下没有可安排的未完成题目" }
                val representative = selected.first()
                val request = ProblemTaskPlanRequest(
                    selection = ProblemTaskSelection(
                        documentId = current.documentId,
                        chapter = current.chapter,
                        maxDifficulty = current.maxDifficulty,
                    ),
                    problemsPerDay = problemsPerDay,
                    startDate = startDate,
                    courseName = representative.courseName,
                    textbook = representative.textbook,
                )
                ExerciseTaskPlanner.plan(request, selected)
            }
                .onSuccess { plan -> extra.update { it.copy(taskPlan = plan, taskPlanBusy = false) } }
                .onFailure { error ->
                    extra.update { it.copy(taskPlanBusy = false) }
                    showError(error)
                }
        }
    }

    fun dismissTaskPlan() = extra.update { it.copy(taskPlan = null) }

    /** 确认生成：逐日创建「待安排」任务（无学习时段），随后交给排程页的本地排程引擎。 */
    fun confirmGenerateTasks() {
        val plan = extra.value.taskPlan ?: return
        viewModelScope.launch {
            extra.update { it.copy(taskPlanBusy = true) }
            runCatching {
                plan.items.forEach { item ->
                    taskRepository.saveTask(
                        StudyTask(
                            title = item.title,
                            description = "题目：${item.problemNumbers.joinToString("、")}",
                            subject = item.subject,
                            priority = TaskPriority.MEDIUM,
                            estimatedMinutes = item.estimatedMinutes,
                            dueDate = item.date,
                            status = TaskStatus.BACKLOG,
                            recurrence = TaskRecurrence.NONE,
                        ),
                    )
                }
            }
                .onSuccess {
                    extra.update {
                        it.copy(
                            taskPlan = null,
                            taskPlanBusy = false,
                            notice = "已生成 ${plan.items.size} 个每日习题任务，可在「学习 → 排程」中安排时间",
                        )
                    }
                }
                .onFailure { error ->
                    extra.update { it.copy(taskPlanBusy = false) }
                    showError(error)
                }
        }
    }

    private fun showError(error: Throwable) {
        extra.update { it.copy(errorMessage = error.message ?: "操作失败，请重试") }
    }

    companion object {
        const val PAGE_SIZE = 50
        const val SELECTION_LIMIT = 1000
    }
}
