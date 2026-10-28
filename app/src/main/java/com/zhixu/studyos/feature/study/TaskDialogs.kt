package com.zhixu.studyos.feature.study

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskConflict
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.task.TaskPlanner
import com.zhixu.studyos.core.task.TaskSessionLockPolicy
import com.zhixu.studyos.core.task.TaskValidation
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class SessionDraft(
    val date: String,
    val start: String,
    val end: String,
)

@Composable
fun TaskEditorDialog(
    initial: StudyTask?,
    defaultDate: LocalDate,
    startScheduled: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (StudyTask, (String?) -> Unit) -> Unit,
) {
    val recurringInstance = initial?.seriesId != null
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var subject by remember { mutableStateOf(initial?.subject.orEmpty()) }
    var priority by remember { mutableStateOf(initial?.priority ?: TaskPriority.MEDIUM) }
    var estimateInput by remember { mutableStateOf((initial?.estimatedMinutes ?: 60).toString()) }
    var dueInput by remember { mutableStateOf(initial?.dueDate?.toString().orEmpty()) }
    var unscheduled by remember {
        mutableStateOf(!startScheduled && initial != null && initial.sessions.isEmpty())
    }
    var sessionDrafts by remember {
        mutableStateOf(
            initial?.sessions?.takeIf(List<TaskSession>::isNotEmpty)?.map {
                SessionDraft(it.date.toString(), formatTaskMinute(it.startMinute), formatTaskMinute(it.endMinuteExclusive))
            } ?: listOf(SessionDraft(defaultDate.toString(), "19:00", "20:00")),
        )
    }
    var recurrence by remember {
        mutableStateOf(if (initial == null) TaskRecurrence.NONE else initial.recurrence)
    }
    var intervalInput by remember { mutableStateOf((initial?.recurrenceInterval ?: 1).toString()) }
    var recurrenceUntil by remember {
        mutableStateOf((initial?.recurrenceUntil ?: defaultDate.plusDays(28)).toString())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "新建学习任务" else "编辑任务") },
        text = {
            TaskDialogColumn {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("科目（可选）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = estimateInput,
                        onValueChange = { estimateInput = it.filter(Char::isDigit) },
                        label = { Text("预计分钟") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("优先级", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskPriority.entries.forEach { value ->
                        FilterChip(
                            selected = priority == value,
                            onClick = { priority = value },
                            label = { Text(TaskPlanner.priorityLabel(value)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = dueInput,
                    onValueChange = { dueInput = it },
                    label = { Text("截止日期（yyyy-MM-dd，可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("详细说明（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !unscheduled,
                        onClick = { unscheduled = false },
                        label = { Text("安排时间") },
                    )
                    FilterChip(
                        selected = unscheduled,
                        onClick = {
                            unscheduled = true
                            recurrence = TaskRecurrence.NONE
                        },
                        label = { Text("暂不安排") },
                    )
                }
                if (!unscheduled) {
                    FieldLabel("学习会话（可拆分多个时间段）")
                    sessionDrafts.forEachIndexed { index, draft ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallField(
                                    label = "日期",
                                    value = draft.date,
                                    onValueChange = { value ->
                                        sessionDrafts = sessionDrafts.toMutableList().also {
                                            it[index] = draft.copy(date = value)
                                        }
                                    },
                                    modifier = Modifier.weight(1.2f),
                                )
                                SmallField(
                                    label = "开始",
                                    value = draft.start,
                                    onValueChange = { value ->
                                        sessionDrafts = sessionDrafts.toMutableList().also {
                                            it[index] = draft.copy(start = value)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                SmallField(
                                    label = "结束",
                                    value = draft.end,
                                    onValueChange = { value ->
                                        sessionDrafts = sessionDrafts.toMutableList().also {
                                            it[index] = draft.copy(end = value)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (sessionDrafts.size > 1) {
                                TextButton(onClick = {
                                    sessionDrafts = sessionDrafts.toMutableList().also { it.removeAt(index) }
                                }) { Text("移除该会话") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val last = sessionDrafts.lastOrNull()
                            sessionDrafts = sessionDrafts + SessionDraft(
                                date = last?.date ?: defaultDate.toString(),
                                start = last?.end ?: "20:00",
                                end = last?.end?.let(::addHour) ?: "21:00",
                            )
                        }) { Text("添加学习会话") }
                    }
                    Text(
                        "新建或手工改动的时段会锁定，不会被自动排程覆盖；未改动的自动时段仍可在下次生成时替换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (initial == null || initial.id == 0L) {
                    FieldLabel("重复")
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        TaskRecurrence.entries.forEach { value ->
                            FilterChip(
                                selected = recurrence == value,
                                enabled = !unscheduled,
                                onClick = { recurrence = value },
                                label = {
                                    Text(
                                        when (value) {
                                            TaskRecurrence.NONE -> "不重复"
                                            TaskRecurrence.DAILY -> "每天"
                                            TaskRecurrence.WEEKLY -> "每周"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (recurrence != TaskRecurrence.NONE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallField(
                                label = "间隔",
                                value = intervalInput,
                                onValueChange = { intervalInput = it.filter(Char::isDigit) },
                                modifier = Modifier.weight(1f),
                            )
                            SmallField(
                                label = "重复至（yyyy-MM-dd）",
                                value = recurrenceUntil,
                                onValueChange = { recurrenceUntil = it },
                                modifier = Modifier.weight(1.6f),
                            )
                        }
                        Text(
                            "周期任务会按次生成有界实例，单项完成后可单独取消完成；最多 200 次。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (recurringInstance) {
                    Text(
                        "该任务属于周期系列，本次编辑只影响当前这一项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val estimate = estimateInput.toIntOrNull()
                    val dueDate = dueInput.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull()
                    }
                    if (dueInput.isNotBlank() && dueDate == null) {
                        error = "截止日期格式无效"
                        return@TextButton
                    }
                    if (estimate == null) {
                        error = "请输入有效的预计分钟数"
                        return@TextButton
                    }
                    val parsedSessions = mutableListOf<TaskSession>()
                    if (!unscheduled) {
                        if (sessionDrafts.isEmpty()) {
                            error = "请至少保留一个学习会话，或选择暂不安排"
                            return@TextButton
                        }
                        sessionDrafts.forEach { draft ->
                            val date = runCatching { LocalDate.parse(draft.date.trim()) }.getOrNull()
                            val start = parseTaskMinute(draft.start)
                            val end = start?.let { taskEndMinute(it, draft.end) }
                            if (date == null || start == null || end == null) {
                                error = "学习会话的日期或时间无效"
                                return@TextButton
                            }
                            parsedSessions += TaskSession(date = date, startMinute = start, endMinuteExclusive = end)
                        }
                    }
                    val repeatUntil = if (recurrence == TaskRecurrence.NONE) null else {
                        runCatching { LocalDate.parse(recurrenceUntil.trim()) }.getOrNull()
                    }
                    if (recurrence != TaskRecurrence.NONE && repeatUntil == null) {
                        error = "请输入有效的周期结束日期"
                        return@TextButton
                    }
                    val status = when {
                        initial == null || initial.id == 0L -> {
                            if (parsedSessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED
                        }
                        initial.isCompleted -> TaskStatus.COMPLETED
                        parsedSessions.isEmpty() -> TaskStatus.BACKLOG
                        initial.status == TaskStatus.IN_PROGRESS -> TaskStatus.IN_PROGRESS
                        else -> TaskStatus.SCHEDULED
                    }
                    val canEditRecurrence = initial == null || initial.id == 0L
                    val sessionsWithLocks = TaskSessionLockPolicy.applyManualEdit(
                        initial = initial?.sessions.orEmpty(),
                        edited = parsedSessions,
                    )
                    val task = StudyTask(
                        id = initial?.id ?: 0,
                        title = title.trim(),
                        description = description.trim(),
                        subject = subject.trim(),
                        priority = priority,
                        estimatedMinutes = estimate,
                        dueDate = dueDate,
                        status = status,
                        completedAt = if (status == TaskStatus.COMPLETED) initial?.completedAt else null,
                        seriesId = initial?.seriesId,
                        recurrence = if (canEditRecurrence) recurrence else initial.recurrence,
                        recurrenceInterval = if (canEditRecurrence) intervalInput.toIntOrNull() ?: -1 else {
                            initial.recurrenceInterval
                        },
                        recurrenceUntil = if (canEditRecurrence) repeatUntil else initial.recurrenceUntil,
                        sortOrder = initial?.sortOrder ?: 0,
                        sessions = sessionsWithLocks,
                    )
                    val validation = TaskValidation.errorOrNull(task)
                    if (validation != null) {
                        error = validation
                    } else {
                        saving = true
                        onSave(task) { result ->
                            saving = false
                            error = result
                            if (result == null) onDismiss()
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@Composable
fun TaskDetailDialog(
    task: StudyTask,
    conflicts: List<TaskConflict>,
    zoneId: ZoneId,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSchedule: () -> Unit,
    onToggleCompleted: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onPostpone: () -> Unit,
    onDeleteCurrent: () -> Unit,
    onDeleteSeries: () -> Unit,
    onToggleSessionLock: (TaskSession, Boolean) -> Unit,
    onOpenHistory: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.title) },
        text = {
            TaskDialogColumn {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(task.status.taskStatusLabel())
                    TextChip("${TaskPlanner.priorityLabel(task.priority)}优先级")
                    if (task.recurrence != TaskRecurrence.NONE) TextChip(TaskPlanner.recurrenceLabel(task))
                }
                if (task.description.isNotBlank()) {
                    Text(task.description, style = MaterialTheme.typography.bodyMedium)
                }
                DetailText("科目", task.subject.ifBlank { "未分类" })
                DetailText("预计时长", "${task.estimatedMinutes} 分钟")
                DetailText("截止日期", task.dueDate?.toString() ?: "未设置")
                if (task.sessions.isEmpty()) {
                    DetailText("计划时间", "待安排")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "计划时间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        task.sessions.forEach { session ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${session.date} " +
                                        formatTaskTimeRange(session.startMinute, session.endMinuteExclusive),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = if (session.isLocked) "已锁定" else "自动可调",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (session.isLocked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                TextButton(
                                    onClick = { onToggleSessionLock(session, !session.isLocked) },
                                ) {
                                    Text(if (session.isLocked) "解锁" else "锁定")
                                }
                            }
                        }
                        Text(
                            "锁定时段不会被自动排程覆盖；未锁定时段可在下次生成时替换。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                task.completedAt?.let {
                    DetailText(
                        "实际完成",
                        it.atZone(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)),
                    )
                }
                if (task.seriesId != null) {
                    Text(
                        "周期任务按次管理；编辑、完成和延期默认只作用于当前这一项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (conflicts.isNotEmpty()) {
                    Text(
                        "课程冲突提醒",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    conflicts.take(4).forEach { conflict ->
                        Text(
                            "${conflict.date} 与「${conflict.otherTitle}」" +
                                " ${formatTaskTimeRange(conflict.otherStartMinute, conflict.otherEndMinuteExclusive)} 冲突",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onToggleCompleted) {
                        Text(if (task.isCompleted) "取消完成" else "标记完成")
                    }
                    when (task.status) {
                        TaskStatus.SCHEDULED -> TextButton(onClick = onStart) { Text("开始学习") }
                        TaskStatus.IN_PROGRESS -> TextButton(onClick = onPause) { Text("暂停") }
                        else -> Unit
                    }
                    if (task.status == TaskStatus.BACKLOG) {
                        TextButton(onClick = onSchedule) { Text("安排时间") }
                    }
                    if (!task.isCompleted && task.sessions.isNotEmpty()) {
                        TextButton(onClick = onPostpone) { Text("延期一天") }
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onDeleteCurrent) { Text("删除本次") }
                    if (task.seriesId != null) {
                        TextButton(onClick = onDeleteSeries) { Text("删除整个系列") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("编辑") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun TaskDeleteConfirmDialog(
    task: StudyTask,
    wholeSeries: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (wholeSeries) "删除整个周期系列？" else "删除任务？") },
        text = {
            Text(
                if (wholeSeries) {
                    "将删除「${task.title}」所属周期系列的全部任务和学习会话，此操作不可撤销。"
                } else {
                    "将删除「${task.title}」及其全部学习会话，此操作不可撤销。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text("确认删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TaskDialogColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SmallField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun DetailText(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun TextChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun addHour(value: String): String {
    val minute = parseTaskMinute(value) ?: return value
    val next = (minute + 60) % (24 * 60)
    return formatTaskMinute(next)
}
