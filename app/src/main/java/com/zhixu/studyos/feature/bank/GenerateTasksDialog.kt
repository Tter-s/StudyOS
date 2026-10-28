package com.zhixu.studyos.feature.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.model.ProblemTaskPlan
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 「生成每日习题任务」对话框（V0.8 第六节）：
 * 选择每日题量与开始日期，预览按日拆分结果；确认后生成「待安排」任务，
 * 时间安排交给「学习 → 排程」中已有的本地排程引擎。
 */
@Composable
fun GenerateTasksDialog(
    plan: ProblemTaskPlan?,
    busy: Boolean,
    onPrepare: (problemsPerDay: Int, startDate: LocalDate) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var problemsPerDayText by remember { mutableStateOf("5") }
    var startDateText by remember { mutableStateOf(LocalDate.now().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成每日习题任务") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "把当前筛选下未完成的题目按每日题量拆分成任务。生成后任务为「待安排」状态，" +
                        "请到「学习 → 排程」运行本地排程。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = problemsPerDayText,
                        onValueChange = { problemsPerDayText = it },
                        label = { Text("每日题量") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = startDateText,
                        onValueChange = { startDateText = it },
                        label = { Text("开始日期(YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                }
                TextButton(
                    onClick = {
                        val perDay = problemsPerDayText.toIntOrNull()
                        val start = parseDate(startDateText)
                        if (perDay != null && perDay > 0 && start != null) {
                            onPrepare(perDay, start)
                        }
                    },
                    enabled = !busy,
                ) { Text("预览拆分") }
                if (plan != null) {
                    Text(
                        text = "共 ${plan.selectedCount} 题，将生成 ${plan.dayCount} 天的任务：",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    plan.items.forEach { item ->
                        Text(
                            text = "${item.date} · ${item.title} · 约 ${item.estimatedMinutes} 分钟",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "生成任务",
                onClick = onConfirm,
                enabled = plan != null && !busy,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
} catch (_: DateTimeParseException) {
    null
}
