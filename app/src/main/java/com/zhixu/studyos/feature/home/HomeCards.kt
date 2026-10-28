package com.zhixu.studyos.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.sleep.SleepPhase

@Composable
internal fun NextClassCard(nextClass: NextClassInfo?) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("下一节课", style = MaterialTheme.typography.titleMedium)
        if (nextClass == null) {
            Text(
                "今天没有后续课程，或尚未设置课表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }
        Text(nextClass.title, style = MaterialTheme.typography.titleLarge)
        Text(
            listOf(nextClass.detail, nextClass.timeLabel()).filter(String::isNotBlank).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CurrentTaskCard(task: StudyTask?, plannedMinutes: Int) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("当前学习任务", style = MaterialTheme.typography.titleMedium)
        if (task == null) {
            Text(
                if (plannedMinutes > 0) {
                    "当前没有正在进行的任务；今日计划学习 $plannedMinutes 分钟。"
                } else {
                    "当前没有正在进行的任务。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }
        Text(task.title, style = MaterialTheme.typography.titleLarge)
        Text(
            listOf(
                task.subject.takeIf(String::isNotBlank),
                task.status.name,
                "${task.estimatedMinutes} 分钟",
            ).filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SleepProtectionCard(state: HomeUiState) {
    val status = state.sleepStatus
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("睡眠保护", style = MaterialTheme.typography.titleMedium)
        if (status == null) {
            Text(
                "尚未读取睡眠设置。可在“我的 → 睡眠与作息”中设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }
        val phase = when (status.phase) {
            SleepPhase.ASLEEP -> "睡眠保护中"
            SleepPhase.WIND_DOWN -> "睡前缓冲"
            SleepPhase.NAP -> "午休"
            SleepPhase.AWAKE -> "清醒"
        }
        Text(
            phase,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(status.message, style = MaterialTheme.typography.bodySmall)
        state.sleepDescription?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun UpcomingExamCard(state: HomeUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("考试倒计时", style = MaterialTheme.typography.titleMedium)
        val exam = state.nextExam
        if (exam == null) {
            Text(
                "没有即将到来的考试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }
        Text(exam.exam.name, style = MaterialTheme.typography.titleLarge)
        Text(
            "${exam.exam.subject} · ${exam.exam.date}" +
                (exam.exam.startMinute?.let { " ${"%02d:%02d".format(it / 60, it % 60)}" } ?: "") +
                " · ${exam.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (exam.daysRemaining <= 1) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
internal fun ExerciseReviewCard(state: HomeUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("今日习题复习", style = MaterialTheme.typography.titleMedium)
        Text(
            state.exerciseReviewDue?.let { "有 $it 道题待巩固；先独立尝试，再按需查看提示。" }
                ?: "暂无可统计的习题记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun VocabularyDueCard(state: HomeUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("英语待复习", style = MaterialTheme.typography.titleMedium)
        Text(
            state.vocabularyDue?.let { "$it 个单词到期；可在“学习 → 词汇”开始复习。" }
                ?: "尚未建立词汇库。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun HomeSuggestionCard(state: HomeUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("学习建议", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.aiSuggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "这是离线规则建议；AI 建议只会在你主动请求时生成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
