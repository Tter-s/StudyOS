package com.zhixu.studyos.feature.bank

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.bank.ProblemImportProgress
import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemDraftState
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import com.zhixu.studyos.core.model.ProblemImportItemStatus

/**
 * 习题册导入向导（V0.8 第一节）：
 * 1. 选择来源：PDF（系统文档选择器）或多张图片（系统 Photo Picker，不申请存储权限）。
 * 2. 分批处理：每页「文本提取（PDF 内嵌优先）→ 本地 OCR → 解析 → 分析」，可取消/继续/重试。
 * 3. 预览确认：草稿逐条编辑，仅可选条目可勾选，确认后单事务入库。
 */
@Composable
fun ProblemImportDialog(
    viewModel: ProblemImportViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                    Text(
                        text = when (state.step) {
                            ProblemImportStep.SELECT_SOURCE -> "导入习题册"
                            ProblemImportStep.PROCESSING -> "正在处理（第 ${state.progress?.donePages ?: 0}/${state.pageCount} 页）"
                            ProblemImportStep.PREVIEW -> "预览确认（${state.selectedIds.size}/${state.drafts.size} 已选）"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                when (state.step) {
                    ProblemImportStep.SELECT_SOURCE -> SourceStep(state, viewModel)
                    ProblemImportStep.PROCESSING -> ProcessingStep(state, viewModel)
                    ProblemImportStep.PREVIEW -> PreviewStep(state, viewModel)
                }
                state.errorMessage?.let { message ->
                    ErrorBanner(message = message, onDismiss = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun SourceStep(state: ProblemImportUiState, viewModel: ProblemImportViewModel) {
    val context = LocalContext.current
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "习题册.pdf"
            viewModel.onPdfPicked(name, uri)
        }
    }
    val imagesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onImagesPicked("图片习题册", uris)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = state.courseName,
            onValueChange = viewModel::setCourseName,
            label = { Text("课程（如：数学分析）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.textbook,
            onValueChange = viewModel::setTextbook,
            label = { Text("教材（如：数学分析下册 习题册）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = "选择 PDF",
                onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "选择多张图片",
                onClick = {
                    imagesLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "大文件将按页分批处理；处理可取消、中断后可继续、失败页可重试。" +
                "识别结果先预览，确认后才会入库。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "已导入来源（点击可继续未完成批次或查看已解析草稿）",
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.documents.isEmpty()) {
            EmptyState(
                title = "还没有导入过习题册",
                message = "选择一个 PDF 或若干张习题册照片开始建立题库。",
                icon = Icons.Rounded.Description,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.documents, key = { it.id }) { document ->
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                text = document.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when (document.type) {
                                    com.zhixu.studyos.core.model.ProblemDocumentType.PDF -> "PDF · "
                                    com.zhixu.studyos.core.model.ProblemDocumentType.IMAGES -> "图片 · "
                                } + "${document.pageCount} 页 · ${formatSize(document.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { viewModel.openDocument(document.id) }) {
                                Text("继续 / 查看草稿")
                            }
                        }
                    }
                }
            }
        }
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                Text("正在复制并解析来源文件…")
            }
        }
    }
}

