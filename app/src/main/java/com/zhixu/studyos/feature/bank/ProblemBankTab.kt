package com.zhixu.studyos.feature.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemCompletionStatus

/**
 * 学习页「题库」标签：个人结构化习题库（V0.8）。
 * 所有数据来自 Room；空状态/错误状态/加载状态齐备。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProblemBankTab(
    state: ProblemBankUiState,
    onStartImportPdf: () -> Unit,
    onStartImportImages: () -> Unit,
    onOpenProblem: (Long) -> Unit,
    onFilterDocument: (Long?) -> Unit,
    onFilterChapter: (String?) -> Unit,
    onFilterCompletion: (ProblemCompletionStatus?) -> Unit,
    onFilterOnlyUnfinished: (Boolean) -> Unit,
    onFilterSearch: (String) -> Unit,
    onClearFilter: () -> Unit,
    onDismissError: () -> Unit,
    onLoadMore: () -> Unit,
    onPrepareTaskPlan: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "个人题库",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "已加载 ${state.loadedCount} / ${state.problemCount} 题 · 已完成 ${state.completedCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                text = "导入 PDF",
                onClick = onStartImportPdf,
                enabled = true,
            )
            SecondaryButton(
                text = "导入图片",
                onClick = onStartImportImages,
            )
            SecondaryButton(
                text = "生成每日任务",
                onClick = { onPrepareTaskPlan(5) },
                enabled = state.problems.any {
                    it.completionStatus != ProblemCompletionStatus.COMPLETED
                },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.filter.searchText,
            onValueChange = onFilterSearch,
            label = { Text("搜索题干 / 题号 / 知识点") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bank_search"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (state.documents.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filter.documentId == null,
                    onClick = { onFilterDocument(null) },
                    label = { Text("全部来源") },
                )
                state.documents.forEach { document ->
                    FilterChip(
                        selected = state.filter.documentId == document.id,
                        onClick = { onFilterDocument(document.id) },
                        label = {
                            Text(
                                "${document.displayName}（${state.documentProblemCounts[document.id] ?: 0}）",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (state.chapters.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filter.chapter == null,
                    onClick = { onFilterChapter(null) },
                    label = { Text("全部章节") },
                )
                state.chapters.forEach { chapter ->
                    FilterChip(
                        selected = state.filter.chapter == chapter,
                        onClick = {
                            onFilterChapter(if (state.filter.chapter == chapter) null else chapter)
                        },
                        label = { Text(chapter, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                FilterChip(
                    selected = state.filter.onlyUnfinished,
                    onClick = { onFilterOnlyUnfinished(!state.filter.onlyUnfinished) },
                    label = { Text("只看未完成") },
                )
                FilterChip(
                    selected = state.filter.completionStatus == ProblemCompletionStatus.COMPLETED,
                    onClick = {
                        onFilterCompletion(
                            if (state.filter.completionStatus == ProblemCompletionStatus.COMPLETED) {
                                null
                            } else {
                                ProblemCompletionStatus.COMPLETED
                            },
                        )
                    },
                    label = { Text("只看已完成") },
                )
                FilterChip(
                    selected = false,
                    onClick = onClearFilter,
                    label = { Text("清除筛选") },
                )
            }
        }
        state.errorMessage?.let { message ->
            // 错误以横幅显示，不替换列表；用户可关闭后继续操作
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) { Text("知道了") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.problems.isEmpty() -> EmptyBankState(
                hasAnyProblem = state.documents.isEmpty(),
                onImportPdf = onStartImportPdf,
                onImportImages = onStartImportImages,
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("bank_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.problems, key = { it.id }) { problem ->
                    ProblemRow(problem = problem, onClick = { onOpenProblem(problem.id) })
                }
                if (state.hasMore || state.isLoadingMore) {
                    item(key = "load_more") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isLoadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                SecondaryButton(
                                    text = "加载更多（已加载 ${state.loadedCount}/${state.problemCount}）",
                                    onClick = onLoadMore,
                                    modifier = Modifier.testTag("bank_load_more"),
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyBankState(
    hasAnyProblem: Boolean,
    onImportPdf: () -> Unit,
    onImportImages: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        EmptyState(
            title = if (hasAnyProblem) "还没有导入习题册" else "当前筛选没有题目",
            message = if (hasAnyProblem) {
                "导入数学分析、高等代数、解析几何等课程的习题册 PDF 或多张图片，" +
                    "建立个人结构化题库。识别结果先预览、确认后入库。"
            } else {
                "尝试调整筛选条件，或继续导入新的页面。"
            },
            icon = Icons.AutoMirrored.Rounded.EventNote,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasAnyProblem) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                PrimaryButton(text = "导入 PDF", onClick = onImportPdf)
                SecondaryButton(text = "导入图片", onClick = onImportImages)
            }
        }
    }
}

@Composable
private fun ProblemRow(problem: ProblemBankEntry, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${problem.problemNumber} 题",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = listOf(problem.chapter, "第 ${problem.pageIndex + 1} 页")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = problem.statement,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                problem.knowledgePoints.take(3).forEach { point ->
                    AssistChip(onClick = {}, label = { Text(point.name, style = MaterialTheme.typography.labelSmall) })
                }
                problem.estimatedDifficulty?.let { difficulty ->
                    Text(
                        text = "难度 $difficulty",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                problem.estimatedMinutes?.let { minutes ->
                    Text(
                        text = "约 $minutes 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (problem.hasFormulaNeedingReview) {
                    Text(
                        text = "公式待校对",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = completionLabel(problem.completionStatus),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

internal fun completionLabel(status: ProblemCompletionStatus): String = when (status) {
    ProblemCompletionStatus.NEW -> "未开始"
    ProblemCompletionStatus.IN_PROGRESS -> "进行中"
    ProblemCompletionStatus.COMPLETED -> "已完成"
}
