package com.zhixu.studyos.feature.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.DetailTopBar
import com.zhixu.studyos.core.designsystem.component.chineseDateWithWeekday
import com.zhixu.studyos.core.model.DayRecord
import java.time.LocalDate

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenDay: (LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailTopBar(title = "历史记录", onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("screen_history"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 4.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "全部记录。点开某一天可以补写或修改总结。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.isLoading && state.days.isEmpty()) {
                item {
                    Text(
                        text = "还没有历史记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(items = state.days, key = { it.date.toEpochDay() }) { day ->
                HistoryDayCard(
                    day = day,
                    today = today,
                    onClick = { onOpenDay(day.date) },
                )
            }
        }
    }
}

@Composable
private fun HistoryDayCard(
    day: DayRecord,
    today: LocalDate,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (day.date) {
                        today -> "今天"
                        today.minusDays(1) -> "昨天"
                        else -> day.date.chineseDateWithWeekday()
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (day.date == today || day.date == today.minusDays(1)) {
                    Text(
                        text = day.date.chineseDateWithWeekday(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = buildList {
                if (day.totalTasks > 0) add("待办 ${day.completedTasks}/${day.totalTasks}")
                if (day.sleepMinutes != null) add("睡眠 ${formatDuration(day.sleepMinutes)}")
                if (day.entertainmentMinutes != null) {
                    add("娱乐 ${formatDuration(day.entertainmentMinutes.toLong())}")
                }
            }.joinToString(" · ").ifBlank { "没有待办与记录" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        day.summary?.let { summary ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
