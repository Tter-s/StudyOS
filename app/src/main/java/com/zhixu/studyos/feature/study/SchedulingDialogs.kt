package com.zhixu.studyos.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.scheduling.ProposedSession
import java.time.LocalDate

@Composable
fun SchedulingSettingsDialog(
    settings: SchedulingSettings,
    subjectPreferences: Map<String, SubjectPreference>,
    subjectOptions: List<String>,
    onDismiss: () -> Unit,
    onSave: (SchedulingSettings, Map<String, SubjectPreference>, (String?) -> Unit) -> Unit,
) {
    var dailyLimitInput by remember { mutableStateOf(settings.dailyLimitMinutes.toString()) }
    var bufferInput by remember { mutableStateOf(settings.bufferMinutes.toString()) }
    var startInput by remember { mutableStateOf(formatScheduleMinute(settings.studyStartMinute)) }
    var endInput by remember { mutableStateOf(formatScheduleMinute(settings.studyEndMinuteExclusive)) }
    var preferences by remember { mutableStateOf(subjectPreferences) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("排程设置") },
        text = {
            SchedulingDialogColumn {
                Text(
                    "硬约束：课程、临时事务、睡眠/用餐/固定事务和锁定时段不会被占用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dailyLimitInput,
                        onValueChange = { dailyLimitInput = it.filter(Char::isDigit).take(4) },
                        label = { Text("每日学习上限（分钟）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = bufferInput,
                        onValueChange = { bufferInput = it.filter(Char::isDigit).take(3) },
                        label = { Text("块间缓冲（分钟）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = { startInput = it.take(5) },
                        label = { Text("学习窗口开始") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = { endInput = it.take(5) },
                        label = { Text("学习窗口结束") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "自动学习块默认每段最多 50 分钟，不跨越午夜；大任务会拆成多段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("科目偏好", style = MaterialTheme.typography.titleSmall)
                Text(
                    "提高会提升排序分数，降低会降低分数；不会改变硬约束。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                subjectOptions.forEach { subject ->
                    val preference = preferences[subject] ?: SubjectPreference.NORMAL
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(subject, style = MaterialTheme.typography.bodyMedium)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            SubjectPreference.entries.forEach { value ->
                                FilterChip(
                                    selected = preference == value,
                                    onClick = {
                                        preferences = preferences + (subject to value)
                                    },
                                    label = { Text(subjectPreferenceLabel(value)) },
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                        }
                    }
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
                    val dailyLimit = dailyLimitInput.toIntOrNull()
                    val buffer = bufferInput.toIntOrNull()
                    val start = parseScheduleTimeInput(startInput)
                    val end = parseScheduleTimeInput(endInput, allowMidnightEnd = true)
                    if (dailyLimit == null || buffer == null || start == null || end == null) {
                        error = "请填写有效的每日上限、缓冲和 24 小时时间"
                        return@TextButton
                    }
                    val newSettings = settings.copy(
                        dailyLimitMinutes = dailyLimit,
                        bufferMinutes = buffer,
                        studyStartMinute = start,
                        studyEndMinuteExclusive = end,
                    )
                    val validation = newSettings.validationError()
                    if (validation != null) {
                        error = validation
                        return@TextButton
                    }
                    saving = true
                    onSave(newSettings, preferences) { message ->
                        saving = false
                        error = message
                        if (message == null) onDismiss()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
fun SchedulingAdjustDialog(
    block: ProposedSession,
    onDismiss: () -> Unit,
    onSave: (LocalDate, Int, Int, (String?) -> Unit) -> Unit,
    onRemove: () -> Unit,
) {
    var dateInput by remember(block.id) { mutableStateOf(block.date.toString()) }
    var startInput by remember(block.id) { mutableStateOf(formatScheduleMinute(block.startMinute)) }
    var endInput by remember(block.id) { mutableStateOf(formatScheduleMinute(block.endMinuteExclusive)) }
    var error by remember(block.id) { mutableStateOf<String?>(null) }
    var saving by remember(block.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("调整学习时段") },
        text = {
            SchedulingDialogColumn {
                Text(block.taskTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    "原时段：${block.date} ${formatScheduleRange(block.startMinute, block.endMinuteExclusive)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = dateInput,
                    onValueChange = { dateInput = it.take(10) },
                    label = { Text("日期（yyyy-MM-dd）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = { startInput = it.take(5) },
                        label = { Text("开始（HH:mm）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = { endInput = it.take(5) },
                        label = { Text("结束（HH:mm）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "手工调整会锁定该时段，后续自动生成不会覆盖。不能与课程、作息、锁定或预览中的其他时段重叠；可超出偏好窗口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    enabled = !saving,
                    onClick = {
                        onRemove()
                        onDismiss()
                    },
                ) {
                    Text("移除这段", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
                    val start = parseScheduleTimeInput(startInput)
                    val end = parseScheduleTimeInput(endInput, allowMidnightEnd = true)
                    if (date == null || start == null || end == null) {
                        error = "请填写有效日期和 24 小时时间"
                        return@TextButton
                    }
                    saving = true
                    onSave(date, start, end) { message ->
                        saving = false
                        error = message
                        if (message == null) onDismiss()
                    }
                },
            ) { Text("保存调整") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
fun SchedulingReminderTimeDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int, (String?) -> Unit) -> Unit,
) {
    var timeInput by remember { mutableStateOf("%02d:%02d".format(initialHour, initialMinute)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("每日提醒时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = timeInput,
                    onValueChange = { timeInput = it.take(5) },
                    label = { Text("时间（HH:mm）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "使用系统推荐的后台调度；实际送达可能因省电策略和系统负载略有延迟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val minuteOfDay = parseScheduleTimeInput(timeInput)
                    if (minuteOfDay == null) {
                        error = "请输入 00:00–23:59 之间的时间"
                        return@TextButton
                    }
                    saving = true
                    onSave(minuteOfDay / 60, minuteOfDay % 60) { message ->
                        saving = false
                        error = message
                        if (message == null) onDismiss()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
fun SchedulingRegenerateConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新生成计划？") },
        text = {
            Text("当前预览中的手工调整和锁定标记会被丢弃；已保存的锁定时段不受影响。")
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text("重新生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SchedulingDialogColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}
