package com.zhixu.studyos.feature.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus

private enum class ProblemFilter { ALL, OPEN, COMPLETED, NEEDS_RETRY }

@Composable
fun LearningProblemsTab(
    state: LearningUiState,
    viewModel: LearningViewModel,
) {
    var filter by remember { mutableStateOf(ProblemFilter.ALL) }
    var editingProblem by remember { mutableStateOf<Problem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showSubjectManager by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Problem?>(null) }
    val filtered = state.problems.filter { problem ->
        when (filter) {
            ProblemFilter.ALL -> true
            ProblemFilter.OPEN -> problem.status != ProblemStatus.COMPLETED
            ProblemFilter.COMPLETED -> problem.status == ProblemStatus.COMPLETED
            ProblemFilter.NEEDS_RETRY -> problem.isMistake || problem.isDifficult
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("习题管理", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "记录独立思考、卡题原因与重练结果，不把使用提示等同于独立完成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showSubjectManager = true }) { Text("科目") }
                    IconButton(
                        onClick = {
                            editingProblem = null
                            showEditor = true
                        },
                        modifier = Modifier.testTag("add_problem"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "录入习题")
                    }
                }
            }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = filter == ProblemFilter.ALL,
                        onClick = { filter = ProblemFilter.ALL },
                        label = { Text("全部 ${state.problems.size}") },
                    )
                    FilterChip(
                        selected = filter == ProblemFilter.OPEN,
                        onClick = { filter = ProblemFilter.OPEN },
                        label = { Text("未完成") },
                    )
                    FilterChip(
                        selected = filter == ProblemFilter.COMPLETED,
                        onClick = { filter = ProblemFilter.COMPLETED },
                        label = { Text("已完成") },
                    )
                    FilterChip(
                        selected = filter == ProblemFilter.NEEDS_RETRY,
                        onClick = { filter = ProblemFilter.NEEDS_RETRY },
                        label = { Text("错题/困难题") },
                    )
                }
            }
            if (filtered.isEmpty()) {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Text("当前筛选下没有习题记录。", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "手动录入后可记录教材、章节、题号、知识点和每次练习结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item { SectionHeader(title = "习题列表") }
                items(filtered, key = { it.id }) { problem ->
                    ProblemCard(
                        problem = problem,
                        subjectName = formatSubjectName(state.subjects, problem.subjectId),
                        onClick = {
                            editingProblem = problem
                            showEditor = true
                        },
                        onRetry = { viewModel.retryProblem(problem.id) },
                    )
                }
            }
        }
    }

    if (showEditor) {
        ProblemEditorDialog(
            initial = editingProblem,
            subjects = state.subjects,
            onDismiss = { showEditor = false },
            onSave = viewModel::saveProblem,
            onDelete = editingProblem?.let { problem ->
                { deleteTarget = problem }
            },
        )
    }
    if (showSubjectManager) {
        SubjectManagerDialog(
            subjects = state.subjects,
            onDismiss = { showSubjectManager = false },
            onAdd = viewModel::addSubject,
        )
    }
    deleteTarget?.let { problem ->
        LearningDeleteConfirmDialog(
            title = "删除习题记录？",
            message = "将删除这道习题及其重练状态，不会影响计时或任务数据。",
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteProblem(problem.id)
                showEditor = false
            },
        )
    }
}

@Composable
private fun ProblemCard(
    problem: Problem,
    subjectName: String,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("problem_${problem.id}")
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildString {
                        if (problem.textbook.isNotBlank()) append(problem.textbook).append(" · ")
                        if (problem.chapter.isNotBlank()) append(problem.chapter).append(" · ")
                        append(problem.problemNumber.ifBlank { "未写题号" })
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    buildString {
                        append(subjectName)
                        append(" · ")
                        append(problem.status.label)
                        if (problem.knowledgePoint.isNotBlank()) append(" · ${problem.knowledgePoint}")
                        if (problem.independentThinkingMinutes > 0) {
                            append(" · 独立思考 ${problem.independentThinkingMinutes} 分钟")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = buildList {
                    if (problem.isMistake) add("错题")
                    if (problem.isDifficult) add("困难题")
                    if (problem.usedAiHint) add("用过 AI 提示")
                    if (problem.retryCount > 0) add("已重练 ${problem.retryCount} 次")
                    problem.blockerReason?.let { add(it.label) }
                }
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (problem.status == ProblemStatus.COMPLETED) {
                    Text(
                        "本次${if (problem.latestAttemptIndependent) "独立完成" else "在提示/协助下完成"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (problem.retryCount > 0 && problem.secondAttemptIndependent != null) {
                        Text(
                            "第二次重练：${if (problem.secondAttemptIndependent == true) "独立完成" else "未独立完成"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (problem.status == ProblemStatus.COMPLETED && (problem.isMistake || problem.isDifficult)) {
                TextButton(onClick = onRetry) { Text("重新练习") }
            }
        }
    }
}
