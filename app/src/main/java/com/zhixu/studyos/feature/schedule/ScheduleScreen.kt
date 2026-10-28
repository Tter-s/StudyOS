package com.zhixu.studyos.feature.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.DaySchedule
import com.zhixu.studyos.core.model.DayScheduleItem
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.ScheduleInstance
import com.zhixu.studyos.core.model.ScheduleItemType

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    importViewModel: ScheduleImportViewModel,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSemesterSettings by remember { mutableStateOf(false) }
    var showCourseEditor by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var selectedInstance by remember { mutableStateOf<ScheduleInstance?>(null) }
    var movingInstance by remember { mutableStateOf<ScheduleInstance?>(null) }
    var showEventEditor by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<OneOffEvent?>(null) }
    var showRoutines by remember { mutableStateOf(false) }
    var showExceptions by remember { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var selectedFreeDay by remember { mutableStateOf<DaySchedule?>(null) }
    var courseToDelete by remember { mutableStateOf<Course?>(null) }
    var eventToDelete by remember { mutableStateOf<OneOffEvent?>(null) }
    var screenError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScheduleContent(
            state = state,
            onBack = onBack,
            onPreviousWeek = viewModel::previousWeek,
            onNextWeek = viewModel::nextWeek,
            onCurrentWeek = viewModel::goToCurrentWeek,
            onRetry = viewModel::retry,
            onSemesterSettings = { showSemesterSettings = true },
            onAddCourse = {
                if (state.snapshot.semester == null) showSemesterSettings = true else {
                    editingCourse = null
                    showCourseEditor = true
                }
            },
            onAddEvent = {
                editingEvent = null
                showEventEditor = true
            },
            onRoutines = { showRoutines = true },
            onExceptions = { showExceptions = true },
            onImport = { showImport = true },
            onItemClick = { item -> selectedInstance = item.instance },
            onDayClick = { selectedFreeDay = it },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    if (showSemesterSettings) {
        SemesterSettingsDialog(
            initial = state.snapshot.semester,
            defaultStartDate = state.weekDays.firstOrNull()?.date ?: viewModel.currentDate(),
            defaultZoneId = state.weekDays.firstOrNull()?.zoneId ?: viewModel.fallbackZoneId,
            onDismiss = { showSemesterSettings = false },
            onSave = viewModel::saveSemester,
        )
    }
    if (showCourseEditor) {
        CourseEditorDialog(
            initial = editingCourse,
            onDismiss = { showCourseEditor = false },
            onSave = viewModel::saveCourse,
        )
    }
    if (showEventEditor) {
        OneOffEventEditorDialog(
            initial = editingEvent,
            defaultDate = state.weekDays.firstOrNull()?.date ?: viewModel.currentDate(),
            onDismiss = { showEventEditor = false },
            onSave = viewModel::saveEvent,
        )
    }

    selectedInstance?.let { instance ->
        when (instance.type) {
            ScheduleItemType.COURSE -> {
                val course = state.snapshot.courses.firstOrNull { it.id == instance.sourceId }
                if (course != null) {
                    CourseDetailDialog(
                        course = course,
                        instance = instance,
                        onDismiss = { selectedInstance = null },
                        onEdit = {
                            editingCourse = course
                            selectedInstance = null
                            showCourseEditor = true
                        },
                        onMove = {
                            movingInstance = instance
                            selectedInstance = null
                        },
                        onCancel = {
                            viewModel.saveException(
                                CourseException(
                                    courseId = course.id,
                                    originalDate = instance.originalDate,
                                    type = CourseExceptionType.CANCEL,
                                ),
                            ) { error -> if (error != null) screenError = error }
                            selectedInstance = null
                        },
                        onDelete = {
                            courseToDelete = course
                            selectedInstance = null
                        },
                    )
                }
            }
            ScheduleItemType.ONE_OFF_EVENT -> {
                val event = state.snapshot.events.firstOrNull { it.id == instance.sourceId }
                if (event != null) {
                    EventDetailDialog(
                        event = event,
                        onDismiss = { selectedInstance = null },
                        onEdit = {
                            editingEvent = event
                            selectedInstance = null
                            showEventEditor = true
                        },
                        onDelete = {
                            eventToDelete = event
                            selectedInstance = null
                        },
                    )
                }
            }
            ScheduleItemType.ROUTINE, ScheduleItemType.TASK -> Unit
        }
    }

    movingInstance?.let { instance ->
        MoveCourseDialog(
            instance = instance,
            onDismiss = { movingInstance = null },
            onSave = viewModel::saveException,
        )
    }
    if (showRoutines) {
        RoutineManagerDialog(
            routines = state.snapshot.routines,
            onDismiss = { showRoutines = false },
            onSave = viewModel::saveRoutine,
            onDelete = viewModel::deleteRoutine,
        )
    }
    if (showExceptions) {
        ExceptionListDialog(
            exceptions = state.snapshot.exceptions,
            courses = state.snapshot.courses,
            onDismiss = { showExceptions = false },
            onRestore = viewModel::deleteException,
        )
    }
    selectedFreeDay?.let { day ->
        FreeTimeDialog(day = day, onDismiss = { selectedFreeDay = null })
    }
    courseToDelete?.let { course ->
        ConfirmDeleteDialog(
            title = "删除「${course.name}」？",
            message = "将删除整门课程及全部调课、停课记录。此操作不会影响一次性日程。",
            onDismiss = { courseToDelete = null },
            onConfirm = { viewModel.deleteCourse(course.id) },
        )
    }
    eventToDelete?.let { event ->
        ConfirmDeleteDialog(
            title = "删除「${event.title}」？",
            message = "只会删除这条一次性日程。",
            onDismiss = { eventToDelete = null },
            onConfirm = { viewModel.deleteEvent(event.id) },
        )
    }
    if (showImport) {
        ScheduleImportDialog(
            viewModel = importViewModel,
            onDismiss = { showImport = false },
        )
    }
    screenError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { screenError = null },
            title = { Text("操作未完成") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { screenError = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun ScheduleContent(
    state: ScheduleUiState,
    onBack: (() -> Unit)?,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onRetry: () -> Unit,
    onSemesterSettings: () -> Unit,
    onAddCourse: () -> Unit,
    onAddEvent: () -> Unit,
    onRoutines: () -> Unit,
    onExceptions: () -> Unit,
    onImport: () -> Unit,
    onItemClick: (DayScheduleItem) -> Unit,
    onDayClick: (DaySchedule) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_schedule")
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("course_manager_back"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回时间分布",
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("课程管理", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "查看每周课程、导入课表，点击课程可编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("schedule_more"),
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多课表操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("设置学期") },
                        onClick = {
                            menuExpanded = false
                            onSemesterSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("一次性日程") },
                        onClick = {
                            menuExpanded = false
                            onAddEvent()
                        },
                        modifier = Modifier.testTag("add_event"),
                    )
                    DropdownMenuItem(
                        text = { Text("作息与固定事务") },
                        onClick = {
                            menuExpanded = false
                            onRoutines()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("调整记录") },
                        onClick = {
                            menuExpanded = false
                            onExceptions()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("从图片导入课表") },
                        onClick = {
                            menuExpanded = false
                            onImport()
                        },
                        modifier = Modifier.testTag("import_schedule"),
                    )
                }
            }
            IconButton(onClick = onAddCourse, modifier = Modifier.testTag("add_course")) {
                Icon(Icons.Rounded.Add, contentDescription = "添加课程")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPreviousWeek, enabled = state.selectedWeek > 1) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "上一教学周")
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.snapshot.semester == null) "本周" else "第 ${state.selectedWeek} 教学周",
                    style = MaterialTheme.typography.titleMedium,
                )
                val first = state.weekDays.firstOrNull()?.date
                val last = state.weekDays.lastOrNull()?.date
                if (first != null && last != null) Text("$first 至 $last", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onNextWeek) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "下一教学周")
            }
            TextButton(onClick = onCurrentWeek) { Text("本周") }
        }

        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.errorMessage != null -> AppCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = "课表暂时无法加载",
                    message = state.errorMessage,
                    icon = Icons.Rounded.ErrorOutline,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(text = "重试", onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            else -> {
                if (state.snapshot.semester == null) {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Text("先设置学期，再添加课程", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "一次性日程和作息可先使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onSemesterSettings) { Text("设置学期") }
                    }
                }
                WeekScheduleGrid(
                    days = state.weekDays,
                    onItemClick = onItemClick,
                    onDayClick = onDayClick,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}
