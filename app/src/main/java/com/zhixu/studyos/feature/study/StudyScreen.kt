package com.zhixu.studyos.feature.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.model.StudyTask
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@Composable
fun StudyScreen(viewModel: TaskViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val historyState by viewModel.historyState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editorVisible by remember { mutableStateOf(false) }
    var editorInitial by remember { mutableStateOf<StudyTask?>(null) }
    var editorDate by remember { mutableStateOf(LocalDate.now()) }
    var editorStartScheduled by remember { mutableStateOf(false) }
    var detailTaskId by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<StudyTask?>(null) }
    var deleteWholeSeries by remember { mutableStateOf(false) }
    val detailTask = detailTaskId?.let { id -> state.allTasks.firstOrNull { it.id == id } }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StudyContent(
            state = state,
            onRetry = viewModel::retry,
            onAddTask = {
                editorInitial = null
                editorDate = state.selectedDate
                editorStartScheduled = true
                editorVisible = true
            },
            onPlanTomorrow = {
                val tomorrow = viewModel.tomorrow()
                viewModel.selectDate(tomorrow)
                viewModel.setViewMode(TaskViewMode.DAY)
                editorInitial = null
                editorDate = tomorrow
                editorStartScheduled = true
                editorVisible = true
            },
            onViewMode = viewModel::setViewMode,
            onPreviousDay = viewModel::previousDay,
            onNextDay = viewModel::nextDay,
            onToday = viewModel::today,
            onSelectDate = viewModel::selectDate,
            onOpenTask = { detailTaskId = it.id },
            onToggleTask = viewModel::setTaskCompleted,
            onMoveTask = viewModel::moveTask,
            onScheduleBacklog = { task ->
                editorInitial = task.copy(sessions = emptyList())
                editorDate = state.today
                editorStartScheduled = true
                editorVisible = true
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    if (editorVisible) {
        TaskEditorDialog(
            initial = editorInitial,
            defaultDate = editorDate,
            startScheduled = editorStartScheduled,
            onDismiss = { editorVisible = false },
            onSave = viewModel::saveTask,
        )
    }
    detailTask?.let { task ->
        TaskDetailDialog(
            task = task,
            conflicts = state.conflictsByTaskId[task.id].orEmpty(),
            zoneId = state.zoneId,
            onDismiss = { detailTaskId = null },
            onEdit = {
                editorInitial = task
                editorDate = task.sessions.firstOrNull()?.date ?: state.selectedDate
                editorStartScheduled = task.sessions.isNotEmpty()
                detailTaskId = null
                editorVisible = true
            },
            onSchedule = {
                editorInitial = task.copy(sessions = emptyList())
                editorDate = state.selectedDate
                editorStartScheduled = true
                detailTaskId = null
                editorVisible = true
            },
            onToggleCompleted = { viewModel.setTaskCompleted(task.id, !task.isCompleted) },
            onStart = { viewModel.startTask(task.id) },
            onPause = { viewModel.pauseTask(task.id) },
            onPostpone = { viewModel.postponeTask(task.id) },
            onDeleteCurrent = {
                deleteTarget = task
                deleteWholeSeries = false
                detailTaskId = null
            },
            onDeleteSeries = {
                deleteTarget = task
                deleteWholeSeries = true
                detailTaskId = null
            },
            onToggleSessionLock = { session, locked ->
                viewModel.setSessionLocked(task.id, session.id, locked)
            },
            onOpenHistory = { viewModel.openTaskHistory(task.id) },
        )
    }
    if (historyState.visible) {
        TaskHistoryDialog(
            state = historyState,
            onDismiss = viewModel::closeTaskHistory,
            onDeleteHistory = { historyState.taskId?.let(viewModel::deleteTaskHistory) },
        )
    }
    deleteTarget?.let { target ->
        TaskDeleteConfirmDialog(
            task = target,
            wholeSeries = deleteWholeSeries,
            onDismiss = { deleteTarget = null },
            onConfirm = { viewModel.deleteTask(target.id, deleteWholeSeries) },
        )
    }
}

@Composable
private fun StudyContent(
    state: TaskUiState,
    onRetry: () -> Unit,
    onAddTask: () -> Unit,
    onPlanTomorrow: () -> Unit,
    onViewMode: (TaskViewMode) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onOpenTask: (StudyTask) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onMoveTask: (Long, Int) -> Unit,
    onScheduleBacklog: (StudyTask) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("学习计划", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "安排、拆分并完成每天的学习任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAddTask, modifier = Modifier.testTag("add_task")) {
                Icon(Icons.Rounded.Add, contentDescription = "新建任务")
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.viewMode == TaskViewMode.DAY,
                onClick = { onViewMode(TaskViewMode.DAY) },
                label = { Text("日视图") },
            )
            Spacer(modifier = Modifier.width(4.dp))
            FilterChip(
                selected = state.viewMode == TaskViewMode.WEEK,
                onClick = { onViewMode(TaskViewMode.WEEK) },
                label = { Text("周视图") },
            )
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
                onClick = onPlanTomorrow,
                modifier = Modifier.testTag("plan_tomorrow"),
            ) {
                Icon(Icons.Rounded.Today, contentDescription = null)
                Text("计划明天")
            }
        }
        state.scheduleWarning?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.sleepConflicts.take(3).forEach { conflict ->
            Text(conflict, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.errorMessage != null -> AppCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = "任务暂时无法加载",
                    message = state.errorMessage,
                    icon = Icons.Rounded.ErrorOutline,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    text = "重试",
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            else -> when (state.viewMode) {
                TaskViewMode.DAY -> DayTaskContent(
                    state = state,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onToday = onToday,
                    onOpenTask = onOpenTask,
                    onToggleTask = onToggleTask,
                    onMoveTask = onMoveTask,
                    onScheduleBacklog = onScheduleBacklog,
                    modifier = Modifier.weight(1f),
                )
                TaskViewMode.WEEK -> WeekTaskContent(
                    state = state,
                    onSelectDate = { date ->
                        onSelectDate(date)
                        onViewMode(TaskViewMode.DAY)
                    },
                    onPreviousWeek = { onSelectDate(state.selectedDate.minusWeeks(1)) },
                    onNextWeek = { onSelectDate(state.selectedDate.plusWeeks(1)) },
                    onOpenTask = onOpenTask,
                    onScheduleBacklog = onScheduleBacklog,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayTaskContent(
    state: TaskUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onOpenTask: (StudyTask) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onMoveTask: (Long, Int) -> Unit,
    onScheduleBacklog: (StudyTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = state.dayPlan ?: return
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousDay) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "前一天")
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.selectedDate == state.today) "今天" else formatTaskDate(state.selectedDate),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        formatTaskDate(state.selectedDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onNextDay) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "后一天")
                }
                TextButton(onClick = onToday) { Text("今天") }
            }
        }
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                val completed = plan.tasks.count { it.task.isCompleted }
                Text(
                    "已完成 $completed/${plan.tasks.size} · 学习 ${formatTaskDuration(plan.taskMinutes.toLong())} · " +
                        "空闲 ${formatTaskDuration(plan.totalFreeMinutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "拖动任务右侧手柄可调整顺序；任务与课程重叠时会显示提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionHeader(title = "当天时间轴") }
        if (plan.timeline.isEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text("当天还没有时间安排", style = MaterialTheme.typography.titleSmall)
                }
            }
        } else {
            items(plan.timeline) { block ->
                StudyTimelineRow(block)
            }
        }
        item { SectionHeader(title = "当天任务") }
        if (plan.tasks.isEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "当天还没有学习任务",
                        message = "点击右上角添加；或先放入待安排，再选择合适时间。",
                        icon = Icons.Rounded.ErrorOutline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            itemsIndexed(
                items = plan.tasks,
                key = { _, entry -> entry.task.id },
            ) { index, entry ->
                TaskListCard(
                    task = entry.task,
                    date = plan.date,
                    index = index,
                    itemCount = plan.tasks.size,
                    conflicts = state.conflictsByTaskId[entry.task.id].orEmpty(),
                    onToggle = { completed -> onToggleTask(entry.task.id, completed) },
                    onOpen = { onOpenTask(entry.task) },
                    onMove = { direction -> onMoveTask(entry.task.id, direction) },
                )
            }
        }
        if (state.backlog.isNotEmpty()) {
            item { SectionHeader(title = "待安排（${state.backlog.size}）") }
            item {
                Text(
                    "未完成且未安排或已过期的任务会留在这里，不会消失。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.backlog, key = { "backlog_${it.id}" }) { task ->
                BacklogTaskCard(
                    task = task,
                    onSchedule = { onScheduleBacklog(task) },
                    onOpen = { onOpenTask(task) },
                )
            }
        }
    }
}

@Composable
private fun WeekTaskContent(
    state: TaskUiState,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onOpenTask: (StudyTask) -> Unit,
    onScheduleBacklog: (StudyTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val monday = state.selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousWeek) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "上一周")
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("每周任务", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatShortTaskDate(monday)} – ${formatShortTaskDate(monday.plusDays(6))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onNextWeek) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "下一周")
                }
            }
        }
        items(state.weekDays, key = { it.date }) { summary ->
            WeekDayCard(
                summary = summary,
                onClick = { onSelectDate(summary.date) },
            )
        }
        if (state.backlog.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(title = "待安排（${state.backlog.size}）")
            }
            items(state.backlog, key = { "week_backlog_${it.id}" }) { task ->
                BacklogTaskCard(
                    task = task,
                    onSchedule = { onScheduleBacklog(task) },
                    onOpen = { onOpenTask(task) },
                )
            }
        }
    }
}
