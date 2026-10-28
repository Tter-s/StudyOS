package com.zhixu.studyos.feature.sleep

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.data.repository.SleepSnapshot
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.sleep.SleepDateOverride
import com.zhixu.studyos.core.sleep.SleepPhase
import com.zhixu.studyos.core.sleep.SleepRecord
import com.zhixu.studyos.core.sleep.SleepSchedule
import com.zhixu.studyos.core.sleep.formatSleepMinute
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun SleepScreen(viewModel: SleepViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editSchedule by remember { mutableStateOf(false) }
    var addOverride by remember { mutableStateOf(false) }
    var addRecord by remember { mutableStateOf(false) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("screen_sleep"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Rounded.Close, contentDescription = "关闭")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("睡眠与作息保护", style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "睡眠是排程硬约束；修改需要你主动确认并保留历史。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item { SleepStatusCard(state) }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("作息设置", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "工作日 ${formatSleepMinute(state.snapshot.schedule.weekdayBedMinute)}–" +
                                            formatSleepMinute(state.snapshot.schedule.weekdayWakeMinute) +
                                            "；周末 ${formatSleepMinute(state.snapshot.schedule.weekendBedMinute)}–" +
                                            formatSleepMinute(state.snapshot.schedule.weekendWakeMinute),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "睡前缓冲 ${state.snapshot.schedule.preSleepBufferMinutes} 分钟" +
                                            if (state.snapshot.schedule.napEnabled) " · 午休已启用" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        if (state.snapshot.schedule.bedtimeModeEnabled) {
                                            "睡前模式：开启（只发送应用内本地通知）"
                                        } else {
                                            "睡前模式：关闭"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                SecondaryButton(text = "编辑", onClick = { editSchedule = true })
                            }
                        }
                    }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("特殊日期作息", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "特殊日期覆盖考试周与周末设置；已保存 ${state.snapshot.overrides.size} 天。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { addOverride = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = "添加特殊日期")
                                }
                            }
                            state.snapshot.overrides.values.sortedBy { it.date }.forEach { override ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${override.date} · ${formatSleepMinute(override.bedMinute)}–" +
                                            "${formatSleepMinute(override.wakeMinute)}" +
                                            (override.note.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { viewModel.deleteOverride(override.date) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "删除特殊日期")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("睡眠记录", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "记录实际入睡、起床、主观质量与次日状态；数据只保存在本机。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { addRecord = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = "记录睡眠")
                                }
                            }
                            state.snapshot.records.take(14).forEach { record ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${record.date} · ${record.durationMinutes?.let { "${it / 60} 小时 ${it % 60} 分" } ?: "仅记录感受"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            listOfNotNull(
                                                record.quality?.let { "质量 $it/5" },
                                                record.nextDayEnergy?.let { "次日状态 $it/5" },
                                                record.note.takeIf(String::isNotBlank),
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteRecord(record.date) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "删除睡眠记录")
                                    }
                                }
                            }
                            if (state.snapshot.records.isEmpty()) {
                                Text(
                                    "还没有睡眠记录。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item { SectionHeader(title = "修改历史") }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            if (state.history.isEmpty()) {
                                Text(
                                    "还没有修改记录。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.history.take(8).forEach { change ->
                                Text(
                                    "${change.changedAtEpochMillis} · ${change.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Text("睡眠保护说明", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "1. 睡眠与睡前缓冲是排程硬约束，任务不会自动排入这些时段。\n" +
                                    "2. 与固定课程冲突时只提示，不会静默覆盖课程或睡眠。\n" +
                                    "3. 睡前模式只发送应用内本地通知，应用没有、也不会声称绕过 Android 系统免打扰权限。\n" +
                                    "4. 睡眠与学习表现的相关性不代表因果关系；数据不足时不会生成结论。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }

    if (editSchedule) {
        SleepScheduleEditorDialog(
            initial = state.snapshot.schedule,
            onDismiss = { editSchedule = false },
            onSave = { schedule, callback ->
                viewModel.saveSchedule(schedule) { error ->
                    callback(error)
                    if (error == null) editSchedule = false
                }
            },
        )
    }
    if (addOverride) {
        SleepOverrideDialog(
            onDismiss = { addOverride = false },
            onSave = { override, callback ->
                viewModel.saveOverride(override) { error ->
                    callback(error)
                    if (error == null) addOverride = false
                }
            },
        )
    }
    if (addRecord) {
        SleepRecordDialog(
            onDismiss = { addRecord = false },
            onSave = { record, callback ->
                viewModel.saveRecord(record) { error ->
                    callback(error)
                    if (error == null) addRecord = false
                }
            },
        )
    }
}

@Composable
private fun SleepStatusCard(state: SleepUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        val overview = state.overview
        val phaseLabel = when (overview?.status?.phase) {
            SleepPhase.ASLEEP -> "睡眠保护中"
            SleepPhase.WIND_DOWN -> "睡前缓冲"
            SleepPhase.NAP -> "午休"
            SleepPhase.AWAKE -> "清醒"
            null -> "无法读取"
        }
        Text(phaseLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            overview?.status?.message ?: "正在读取睡眠设置",
            style = MaterialTheme.typography.bodyMedium,
        )
        overview?.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        overview?.intervals?.takeIf { it.isNotEmpty() }?.let { intervals ->
            Text(
                "今日睡眠保护时段：" + intervals.joinToString("、") {
                    "${formatSleepMinute(it.startMinute)}–${formatSleepMinute(it.endMinuteExclusive)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        overview?.conflicts?.takeIf { it.isNotEmpty() }?.let { conflicts ->
            Spacer(modifier = Modifier.height(6.dp))
            conflicts.take(4).forEach {
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        overview?.recommendations?.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SleepScheduleEditorDialog(
    initial: SleepSchedule,
    onDismiss: () -> Unit,
    onSave: (SleepSchedule, (String?) -> Unit) -> Unit,
) {
    var weekdayBed by remember { mutableStateOf(formatSleepMinute(initial.weekdayBedMinute)) }
    var weekdayWake by remember { mutableStateOf(formatSleepMinute(initial.weekdayWakeMinute)) }
    var weekendBed by remember { mutableStateOf(formatSleepMinute(initial.weekendBedMinute)) }
    var weekendWake by remember { mutableStateOf(formatSleepMinute(initial.weekendWakeMinute)) }
    var earliestWake by remember { mutableStateOf(formatSleepMinute(initial.earliestWakeMinute)) }
    var latestBed by remember { mutableStateOf(formatSleepMinute(initial.latestBedMinute)) }
    var buffer by remember { mutableStateOf(initial.preSleepBufferMinutes.toString()) }
    var napEnabled by remember { mutableStateOf(initial.napEnabled) }
    var napStart by remember { mutableStateOf(formatSleepMinute(initial.napStartMinute)) }
    var napEnd by remember { mutableStateOf(formatSleepMinute(initial.napEndMinute)) }
    var examStart by remember { mutableStateOf(initial.examPeriodStart?.toString().orEmpty()) }
    var examEnd by remember { mutableStateOf(initial.examPeriodEnd?.toString().orEmpty()) }
    var examBed by remember { mutableStateOf(initial.examBedMinute?.let(::formatSleepMinute).orEmpty()) }
    var examWake by remember { mutableStateOf(initial.examWakeMinute?.let(::formatSleepMinute).orEmpty()) }
    var bedtimeMode by remember { mutableStateOf(initial.bedtimeModeEnabled) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑睡眠与作息") },
        text = {
            Column(
                modifier = Modifier.height(480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("工作日", style = MaterialTheme.typography.labelLarge)
                TimeRow("睡觉", weekdayBed) { weekdayBed = it }
                TimeRow("起床", weekdayWake) { weekdayWake = it }
                Text("周末（周五、周六夜）", style = MaterialTheme.typography.labelLarge)
                TimeRow("睡觉", weekendBed) { weekendBed = it }
                TimeRow("起床", weekendWake) { weekendWake = it }
                Text("边界与缓冲", style = MaterialTheme.typography.labelLarge)
                TimeRow("最早起床", earliestWake) { earliestWake = it }
                TimeRow("最晚睡觉", latestBed) { latestBed = it }
                OutlinedTextField(
                    value = buffer,
                    onValueChange = { buffer = it.filter(Char::isDigit) },
                    label = { Text("睡前缓冲（分钟）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("午休", modifier = Modifier.weight(1f))
                    Switch(checked = napEnabled, onCheckedChange = { napEnabled = it })
                }
                if (napEnabled) {
                    TimeRow("午休开始", napStart) { napStart = it }
                    TimeRow("午休结束", napEnd) { napEnd = it }
                }
                Text("考试周临时作息（可选）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = examStart,
                    onValueChange = { examStart = it },
                    label = { Text("考试周开始 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = examEnd,
                    onValueChange = { examEnd = it },
                    label = { Text("考试周结束 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TimeRow("考试周睡觉（可空）", examBed) { examBed = it }
                TimeRow("考试周起床（可空）", examWake) { examWake = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("睡前模式")
                        Text(
                            "到睡前缓冲开始时发送一条本地通知，展示明日课程与任务摘要；不控制系统免打扰。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = bedtimeMode, onCheckedChange = { bedtimeMode = it })
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val times = listOf(
                        weekdayBed, weekdayWake, weekendBed, weekendWake, earliestWake, latestBed,
                    )
                    val parsedTimes = times.map(::parseTime)
                    if (parsedTimes.any { it == null }) {
                        error = "时间格式应为 HH:mm"
                        return@TextButton
                    }
                    val parsedExamStart = examStart.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull()
                    }
                    val parsedExamEnd = examEnd.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull()
                    }
                    if (examStart.isNotBlank() && parsedExamStart == null ||
                        examEnd.isNotBlank() && parsedExamEnd == null
                    ) {
                        error = "考试周日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    val examBedMinute = examBed.trim().takeIf(String::isNotEmpty)?.let(::parseTime)
                    val examWakeMinute = examWake.trim().takeIf(String::isNotEmpty)?.let(::parseTime)
                    if (examBed.isNotBlank() && examBedMinute == null ||
                        examWake.isNotBlank() && examWakeMinute == null
                    ) {
                        error = "考试周时间格式应为 HH:mm"
                        return@TextButton
                    }
                    val schedule = initial.copy(
                        weekdayBedMinute = parsedTimes[0]!!,
                        weekdayWakeMinute = parsedTimes[1]!!,
                        weekendBedMinute = parsedTimes[2]!!,
                        weekendWakeMinute = parsedTimes[3]!!,
                        earliestWakeMinute = parsedTimes[4]!!,
                        latestBedMinute = parsedTimes[5]!!,
                        preSleepBufferMinutes = buffer.toIntOrNull() ?: 30,
                        napEnabled = napEnabled,
                        napStartMinute = parseTime(napStart) ?: (13 * 60),
                        napEndMinute = parseTime(napEnd) ?: (14 * 60),
                        examPeriodStart = parsedExamStart,
                        examPeriodEnd = parsedExamEnd,
                        examBedMinute = examBedMinute,
                        examWakeMinute = examWakeMinute,
                        bedtimeModeEnabled = bedtimeMode,
                    )
                    onSave(schedule) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TimeRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SleepOverrideDialog(
    onDismiss: () -> Unit,
    onSave: (SleepDateOverride, (String?) -> Unit) -> Unit,
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var bed by remember { mutableStateOf("23:00") }
    var wake by remember { mutableStateOf("07:00") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("特殊日期作息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TimeRow("睡觉 HH:mm", bed) { bed = it }
                TimeRow("起床 HH:mm", wake) { wake = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("说明（例如考试周）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                    val bedMinute = parseTime(bed)
                    val wakeMinute = parseTime(wake)
                    if (parsedDate == null) {
                        error = "日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    if (bedMinute == null || wakeMinute == null) {
                        error = "时间格式应为 HH:mm"
                        return@TextButton
                    }
                    onSave(
                        SleepDateOverride(
                            date = parsedDate,
                            bedMinute = bedMinute,
                            wakeMinute = wakeMinute,
                            note = note,
                        ),
                    ) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SleepRecordDialog(
    onDismiss: () -> Unit,
    onSave: (SleepRecord, (String?) -> Unit) -> Unit,
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var bed by remember { mutableStateOf("23:30") }
    var wake by remember { mutableStateOf("07:30") }
    var quality by remember { mutableStateOf(0) }
    var energy by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录睡眠") },
        text = {
            Column(
                modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("入睡日期 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TimeRow("入睡时间 HH:mm", bed) { bed = it }
                TimeRow("起床时间 HH:mm", wake) { wake = it }
                Text("主观睡眠质量（0 不填）", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    (0..5).forEach { value ->
                        FilterChip(
                            selected = quality == value,
                            onClick = { quality = value },
                            label = { Text(if (value == 0) "不填" else "$value") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Text("次日精神状态（0 不填）", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    (0..5).forEach { value ->
                        FilterChip(
                            selected = energy == value,
                            onClick = { energy = value },
                            label = { Text(if (value == 0) "不填" else "$value") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                    val bedMinute = parseTime(bed)
                    val wakeMinute = parseTime(wake)
                    if (parsedDate == null) {
                        error = "日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    if (bedMinute == null || wakeMinute == null) {
                        error = "时间格式应为 HH:mm"
                        return@TextButton
                    }
                    val zone = ZoneId.systemDefault()
                    val bedInstant = parsedDate.atTime(LocalTime.of(bedMinute / 60, bedMinute % 60))
                        .atZone(zone).toInstant()
                    var wakeLocalDate = parsedDate
                    if (wakeMinute <= bedMinute) wakeLocalDate = parsedDate.plusDays(1)
                    val wakeInstant = wakeLocalDate.atTime(LocalTime.of(wakeMinute / 60, wakeMinute % 60))
                        .atZone(zone).toInstant()
                    onSave(
                        SleepRecord(
                            date = parsedDate,
                            bedAt = bedInstant,
                            wakeAt = wakeInstant,
                            quality = quality.takeIf { it > 0 },
                            nextDayEnergy = energy.takeIf { it > 0 },
                            note = note,
                        ),
                    ) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun parseTime(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
