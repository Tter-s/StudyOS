package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.bank.ProblemDeduplicator
import com.zhixu.studyos.core.data.local.ProblemBankDao
import com.zhixu.studyos.core.data.local.ProblemBankEntryEntity
import com.zhixu.studyos.core.data.local.ProblemBankFormulaEntity
import com.zhixu.studyos.core.data.local.ProblemBankKnowledgePointEntity
import com.zhixu.studyos.core.data.local.ProblemDocumentEntity
import com.zhixu.studyos.core.data.local.ProblemImportBatchEntity
import com.zhixu.studyos.core.data.local.ProblemImportItemEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemDocumentPage
import com.zhixu.studyos.core.model.ProblemDocumentType
import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemFormula
import com.zhixu.studyos.core.model.ProblemImportBatch
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import com.zhixu.studyos.core.model.ProblemImportItem
import com.zhixu.studyos.core.model.ProblemImportItemStatus
import com.zhixu.studyos.core.model.ProblemKnowledgePoint
import com.zhixu.studyos.core.model.ProblemPageTextSource
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ProblemConfirmResult(
    val inserted: Int,
    val skippedDuplicates: Int,
)

/** 题库分页/筛选条件。字段语义与 UI 一致，全部在 SQL 层过滤。 */
data class ProblemQuery(
    val documentId: Long? = null,
    val chapter: String? = null,
    val completionStatus: com.zhixu.studyos.core.model.ProblemCompletionStatus? = null,
    val onlyUnfinished: Boolean = false,
    val maxDifficulty: Int? = null,
    val searchText: String = "",
    val onlyFormulaNeedsReview: Boolean = false,
)

data class ProblemPage(
    val problems: List<ProblemBankEntry>,
    val totalCount: Int,
    val completedCount: Int,
)

interface ProblemBankRepository {
    fun observeDocuments(): Flow<List<ProblemDocument>>

    /** 完整列表（仅 AI 分析等需要全量摘要的场景使用；题库 UI 必须分页）。 */
    fun observeProblems(): Flow<List<ProblemBankEntry>>

    /** 分页查询题库，过滤与排序在数据库内完成。 */
    suspend fun pageProblems(query: ProblemQuery, limit: Int, offset: Int): ProblemPage

    /** 生成任务时需要全部命中筛选的题目（有上限，避免一次读取过多）。 */
    suspend fun problemsForSelection(query: ProblemQuery, limit: Int): List<ProblemBankEntry>

    suspend fun distinctChapters(): List<String>

    /** AI 分析用的困难题目候选（有上限，按更新时间倒序）。 */
    suspend fun difficultProblemCandidates(limit: Int): List<ProblemBankEntry>

    suspend fun getProblem(id: Long): ProblemBankEntry?
    suspend fun getDocument(id: Long): ProblemDocument?
    suspend fun findDocumentBySha256(sha256: String): ProblemDocument?

    /** 新建来源文档记录；相同内容（SHA-256）重复导入时抛出异常。 */
    suspend fun createDocument(
        displayName: String,
        relativePath: String,
        originalName: String,
        sizeBytes: Long,
        contentSha256: String,
        pageCount: Int,
        type: ProblemDocumentType,
    ): Long

    suspend fun upsertPageText(documentId: Long, pageIndex: Int, text: String, source: ProblemPageTextSource)

    suspend fun createBatch(documentId: Long, totalPages: Int): Long
    suspend fun findResumableBatch(documentId: Long): ProblemImportBatch?
    suspend fun findLatestBatch(documentId: Long): ProblemImportBatch?
    suspend fun getBatch(batchId: Long): ProblemImportBatch?
    suspend fun getBatchItems(batchId: Long): List<ProblemImportItem>
    suspend fun updateBatchStatus(batchId: Long, status: ProblemImportBatchStatus)

