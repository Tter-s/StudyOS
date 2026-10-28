package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.data.local.ProblemBankDao
import com.zhixu.studyos.core.data.local.ProblemDocumentPageEntity
import com.zhixu.studyos.core.data.local.ProblemImportBatchEntity
import com.zhixu.studyos.core.data.local.ProblemImportItemEntity
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import com.zhixu.studyos.core.model.ProblemImportItemStatus
import com.zhixu.studyos.core.model.ProblemPageTextSource
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 流水线进度快照，驱动向导页的进度 UI。 */
data class ProblemImportProgress(
    val batchId: Long,
    val documentId: Long,
    val status: ProblemImportBatchStatus,
    val totalPages: Int,
    val donePages: Int,
    val failedPages: Int,
    val pendingPages: Int,
    val currentPageIndex: Int?,
    val items: List<ItemProgress>,
    val fatalMessage: String?,
) {
    val isFinished: Boolean
        get() = status != ProblemImportBatchStatus.RUNNING

    data class ItemProgress(
        val pageIndex: Int,
        val status: ProblemImportItemStatus,
        val attempts: Int,
        val lastError: String?,
        val problemCount: Int,
    )
}

/**
 * 习题册批量导入流水线（V0.8 一.5/一.6）。
 *
 * - 大文件按页分批处理：每页「取文本（内嵌优先，缺失 OCR）→ 解析 → 分析 → 持久化」；
 *   每页完成后立即写入数据库，进程中断也不丢失已完成页。
 * - 取消：协作式检查数据库批次状态与协程取消，把 RUNNING 页回退为 PENDING。
 * - 恢复：跳过 DONE 页，继续处理剩余页；页面文本已持久化时直接复用。
 * - 失败重试：单页失败只记录错误并继续；FAILED 页可整体重试，attempts 递增。
 * - 草稿以 JSON 持久化到批次条目，确认入库前始终可重新预览与编辑。
 */
