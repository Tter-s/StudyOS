package com.zhixu.studyos.feature.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.zhixu.studyos.core.model.BlockerReason
import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.ProcrastinationReason
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TimingSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SubjectManagerDialog(
    subjects: List<Subject>,
    onDismiss: () -> Unit,
    onAdd: (String, (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("科目管理") },
        text = {
            LearningDialogColumn {
                Text(
                    "五个预设科目随应用提供；你可以添加自己的科目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                subjects.forEach { subject ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(subject.name, modifier = Modifier.weight(1f))
                        if (subject.isPreset) {
                            Text(
                                "预设",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新科目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    onAdd(name) { result ->
                        saving = false
                        error = result
                        if (result == null) name = ""
                    }
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun ManualTimingDialog(
    subjects: List<Subject>,
    tasks: List<StudyTask>,
    defaultDate: LocalDate,
    defaultStartTime: String,
    zoneId: ZoneId,
    onDismiss: () -> Unit,
    onSave: (Long, Long?, LocalDate, Instant, Int, String, (String?) -> Unit) -> Unit,
) {
    var subjectId by remember { mutableStateOf(subjects.firstOrNull()?.id) }
    var taskId by remember { mutableStateOf<Long?>(null) }
    var dateInput by remember { mutableStateOf(defaultDate.toString()) }
    var startInput by remember { mutableStateOf(defaultStartTime) }
    var durationInput by remember { mutableStateOf("30") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var taskMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("补录学习记录") },
        text = {
            LearningDialogColumn {
                SubjectChips(subjects, subjectId) { subjectId = it }
                Box {
                    OutlinedButton(onClick = { taskMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(tasks.firstOrNull { it.id == taskId }?.title ?: "不关联任务")
                    }
                    DropdownMenu(expanded = taskMenuOpen, onDismissRequest = { taskMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("不关联任务") },
                            onClick = {
                                taskId = null
                                taskMenuOpen = false
                            },
                        )
                        tasks.take(30).forEach { task ->
                            DropdownMenuItem(
                                text = { Text(task.title) },
                                onClick = {
                                    taskId = task.id
                                    taskMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningField("日期", dateInput, { dateInput = it }, Modifier.weight(1.3f))
                    LearningField("开始时间", startInput, { startInput = it }, Modifier.weight(1f))
                }
                LearningField(
                    "时长（分钟）",
                    durationInput,
                    { durationInput = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    numeric = true,
                )
                LearningField("备注（可选）", note, { note = it }, modifier = Modifier.fillMaxWidth())
                Text(
                    "补录用于已经发生的学习；不会计入后台计时。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val subject = subjectId
                    val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
                    val minute = parseTaskMinute(startInput)
                    val duration = durationInput.toIntOrNull()
                    if (subject == null) {
                        error = "请选择科目"
                    } else if (date == null || minute == null || duration == null) {
                        error = "请检查日期、开始时间和时长"
                    } else {
                        val startedAt = date.atTime(minute / 60, minute % 60)
                            .atZone(zoneId)
                            .toInstant()
                        saving = true
                        onSave(subject, taskId, date, startedAt, duration, note) { result ->
                            saving = false
                            error = result
                            if (result == null) onDismiss()
                        }
                    }
                },
            ) { Text("补录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun CorrectTimingDialog(
    session: TimingSession,
    subjectName: String,
    taskTitle: String?,
    onDismiss: () -> Unit,
    onSave: (Int, String, (String?) -> Unit) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var minutes by remember {
        mutableStateOf((session.accumulatedMillis / 60_000L).coerceAtLeast(1L).toString())
    }
    var note by remember { mutableStateOf(session.note) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("修正学习记录") },
        text = {
            LearningDialogColumn {
                Text("$subjectName · ${session.mode.label}")
                taskTitle?.let { Text("关联任务：$it") }
                LearningField(
                    "实际学习时长（分钟）",
                    minutes,
                    { minutes = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    numeric = true,
                )
                LearningField("备注", note, { note = it }, modifier = Modifier.fillMaxWidth())
                Text(
                    "修正只改变实际时长，不改变每次中断的记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) {
                    TextButton(onClick = {
                        onDelete()
                        onDismiss()
                    }) { Text("删除这条记录") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val value = minutes.toIntOrNull()
                    if (value == null || value !in 1..1440) {
                        error = "请输入 1–1440 分钟"
                    } else {
                        saving = true
                        onSave(value, note) { result ->
                            saving = false
                            error = result
                            if (result == null) onDismiss()
                        }
                    }
                },
            ) { Text("保存修正") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ProblemEditorDialog(
    initial: Problem?,
    subjects: List<Subject>,
    onDismiss: () -> Unit,
    onSave: (Problem, (String?) -> Unit) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var subjectId by remember { mutableStateOf(initial?.subjectId ?: subjects.firstOrNull()?.id) }
    var textbook by remember { mutableStateOf(initial?.textbook.orEmpty()) }
    var chapter by remember { mutableStateOf(initial?.chapter.orEmpty()) }
    var number by remember { mutableStateOf(initial?.problemNumber.orEmpty()) }
    var knowledge by remember { mutableStateOf(initial?.knowledgePoint.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var status by remember { mutableStateOf(initial?.status ?: ProblemStatus.TODO) }
    var thinkingInput by remember { mutableStateOf((initial?.independentThinkingMinutes ?: 0).toString()) }
    var usedAiHint by remember { mutableStateOf(initial?.usedAiHint ?: false) }
    var blockerReason by remember { mutableStateOf(initial?.blockerReason) }
    var isMistake by remember { mutableStateOf(initial?.isMistake ?: false) }
    var isDifficult by remember { mutableStateOf(initial?.isDifficult ?: false) }
    var independentCompleted by remember { mutableStateOf(initial?.independentCompleted ?: false) }
    var secondAttemptIndependent by remember { mutableStateOf(initial?.secondAttemptIndependent ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val retryCount = initial?.retryCount ?: 0

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "录入习题" else "编辑习题") },
        text = {
            LearningDialogColumn {
                SubjectChips(subjects, subjectId) { subjectId = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningField("教材", textbook, { textbook = it }, Modifier.weight(1f))
                    LearningField("章节", chapter, { chapter = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningField("题号", number, { number = it }, Modifier.weight(1f))
                    LearningField("知识点", knowledge, { knowledge = it }, Modifier.weight(1f))
                }
                LearningField("题目/说明（可选）", description, { description = it }, Modifier.fillMaxWidth())
                FieldLabel("完成状态")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    ProblemStatus.entries.forEach { value ->
                        FilterChip(
                            selected = status == value,
                            onClick = { status = value },
                            label = { Text(value.label) },
                        )
                    }
                }
                LearningField(
                    "独立思考时长（分钟）",
                    thinkingInput,
                    { thinkingInput = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    numeric = true,
                )
                ToggleRow("使用过 AI 提示", usedAiHint) { usedAiHint = it }
                ToggleRow("标记为错题", isMistake) { isMistake = it }
                ToggleRow("标记为困难题", isDifficult) { isDifficult = it }
                if (status == ProblemStatus.COMPLETED) {
                    ToggleRow("本次独立完成", independentCompleted) { independentCompleted = it }
                    if (retryCount > 0) {
                        ToggleRow("第二次重练独立完成", secondAttemptIndependent) {
                            secondAttemptIndependent = it
                        }
                    }
                }
                FieldLabel("卡题原因")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = blockerReason == null,
                        onClick = { blockerReason = null },
                        label = { Text("无") },
                    )
                    BlockerReason.entries.forEach { value ->
                        FilterChip(
                            selected = blockerReason == value,
                            onClick = { blockerReason = value; isDifficult = true },
                            label = { Text(value.label) },
                        )
                    }
                }
                if (retryCount > 0) {
                    Text(
                        "已重新练习 $retryCount 次；完成后可记录第二次是否独立完成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) {
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                    ) { Text("删除这条习题记录") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val subject = subjectId
                    val thinking = thinkingInput.toIntOrNull()
                    if (subject == null) {
                        error = "请选择科目"
                    } else if (thinking == null) {
                        error = "请输入独立思考时长"
                    } else {
                        val now = Instant.now()
                        val problem = Problem(
                            id = initial?.id ?: 0,
                            subjectId = subject,
                            textbook = textbook.trim(),
                            chapter = chapter.trim(),
                            problemNumber = number.trim(),
                            knowledgePoint = knowledge.trim(),
                            description = description.trim(),
                            status = status,
                            independentThinkingMinutes = thinking,
                            usedAiHint = usedAiHint,
                            blockerReason = blockerReason,
                            isMistake = isMistake,
                            isDifficult = isDifficult || blockerReason != null,
                            independentCompleted = if (status == ProblemStatus.COMPLETED) {
                                independentCompleted
                            } else {
                                null
                            },
                            retryCount = retryCount,
                            secondAttemptIndependent = if (retryCount > 0 && status == ProblemStatus.COMPLETED) {
                                secondAttemptIndependent
                            } else {
                                null
                            },
                            completedAt = if (status == ProblemStatus.COMPLETED) {
                                initial?.completedAt ?: now
                            } else {
                                null
                            },
                            createdAt = initial?.createdAt ?: now,
                            updatedAt = now,
                        )
                        saving = true
                        onSave(problem) { result ->
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
fun GoalsDialog(
    initial: LearningGoals,
    onDismiss: () -> Unit,
    onSave: (LearningGoals, (String?) -> Unit) -> Unit,
) {
    var dailyMinutes by remember { mutableStateOf(initial.dailyStudyMinutes?.toString().orEmpty()) }
    var weeklyMinutes by remember { mutableStateOf(initial.weeklyStudyMinutes?.toString().orEmpty()) }
    var dailyTasks by remember { mutableStateOf(initial.dailyTaskTarget?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("调整学习目标") },
        text = {
            LearningDialogColumn {
                Text(
                    "目标用于帮助安排节奏，不设置惩罚，也不会因为未达成而改变其他数据。留空或 0 表示暂不设目标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LearningField(
                    "每日学习分钟（可空）",
                    dailyMinutes,
                    { dailyMinutes = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    numeric = true,
                )
                LearningField(
                    "每周学习分钟（可空）",
                    weeklyMinutes,
                    { weeklyMinutes = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    numeric = true,
                )
                LearningField(
                    "每日完成任务数（可空）",
                    dailyTasks,
                    { dailyTasks = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    numeric = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val goals = LearningGoals(
                        dailyStudyMinutes = dailyMinutes.toIntOrNull()?.takeIf { it > 0 },
                        weeklyStudyMinutes = weeklyMinutes.toIntOrNull()?.takeIf { it > 0 },
                        dailyTaskTarget = dailyTasks.toIntOrNull()?.takeIf { it > 0 },
                    )
                    saving = true
                    onSave(goals) { result ->
                        saving = false
                        error = result
                        if (result == null) onDismiss()
                    }
                },
            ) { Text("保存目标") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun SelfControlEntryDialog(
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (LocalDate, ProcrastinationReason?, Boolean, String, (String?) -> Unit) -> Unit,
) {
    var dateInput by remember { mutableStateOf(defaultDate.toString()) }
    var reason by remember { mutableStateOf<ProcrastinationReason?>(null) }
    var voluntary by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("记录一次状态") },
        text = {
            LearningDialogColumn {
                Text(
                    "只是记录当时发生了什么，方便以后调整节奏；不评价自控力高低。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LearningField("日期", dateInput, { dateInput = it }, modifier = Modifier.fillMaxWidth())
                FieldLabel("拖延原因（可选）")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = reason == null,
                        onClick = { reason = null },
                        label = { Text("未记录") },
                    )
                    ProcrastinationReason.entries.forEach { value ->
                        FilterChip(
                            selected = reason == value,
                            onClick = { reason = value },
                            label = { Text(value.label) },
                        )
                    }
                }
                ToggleRow("发生了主动中断", voluntary) { voluntary = it }
                LearningField("备注（可选）", note, { note = it }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
                    if (date == null) {
                        error = "日期格式无效"
                    } else {
                        saving = true
                        onSave(date, reason, voluntary, note) { result ->
                            saving = false
                            error = result
                            if (result == null) onDismiss()
                        }
                    }
                },
            ) { Text("保存记录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun LearningDeleteConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
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
private fun SubjectChips(
    subjects: List<Subject>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    Text("科目", style = MaterialTheme.typography.labelLarge)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        subjects.forEach { subject ->
            FilterChip(
                selected = selectedId == subject.id,
                onClick = { onSelect(subject.id) },
                label = { Text(subject.name) },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun LearningDialogColumn(content: @Composable () -> Unit) {
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
private fun LearningField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
    )
}
