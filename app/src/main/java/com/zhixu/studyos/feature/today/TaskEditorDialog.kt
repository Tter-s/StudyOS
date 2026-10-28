package com.zhixu.studyos.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.model.DailyTask
import com.zhixu.studyos.core.model.MAX_DAILY_TASK_TITLE_LENGTH

@Composable
fun TaskEditorDialog(
    initial: DailyTask?,
    dateLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    val focusRequester = remember { FocusRequester() }
    val valid = title.isNotBlank()

    LaunchedEffect(initial?.id) {
        kotlinx.coroutines.delay(120)
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加待办" else "编辑待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_DAILY_TASK_TITLE_LENGTH) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("task_title_input"),
                    label = { Text("要做什么") },
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (valid) {
                                onSave(title)
                                onDismiss()
                            }
                        },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        onSave(title)
                        onDismiss()
                    }
                },
                enabled = valid,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                if (onDelete != null) {
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
