package com.zhixu.studyos.feature.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.DetailTopBar
import com.zhixu.studyos.core.designsystem.component.PageTitle
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.designsystem.component.chineseDateWithWeekday
import com.zhixu.studyos.core.designsystem.component.chineseMonthDay
import com.zhixu.studyos.core.model.DailyTask
import java.time.LocalDate

private sealed interface TaskEditorTarget {
    data object New : TaskEditorTarget
    data class Edit(val task: DailyTask) : TaskEditorTarget
}

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onOpenTomorrow: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editorTarget by remember { mutableStateOf<TaskEditorTarget?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshDate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("screen_home"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    PageTitle(
                        title = "今天",
                        subtitle = state.date.chineseDateWithWeekday(),
                    )
                }
                item { ProgressCard(state) }
                item {
                    SectionHeader(
                        title = "今日待办",
                        modifier = Modifier.fillMaxWidth(),
                        trailing = {
                            TextButton(
                                onClick = { editorTarget = TaskEditorTarget.New },
                                modifier = Modifier.testTag("today_add_task_text"),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("添加")
                            }
                        },
                    )
                }
                if (state.tasks.isEmpty()) {
                    item {
                        EmptyTaskCard(
                            title = "今天还没有待办",
                            message = "想做什么，就记一件。",
                            buttonText = "添加第一件",
                            onAdd = { editorTarget = TaskEditorTarget.New },
                        )
                    }
                } else {
                    items(items = state.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { viewModel.setCompleted(task, it) },
                            onEdit = { editorTarget = TaskEditorTarget.Edit(task) },
                        )
                    }
                }
                item {
                    TomorrowCard(
                        tasks = state.nextDayTasks,
                        onOpen = onOpenTomorrow,
                    )
                }
            }
            FloatingActionButton(
                onClick = { editorTarget = TaskEditorTarget.New },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .testTag("today_add_task"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加待办")
            }
        }
    }

    when (val target = editorTarget) {
        TaskEditorTarget.New -> TaskEditorDialog(
            initial = null,
            dateLabel = state.date.chineseDateWithWeekday(),
            onDismiss = { editorTarget = null },
            onSave = viewModel::addTask,
        )
        is TaskEditorTarget.Edit -> TaskEditorDialog(
            initial = target.task,
            dateLabel = state.date.chineseDateWithWeekday(),
            onDismiss = { editorTarget = null },
            onSave = { viewModel.renameTask(target.task.id, it) },
            onDelete = { viewModel.deleteTask(target.task.id) },
        )
        null -> Unit
    }
}

@Composable
private fun ProgressCard(state: DayTasksUiState) {
    val fraction = if (state.total == 0) 0f else state.completed.toFloat() / state.total
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.completed} / ${state.total}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when {
                        state.total == 0 -> "今天还没有安排"
                        state.completed == state.total -> "今天的待办都完成了"
                        else -> "还有 ${state.total - state.completed} 件没做，慢慢来"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun TaskRow(
    task: DailyTask,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("task_check_${task.id}"),
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                color = if (task.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit)
                    .padding(vertical = 14.dp, horizontal = 4.dp),
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("task_edit_${task.id}"),
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑待办",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyTaskCard(
    title: String,
    message: String,
    buttonText: String,
    onAdd: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(text = buttonText, onClick = onAdd)
    }
}

@Composable
private fun TomorrowCard(
    tasks: List<DailyTask>,
    onOpen: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .testTag("today_tomorrow"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "明日待办", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (tasks.isEmpty()) {
                        "睡前花一分钟，安排明天。"
                    } else {
                        "已安排 ${tasks.size} 项"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val preview = tasks.take(2).joinToString(" · ") { it.title }
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = if (tasks.isEmpty()) "去安排" else "查看",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun DayTasksScreen(
    viewModel: TodayViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editorTarget by remember { mutableStateOf<TaskEditorTarget?>(null) }
    val title = if (state.date == LocalDate.now()) "今日待办" else "明日待办"

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailTopBar(title = title, onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("screen_day_tasks"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = state.date.chineseDateWithWeekday(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "晚上安排，明天再做；未完成不会自动带到下一天。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.tasks.isEmpty()) {
                    item {
                        EmptyTaskCard(
                            title = "还没有安排",
                            message = "写下一两件明天想做的事就好。",
                            buttonText = "添加待办",
                            onAdd = { editorTarget = TaskEditorTarget.New },
                        )
                    }
                } else {
                    items(items = state.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { viewModel.setCompleted(task, it) },
                            onEdit = { editorTarget = TaskEditorTarget.Edit(task) },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { editorTarget = TaskEditorTarget.New },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .testTag("day_add_task"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加待办")
            }
        }
    }

    when (val target = editorTarget) {
        TaskEditorTarget.New -> TaskEditorDialog(
            initial = null,
            dateLabel = state.date.chineseDateWithWeekday(),
            onDismiss = { editorTarget = null },
            onSave = viewModel::addTask,
        )
        is TaskEditorTarget.Edit -> TaskEditorDialog(
            initial = target.task,
            dateLabel = state.date.chineseDateWithWeekday(),
            onDismiss = { editorTarget = null },
            onSave = { viewModel.renameTask(target.task.id, it) },
            onDelete = { viewModel.deleteTask(target.task.id) },
        )
        null -> Unit
    }
}
