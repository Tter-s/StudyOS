package com.zhixu.studyos.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp

/**
 * 单个任务的完整事件历史。默认不会随任务删除而级联清空；
 * 用户可在这里明确选择永久删除该任务的历史。
 */
@Composable
fun TaskHistoryDialog(
    state: TaskHistoryUiState,
    onDismiss: () -> Unit,
    onDeleteHistory: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务历史") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    state.isLoading -> Row(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) { CircularProgressIndicator() }
                    state.errorMessage != null -> Text(
                        state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.events.isEmpty() -> Text(
                        "这个任务还没有历史事件。V2.0 之前创建的任务不会追溯生成事件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Text(
                            "共 ${state.events.size} 条事件（最新在前）。事件来源区分用户、本地排程、AI 建议与系统。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.events.forEach { view ->
                            HorizontalDivider()
                            Text(
                                "${view.timeLabel} · ${view.title}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (view.detail.isNotBlank()) {
                                Text(
                                    view.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                "来源：${view.sourceLabel}" +
                                    if (view.event.reason.isNullOrBlank()) "" else " · ${view.event.reason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) { Text("永久删除历史") }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("永久删除任务历史？") },
            text = {
                Text(
                    "只会删除这个任务的本地事件历史，不会删除任务本身或其他记录。此操作无法撤销；" +
                        "如果你需要保留审计痕迹，请先导出 JSON 或备份。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteHistory()
                    },
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
