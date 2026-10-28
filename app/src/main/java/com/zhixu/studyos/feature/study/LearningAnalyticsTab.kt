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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.MetricCard
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.model.SubjectLearningStats

@Composable
fun LearningAnalyticsTab(state: LearningUiState) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("学习分析", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "按科目累计统计过程数据，帮助观察投入与完成情况。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "学习时长不等于学习效果",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "这里只陈列专注时长、完成率和习题记录，不用时长直接推断掌握程度，也不做排名或惩罚。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionHeader(title = "科目概览") }
        if (state.subjectAnalytics.stats.isEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text("还没有科目数据。", style = MaterialTheme.typography.titleSmall)
                }
            }
        } else {
            items(state.subjectAnalytics.stats, key = { it.subject.id }) { stats ->
                SubjectAnalyticsCard(stats)
            }
        }
        item {
            Text(
                "独立完成比例来自每次完成时记录的“是否独立完成”；重练后使用最新一次重练结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SubjectAnalyticsCard(stats: SubjectLearningStats) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(stats.subject.name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "学习时长",
                value = formatLearningMillis(stats.studyMillis),
                supportingText = "已完成计时与补录",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "任务完成率",
                value = formatLearningRate(stats.taskCompletionRate),
                supportingText = "${stats.taskCompleted}/${stats.taskTotal} 项任务",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "习题完成",
                value = stats.problemCompleted.toString(),
                supportingText = "当前已完成",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "困难题",
                value = stats.difficultProblemCount.toString(),
                supportingText = "标记为困难",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "独立完成比例：${formatLearningRate(stats.independentCompletionRate)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { stats.independentCompletionRate ?: 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
