package com.zhixu.studyos.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.model.DaySchedule
import com.zhixu.studyos.core.model.DayScheduleItem
import com.zhixu.studyos.core.model.ScheduleItemType

private val timeAxisWidth = 48.dp
private val dayColumnWidth = 136.dp
private val hourHeight = 56.dp

@Composable
fun WeekScheduleGrid(
    days: List<DaySchedule>,
    onItemClick: (DayScheduleItem) -> Unit,
    onDayClick: (DaySchedule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val gridWidth = timeAxisWidth + dayColumnWidth * 7
    val gridHeight = hourHeight * 24
    val gridColor = MaterialTheme.colorScheme.outline

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScroll)
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 8.dp),
        ) {
            Spacer(modifier = Modifier.width(timeAxisWidth))
            days.forEach { day ->
                Column(
                    modifier = Modifier
                        .width(dayColumnWidth)
                        .clickable { onDayClick(day) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "${weekDayLabels[day.date.dayOfWeek.value - 1]} ${day.date.monthValue}/${day.date.dayOfMonth}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "空闲 ${formatDuration(day.totalFreeMinutes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll),
        ) {
            Box(
                modifier = Modifier
                    .width(gridWidth)
                    .height(gridHeight)
                    .drawBehind {
                        for (hour in 0..24) {
                            val y = (hourHeight * hour).toPx()
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
                        }
                        for (column in 0..7) {
                            val x = (timeAxisWidth + dayColumnWidth * column).toPx()
                            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
                        }
                    },
            ) {
                for (hour in 0..23) {
                    Text(
                        text = "%02d:00".format(hour),
                        modifier = Modifier.offset(
                            x = 3.dp,
                            y = if (hour == 0) 1.dp else minuteOffset(hour * 60) - 9.dp,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                days.forEachIndexed { dayIndex, day ->
                    day.items.filter { it.instance.type != ScheduleItemType.ROUTINE }.forEach { item ->
                        ScheduleBlock(
                            item = item,
                            isContinuation = item.instance.anchorDate != day.date,
                            modifier = Modifier
                                .offset(
                                    x = timeAxisWidth + dayColumnWidth * dayIndex + 3.dp,
                                    y = minuteOffset(item.segmentStartMinute) + 1.dp,
                                )
                                .width(dayColumnWidth - 6.dp)
                                .height(
                                    minuteOffset(item.segmentEndMinuteExclusive - item.segmentStartMinute)
                                        .coerceAtLeast(26.dp) - 2.dp,
                                ),
                            onClick = { onItemClick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleBlock(
    item: DayScheduleItem,
    isContinuation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val baseColor = Color(item.instance.colorArgb)
    val darkTheme = isSystemInDarkTheme()
    val shape = RoundedCornerShape(8.dp)
    val titlePrefix = when {
        isContinuation -> "续 · "
        item.instance.isMoved -> "调 · "
        item.instance.type == ScheduleItemType.ONE_OFF_EVENT -> "日程 · "
        else -> ""
    }
    Column(
        modifier = modifier
            .testTag("schedule_${item.instance.type.name}_${item.instance.sourceId}_${item.instance.originalDate}")
            .clip(shape)
            .background(baseColor.copy(alpha = if (darkTheme) 0.34f else 0.24f))
            .border(1.dp, baseColor.copy(alpha = 0.85f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = titlePrefix + item.instance.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${formatMinute(item.segmentStartMinute)}–${formatMinute(item.segmentEndMinuteExclusive)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun minuteOffset(minutes: Int): Dp = hourHeight * (minutes / 60f)
