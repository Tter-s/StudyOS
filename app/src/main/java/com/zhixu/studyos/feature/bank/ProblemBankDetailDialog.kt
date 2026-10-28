package com.zhixu.studyos.feature.bank

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import com.zhixu.studyos.core.bank.ProblemPageRenderer
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemFormula
import com.zhixu.studyos.core.model.ProblemReviewStatus

/**
 * 题目详情：题干与公式的预览和修改、知识点/难度/用时、完成与复习状态、
 * 学习建议（含来源标注）、题目依赖、原始页面查看（V0.8 第二/三/四节）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProblemBankDetailDialog(
    problem: ProblemBankEntry,
    document: ProblemDocument?,
    pageRenderer: ProblemPageRenderer?,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (ProblemBankEntry) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var draft by remember(problem.id, problem.updatedAtEpochMillis) { mutableStateOf(problem) }
    var showPageViewer by remember { mutableStateOf(false) }

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
                        text = "第 ${problem.problemNumber} 题",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onDelete(problem.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "删除题目")
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    errorMessage?.let { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissError) { Text("知道了") }
                        }
                    }
                    if (problem.learningSuggestion != null) {
                        SuggestionCard(problem = problem)
                    }
                    if (problem.dependsOn.isNotEmpty()) {
                        AppInlineCard(title = "依赖题目") {
                            Text(
                                text = problem.dependsOn.joinToString("、") { "第 $it 题" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.statement,
                        onValueChange = { draft = draft.copy(statement = it) },
                        label = { Text("题干") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    FormulaSection(
                        formulas = draft.formulas,
                        onChange = { formulas -> draft = draft.copy(formulas = formulas) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.problemNumber,
                            onValueChange = { draft = draft.copy(problemNumber = it) },
                            label = { Text("题号") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draft.chapter,
                            onValueChange = { draft = draft.copy(chapter = it) },
                            label = { Text("章节") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.courseName,
                            onValueChange = { draft = draft.copy(courseName = it) },
                            label = { Text("课程") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draft.textbook,
                            onValueChange = { draft = draft.copy(textbook = it) },
                            label = { Text("教材") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.estimatedDifficulty?.toString().orEmpty(),
                            onValueChange = { value ->
                                draft = draft.copy(estimatedDifficulty = value.toIntOrNull()?.coerceIn(1, 5))
                            },
                            label = { Text("难度 1–5") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draft.estimatedMinutes?.toString().orEmpty(),
                            onValueChange = { value ->
                                draft = draft.copy(estimatedMinutes = value.toIntOrNull()?.takeIf { it > 0 })
                            },
                            label = { Text("预计用时(分)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draft.actualMinutes?.toString().orEmpty(),
                            onValueChange = { value ->
                                draft = draft.copy(actualMinutes = value.toIntOrNull()?.takeIf { it >= 0 })
                            },
                            label = { Text("实际用时(分)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Text(
                        text = "知识点（点击移除，输入后回车添加）",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    KnowledgePointEditor(
                        points = draft.knowledgePoints,
                        onChange = { draft = draft.copy(knowledgePoints = it) },
                    )
                    Text(text = "完成状态", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ProblemCompletionStatus.entries.forEachIndexed { index, status ->
                            SegmentedButton(
                                selected = draft.completionStatus == status,
                                onClick = { draft = draft.copy(completionStatus = status) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ProblemCompletionStatus.entries.size,
                                ),
                            ) {
                                Text(completionLabel(status))
                            }
                        }
                    }
                    Text(text = "复习状态", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ProblemReviewStatus.entries.forEachIndexed { index, status ->
                            SegmentedButton(
                                selected = draft.reviewStatus == status,
                                onClick = { draft = draft.copy(reviewStatus = status) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ProblemReviewStatus.entries.size,
                                ),
                            ) {
                                Text(reviewLabel(status))
                            }
                        }
                    }
                    if (document != null) {
                        TextButton(onClick = { showPageViewer = true }) {
                            Icon(Icons.Rounded.Image, contentDescription = null)
                            Spacer(modifier = Modifier.padding(2.dp))
                            Text("查看原始页面（第 ${problem.pageIndex + 1} 页）")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("取消") }
                    com.zhixu.studyos.core.designsystem.component.PrimaryButton(
                        text = "保存修改",
                        onClick = { onSave(draft) },
                        modifier = Modifier.weight(2f),
                    )
                }
            }
        }
    }
    if (showPageViewer && document != null && pageRenderer != null) {
        PageViewerDialog(
            document = document,
            initialPageIndex = problem.pageIndex,
            pageRenderer = pageRenderer,
            onDismiss = { showPageViewer = false },
        )
    }
}

@Composable
private fun SuggestionCard(problem: ProblemBankEntry) {
    AppInlineCard(
        title = "学习建议（${sourceLabel(problem.suggestionSource)}）",
    ) {
        Text(
            text = problem.learningSuggestion.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun sourceLabel(source: com.zhixu.studyos.core.model.ProblemAnalysisSource?): String =
    when (source) {
        com.zhixu.studyos.core.model.ProblemAnalysisSource.USER -> "用户修正"
        com.zhixu.studyos.core.model.ProblemAnalysisSource.HEURISTIC -> "本地规则分析，仅供参考"
        null -> "仅供参考"
    }

internal fun reviewLabel(status: ProblemReviewStatus): String = when (status) {
    ProblemReviewStatus.NOT_STARTED -> "未复习"
    ProblemReviewStatus.REVIEWING -> "复习中"
    ProblemReviewStatus.MASTERED -> "已掌握"
}

@Composable
private fun FormulaSection(
    formulas: List<ProblemFormula>,
    onChange: (List<ProblemFormula>) -> Unit,
) {
    if (formulas.isEmpty()) return
    // 每条公式是一个本地 WebView；大量公式一次性渲染会明显卡顿。
    // 默认只渲染前 8 条，其余按需展开（编辑字段同样按需展示）。
    var showAllFormulas by remember { mutableStateOf(false) }
    val visibleCount = if (showAllFormulas) formulas.size else minOf(formulas.size, MAX_FORMULA_PREVIEWS)
    Text(
        text = "公式（无法预览的已保留原文，请人工校对）",
        style = MaterialTheme.typography.labelLarge,
    )
    formulas.take(visibleCount).forEachIndexed { index, formula ->
        Column {
            if (formula.needsReview) {
                Text(
                    text = "该公式为推断结果，需要人工校对",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                    onChange(
                        formulas.mapIndexed { i, f ->
                            if (i == index) f.copy(latex = value) else f
                        },
                    )
                },
                label = { Text("LaTeX 原文（来源：${formula.originalSnippet.take(30)}）") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                onChange(formulas.filterIndexed { i, _ -> i != index })
            }) {
                Text("删除该公式")
            }
        }
    }
    if (formulas.size > MAX_FORMULA_PREVIEWS) {
        TextButton(onClick = { showAllFormulas = !showAllFormulas }) {
            Text(
                if (showAllFormulas) {
                    "收起后续公式"
                } else {
                    "显示其余 ${formulas.size - MAX_FORMULA_PREVIEWS} 条公式"
                },
            )
        }
    }
}

private const val MAX_FORMULA_PREVIEWS = 8

@Composable
private fun KnowledgePointEditor(
    points: List<com.zhixu.studyos.core.model.ProblemKnowledgePoint>,
    onChange: (List<com.zhixu.studyos.core.model.ProblemKnowledgePoint>) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        points.forEach { point ->
            FilterChip(
                selected = false,
                onClick = { onChange(points.filterNot { it.name == point.name }) },
                label = { Text(point.name) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("新增知识点") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val name = input.trim()
                if (name.isNotEmpty()) {
                    onChange(
                        points + com.zhixu.studyos.core.model.ProblemKnowledgePoint(
                            name = name,
                            source = com.zhixu.studyos.core.model.ProblemAnalysisSource.USER,
                        ),
                    )
                    input = ""
                }
            },
        ) { Text("添加") }
    }
}

/** 原始页面查看器：始终从保存的原始文件渲染（PDF 渲染/图片解码）。 */
@Composable
fun PageViewerDialog(
    document: ProblemDocument,
    initialPageIndex: Int,
    pageRenderer: ProblemPageRenderer,
    onDismiss: () -> Unit,
) {
    var pageIndex by remember { mutableStateOf(initialPageIndex) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(document.id, pageIndex) {
        loading = true
        error = null
        bitmap = null
        runCatching { pageRenderer.render(document, pageIndex, maxDimension = 2048) }
            .onSuccess { rendered -> bitmap = rendered; loading = false }
            .onFailure { failure ->
                error = failure.message ?: "页面渲染失败"
                loading = false
            }
    }

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
                        text = "${document.displayName} · 第 ${pageIndex + 1} / ${document.pageCount} 页",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { if (pageIndex > 0) pageIndex -= 1 },
                        enabled = pageIndex > 0 && !loading,
                    ) { Text("上一页") }
                    TextButton(
                        onClick = { if (pageIndex < document.pageCount - 1) pageIndex += 1 },
                        enabled = pageIndex < document.pageCount - 1 && !loading,
                    ) { Text("下一页") }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator()
                        error != null -> Text(
                            text = error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        bitmap != null -> Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "原始页面 ${pageIndex + 1}",
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppInlineCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}