@Composable
private fun ProcessingStep(state: ProblemImportUiState, viewModel: ProblemImportViewModel) {
    val progress = state.progress
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (progress == null) {
            if (state.resumable != null) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "该来源有一个未完成的导入批次（${state.resumable.batch.totalPages} 页），可继续处理。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                PrimaryButton(
                    text = "继续处理",
                    onClick = viewModel::resumeBatch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
            } else {
                Text("正在准备处理…")
            }
        } else {
            val finished = progress.status != ProblemImportBatchStatus.RUNNING
            Text(
                text = when (progress.status) {
                    ProblemImportBatchStatus.RUNNING -> "处理中：已完成 ${progress.donePages} 页，" +
                        "失败 ${progress.failedPages} 页，剩余 ${progress.pendingPages} 页"
                    ProblemImportBatchStatus.PAUSED -> "已暂停：完成 ${progress.donePages} 页，失败 ${progress.failedPages} 页"
                    ProblemImportBatchStatus.COMPLETED -> "处理完成：${progress.donePages} 页"
                    ProblemImportBatchStatus.ABORTED -> "处理中止：${progress.fatalMessage ?: "未知错误"}"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (progress.failedPages > 0) {
                SecondaryButton(
                    text = "重试失败页（${progress.failedPages}）",
                    onClick = viewModel::retryFailed,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (progress.status == ProblemImportBatchStatus.PAUSED && progress.pendingPages > 0) {
                SecondaryButton(
                    text = "继续处理剩余页（${progress.pendingPages}）",
                    onClick = viewModel::resumeBatch,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (finished && progress.donePages > 0) {
                PrimaryButton(
                    text = if (progress.failedPages > 0) "先预览已完成页" else "进入预览确认",
                    onClick = viewModel::enterPreview,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag("import_page_list"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(progress.items, key = { it.pageIndex }) { item ->
                    PageItemRow(item)
                }
            }
            TextButton(
                onClick = viewModel::cancelBatch,
                enabled = progress.status == ProblemImportBatchStatus.RUNNING,
            ) { Text("取消处理（保留已完成页）") }
        }
    }
}

@Composable
private fun PageItemRow(item: ProblemImportProgress.ItemProgress) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.status) {
            ProblemImportItemStatus.PENDING -> Text(
                "待处理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProblemImportItemStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.padding(horizontal = 4.dp).height(16.dp),
                strokeWidth = 2.dp,
            )
            ProblemImportItemStatus.DONE -> Text(
                "完成（${item.problemCount} 题）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            ProblemImportItemStatus.FAILED -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = "失败",
                tint = MaterialTheme.colorScheme.error,
            )
            ProblemImportItemStatus.SKIPPED -> Text(
                "跳过",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(modifier = Modifier.padding(4.dp))
        Text(
            text = "第 ${item.pageIndex + 1} 页" +
                (item.lastError?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewStep(state: ProblemImportUiState, viewModel: ProblemImportViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "可入库 ${state.readyCount} · 待校对 ${state.needsReviewCount} · " +
                "重复/无效 ${state.drafts.size - state.readyCount - state.needsReviewCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "分析引擎：${state.analysisEngineName}。识别结果不保证完全正确，请核对后入库。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("import_preview_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.drafts, key = { it.localId }) { draft ->
                DraftCard(
                    draft = draft,
                    analysis = state.analyses[draft.localId],
                    selected = draft.localId in state.selectedIds,
                    onToggle = { viewModel.toggleDraftSelected(draft.localId) },
                    onUpdate = viewModel::updateDraft,
                    onRemove = { viewModel.removeDraft(draft.localId) },
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = viewModel::reset,
                modifier = Modifier.weight(1f),
            ) { Text("放弃本次预览") }
            PrimaryButton(
                text = "确认入库（${state.selectedIds.size}）",
                onClick = viewModel::confirmImport,
                enabled = state.selectedIds.isNotEmpty() && !state.busy,
                modifier = Modifier.weight(2f),
            )
        }
    }
}

@Composable
private fun DraftCard(
    draft: ProblemDraft,
    analysis: com.zhixu.studyos.core.model.ProblemDraftAnalysis?,
    selected: Boolean,
    onToggle: () -> Unit,
    onUpdate: (ProblemDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember(draft.localId) { mutableStateOf(false) }
    val state = analysis?.state ?: ProblemDraftState.NEEDS_REVIEW
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                enabled = analysis?.isSelectable == true,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "第 ${draft.problemNumber.ifBlank { "?" }} 题 · 第 ${draft.pageIndex + 1} 页",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = draft.statement,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "编辑")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "移除草稿")
            }
        }
        when (state) {
            ProblemDraftState.READY -> Unit
            ProblemDraftState.NEEDS_REVIEW -> Text(
                text = "待校对：" + (analysis?.needsReview?.joinToString("、") { fieldLabel(it) } ?: ""),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
            ProblemDraftState.INCOMPLETE -> Text(
                text = analysis?.validationError ?: "数据不完整",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
            ProblemDraftState.DUPLICATE -> Text(
                text = analysis?.duplicateOf ?: "重复题目，不能入库",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
            ProblemDraftState.SUSPECTED_DUPLICATE -> Text(
                text = analysis?.suspectedDuplicateOf ?: "疑似重复，请确认",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        draft.warnings.take(2).forEach { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            OutlinedTextField(
                value = draft.problemNumber,
                onValueChange = { onUpdate(draft.copy(problemNumber = it)) },
                label = { Text("题号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.chapter,
                onValueChange = { onUpdate(draft.copy(chapter = it)) },
                label = { Text("章节") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.statement,
                onValueChange = { onUpdate(draft.copy(statement = it)) },
                label = { Text("题干") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            draft.formulas.forEachIndexed { index, formula ->
                FormulaPreview(
                    latex = formula.latex,
                    displayMode = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
                OutlinedTextField(
                    value = formula.latex,
                    onValueChange = { value ->
                        onUpdate(
                            draft.copy(
                                formulas = draft.formulas.mapIndexed { i, f ->
                                    if (i == index) f.copy(latex = value) else f
                                },
                            ),
                        )
                    },
                    label = { Text("公式 LaTeX") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.estimatedDifficulty?.toString().orEmpty(),
                    onValueChange = { value ->
                        onUpdate(draft.copy(estimatedDifficulty = value.toIntOrNull()?.coerceIn(1, 5)))
                    },
                    label = { Text("难度 1–5") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.estimatedMinutes?.toString().orEmpty(),
                    onValueChange = { value ->
                        onUpdate(draft.copy(estimatedMinutes = value.toIntOrNull()?.takeIf { it > 0 }))
                    },
                    label = { Text("预计用时(分)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (draft.knowledgePoints.isNotEmpty()) {
                Text(
                    text = "知识点：" + draft.knowledgePoints.joinToString("、") { it.name },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.padding(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

internal fun fieldLabel(field: com.zhixu.studyos.core.model.ProblemDraftField): String = when (field) {
    com.zhixu.studyos.core.model.ProblemDraftField.NUMBER -> "题号"
    com.zhixu.studyos.core.model.ProblemDraftField.STATEMENT -> "题干"
    com.zhixu.studyos.core.model.ProblemDraftField.FORMULA -> "公式"
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }.getOrNull()