    /**
     * 用户确认后入库：在单个 Room 事务内逐条查重并写入题目、公式、知识点。
     * 调用方只传确认入库的草稿；同来源同章节同题号在事务内再次查重跳过，
     * 任何一条失败都会整体回滚，不会产生半批数据，也不会丢失此前已确认的题目。
     */
    suspend fun confirmDrafts(drafts: List<ProblemDraft>): ProblemConfirmResult

    /** 用户编辑已入库题目（题干、公式、元数据、状态）。 */
    suspend fun updateProblem(entry: ProblemBankEntry)

    suspend fun deleteProblem(problemId: Long)

    /** 删除来源文档；仍有题目关联时抛出异常，保证已确认题目不丢失。 */
    suspend fun deleteDocument(documentId: Long)

    suspend fun existingKeys(documentId: Long): Set<String>
    suspend fun suspectedDuplicateKeys(textbook: String, excludeDocumentId: Long): Set<String>
    suspend fun getPageText(documentId: Long, pageIndex: Int): ProblemDocumentPage?
    suspend fun problemsByIds(ids: List<Long>): List<ProblemBankEntry>
    suspend fun documentProblemCounts(): Map<Long, Int>
}

class OfflineProblemBankRepository(
    private val database: StudyOSDatabase,
    private val dao: ProblemBankDao,
    private val clock: Clock,
) : ProblemBankRepository {
    companion object {
        const val MAX_PAGE_SIZE = 2000
    }


    override fun observeDocuments(): Flow<List<ProblemDocument>> =
        dao.observeDocuments().map { rows -> rows.map { it.toDomainModel() } }

    override fun observeProblems(): Flow<List<ProblemBankEntry>> =
        dao.observeAllProblems().map { rows -> rows.map { it.toDomainModel() } }

    override suspend fun pageProblems(
        query: ProblemQuery,
        limit: Int,
        offset: Int,
    ): ProblemPage {
        require(limit in 1..MAX_PAGE_SIZE) { "分页大小应在 1–$MAX_PAGE_SIZE 之间" }
        require(offset >= 0) { "分页偏移不能为负" }
        val params = query.toSqlParams()
        val rows = dao.pageProblems(
            documentId = params.documentId,
            chapter = params.chapter,
            completionStatus = params.completionStatus,
            onlyUnfinished = params.onlyUnfinished,
            maxDifficulty = params.maxDifficulty,
            onlyFormulaReview = params.onlyFormulaReview,
            searchPattern = params.searchPattern,
            limit = limit,
            offset = offset,
        )
        val counts = dao.countProblems(
            documentId = params.documentId,
            chapter = params.chapter,
            completionStatus = params.completionStatus,
            onlyUnfinished = params.onlyUnfinished,
            maxDifficulty = params.maxDifficulty,
            onlyFormulaReview = params.onlyFormulaReview,
            searchPattern = params.searchPattern,
        )
        return ProblemPage(
            problems = rows.map { it.toDomainModel() },
            totalCount = counts.total,
            completedCount = counts.completed,
        )
    }

    override suspend fun problemsForSelection(query: ProblemQuery, limit: Int): List<ProblemBankEntry> {
        require(limit in 1..MAX_PAGE_SIZE) { "选择题目的上限应在 1–$MAX_PAGE_SIZE 之间" }
        val params = query.toSqlParams()
        return dao.pageProblems(
            documentId = params.documentId,
            chapter = params.chapter,
            completionStatus = params.completionStatus,
            onlyUnfinished = params.onlyUnfinished,
            maxDifficulty = params.maxDifficulty,
            onlyFormulaReview = params.onlyFormulaReview,
            searchPattern = params.searchPattern,
            limit = limit,
            offset = 0,
        ).map { it.toDomainModel() }
    }

    override suspend fun distinctChapters(): List<String> = dao.distinctChapters()

    override suspend fun difficultProblemCandidates(limit: Int): List<ProblemBankEntry> {
        require(limit in 1..200) { "困难题目候选上限应在 1–200 之间" }
        return dao.difficultProblemCandidates(limit).map { it.toDomainModel() }
    }

    private data class SqlParams(
        val documentId: Long?,
        val chapter: String?,
        val completionStatus: String?,
        val onlyUnfinished: Int,
        val maxDifficulty: Int?,
        val onlyFormulaReview: Int,
        val searchPattern: String,
    )

    private fun ProblemQuery.toSqlParams(): SqlParams {
        val text = searchText.trim()
        val escaped = if (text.isEmpty()) {
            ""
        } else {
            "%" + text
                .replace("|", "||")
                .replace("%", "|%")
                .replace("_", "|_") + "%"
        }
        return SqlParams(
            documentId = documentId,
            chapter = chapter?.takeIf { it.isNotBlank() },
            completionStatus = completionStatus?.name,
            onlyUnfinished = if (onlyUnfinished) 1 else 0,
            maxDifficulty = maxDifficulty,
            onlyFormulaReview = if (onlyFormulaNeedsReview) 1 else 0,
            searchPattern = escaped,
        )
    }

    override suspend fun getProblem(id: Long): ProblemBankEntry? =
        dao.getProblemWithDetails(id)?.toDomainModel()

    override suspend fun getDocument(id: Long): ProblemDocument? = dao.getDocument(id)?.toDomainModel()

    override suspend fun findDocumentBySha256(sha256: String): ProblemDocument? =
        dao.findDocumentBySha256(sha256)?.toDomainModel()

    override suspend fun createDocument(
        displayName: String,
        relativePath: String,
        originalName: String,
        sizeBytes: Long,
        contentSha256: String,
        pageCount: Int,
        type: ProblemDocumentType,
    ): Long {
        dao.findDocumentBySha256(contentSha256)?.let { existing ->
            throw IllegalArgumentException("相同内容的来源「${existing.displayName}」已导入过，不能重复导入")
        }
        val now = clock.millis()
        return dao.insertDocument(
            ProblemDocumentEntity(
                displayName = displayName.trim().ifEmpty { originalName },
                type = type.name,
                relativePath = relativePath,
                originalName = originalName,
                sizeBytes = sizeBytes,
                contentSha256 = contentSha256,
                pageCount = pageCount,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    override suspend fun upsertPageText(
        documentId: Long,
        pageIndex: Int,
        text: String,
        source: ProblemPageTextSource,
    ) {
        dao.upsertPage(
            com.zhixu.studyos.core.data.local.ProblemDocumentPageEntity(
                documentId = documentId,
                pageIndex = pageIndex,
                text = text,
                textSource = source.name,
                createdAtEpochMillis = clock.millis(),
            ),
        )
    }

    override suspend fun createBatch(documentId: Long, totalPages: Int): Long {
        require(totalPages > 0) { "批次页数必须大于 0" }
        val now = clock.millis()
        val batchId = dao.insertBatch(
            ProblemImportBatchEntity(
                documentId = documentId,
                status = ProblemImportBatchStatus.PAUSED.name,
                totalPages = totalPages,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        dao.insertItems(
            (0 until totalPages).map { pageIndex ->
                ProblemImportItemEntity(
                    batchId = batchId,
                    pageIndex = pageIndex,
                    status = ProblemImportItemStatus.PENDING.name,
                    attempts = 0,
                    lastError = null,
                    draftJson = null,
                )
            },
        )
        return batchId
    }

    override suspend fun findResumableBatch(documentId: Long): ProblemImportBatch? =
        dao.findResumableBatch(documentId)?.toDomainModel()

    override suspend fun findLatestBatch(documentId: Long): ProblemImportBatch? =
        dao.findLatestBatch(documentId)?.toDomainModel()

    override suspend fun getBatch(batchId: Long): ProblemImportBatch? = dao.getBatch(batchId)?.toDomainModel()

    override suspend fun getBatchItems(batchId: Long): List<ProblemImportItem> =
        dao.getItems(batchId).map { it.toDomainModel() }

    override suspend fun updateBatchStatus(batchId: Long, status: ProblemImportBatchStatus) {
        dao.updateBatchStatus(batchId, status, clock.millis())
    }

    override suspend fun confirmDrafts(drafts: List<ProblemDraft>): ProblemConfirmResult {
        if (drafts.isEmpty()) return ProblemConfirmResult(0, 0)
        return database.withTransaction {
            var inserted = 0
            var skipped = 0
            val now = clock.millis()
            drafts.forEach { draft ->
                require(draft.problemNumber.isNotBlank()) { "草稿题号不能为空，请先在预览中修正" }
                require(draft.statement.isNotBlank()) { "草稿「${draft.problemNumber}」题干不能为空，请先在预览中修正" }
                val existing = dao.findExistingNumbers(
                    documentId = draft.documentId,
                    chapter = draft.chapter.trim(),
                    numbers = listOf(draft.problemNumber.trim()),
                )
                if (existing.isNotEmpty()) {
                    skipped += 1
                    return@forEach
                }
                val problemId = dao.insertProblem(
                    ProblemBankEntryEntity(
                        documentId = draft.documentId,
                        pageIndex = draft.pageIndex,
                        courseId = null,
                        courseName = draft.courseName.trim(),
                        textbook = draft.textbook.trim(),
                        chapter = draft.chapter.trim(),
                        problemNumber = draft.problemNumber.trim(),
                        statement = draft.statement.trim(),
                        estimatedDifficulty = draft.estimatedDifficulty,
                        difficultySource = com.zhixu.studyos.core.model.ProblemAnalysisSource.HEURISTIC.name,
                        estimatedMinutes = draft.estimatedMinutes,
                        actualMinutes = null,
                        completionStatus = com.zhixu.studyos.core.model.ProblemCompletionStatus.NEW.name,
                        reviewStatus = com.zhixu.studyos.core.model.ProblemReviewStatus.NOT_STARTED.name,
                        learningSuggestion = draft.learningSuggestion,
                        suggestionSource = if (draft.learningSuggestion == null) {
                            null
                        } else {
                            com.zhixu.studyos.core.model.ProblemAnalysisSource.HEURISTIC.name
                        },
                        dependsOn = draft.dependsOn.joinToString("\n"),
                        importBatchId = null,
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
                dao.insertFormulas(
                    draft.formulas.map { formula ->
                        ProblemBankFormulaEntity(
                            problemId = problemId,
                            orderIndex = formula.orderIndex,
                            latex = formula.latex,
                            confidence = formula.confidence,
                            needsReview = formula.needsReview,
                            originalSnippet = formula.originalSnippet,
                        )
                    },
                )
                dao.insertKnowledgePoints(
                    draft.knowledgePoints.map { point ->
                        ProblemBankKnowledgePointEntity(
                            problemId = problemId,
                            name = point.name,
                            source = point.source.name,
                        )
                    },
                )
                inserted += 1
            }
            ProblemConfirmResult(inserted = inserted, skippedDuplicates = skipped)
        }
    }

    override suspend fun updateProblem(entry: ProblemBankEntry) {
        require(entry.id > 0) { "题目不存在" }
        require(entry.problemNumber.isNotBlank()) { "题号不能为空" }
        require(entry.statement.isNotBlank()) { "题干不能为空" }
        entry.estimatedDifficulty?.let { require(it in 1..5) { "难度应为 1–5" } }
        entry.actualMinutes?.let { require(it >= 0) { "实际用时不能为负" } }
        entry.estimatedMinutes?.let { require(it > 0) { "预计用时必须大于 0" } }
        require(entry.formulas.size <= ProblemDeduplicator.MAX_FORMULAS) { "公式数量过多" }
        database.withTransaction {
            val loaded = requireNotNull(dao.getProblemWithDetails(entry.id)) { "题目不存在，可能已被删除" }
            if (loaded.entry.chapter != entry.chapter.trim() ||
                loaded.entry.problemNumber != entry.problemNumber.trim()
            ) {
                val clash = dao.findExistingNumbers(
                    documentId = loaded.entry.documentId,
                    chapter = entry.chapter.trim(),
                    numbers = listOf(entry.problemNumber.trim()),
                )
                require(clash.isEmpty()) { "同来源已存在「${entry.chapter} ${entry.problemNumber}」" }
            }
            val now = clock.millis()
            dao.deleteFormulasForProblem(entry.id)
            dao.deleteKnowledgePointsForProblem(entry.id)
            dao.updateProblemEntity(
                loaded.entry.copy(
                    courseId = entry.courseId,
                    courseName = entry.courseName.trim(),
                    textbook = entry.textbook.trim(),
                    chapter = entry.chapter.trim(),
                    problemNumber = entry.problemNumber.trim(),
                    statement = entry.statement.trim(),
                    estimatedDifficulty = entry.estimatedDifficulty,
                    difficultySource = entry.difficultySource.name,
                    estimatedMinutes = entry.estimatedMinutes,
                    actualMinutes = entry.actualMinutes,
                    completionStatus = entry.completionStatus.name,
                    reviewStatus = entry.reviewStatus.name,
                    learningSuggestion = entry.learningSuggestion,
                    suggestionSource = entry.suggestionSource?.name,
                    dependsOn = entry.dependsOn.joinToString("\n"),
                    updatedAtEpochMillis = now,
                ),
            )
            entry.formulas.forEachIndexed { index, formula ->
                dao.insertFormulas(
                    listOf(
                        ProblemBankFormulaEntity(
                            problemId = entry.id,
                            orderIndex = index,
                            latex = formula.latex.trim(),
                            confidence = formula.confidence,
                            needsReview = formula.needsReview,
                            originalSnippet = formula.originalSnippet,
                        ),
                    ),
                )
            }
            dao.insertKnowledgePoints(
                entry.knowledgePoints.map { point ->
                    ProblemBankKnowledgePointEntity(
                        problemId = entry.id,
                        name = point.name.trim(),
                        source = point.source.name,
                    )
                },
            )
        }
    }

    override suspend fun deleteProblem(problemId: Long) {
        database.withTransaction { dao.deleteProblem(problemId) }
    }

    override suspend fun deleteDocument(documentId: Long) {
        database.withTransaction {
            val count = dao.countProblemsForDocument(documentId)
            if (count > 0) {
                throw IllegalArgumentException(
                    "该来源下仍有 $count 道题目，请先删除题目再删除来源",
                )
            }
            if (dao.getDocument(documentId) != null) {
                dao.deleteDocumentById(documentId)
            }
        }
    }

    override suspend fun existingKeys(documentId: Long): Set<String> =
        dao.findKeysForDocument(documentId)
            .map { ProblemDeduplicator.key(documentId, it.chapter, it.number) }
            .toSet()

    override suspend fun suspectedDuplicateKeys(textbook: String, excludeDocumentId: Long): Set<String> {
        if (textbook.isBlank()) return emptySet()
        return dao.findSuspectedKeys(textbook.trim(), excludeDocumentId)
            .map { ProblemDeduplicator.suspectedKey(textbook, it.chapter, it.number) }
            .toSet()
    }

    override suspend fun getPageText(documentId: Long, pageIndex: Int): ProblemDocumentPage? =
        dao.getPage(documentId, pageIndex)?.toDomainModel()

    override suspend fun problemsByIds(ids: List<Long>): List<ProblemBankEntry> =
        ids.mapNotNull { dao.getProblemWithDetails(it)?.toDomainModel() }

    override suspend fun documentProblemCounts(): Map<Long, Int> =
        dao.problemCountsByDocument().associate { it.documentId to it.problemCount }
}
