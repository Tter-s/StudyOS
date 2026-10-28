package com.zhixu.studyos.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.DaySchedule
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.RoutineRepeat
import com.zhixu.studyos.core.model.ScheduleInstance
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.WeekParity
import com.zhixu.studyos.core.schedule.ScheduleValidation
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SemesterSettingsDialog(
    initial: SemesterSettings?,
    defaultStartDate: LocalDate,
    defaultZoneId: ZoneId,
    onDismiss: () -> Unit,
    onSave: (LocalDate, ZoneId, (String?) -> Unit) -> Unit,
) {
    var dateInput by remember { mutableStateOf((initial?.startDate ?: defaultStartDate).toString()) }
    var zoneInput by remember { mutableStateOf((initial?.zoneId ?: defaultZoneId).id) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("学期设置") },
        text = {
            DialogColumn {
                DialogField("学期开始日期（yyyy-MM-dd）", dateInput, { dateInput = it })
                DialogField("时区（IANA，例如 Asia/Shanghai）", zoneInput, { zoneInput = it })
                Text(
                    "第一周从开始日期所在周的周一起算；学期开始日期之前不生成课程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
                    val zone = runCatching { ZoneId.of(zoneInput.trim()) }.getOrNull()
                    when {
                        date == null -> error = "日期格式无效，例如 2026-09-07"
                        zone == null -> error = "时区无效，例如 Asia/Shanghai"
                        else -> {
                            saving = true
                            onSave(date, zone) { result ->
                                saving = false
                                error = result
                                if (result == null) onDismiss()
                            }
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun CourseEditorDialog(
    initial: Course?,
    onDismiss: () -> Unit,
    onSave: (Course, (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var teacher by remember { mutableStateOf(initial?.teacher.orEmpty()) }
    var classroom by remember { mutableStateOf(initial?.classroom.orEmpty()) }
    var dayOfWeek by remember { mutableIntStateOf(initial?.dayOfWeek ?: 1) }
    var startInput by remember { mutableStateOf(formatMinute(initial?.startMinute ?: 8 * 60)) }
    var endInput by remember { mutableStateOf(formatMinute(initial?.endMinuteExclusive ?: 8 * 60 + 50)) }
    var startWeekInput by remember { mutableStateOf((initial?.startWeek ?: 1).toString()) }
    var endWeekInput by remember { mutableStateOf((initial?.endWeek ?: 16).toString()) }
    var parity by remember { mutableStateOf(initial?.weekParity ?: WeekParity.ALL) }
    var color by remember { mutableIntStateOf(initial?.colorArgb ?: courseColorPalette.first()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "添加课程" else "编辑课程") },
        text = {
            DialogColumn {
                DialogField("课程名称", name, { name = it })
                DialogField("教师（可选）", teacher, { teacher = it })
                DialogField("教室", classroom, { classroom = it })
                FieldLabel("星期")
                WeekdaySelector(selected = dayOfWeek, onSelect = { dayOfWeek = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogField("开始（HH:mm）", startInput, { startInput = it }, Modifier.weight(1f))
                    DialogField("结束（HH:mm）", endInput, { endInput = it }, Modifier.weight(1f))
                }
                Text(
                    "支持任意时间；结束早于开始按次日计算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogField("开始周", startWeekInput, { startWeekInput = it }, Modifier.weight(1f), true)
                    DialogField("结束周", endWeekInput, { endWeekInput = it }, Modifier.weight(1f), true)
                }
                FieldLabel("单双周规则")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(WeekParity.ALL to "每周", WeekParity.ODD to "单周", WeekParity.EVEN to "双周").forEach { item ->
                        FilterChip(selected = parity == item.first, onClick = { parity = item.first }, label = { Text(item.second) })
                    }
                }
                FieldLabel("课程颜色")
                ColorSelector(selected = color, onSelect = { color = it })
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val start = parseClockMinute(startInput)
                    val end = start?.let { endMinuteFromInput(it, endInput) }
                    val course = Course(
                        id = initial?.id ?: 0,
                        name = name.trim(),
                        teacher = teacher.trim().ifEmpty { null },
                        classroom = classroom.trim(),
                        dayOfWeek = dayOfWeek,
                        startMinute = start ?: -1,
                        endMinuteExclusive = end ?: -1,
                        startWeek = startWeekInput.toIntOrNull() ?: -1,
                        endWeek = endWeekInput.toIntOrNull() ?: -1,
                        weekParity = parity,
                        colorArgb = color,
                    )
                    val validation = when {
                        start == null || end == null -> "请输入有效且不同的起止时间（HH:mm）"
                        else -> ScheduleValidation.courseError(course)
                    }
                    if (validation != null) {
                        error = validation
                    } else {
                        saving = true
                        onSave(course) { result ->
                            saving = false
                            error = result
                            if (result == null) onDismiss()
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun CourseDetailDialog(
    course: Course,
    instance: ScheduleInstance,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.name) },
        text = {
            DialogColumn {
                DetailLine("教师", course.teacher?.takeIf(String::isNotBlank) ?: "未填写")
                DetailLine("教室", course.classroom)
                DetailLine("本次时间", "${instance.anchorDate} ${formatTimeRange(instance.startMinute, instance.endMinuteExclusive)}")
                DetailLine(
                    "重复规则",
                    "${weekDayLabels[course.dayOfWeek - 1]} · 第${course.startWeek}–${course.endWeek}周 · " +
                        when (course.weekParity) {
                            WeekParity.ALL -> "每周"
                            WeekParity.ODD -> "单周"
                            WeekParity.EVEN -> "双周"
                        },
                )
                if (instance.isMoved) DetailLine("调课", "原日期 ${instance.originalDate}；仅本次时间改变")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onMove) { Text("临时调课") }
                    TextButton(onClick = onCancel) { Text("当日停课") }
                    TextButton(onClick = onDelete) { Text("删除课程") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("编辑") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun MoveCourseDialog(
    instance: ScheduleInstance,
    onDismiss: () -> Unit,
    onSave: (CourseException, (String?) -> Unit) -> Unit,
) {
    var dateInput by remember { mutableStateOf(instance.anchorDate.toString()) }
    var startInput by remember { mutableStateOf(formatMinute(instance.startMinute)) }
    var endInput by remember { mutableStateOf(formatMinute(instance.endMinuteExclusive)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("临时调课 · ${instance.title}") },
        text = {
            DialogColumn {
                Text("只调整 ${instance.originalDate} 这一次，不改变整门课程的重复规则。")
                DialogField("目标日期（yyyy-MM-dd）", dateInput, { dateInput = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogField("开始（HH:mm）", startInput, { startInput = it }, Modifier.weight(1f))
                    DialogField("结束（HH:mm）", endInput, { endInput = it }, Modifier.weight(1f))
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
                    val start = parseClockMinute(startInput)
                    val end = start?.let { endMinuteFromInput(it, endInput) }
                    if (date == null || start == null || end == null) {
                        error = "请输入有效日期及不同的起止时间"
                    } else {
                        val exception = CourseException(
                            courseId = instance.sourceId,
                            originalDate = instance.originalDate,
                            type = CourseExceptionType.MOVE,
                            targetDate = date,
                            targetStartMinute = start,
                            targetEndMinuteExclusive = end,
                        )
                        saving = true
                        onSave(exception) { result ->
                            saving = false
                            error = result
                            if (result == null) onDismiss()
                        }
                    }
                },
            ) { Text("保存调课") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun OneOffEventEditorDialog(
    initial: OneOffEvent?,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (OneOffEvent, (String?) -> Unit) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var dateInput by remember { mutableStateOf((initial?.date ?: defaultDate).toString()) }
    var startInput by remember { mutableStateOf(formatMinute(initial?.startMinute ?: 18 * 60)) }
    var endInput by remember { mutableStateOf(formatMinute(initial?.endMinuteExclusive ?: 19 * 60)) }
    var location by remember { mutableStateOf(initial?.location.orEmpty()) }
    var color by remember { mutableIntStateOf(initial?.colorArgb ?: courseColorPalette[3]) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "添加一次性日程" else "编辑一次性日程") },
        text = {
            DialogColumn {
                DialogField("日程标题", title, { title = it })
                DialogField("日期（yyyy-MM-dd）", dateInput, { dateInput = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogField("开始（HH:mm）", startInput, { startInput = it }, Modifier.weight(1f))
                    DialogField("结束（HH:mm）", endInput, { endInput = it }, Modifier.weight(1f))
                }
                DialogField("地点（可选）", location, { location = it })
                FieldLabel("日程颜色")
                ColorSelector(selected = color, onSelect = { color = it })
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
                    val start = parseClockMinute(startInput)
                    val end = start?.let { endMinuteFromInput(it, endInput) }
                    val event = OneOffEvent(
                        id = initial?.id ?: 0,
                        title = title.trim(),
                        date = date ?: LocalDate.MIN,
                        startMinute = start ?: -1,
                        endMinuteExclusive = end ?: -1,
                        location = location.trim(),
                        colorArgb = color,
                    )
                    val validation = when {
                        date == null -> "日期格式无效"
                        start == null || end == null -> "请输入有效且不同的起止时间"
                        else -> ScheduleValidation.eventError(event)
                    }
                    if (validation != null) {
                        error = validation
                    } else {
                        saving = true
                        onSave(event) { result ->
                            saving = false
                            error = result
                            if (result == null) onDismiss()
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun EventDetailDialog(
    event: OneOffEvent,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = {
            DialogColumn {
                DetailLine("时间", "${event.date} ${formatTimeRange(event.startMinute, event.endMinuteExclusive)}")
                DetailLine("地点", event.location.ifBlank { "未填写" })
                TextButton(onClick = onDelete) { Text("删除日程") }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("编辑") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun RoutineManagerDialog(
    routines: List<RoutineBlock>,
    onDismiss: () -> Unit,
    onSave: (RoutineBlock, (String?) -> Unit) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var title by remember { mutableStateOf("睡眠") }
    var category by remember { mutableStateOf(RoutineCategory.SLEEP) }
    var repeat by remember { mutableStateOf(RoutineRepeat.DAILY) }
    var dayOfWeek by remember { mutableIntStateOf(1) }
    var startInput by remember { mutableStateOf("23:00") }
    var endInput by remember { mutableStateOf("07:00") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("作息与固定事务") },
        text = {
            DialogColumn {
                Text("这些时间段会从每日空闲时间中排除；跨日睡眠同样有效。")
                if (routines.isEmpty()) {
                    Text("尚未设置作息或固定事务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    routines.forEach { routine ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(routine.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${routineLabel(routine)} · ${formatTimeRange(routine.startMinute, routine.endMinuteExclusive)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDelete(routine.id) }) { Text("删除") }
                        }
                    }
                }
                HorizontalDivider()
                Text("新增事务", style = MaterialTheme.typography.titleMedium)
                DialogField("名称", title, { title = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutineCategory.entries.forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = {
                                category = item
                                if (title in listOf("睡眠", "用餐", "固定事务")) title = routineCategoryLabel(item)
                            },
                            label = { Text(routineCategoryLabel(item)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = repeat == RoutineRepeat.DAILY, onClick = { repeat = RoutineRepeat.DAILY }, label = { Text("每天") })
                    FilterChip(selected = repeat == RoutineRepeat.WEEKLY, onClick = { repeat = RoutineRepeat.WEEKLY }, label = { Text("每周") })
                }
                if (repeat == RoutineRepeat.WEEKLY) WeekdaySelector(selected = dayOfWeek, onSelect = { dayOfWeek = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogField("开始（HH:mm）", startInput, { startInput = it }, Modifier.weight(1f))
                    DialogField("结束（HH:mm）", endInput, { endInput = it }, Modifier.weight(1f))
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val start = parseClockMinute(startInput)
                    val end = start?.let { endMinuteFromInput(it, endInput) }
                    val routine = RoutineBlock(
                        title = title.trim(),
                        category = category,
                        repeat = repeat,
                        dayOfWeek = if (repeat == RoutineRepeat.WEEKLY) dayOfWeek else null,
                        startMinute = start ?: -1,
                        endMinuteExclusive = end ?: -1,
                    )
                    val validation = if (start == null || end == null) {
                        "请输入有效且不同的起止时间"
                    } else {
                        ScheduleValidation.routineError(routine)
                    }
                    if (validation != null) {
                        error = validation
                    } else {
                        saving = true
                        onSave(routine) { result ->
                            saving = false
                            error = result
                        }
                    }
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun ExceptionListDialog(
    exceptions: List<CourseException>,
    courses: List<Course>,
    onDismiss: () -> Unit,
    onRestore: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调课与停课记录") },
        text = {
            DialogColumn {
                if (exceptions.isEmpty()) {
                    Text("暂无日期例外。整门课程仍按原重复规则展示。")
                } else {
                    exceptions.forEach { exception ->
                        val courseName = courses.firstOrNull { it.id == exception.courseId }?.name ?: "已删除课程"
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(courseName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (exception.type == CourseExceptionType.CANCEL) {
                                        "${exception.originalDate} 停课"
                                    } else {
                                        "${exception.originalDate} 调至 ${exception.targetDate} " +
                                            formatTimeRange(exception.targetStartMinute ?: 0, exception.targetEndMinuteExclusive ?: 0)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { onRestore(exception.id) }) { Text("恢复") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun FreeTimeDialog(day: DaySchedule, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${day.date} · 空闲时间") },
        text = {
            DialogColumn {
                Text("合计 ${formatDuration(day.totalFreeMinutes)}；按 ${day.zoneId.id} 当地自然日计算。")
                if (day.freeTimes.isEmpty()) {
                    Text("当天没有可用空闲段。")
                } else {
                    day.freeTimes.forEach { interval ->
                        Text(formatFreeTime(interval), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(
                    "已排除课程、一次性日程及已设置的睡眠、用餐和固定事务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun ConfirmDeleteDialog(
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
private fun DialogColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(max = 540.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun DialogField(
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
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

@Composable
private fun WeekdaySelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        weekDayLabels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index + 1, onClick = { onSelect(index + 1) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ColorSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        courseColorPalette.forEach { color ->
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(color), CircleShape)
                    .border(
                        width = if (selected == color) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun ErrorText(error: String?) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun routineCategoryLabel(category: RoutineCategory): String = when (category) {
    RoutineCategory.SLEEP -> "睡眠"
    RoutineCategory.MEAL -> "用餐"
    RoutineCategory.FIXED -> "固定事务"
}

private fun routineLabel(routine: RoutineBlock): String = when (routine.repeat) {
    RoutineRepeat.DAILY -> "每天"
    RoutineRepeat.WEEKLY -> "每${weekDayLabels[(routine.dayOfWeek ?: 1) - 1]}"
}
