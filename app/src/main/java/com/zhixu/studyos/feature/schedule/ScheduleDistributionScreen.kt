package com.zhixu.studyos.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.chineseDateWithWeekday
import com.zhixu.studyos.core.model.DayScheduleItem
import java.time.LocalDate

private data class TimeBlock(
    val label: String,
    val startMinute: Int,
    val endMinute: Int,
)

private val timeBlocks = listOf(
    TimeBlock("上午", 6 * 60, 12 * 60),
    TimeBlock("下午", 12 * 60, 18 * 60),
    TimeBlock("晚上", 18 * 60, 24 * 60),
)

private fun minuteLabel(minute: Int): String =
    "%02d:%02d".format((minute / 60).coerceIn(0, 24), (minute % 60).coerceIn(0, 59))

/**
 * 「时间分布」：只呈现今天这一天，按 6–12、12–18、18–24 三段显示每段要做什么。
 * 课程的增删改统一收进右上角菜单的「课程管理」二级页。
 */
@Composable
fun ScheduleDistributionScreen(
    state: ScheduleUiState,
    onOpenManager: () -> Unit,
    onRetry: () -> Unit,
) {
    val today = LocalDate.now()
    val todaySchedule = state.weekDays.firstOrNull { it.date == today }
    val completedCount = state.todayTasks.count { it.isCompleted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_schedule")
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("时间分布", style = MaterialTheme.typography.headlineSmall)
                Text(
                    today.chineseDateWithWeekday(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("distribution_more"),
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("课程管理") },
                        onClick = {
                            menuExpanded = false
                            onOpenManager()
                        },
                        modifier = Modifier.testTag("open_course_manager"),
                    )
                }
            }
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

            state.errorMessage != null -> AppCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = "时间分布暂时无法加载",
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

            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag("screen_time_distribution"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.todayTasks.isNotEmpty()) {
                    Text(
                        text = "今天 ${state.todayTasks.size} 件待办 · 已完成 $completedCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("distribution_task_summary"),
                    )
                }
                timeBlocks.forEach { block ->
                    val items = todaySchedule?.items
                        ?.filter { item ->
                            item.segmentStartMinute < block.endMinute &&
                                item.segmentEndMinuteExclusive > block.startMinute
                        }
                        ?.sortedBy { it.segmentStartMinute }
                        .orEmpty()
                    TimeBlockCard(block = block, items = items)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TimeBlockCard(block: TimeBlock, items: List<DayScheduleItem>) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("distribution_block_${block.startMinute}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${minuteLabel(block.startMinute)} – ${minuteLabel(block.endMinute)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = block.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (items.isEmpty()) "空闲" else "${items.size} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (items.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "这段时间没有安排。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(item.instance.colorArgb), CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.instance.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.instance.subtitle.isNotBlank()) {
                            Text(
                                text = item.instance.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = "${minuteLabel(item.instance.startMinute)}–" +
                            minuteLabel(item.instance.endMinuteExclusive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
