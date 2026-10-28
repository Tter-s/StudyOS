package com.zhixu.studyos.feature.bank

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.bank.ExerciseImportPipeline
import com.zhixu.studyos.core.bank.ProblemDocumentStore
import com.zhixu.studyos.core.bank.ProblemDraftJson
import com.zhixu.studyos.core.data.repository.ProblemBankRepository
import com.zhixu.studyos.core.model.ProblemAnalysisEngine
import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemDraftAnalysis
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemImportBatch
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import java.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 向导步骤：选择来源 → 分批处理 → 预览确认。 */
enum class ProblemImportStep { SELECT_SOURCE, PROCESSING, PREVIEW }

data class ProblemImportUiState(
    val step: ProblemImportStep = ProblemImportStep.SELECT_SOURCE,
    val busy: Boolean = false,
    val documents: List<ProblemDocument> = emptyList(),
    val resumable: ResumableBatch? = null,
    val batchId: Long? = null,
    val documentId: Long? = null,
    val documentName: String = "",
    val pageCount: Int = 0,
    val progress: com.zhixu.studyos.core.bank.ProblemImportProgress? = null,
    val drafts: List<ProblemDraft> = emptyList(),
    val analyses: Map<String, ProblemDraftAnalysis> = emptyMap(),
    val selectedIds: Set<String> = emptySet(),
    val courseName: String = "",
    val textbook: String = "",
    val analysisEngineName: String = "",
    val errorMessage: String? = null,
    val notice: String? = null,
) {
    val selectableDrafts: List<ProblemDraft>
        get() = drafts.filter { analyses[it.localId]?.isSelectable == true }

    val readyCount: Int
        get() = drafts.count { analyses[it.localId]?.state == com.zhixu.studyos.core.model.ProblemDraftState.READY }

    val needsReviewCount: Int
        get() = drafts.count {
            analyses[it.localId]?.state == com.zhixu.studyos.core.model.ProblemDraftState.NEEDS_REVIEW
        }

    data class ResumableBatch(val batch: ProblemImportBatch, val document: ProblemDocument)
}

/**
 * 习题册导入向导。
 * 识别结果一律先进入预览草稿，用户确认后单事务入库（V0.8 第五节）；
 * AI/OCR 输出不直接写库，失败页可重试，取消/退出后批次可恢复。
 */
