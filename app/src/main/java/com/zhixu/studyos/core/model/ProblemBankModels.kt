package com.zhixu.studyos.core.model

import java.time.LocalDate

/** 导入来源类型。 */
enum class ProblemDocumentType { PDF, IMAGES }

/** 页面文字来源：PDF 内嵌文本、本地 OCR、无可识别文本。 */
enum class ProblemPageTextSource { EMBEDDED, OCR, NONE }

/** 完成状态。 */
enum class ProblemCompletionStatus { NEW, IN_PROGRESS, COMPLETED }

/** 复习状态。 */
enum class ProblemReviewStatus { NOT_STARTED, REVIEWING, MASTERED }

/** 难度/知识点/预计用时的来源：本地规则、用户修正。AI 引擎在 V0.9 通过独立接口接入。 */
enum class ProblemAnalysisSource { HEURISTIC, USER }

/** 导入批次状态。RUNNING 之外的状态都允许重新打开向导继续或重试。 */
enum class ProblemImportBatchStatus { RUNNING, PAUSED, COMPLETED, ABORTED }

/** 批次内单页处理状态。 */
enum class ProblemImportItemStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** 题库来源文档（已复制到应用私有目录的原始文件）。 */
data class ProblemDocument(
    val id: Long = 0,
    val displayName: String,
    val type: ProblemDocumentType,
    /** 应用私有目录内的相对路径；页面渲染与“查看原始页面”始终从该文件读取。 */
    val relativePath: String,
    val originalName: String,
    val sizeBytes: Long,
    val contentSha256: String,
    val pageCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** 来源文档内一页的最佳可用文本（内嵌文本优先，缺失时为 OCR 结果）。 */
data class ProblemDocumentPage(
    val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val text: String,
    val textSource: ProblemPageTextSource,
    val createdAtEpochMillis: Long,
)

/** 一次导入批次的持久化进度，支持取消、恢复与失败重试。 */
data class ProblemImportBatch(
    val id: Long = 0,
    val documentId: Long,
    val status: ProblemImportBatchStatus,
    val totalPages: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** 批次内一页的处理记录；DONE 后草稿 JSON 已持久化，进程结束也不会丢。 */
data class ProblemImportItem(
    val id: Long = 0,
    val batchId: Long,
    val pageIndex: Int,
    val status: ProblemImportItemStatus,
    val attempts: Int,
    val lastError: String?,
    val draftJson: String?,
)

/** 页面上的一个 LaTeX 公式候选。 */
data class ProblemFormula(
    val id: Long = 0,
    val orderIndex: Int,
    val latex: String,
    /** 0..1；显式 LaTeX 定界符更高，裸符号推断更低。 */
    val confidence: Double,
    val needsReview: Boolean,
    val originalSnippet: String,
)

/** 题目的一个知识点标签。 */
data class ProblemKnowledgePoint(
    val id: Long = 0,
    val name: String,
    val source: ProblemAnalysisSource,
)

/** 个人题库中的一道题，对应 V0.8 第二节的题目结构。 */
data class ProblemBankEntry(
    val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val courseId: Long?,
    val courseName: String,
    val textbook: String,
    val chapter: String,
    val problemNumber: String,
    val statement: String,
    val formulas: List<ProblemFormula> = emptyList(),
    val knowledgePoints: List<ProblemKnowledgePoint> = emptyList(),
    val estimatedDifficulty: Int? = null,
    val difficultySource: ProblemAnalysisSource = ProblemAnalysisSource.HEURISTIC,
    val estimatedMinutes: Int? = null,
    val actualMinutes: Int? = null,
    val completionStatus: ProblemCompletionStatus = ProblemCompletionStatus.NEW,
    val reviewStatus: ProblemReviewStatus = ProblemReviewStatus.NOT_STARTED,
    /** AI 分析给出的学习建议；本地规则分析时标注来源为 HEURISTIC。 */
    val learningSuggestion: String? = null,
    val suggestionSource: ProblemAnalysisSource? = null,
    /** AI/规则给出的题目依赖（本题依赖的其他题号）。 */
    val dependsOn: List<String> = emptyList(),
    val importBatchId: Long? = null,
    val createdAtEpochMillis: Long = 0,
    val updatedAtEpochMillis: Long = 0,
) {
    val hasFormulaNeedingReview: Boolean
        get() = formulas.any { it.needsReview }
}

/** 预览阶段的可编辑题目草稿；确认前不写题库表。 */
data class ProblemDraft(
    val localId: String,
    val documentId: Long,
    val pageIndex: Int,
    val problemNumber: String,
    val statement: String,
    val chapter: String,
    val courseName: String,
    val textbook: String,
    val formulas: List<ProblemFormula> = emptyList(),
    val knowledgePoints: List<ProblemKnowledgePoint> = emptyList(),
    val estimatedDifficulty: Int? = null,
    val estimatedMinutes: Int? = null,
    val learningSuggestion: String? = null,
    val dependsOn: List<String> = emptyList(),
    val needsReview: Set<ProblemDraftField> = emptySet(),
    val warnings: List<String> = emptyList(),
)

enum class ProblemDraftField { NUMBER, STATEMENT, FORMULA }

enum class ProblemDraftState { READY, NEEDS_REVIEW, INCOMPLETE, DUPLICATE, SUSPECTED_DUPLICATE }

/** 草稿的校验/去重分析结果，驱动预览列表的可选状态。 */
data class ProblemDraftAnalysis(
    val localId: String,
    val validationError: String?,
    val needsReview: Set<ProblemDraftField>,
    /** 与同批草稿或同来源已入库题目重复（同来源+章节+题号）。 */
    val duplicateOf: String?,
    /** 跨来源同教材+章节+题号的疑似重复，仅提示不阻断。 */
    val suspectedDuplicateOf: String?,
) {
    val state: ProblemDraftState
        get() = when {
            validationError != null -> ProblemDraftState.INCOMPLETE
            duplicateOf != null -> ProblemDraftState.DUPLICATE
            needsReview.isNotEmpty() -> ProblemDraftState.NEEDS_REVIEW
            suspectedDuplicateOf != null -> ProblemDraftState.SUSPECTED_DUPLICATE
            else -> ProblemDraftState.READY
        }

    val isSelectable: Boolean get() = state == ProblemDraftState.READY || state == ProblemDraftState.SUSPECTED_DUPLICATE
}

/** 单页解析结果。 */
data class ParsedExercisePage(
    val pageIndex: Int,
    val problems: List<ProblemDraft>,
    val textSource: ProblemPageTextSource,
    val textLength: Int,
    val warnings: List<String> = emptyList(),
)

/** 启发式/AI 分析结果（独立接口的输出，不直接写库）。 */
data class ProblemAnalysisResult(
    val knowledgePoints: List<String>,
    val estimatedDifficulty: Int?,
    val estimatedMinutes: Int?,
    val learningSuggestion: String?,
    val dependsOn: List<String>,
    val source: ProblemAnalysisSource,
)

/**
 * 题目分析引擎的独立接口。V0.8 只内置本地规则实现；
 * AI 引擎（V0.9 AIProvider）实现同一接口即可接入，核心解析/预览/入库流程不感知具体引擎。
 */
interface ProblemAnalysisEngine {
    val name: String

    fun analyze(statement: String, formulas: List<ProblemFormula>, chapter: String): ProblemAnalysisResult
}

/** 生成每日习题任务时的题目选择条件。 */
data class ProblemTaskSelection(
    val documentId: Long?,
    val chapter: String?,
    val maxDifficulty: Int?,
    /** 只选未完成的题目。 */
    val onlyNotCompleted: Boolean = true,
    val problemIds: Set<Long> = emptySet(),
)

/** 生成任务请求：按每日题量从起始日期开始拆分。 */
data class ProblemTaskPlanRequest(
    val selection: ProblemTaskSelection,
    val problemsPerDay: Int,
    val startDate: LocalDate,
    val courseName: String,
    val textbook: String,
)

/** 计划生成的一个每日习题任务。 */
data class ProblemTaskPlanItem(
    val date: LocalDate,
    val problemNumbers: List<String>,
    val title: String,
    val subject: String,
    val estimatedMinutes: Int,
)

data class ProblemTaskPlan(
    val items: List<ProblemTaskPlanItem>,
    val selectedCount: Int,
) {
    val dayCount: Int get() = items.size
}