class ExerciseImportPipeline(
    private val dao: ProblemBankDao,
    private val sourceFactory: ExercisePageSourceFactory,
    private val parser: ExerciseProblemParser,
    private val ocrRecognizer: ExerciseOcrRecognizer?,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ocrPageMaxDimension: Int = 1600,
) {
    private val activeRuns = mutableSetOf<Long>()
    private val activeRunsMutex = Mutex()

    suspend fun runBatch(
        batchId: Long,
        courseName: String,
        textbook: String,
        onProgress: suspend (ProblemImportProgress) -> Unit = {},
    ): ProblemImportProgress = withContext(ioDispatcher) {
        val batch = requireNotNull(dao.getBatch(batchId)) { "导入批次不存在" }.toDomainModel()
        require(batch.status != ProblemImportBatchStatus.COMPLETED) { "该批次已完成处理" }
        activeRunsMutex.withLock {
            require(batchId !in activeRuns) { "批次正在处理中" }
            activeRuns += batchId
        }
        val document = requireNotNull(dao.getDocument(batch.documentId)) {
            "来源文档不存在，可能已被删除"
        }.toDomainModel()
        dao.updateBatchStatus(batchId, ProblemImportBatchStatus.RUNNING, clock.millis())
        var currentPage: Int? = null
        var fatalMessage: String? = null
        var stoppedByCancel = false
        try {
            sourceFactory.open(document).use { source ->
                val items = dao.getItems(batchId).map { it.toDomainModel() }
                val doneNumbers = items
                    .filter { it.status == ProblemImportItemStatus.DONE }
                    .mapNotNull { item ->
                        item.draftJson?.let { json ->
                            ProblemDraftJson.decodeDrafts(json, batch.documentId)
                                .mapNotNull { it.problemNumber.toIntOrNull() }
                                .maxOrNull()
                        }
                    }
                var lastNumber = doneNumbers.maxOrNull()
                for (item in items) {
                    currentCoroutineContext().ensureActive()
                    // 用户已取消（数据库状态被改为 PAUSED）时优雅停止
                    val batchNow = dao.getBatch(batchId)?.toDomainModel()
                    if (batchNow?.status != ProblemImportBatchStatus.RUNNING) {
                        stoppedByCancel = true
                        break
                    }
                    if (item.status == ProblemImportItemStatus.DONE ||
                        item.status == ProblemImportItemStatus.SKIPPED
                    ) {
                        continue
                    }
                    currentPage = item.pageIndex
                    val attempts = item.attempts + 1
                    dao.updateItem(
                        itemId = item.id,
                        status = ProblemImportItemStatus.RUNNING,
                        attempts = attempts,
                        lastError = item.lastError,
                        draftJson = item.draftJson,
                    )
                    try {
                        val result = processPage(source, document, item.pageIndex, courseName, textbook, lastNumber)
                        dao.upsertPage(
                            ProblemDocumentPageEntity(
                                documentId = batch.documentId,
                                pageIndex = item.pageIndex,
                                text = result.text,
                                textSource = result.textSource.name,
                                createdAtEpochMillis = clock.millis(),
                            ),
                        )
                        val draftJson = ProblemDraftJson.encodeDrafts(result.drafts)
                        dao.updateItem(
                            itemId = item.id,
                            status = ProblemImportItemStatus.DONE,
                            attempts = attempts,
                            lastError = null,
                            draftJson = draftJson,
                        )
                        lastNumber = result.drafts
                            .mapNotNull { it.problemNumber.toIntOrNull() }
                            .maxOrNull() ?: lastNumber
                    } catch (error: CancellationException) {
                        dao.updateItem(
                            itemId = item.id,
                            status = ProblemImportItemStatus.PENDING,
                            attempts = item.attempts,
                            lastError = item.lastError,
                            draftJson = item.draftJson,
                        )
                        throw error
                    } catch (error: Exception) {
                        dao.updateItem(
                            itemId = item.id,
                            status = ProblemImportItemStatus.FAILED,
                            attempts = attempts,
                            lastError = error.message ?: "处理失败",
                            draftJson = item.draftJson,
                        )
                    }
                    onProgress(progressOf(batchId, batch.documentId, batch.totalPages, currentPage))
                }
            }
        } catch (error: CancellationException) {
            dao.updateBatchStatus(batchId, ProblemImportBatchStatus.PAUSED, clock.millis())
            throw error
        } catch (error: Exception) {
            fatalMessage = error.message ?: "导入失败"
            dao.updateBatchStatus(batchId, ProblemImportBatchStatus.ABORTED, clock.millis())
        } finally {
            activeRunsMutex.withLock { activeRuns -= batchId }
        }
        if (fatalMessage == null) {
            val items = dao.getItems(batchId).map { it.toDomainModel() }
            val failed = items.count { it.status == ProblemImportItemStatus.FAILED }
            val status = when {
                // 用户取消：保持 PAUSED，即使没有失败页，等待用户继续或直接预览
                stoppedByCancel -> ProblemImportBatchStatus.PAUSED
                failed == 0 -> ProblemImportBatchStatus.COMPLETED
                else -> ProblemImportBatchStatus.PAUSED
            }
            dao.updateBatchStatus(batchId, status, clock.millis())
        }
        progressOf(batchId, batch.documentId, batch.totalPages, currentPage, fatalMessage).also { onProgress(it) }
    }

    /** 用户取消：置为 PAUSED，正在运行的 runBatch 会在下一页前停止。 */
    suspend fun cancel(batchId: Long) {
        dao.updateBatchStatus(batchId, ProblemImportBatchStatus.PAUSED, clock.millis())
    }

    /** 失败重试：把 FAILED 页重新入队并继续处理。 */
    suspend fun retryFailed(
        batchId: Long,
        courseName: String,
        textbook: String,
        onProgress: suspend (ProblemImportProgress) -> Unit = {},
    ): ProblemImportProgress {
        val batch = requireNotNull(dao.getBatch(batchId)) { "导入批次不存在" }
        val items = dao.getItems(batchId).map { it.toDomainModel() }
        val failed = items.filter { it.status == ProblemImportItemStatus.FAILED }
        require(failed.isNotEmpty()) { "没有需要重试的页面" }
        failed.forEach { item ->
            dao.updateItem(
                itemId = item.id,
                status = ProblemImportItemStatus.PENDING,
                attempts = item.attempts,
                lastError = item.lastError,
                draftJson = item.draftJson,
            )
        }
        return runBatch(batchId, courseName, textbook, onProgress)
    }

    /** 进程中断后重新打开向导时的恢复入口：DONE 页自动跳过。 */
    suspend fun resumeBatch(
        batchId: Long,
        courseName: String,
        textbook: String,
        onProgress: suspend (ProblemImportProgress) -> Unit = {},
    ): ProblemImportProgress {
        val batch = requireNotNull(dao.getBatch(batchId)) { "导入批次不存在" }
        val isActive = activeRunsMutex.withLock { batchId in activeRuns }
        require(!isActive) { "批次正在处理中" }
        return runBatch(batchId, courseName, textbook, onProgress)
    }

    /** 读取批次草稿（全部 DONE 页），供预览编辑。 */
    suspend fun loadDrafts(batchId: Long): List<com.zhixu.studyos.core.model.ProblemDraft> {
        val batch = requireNotNull(dao.getBatch(batchId)) { "导入批次不存在" }
        return dao.getItems(batchId).map { it.toDomainModel() }
            .filter { it.status == ProblemImportItemStatus.DONE && it.draftJson != null }
            .sortedBy { it.pageIndex }
            .flatMap { item -> ProblemDraftJson.decodeDrafts(item.draftJson!!, batch.documentId) }
    }

    private suspend fun processPage(
        source: ExercisePageSource,
        document: ProblemDocument,
        pageIndex: Int,
        courseName: String,
        textbook: String,
        lastNumber: Int?,
        ): PageResult {
        val savedPage = dao.getPage(document.id, pageIndex)
        var text = savedPage?.text.orEmpty()
        var textSource = savedPage?.let { ProblemPageTextSource.valueOf(it.textSource) }
        if (text.isBlank()) {
            text = source.embeddedText(pageIndex).trim()
            textSource = if (text.isNotBlank()) ProblemPageTextSource.EMBEDDED else null
        }
        if (text.isBlank() && ocrRecognizer != null) {
            val bitmap = source.renderPage(pageIndex, ocrPageMaxDimension)
            try {
                text = ocrRecognizer.recognize(bitmap).trim()
                textSource = if (text.isNotBlank()) ProblemPageTextSource.OCR else ProblemPageTextSource.NONE
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        val effectiveSource = textSource ?: ProblemPageTextSource.NONE
        val drafts = if (text.isBlank()) {
            emptyList()
        } else {
            parser.parse(
                pageIndex = pageIndex,
                pageText = text,
                textSource = effectiveSource,
                courseName = courseName,
                textbook = textbook,
                firstNumberHint = lastNumber?.plus(1),
            ).problems
        }
        return PageResult(text = text, textSource = effectiveSource, drafts = drafts)
    }

    private suspend fun progressOf(
        batchId: Long,
        documentId: Long,
        totalPages: Int,
        currentPageIndex: Int?,
        fatalMessage: String? = null,
    ): ProblemImportProgress {
        val batch = dao.getBatch(batchId)
        val items = dao.getItems(batchId).map { item ->
            val domain = item.toDomainModel()
            ProblemImportProgress.ItemProgress(
                pageIndex = domain.pageIndex,
                status = domain.status,
                attempts = domain.attempts,
                lastError = domain.lastError,
                problemCount = item.draftJson?.let {
                    runCatching { ProblemDraftJson.decodeDrafts(it, documentId).size }.getOrDefault(0)
                } ?: 0,
            )
        }
        return ProblemImportProgress(
            batchId = batchId,
            documentId = documentId,
            status = batch?.toDomainModel()?.status ?: ProblemImportBatchStatus.ABORTED,
            totalPages = totalPages,
            donePages = items.count { it.status == ProblemImportItemStatus.DONE },
            failedPages = items.count { it.status == ProblemImportItemStatus.FAILED },
            pendingPages = items.count {
                it.status == ProblemImportItemStatus.PENDING || it.status == ProblemImportItemStatus.RUNNING
            },
            currentPageIndex = currentPageIndex,
            items = items,
            fatalMessage = fatalMessage,
        )
    }

    private data class PageResult(
        val text: String,
        val textSource: ProblemPageTextSource,
        val drafts: List<com.zhixu.studyos.core.model.ProblemDraft>,
    )
}
