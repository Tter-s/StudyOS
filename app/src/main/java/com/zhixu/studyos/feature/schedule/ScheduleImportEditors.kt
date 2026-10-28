package com.zhixu.studyos.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.importer.PeriodGrid
import com.zhixu.studyos.core.importer.PeriodSlot
import com.zhixu.studyos.core.importer.ScheduleImportDraft
import com.zhixu.studyos.core.importer.ScheduleImportDraftAnalysis
import com.zhixu.studyos.core.importer.ScheduleImportFormat
import com.zhixu.studyos.core.importer.ScheduleImportValidation
import com.zhixu.studyos.core.model.WeekParity

@Composable
fun ImportDraftEditorDialog(
    draft: ScheduleImportDraft,
    grid: PeriodGrid,
    analysis: ScheduleImportDraftAnalysis?,
    onDismiss: () -> Unit,
    onSave: (ScheduleImportDraft) -> Unit,
) {
    var name by remember(draft.localId) { mutableStateOf(draft.name) }
    var teacher by remember(draft.localId) { mutableStateOf(draft.teacher.orEmpty()) }
    var classroom by remember(draft.localId) { mutableStateOf(draft.classroom) }
    var dayOfWeek by remember(draft.localId) { mutableIntStateOf(draft.dayOfWeek ?: 0) }
    var periodStart by remember(draft.localId) { mutableStateOf(draft.periodStart?.toString().orEmpty()) }
    var periodEnd by remember(draft.localId) { mutableStateOf(draft.periodEnd?.toString().orEmpty()) }
    var startWeek by remember(draft.localId) { mutableStateOf(draft.startWeek?.toString().orEmpty()) }
    var endWeek by remember(draft.localId) { mutableStateOf(draft.endWeek?.toString().orEmpty()) }
    var parity by remember(draft.localId) { mutableStateOf(draft.weekParity) }
    var color by remember(draft.localId) { mutableIntStateOf(draft.colorArgb) }
    var error by remember(draft.localId) { mutableStateOf<String?>(null) }

    fun buildDraft() = draft.copy(
        name = name.trim(),
        teacher = teacher.trim().ifEmpty { null },
        classroom = classroom.trim(),
        dayOfWeek = dayOfWeek.takeIf { it in 1..7 },
        periodStart = periodStart.toIntOrNull(),
        periodEnd = periodEnd.toIntOrNull(),
        startWeek = startWeek.toIntOrNull(),
        endWeek = endWeek.toIntOrNull(),
        weekParity = parity,
        colorArgb = color,
        needsReview = emptySet(),
        notes = emptyList(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改识别结果") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "保存会同时确认本行的待确认字段。留空或无效的必填字段无法保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                analysis?.validationError?.let { validation ->
                    Text(
                        text = "当前问题：$validation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val conflictMessages = buildList {
                    analysis?.duplicates?.forEach { add("重复：" + ScheduleImportFormat.overlap(it)) }
                    analysis?.conflicts?.forEach { add("冲突：" + ScheduleImportFormat.overlap(it)) }
                }
                if (conflictMessages.isNotEmpty()) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "重复与冲突",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            conflictMessages.forEach { message ->
                                Text(
                                    text = "· $message",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            Text(
                                text = "修改星期、节次、周次或课程名后会自动重新检测。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                ImportField("课程名称", name, { name = it })
                ImportField("教师（可选）", teacher, { teacher = it })
                ImportField("教室", classroom, { classroom = it })

                ImportLabel("星期")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    weekDayLabels.forEachIndexed { index, label ->
                        FilterChip(
                            selected = dayOfWeek == index + 1,
                            onClick = { dayOfWeek = index + 1 },
                            label = { Text(label) },
                        )
                    }
                }

                ImportLabel("节次（第 1–${grid.size} 节）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportField("开始节次", periodStart, { periodStart = it }, Modifier.weight(1f), true)
                    ImportField("结束节次", periodEnd, { periodEnd = it }, Modifier.weight(1f), true)
                }
                val previewRange = ScheduleImportValidation.timeRange(
                    buildDraft(),
                    grid,
                )
                if (previewRange != null) {
                    Text(
                        text = "对应时间 ${ScheduleImportFormat.clock(previewRange.first)}–" +
                            ScheduleImportFormat.clock(previewRange.second),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                ImportLabel("单双周规则")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        WeekParity.ALL to "每周",
                        WeekParity.ODD to "单周",
                        WeekParity.EVEN to "双周",
                    ).forEach { item ->
                        FilterChip(
                            selected = parity == item.first,
                            onClick = { parity = item.first },
                            label = { Text(item.second) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportField("开始周", startWeek, { startWeek = it }, Modifier.weight(1f), true)
                    ImportField("结束周", endWeek, { endWeek = it }, Modifier.weight(1f), true)
                }

                ImportLabel("课程颜色")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    courseColorPalette.forEach { paletteColor ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(Color(paletteColor), CircleShape)
                                .then(
                                    if (color == paletteColor) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { color = paletteColor },
                        )
                    }
                }

                if (draft.notes.isNotEmpty()) {
                    HorizontalDivider()
                    Text("识别提示", style = MaterialTheme.typography.labelLarge)
                    draft.notes.forEach { note ->
                        Text(
                            text = "· $note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (draft.sourceText.isNotBlank()) {
                    Text("识别原文", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = draft.sourceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ImportErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = buildDraft()
                    val validation = ScheduleImportValidation.validationError(updated, grid)
                    if (validation != null) {
                        error = validation
                    } else {
                        onSave(updated)
                    }
                },
            ) { Text("保存并确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ImportPeriodGridDialog(
    grid: PeriodGrid,
    onDismiss: () -> Unit,
    onSave: (PeriodGrid) -> Unit,
) {
    data class SlotInput(val start: String, val end: String)

    var inputs by remember(grid) {
        mutableStateOf(
            grid.slots.map { slot ->
                SlotInput(
                    start = ScheduleImportFormat.clock(slot.startMinute),
                    end = ScheduleImportFormat.clock(slot.endMinuteExclusive),
                )
            },
        )
    }
    var error by remember(grid) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("核对节次时间") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "这些时间只用于把识别到的节次转换为课程起止时间，并只影响本次导入。请按学校作息填写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                inputs.forEachIndexed { index, input ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "第 ${index + 1} 节",
                            modifier = Modifier.width(60.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = input.start,
                            onValueChange = { value ->
                                inputs = inputs.toMutableList().also {
                                    it[index] = input.copy(start = value)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("开始") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = input.end,
                            onValueChange = { value ->
                                inputs = inputs.toMutableList().also {
                                    it[index] = input.copy(end = value)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("结束") },
                            singleLine = true,
                        )
                    }
                }
                ImportErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    var failure: String? = null
                    val slots = inputs.mapIndexedNotNull { index, input ->
                        val start = parseClockMinute(input.start)
                        val end = parseClockMinute(input.end)
                        if (start == null || end == null) {
                            failure = failure ?: "第 ${index + 1} 节时间格式无效，例如 08:00"
                            return@mapIndexedNotNull null
                        }
                        PeriodSlot(index + 1, start, end)
                    }
                    if (failure == null) {
                        val candidate = PeriodGrid(slots)
                        val validation = candidate.validationError()
                        if (validation != null) failure = validation
                        if (validation == null) onSave(candidate)
                    }
                    error = failure
                },
            ) { Text("保存并确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
    )
}

@Composable
private fun ImportLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ImportErrorText(error: String?) {
    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