class ProblemImportViewModel(
    private val repository: ProblemBankRepository,
    private val store: ProblemDocumentStore,
    private val pipeline: ExerciseImportPipeline,
    private val analysisEngine: ProblemAnalysisEngine,
    private val clock: Clock,
) : ViewModel() {

    private val state = MutableStateFlow(ProblemImportUiState())
    val uiState: StateFlow<ProblemImportUiState> = state.asStateFlow()

    private var runJob: Job? = null
    private var documentsJob: Job? = null

    init {
        refreshDocuments()
    }

    /**
     * 只保留一个来源列表订阅；重复调用不会叠加收集器
     * （否则每次 reset 都会新增一个永不结束的 collect）。
     */
    fun refreshDocuments() {
        if (documentsJob?.isActive == true) return
        documentsJob = viewModelScope.launch {
            repository.observeDocuments().collect { list ->
                state.update { it.copy(documents = list) }
            }
        }
    }

    fun setCourseName(value: String) = state.update { it.copy(courseName = value) }

    fun setTextbook(value: String) = state.update { it.copy(textbook = value) }

    /** PDF 选择回调。 */
    fun onPdfPicked(displayName: String, uri: Uri) {
        startImport(displayName) { store.importPdf(displayName, uri) }
    }

    /** 多张图片选择回调。 */
    fun onImagesPicked(displayName: String, uris: List<Uri>) {
        startImport(displayName) { store.importImages(displayName, uris) }
    }

    private fun startImport(
        displayName: String,
        import: suspend () -> ProblemDocumentStore.StoredSource,
    ) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.update {
                it.copy(
                    busy = true,
                    errorMessage = null,
                    notice = null,
                    documentName = displayName.substringBeforeLast('.'),
                )
            }
            runCatching { import() }
                .onSuccess { stored ->
                    runCatching {
                        val documentId = repository.createDocument(
                            displayName = displayName,
                            relativePath = stored.relativePath,
                            originalName = stored.originalName,
                            sizeBytes = stored.sizeBytes,
                            contentSha256 = stored.contentSha256,
                            pageCount = stored.pageCount,
                            type = stored.type,
                        )
                        val batchId = repository.createBatch(documentId, stored.pageCount)
                        state.update {
                            it.copy(
                                busy = false,
                                step = ProblemImportStep.PROCESSING,
                                batchId = batchId,
                                documentId = documentId,
                                pageCount = stored.pageCount,
                            )
                        }
                        runBatch(batchId)
                    }.onFailure { error ->
                        // 来源记录未建立（如内容重复）时删除已复制的文件
                        store.deleteStoredSource(stored.relativePath)
                        fail(error)
                    }
                }
                .onFailure { error -> fail(error) }
        }
    }

    /** 重新打开向导：若有未完成批次则提供继续/重试入口，已完成批次直接进入预览。 */
    fun openDocument(documentId: Long) {
        if (state.value.busy) return
        viewModelScope.launch {
            runCatching {
                val document = requireNotNull(repository.getDocument(documentId)) { "来源不存在" }
                state.update { it.copy(busy = true, errorMessage = null) }
                val resumable = repository.findResumableBatch(documentId)
                when {
                    resumable != null -> state.update {
                        it.copy(
                            busy = false,
                            step = ProblemImportStep.PROCESSING,
                            batchId = resumable.id,
                            documentId = documentId,
                            documentName = document.displayName,
                            pageCount = resumable.totalPages,
                            resumable = ProblemImportUiState.ResumableBatch(resumable, document),
                        )
                    }
                    else -> {
                        loadPreview(documentId, document.displayName)
                        state.update { it.copy(busy = false) }
                    }
                }
            }.onFailure { error -> fail(error) }
        }
    }

    fun resumeBatch() {
        val current = state.value
        val batchId = current.batchId ?: return
        runJob = viewModelScope.launch {
            state.update { it.copy(busy = true, resumable = null) }
            runCatching {
                pipeline.resumeBatch(
                    batchId = batchId,
                    courseName = current.courseName,
                    textbook = current.textbook,
                ) { progress -> state.update { it.copy(progress = progress) } }
            }
                .onSuccess { finishRun(batchId, it) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    fail(error)
                }
        }
    }

    fun retryFailed() {
        val current = state.value
        val batchId = current.batchId ?: return
        runJob = viewModelScope.launch {
            state.update { it.copy(busy = true) }
            runCatching {
                pipeline.retryFailed(
                    batchId = batchId,
                    courseName = current.courseName,
                    textbook = current.textbook,
                ) { progress -> state.update { it.copy(progress = progress) } }
            }
                .onSuccess { finishRun(batchId, it) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    fail(error)
                }
        }
    }

    fun cancelBatch() {
        val batchId = state.value.batchId ?: return
        viewModelScope.launch {
            runCatching { pipeline.cancel(batchId) }
            // 流水线在下一页前检测到 PAUSED 并优雅停止；这里同步刷新一次状态
            runCatching {
                repository.getBatch(batchId)?.let { batch ->
                    state.update {
                        it.copy(
                            progress = it.progress?.copy(status = batch.status)
                                ?: com.zhixu.studyos.core.bank.ProblemImportProgress(
                                    batchId = batch.id,
                                    documentId = batch.documentId,
                                    status = batch.status,
                                    totalPages = batch.totalPages,
                                    donePages = 0,
                                    failedPages = 0,
                                    pendingPages = batch.totalPages,
                                    currentPageIndex = null,
                                    items = emptyList(),
                                    fatalMessage = null,
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun runBatch(batchId: Long) {
        val current = state.value
        runJob = viewModelScope.launch {
            runCatching {
                pipeline.runBatch(
                    batchId = batchId,
                    courseName = current.courseName,
                    textbook = current.textbook,
                ) { progress -> state.update { it.copy(progress = progress) } }
            }
                .onSuccess { finishRun(batchId, it) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    fail(error)
                }
        }
    }

    private suspend fun finishRun(batchId: Long, progress: com.zhixu.studyos.core.bank.ProblemImportProgress) {
        state.update { it.copy(progress = progress) }
        val documentId = state.value.documentId ?: return
        val documentName = state.value.documentName
        if (progress.status == ProblemImportBatchStatus.ABORTED) {
            state.update { it.copy(busy = false, errorMessage = progress.fatalMessage ?: "导入失败") }
            return
        }
        when {
            // 全部成功：直接进入预览
            progress.status == ProblemImportBatchStatus.COMPLETED && progress.donePages > 0 -> {
                loadPreview(documentId, documentName)
            }
            // 有失败页：留在处理步骤，提供重试或跳过失败页进入预览
            progress.donePages > 0 -> {
                state.update {
                    it.copy(
                        busy = false,
                        step = ProblemImportStep.PROCESSING,
                        notice = "${progress.failedPages} 页处理失败，可重试或先预览已完成页",
                    )
                }
            }
            else -> {
                state.update {
                    it.copy(
                        busy = false,
                        step = ProblemImportStep.PROCESSING,
                        notice = "没有页面处理成功，可重试失败页",
                    )
                }
            }
        }
    }

    /** 跳过失败页，直接预览已完成页的草稿。 */
    fun enterPreview() {
        val current = state.value
        val documentId = current.documentId ?: return
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            runCatching { loadPreview(documentId, current.documentName) }
                .onFailure { error -> fail(error) }
        }
    }

    private suspend fun loadPreview(documentId: Long, documentName: String) {
        val batch = repository.findResumableBatch(documentId)
            ?: repository.findLatestBatch(documentId)
        val drafts = if (batch != null) pipeline.loadDrafts(batch.id) else emptyList()
        val existingKeys = repository.existingKeys(documentId)
        val textbook = state.value.textbook.ifBlank { documentName }
        val normalizedDrafts = drafts.map { draft ->
            draft.copy(
                documentId = documentId,
                courseName = state.value.courseName.ifBlank { draft.courseName },
                textbook = textbook,
            )
        }
        val suspectedKeys = repository.suspectedDuplicateKeys(textbook, documentId)
        keySet = existingKeys
        suspectedKeySet = suspectedKeys
        val analyses = com.zhixu.studyos.core.bank.ProblemDeduplicator.analyzeAll(
            drafts = normalizedDrafts,
            existingKeys = existingKeys,
            suspectedKeys = suspectedKeys,
        )
        val selectable = analyses.filterValues { it.isSelectable }.keys
        state.update {
            it.copy(
                busy = false,
                step = ProblemImportStep.PREVIEW,
                drafts = normalizedDrafts,
                analyses = analyses,
                selectedIds = selectable.toSet(),
                textbook = textbook,
                analysisEngineName = analysisEngine.name,
                notice = when {
                    normalizedDrafts.isEmpty() -> "该来源没有已解析的题目草稿，请先处理页面"
                    selectable.isEmpty() ->
                        "这些题目已全部入库或需要校对，没有可直接导入的新题目"
                    else -> it.notice
                },
            )
        }
    }

    /** 用户编辑草稿后重新分析（去重与待确认状态）。 */
    fun updateDraft(updated: ProblemDraft) {
        val current = state.value
        val drafts = current.drafts.map { if (it.localId == updated.localId) updated else it }
        val analyses = com.zhixu.studyos.core.bank.ProblemDeduplicator.analyzeAll(
            drafts = drafts,
            existingKeys = keySet,
            suspectedKeys = suspectedKeySet,
        )
        state.update { it.copy(drafts = drafts, analyses = analyses) }
    }

    private var keySet: Set<String> = emptySet()
    private var suspectedKeySet: Set<String> = emptySet()

    fun toggleDraftSelected(localId: String) = state.update { current ->
        val analysis = current.analyses[localId]
        if (analysis?.isSelectable != true) return@update current
        val selected = current.selectedIds.toMutableSet()
        if (!selected.add(localId)) selected.remove(localId)
        current.copy(selectedIds = selected)
    }

    fun removeDraft(localId: String) = state.update { current ->
        current.copy(
            drafts = current.drafts.filter { it.localId != localId },
            selectedIds = current.selectedIds - localId,
            analyses = current.analyses - localId,
        )
    }

    /** 确认入库：只提交选中且可选的草稿；单事务写入。 */
    fun confirmImport() {
        val current = state.value
        val selected = current.drafts.filter {
            it.localId in current.selectedIds && current.analyses[it.localId]?.isSelectable == true
        }
        if (selected.isEmpty()) {
            state.update { it.copy(errorMessage = "没有可入库的题目，请先修正待确认项") }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            runCatching { repository.confirmDrafts(selected) }
                .onSuccess { result ->
                    // 回到来源选择步骤，但保留入库结果通知供宿主显示
                    state.update {
                        it.copy(
                            busy = false,
                            step = ProblemImportStep.SELECT_SOURCE,
                            batchId = null,
                            documentId = null,
                            pageCount = 0,
                            progress = null,
                            resumable = null,
                            drafts = emptyList(),
                            analyses = emptyMap(),
                            selectedIds = emptySet(),
                            notice = "已入库 ${result.inserted} 道题" +
                                (if (result.skippedDuplicates > 0) "，跳过重复 ${result.skippedDuplicates} 道" else ""),
                        )
                    }
                }
                .onFailure { error -> fail(error) }
        }
    }

    fun dismissError() = state.update { it.copy(errorMessage = null) }

    fun consumeNotice() = state.update { it.copy(notice = null) }

    fun reset() {
        runJob?.cancel()
        // 保留已加载的来源列表，避免重置后列表空白；docs 订阅只保留一个
        val documents = state.value.documents
        state.value = ProblemImportUiState(documents = documents)
    }

    private fun fail(error: Throwable) {
        state.update {
            it.copy(
                busy = false,
                errorMessage = error.message ?: "导入失败，请重试",
            )
        }
    }
}
