package com.zhixu.studyos.feature.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.TimingSession
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LearningTimerTab(
    state: LearningUiState,
    viewModel: LearningViewModel,
) {
    var selectedSubjectId by remember(state.subjects) { mutableStateOf(state.subjects.firstOrNull()?.id) }
    var selectedTaskId by remember { mutableStateOf<Long?>(null) }
    var mode by remember { mutableStateOf(StudyTimerMode.STOPWATCH) }
    var pomodoroMinutes by remember { mutableStateOf(25) }
    var error by remember { mutableStateOf<String?>(null) }
    var taskMenuOpen by remember { mutableStateOf(false) }
    var showSubjectManager by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var correctingSession by remember { mutableStateOf<TimingSession?>(null) }
    var deletingTiming by remember { mutableStateOf<TimingSession?>(null) }
    val active = state.activeTimer
    val activeSubject = active?.let { formatSubjectName(state.subjects, it.subjectId) }
    val activeTask = active?.taskId?.let { id -> state.tasks.firstOrNull { it.id == id } }

    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("专注计时", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "正计时或番茄钟，只在应用前台累计；离开应用会自动暂停。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showSubjectManager = true }) { Text("科目管理") }
                }
            }
            if (active != null) {
                item {
                    ActiveTimerCard(
                        session = active,
                        now = state.now,
                        subjectName = activeSubject.orEmpty(),
                        taskTitle = activeTask?.title,
                        onPause = viewModel::pauseTimer,
                        onResume = viewModel::resumeTimer,
                        onFinish = viewModel::finishTimer,
                    )
                }
            } else {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        if (state.subjects.isEmpty()) {
                            Text("还没有科目。先添加科目，再开始计时。")
                            TextButton(onClick = { showSubjectManager = true }) { Text("添加科目") }
                        } else {
                            Text("开始一次专注", style = MaterialTheme.typography.titleMedium)
                            Text("科目", style = MaterialTheme.typography.labelLarge)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                state.subjects.forEach { subject ->
                                    FilterChip(
                                        selected = selectedSubjectId == subject.id,
                                        onClick = { selectedSubjectId = subject.id },
                                        label = { Text(subject.name) },
                                    )
                                }
                            }
                            Box {
                                OutlinedButton(
                                    onClick = { taskMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        state.tasks.firstOrNull { it.id == selectedTaskId }?.title
                                            ?: "不关联任务（可选）",
                                    )
                                }
                                DropdownMenu(expanded = taskMenuOpen, onDismissRequest = { taskMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("不关联任务") },
                                        onClick = {
                                            selectedTaskId = null
                                            taskMenuOpen = false
                                        },
                                    )
                                    state.tasks.filterNot(StudyTask::isCompleted).take(30).forEach { task ->
                                        DropdownMenuItem(
                                            text = { Text(task.title) },
                                            onClick = {
                                                selectedTaskId = task.id
                                                taskMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                            Row {
                                FilterChip(
                                    selected = mode == StudyTimerMode.STOPWATCH,
                                    onClick = { mode = StudyTimerMode.STOPWATCH },
                                    label = { Text("正计时") },
                                )
                                FilterChip(
                                    selected = mode == StudyTimerMode.POMODORO,
                                    onClick = { mode = StudyTimerMode.POMODORO },
                                    label = { Text("番茄钟") },
                                )
                            }
                            if (mode == StudyTimerMode.POMODORO) {
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    listOf(15, 25, 45, 60).forEach { minutes ->
                                        FilterChip(
                                            selected = pomodoroMinutes == minutes,
                                            onClick = { pomodoroMinutes = minutes },
                                            label = { Text("$minutes 分钟") },
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    val subjectId = selectedSubjectId
                                    if (subjectId == null) {
                                        error = "请选择科目"
                                    } else {
                                        viewModel.startTimer(
                                            subjectId = subjectId,
                                            taskId = selectedTaskId,
                                            mode = mode,
                                            plannedDurationMinutes = pomodoroMinutes.takeIf {
                                                mode == StudyTimerMode.POMODORO
                                            },
                                        ) { result -> error = result }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("start_timer"),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("开始专注")
                            }
                            error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "后台或锁屏时间不计入；重新打开需要手动继续。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = { showManualEntry = true },
                    enabled = state.subjects.isNotEmpty() && active == null,
                ) {
                    Icon(Icons.Rounded.MoreTime, contentDescription = null)
                    Text("补录已完成的学习")
                }
            }
            item { SectionHeader(title = "最近学习记录") }
            if (state.recentTimings.isEmpty()) {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Text("还没有完成的学习记录。")
                    }
                }
            } else {
                items(state.recentTimings.take(30), key = { it.id }) { session ->
                    TimingHistoryCard(
                        session = session,
                        subjectName = formatSubjectName(state.subjects, session.subjectId),
                        taskTitle = session.taskId?.let { id -> state.tasks.firstOrNull { it.id == id }?.title },
                        onClick = { correctingSession = session },
                    )
                }
            }
        }
    }

    if (showSubjectManager) {
        SubjectManagerDialog(
            subjects = state.subjects,
            onDismiss = { showSubjectManager = false },
            onAdd = viewModel::addSubject,
        )
    }
    if (showManualEntry) {
        ManualTimingDialog(
            subjects = state.subjects,
            tasks = state.tasks.filterNot(StudyTask::isCompleted),
            defaultDate = state.today,
            defaultStartTime = state.now.atZone(state.zoneId).toLocalTime().withSecond(0).withNano(0).toString(),
            zoneId = state.zoneId,
            onDismiss = { showManualEntry = false },
            onSave = { subjectId, taskId, date, startedAt, minutes, note, onResult ->
                viewModel.addManualTiming(subjectId, taskId, date, startedAt, minutes, note, onResult)
            },
        )
    }
    correctingSession?.let { session ->
        CorrectTimingDialog(
            session = session,
            subjectName = formatSubjectName(state.subjects, session.subjectId),
            taskTitle = session.taskId?.let { id -> state.tasks.firstOrNull { it.id == id }?.title },
            onDismiss = { correctingSession = null },
            onSave = { minutes, note, onResult ->
                viewModel.correctTiming(session.id, minutes, note, onResult)
            },
            onDelete = {
                deletingTiming = session
                correctingSession = null
            },
        )
    }
    deletingTiming?.let { session ->
        LearningDeleteConfirmDialog(
            title = "删除学习记录？",
            message = "将删除 ${formatSubjectName(state.subjects, session.subjectId)} 的这条完成记录。",
            onDismiss = { deletingTiming = null },
            onConfirm = { viewModel.deleteTiming(session.id) },
        )
    }
}

@Composable
private fun ActiveTimerCard(
    session: TimingSession,
    now: java.time.Instant,
    subjectName: String,
    taskTitle: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    val elapsed = session.elapsedMillis(now)
    val plannedMillis = session.plannedDurationMinutes?.times(60_000L)
    val displayMillis = if (session.mode == StudyTimerMode.POMODORO) {
        session.remainingMillis(now) ?: elapsed
    } else {
        elapsed
    }
    AppCard(modifier = Modifier.fillMaxWidth().testTag("active_timer")) {
        Text(
            subjectName + if (taskTitle != null) " · $taskTitle" else "",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "${session.mode.label} · ${if (session.status == StudyTimerStatus.RUNNING) "进行中" else "已暂停"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatLearningDuration(displayMillis),
            style = MaterialTheme.typography.displaySmall,
        )
        if (plannedMillis != null) {
            LinearProgressIndicator(
                progress = { (elapsed.toFloat() / plannedMillis).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "已中断 ${session.interruptionCount} 次；离开应用会自动暂停。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.status == StudyTimerStatus.RUNNING) {
                OutlinedButton(onClick = onPause) {
                    Icon(Icons.Rounded.Pause, contentDescription = null)
                    Text("暂停")
                }
            } else {
                Button(onClick = onResume) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Text("继续")
                }
            }
            OutlinedButton(onClick = onFinish) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Text("结束")
            }
        }
    }
}

@Composable
private fun TimingHistoryCard(
    session: TimingSession,
    subjectName: String,
    taskTitle: String?,
    onClick: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$subjectName · ${session.mode.label}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    buildString {
                        append(formatLearningDate(session.studyDate))
                        append(" · ")
                        append(formatLearningDuration(session.accumulatedMillis))
                        if (session.interruptionCount > 0) append(" · 中断 ${session.interruptionCount} 次")
                        if (session.correctedAt != null) append(" · 已修正")
                        if (taskTitle != null) append(" · $taskTitle")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                session.note.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onClick) { Text("修正") }
        }
    }
}

private val learningTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
