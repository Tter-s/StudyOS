package com.zhixu.studyos.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.model.DailySelfControlTrend
import com.zhixu.studyos.core.model.SelfControlEntry
import com.zhixu.studyos.core.model.WeeklySelfControlTrend

@Composable
fun LearningSelfControlTab(
    state: LearningUiState,
    viewModel: LearningViewModel,
) {
    val overview = state.selfControl ?: return
    var showGoals by remember { mutableStateOf(false) }
    var showEntry by remember { mutableStateOf(false) }
    var deletingEntry by remember { mutableStateOf<SelfControlEntry?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("学习节奏", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "记录拖延与中断只用于理解自己的学习节奏，不用于自责或惩罚。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { showGoals = true },
                    modifier = Modifier.testTag("adjust_goals"),
                ) { Text("调整目标") }
                TextButton(
                    onClick = { showEntry = true },
                    modifier = Modifier.testTag("record_self_control"),
                ) { Text("记录一次状态") }
            }
        }
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("当前目标", style = MaterialTheme.typography.titleMedium)
                GoalProgressLine(
                    label = "今日学习",
                    actual = overview.today.actualMinutes,
                    target = overview.goals.dailyStudyMinutes,
                    unit = "分钟",
                )
                GoalProgressLine(
                    label = "本周学习",
                    actual = overview.week.actualMinutes,
                    target = overview.goals.weeklyStudyMinutes,
                    unit = "分钟",
                )
                GoalProgressLine(
                    label = "今日完成任务",
                    actual = overview.today.completedTasks,
                    target = overview.goals.dailyTaskTarget,
                    unit = "项",
                )
                Text(
                    "目标可以随时调整；未达成的数据不会触发惩罚或负面评价。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionHeader(title = "今天") }
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "计划完成 ${overview.today.completedTasks}/${overview.today.plannedTasks} · " +
                        formatLearningRate(overview.today.completionRate),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "任务预计 ${formatLearningMinutes(overview.today.plannedMinutes.toLong())}，" +
                        "实际计时 ${formatLearningMinutes(overview.today.actualMinutes.toLong())}；" +
                        formatSignedMinutes(overview.today.timeDeviationMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "计时中断 ${overview.today.timerInterruptions} 次 · " +
                        "主动中断记录 ${overview.today.voluntaryInterruptions} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionHeader(title = "本周") }
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "计划完成 ${overview.week.completedTasks}/${overview.week.plannedTasks} · " +
                        formatLearningRate(overview.week.completionRate),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "任务预计 ${formatLearningMinutes(overview.week.plannedMinutes.toLong())}，" +
                        "实际计时 ${formatLearningMinutes(overview.week.actualMinutes.toLong())}；" +
                        formatSignedMinutes(overview.week.timeDeviationMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "计时中断 ${overview.week.timerInterruptions} 次 · " +
                        "主动中断记录 ${overview.week.voluntaryInterruptions} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionHeader(title = "每日趋势（近 7 天）") }
        items(overview.dailyTrend) { daily ->
            DailyTrendRow(daily)
        }
        item { SectionHeader(title = "每周趋势（近 4 周）") }
        items(overview.weeklyTrend) { weekly ->
            WeeklyTrendRow(weekly)
        }
        item { SectionHeader(title = "最近的状态记录") }
        if (overview.entries.isEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text("还没有状态记录。可以在需要时记下拖延原因或主动中断。")
                }
            }
        } else {
            items(overview.entries, key = { it.id }) { entry ->
                SelfControlEntryCard(
                    entry = entry,
                    onDelete = { deletingEntry = entry },
                )
            }
        }
    }

    if (showGoals) {
        GoalsDialog(
            initial = overview.goals,
            onDismiss = { showGoals = false },
            onSave = viewModel::saveGoals,
        )
    }
    if (showEntry) {
        SelfControlEntryDialog(
            defaultDate = state.today,
            onDismiss = { showEntry = false },
            onSave = { date, reason, voluntary, note, onResult ->
                viewModel.addSelfControlEntry(date, reason, voluntary, note, onResult)
            },
        )
    }
    deletingEntry?.let { entry ->
        LearningDeleteConfirmDialog(
            title = "删除状态记录？",
            message = "只会删除这条自控记录，不影响任务和学习时长。",
            onDismiss = { deletingEntry = null },
            onConfirm = { viewModel.deleteSelfControlEntry(entry.id) },
        )
    }
}

@Composable
private fun GoalProgressLine(label: String, actual: Int, target: Int?, unit: String) {
    Column {
        Text(
            if (target == null) "$label：未设置" else "$label：$actual / $target $unit",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (target != null && target > 0) {
            LinearProgressIndicator(
                progress = { (actual.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DailyTrendRow(trend: DailySelfControlTrend) {
    val completionRate = trend.completionRate
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatShortLearningDate(trend.date), style = MaterialTheme.typography.titleSmall)
            Text(formatLearningRate(trend.completionRate), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "计划 ${trend.plannedTasks} 项，完成 ${trend.completedTasks} 项 · " +
                "预计 ${formatLearningMinutes(trend.plannedMinutes.toLong())}，" +
                "实际 ${formatLearningMinutes(trend.actualMinutes.toLong())} · 中断 ${trend.interruptions} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (completionRate != null) {
            LinearProgressIndicator(
                progress = { completionRate },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WeeklyTrendRow(trend: WeeklySelfControlTrend) {
    val completionRate = trend.completionRate
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${formatShortLearningDate(trend.start)}–${formatShortLearningDate(trend.end)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(formatLearningRate(trend.completionRate), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "计划 ${trend.plannedTasks} 项，完成 ${trend.completedTasks} 项 · " +
                "实际学习 ${formatLearningMinutes(trend.actualMinutes.toLong())}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (completionRate != null) {
            LinearProgressIndicator(
                progress = { completionRate },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SelfControlEntryCard(entry: SelfControlEntry, onDelete: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatShortLearningDate(entry.date), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onDelete, modifier = Modifier.padding(start = 8.dp)) { Text("删除") }
        }
        Text(
            buildString {
                append(entry.procrastinationReason?.label ?: "未记录具体原因")
                if (entry.voluntaryInterruption) append(" · 发生了主动中断")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (entry.note.isNotBlank()) {
            Text(
                entry.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
