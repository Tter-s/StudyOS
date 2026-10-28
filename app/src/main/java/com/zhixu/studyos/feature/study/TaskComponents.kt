package com.zhixu.studyos.feature.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.model.StudyBlockKind
import com.zhixu.studyos.core.model.StudyDayBlock
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskConflict
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.task.TaskPlanner
import java.time.LocalDate

@Composable
internal fun StudyTimelineRow(block: StudyDayBlock) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTaskMinute(block.startMinute),
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(42.dp)
                .background(Color(block.colorArgb), RoundedCornerShape(3.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    if (block.isContinuation) append("续 · ")
                    if (block.isCompleted) append("已完成 · ")
                    append(block.title)
                },
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (block.isCompleted) TextDecoration.LineThrough else null,
            )
            Text(
                text = "${block.kind.label()} · ${block.subtitle}",
                style = MaterialTheme.typography.bodySmall,
                color = if (block.kind == StudyBlockKind.REST) Color(block.colorArgb) else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = formatTaskTimeRange(block.startMinute, block.endMinuteExclusive),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TaskListCard(
    task: StudyTask,
    date: LocalDate,
    index: Int,
    itemCount: Int,
    conflicts: List<TaskConflict>,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val dragThreshold = with(density) { 54.dp.toPx() }
    var accumulatedDrag by remember(task.id) { mutableFloatStateOf(0f) }
    val segments = task.sessionsVisibleOn(date)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_${task.id}")
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onToggle(!task.isCompleted) },
                modifier = Modifier.testTag("task_complete_${task.id}"),
            ) {
                Icon(
                    imageVector = if (task.isCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (task.isCompleted) "取消完成" else "完成",
                    tint = if (task.isCompleted) MaterialTheme.colorScheme.primary else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(priorityColor(task.priority), CircleShape),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                    )
                    if (task.recurrence != TaskRecurrence.NONE) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = "周期任务",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = buildString {
                        if (task.subject.isNotBlank()) append("${task.subject} · ")
                        append(TaskPlanner.priorityLabel(task.priority)).append("优先级 · ")
                        append(task.estimatedMinutes).append(" 分钟")
                        if (task.status == TaskStatus.IN_PROGRESS) append(" · 进行中")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (segments.isNotEmpty()) {
                    Text(
                        text = segments.joinToString(" / ") { segment ->
                            "${formatTaskTimeRange(segment.startMinute, segment.endMinuteExclusive)}" +
                                if (segment.isContinuation) "（续）" else ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (conflicts.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = "课程冲突",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "与「${conflicts.first().otherTitle}」等 ${conflicts.size} 处课程冲突",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "长按拖动调整顺序",
                modifier = Modifier
                    .testTag("task_drag_${task.id}")
                    .padding(horizontal = 6.dp)
                    .pointerInput(task.id, index, itemCount) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { accumulatedDrag = 0f },
                            onDragEnd = { accumulatedDrag = 0f },
                            onDragCancel = { accumulatedDrag = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulatedDrag += dragAmount.y
                                if (accumulatedDrag > dragThreshold && index < itemCount - 1) {
                                    onMove(1)
                                    accumulatedDrag = 0f
                                } else if (accumulatedDrag < -dragThreshold && index > 0) {
                                    onMove(-1)
                                    accumulatedDrag = 0f
                                }
                            },
                        )
                    },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BacklogTaskCard(
    task: StudyTask,
    onSchedule: () -> Unit,
    onOpen: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backlog_task_${task.id}")
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildString {
                        if (task.subject.isNotBlank()) append("${task.subject} · ")
                        append("预计 ${task.estimatedMinutes} 分钟")
                        task.dueDate?.let { append(" · 截止 ${formatShortTaskDate(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSchedule) { Text("安排") }
        }
    }
}

@Composable
internal fun WeekDayCard(
    summary: TaskWeekDaySummary,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("week_day_${summary.date}")
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTaskDate(summary.date),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "任务 ${summary.completedCount}/${summary.totalCount} · 课程 ${summary.courseCount} · " +
                        "空闲 ${formatTaskDuration(summary.plan.totalFreeMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (summary.totalCount == 0) "无任务" else "${summary.totalCount} 项",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (summary.plan.tasks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            summary.plan.tasks.take(3).forEach { entry ->
                Text(
                    text = "· ${entry.task.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.task.isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (entry.task.isCompleted) TextDecoration.LineThrough else null,
                )
            }
            if (summary.plan.tasks.size > 3) {
                Text(
                    text = "还有 ${summary.plan.tasks.size - 3} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun priorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.HIGH -> MaterialTheme.colorScheme.error
    TaskPriority.MEDIUM -> MaterialTheme.colorScheme.primary
    TaskPriority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun StudyBlockKind.label(): String = when (this) {
    StudyBlockKind.COURSE -> "课程"
    StudyBlockKind.EVENT -> "日程"
    StudyBlockKind.TASK -> "学习"
    StudyBlockKind.REST -> "休息"
}
