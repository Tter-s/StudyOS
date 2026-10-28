package com.zhixu.studyos.feature.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.PageTitle
import com.zhixu.studyos.core.designsystem.component.chineseDateWithWeekday
import com.zhixu.studyos.core.model.MAX_ENTERTAINMENT_MINUTES

@Composable
fun RecordsScreen(
    viewModel: RecordsHubViewModel,
    onOpenSummary: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenEntertainment: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)
            .testTag("screen_records"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageTitle(
            title = "记录",
            subtitle = state.date.chineseDateWithWeekday(),
        )

        AppCard(modifier = Modifier.fillMaxWidth()) {
            RecordEntry(
                icon = Icons.Rounded.EditNote,
                title = "每日总结",
                value = state.summary?.replace('\n', ' ') ?: "还没有写今天做了什么",
                onClick = onOpenSummary,
                testTag = "records_summary",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            RecordEntry(
                icon = Icons.Rounded.Bedtime,
                title = "睡眠记录",
                value = state.lastSleep?.let { record ->
                    val day = if (record.date == state.date) "昨晚" else record.date.chineseDateWithWeekday()
                    "$day · ${formatDuration(record.durationMinutes)}"
                } ?: "还没有记录睡眠",
                onClick = onOpenSleep,
                testTag = "records_sleep",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            RecordEntry(
                icon = Icons.Rounded.SportsEsports,
                title = "娱乐时间",
                value = state.entertainmentMinutes?.let { minutes ->
                    if (minutes == 0) "今天没有娱乐时间" else "今天 ${formatDuration(minutes.toLong())}"
                } ?: "还没有记录娱乐时间",
                onClick = onOpenEntertainment,
                testTag = "records_entertainment",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            RecordEntry(
                icon = Icons.Rounded.History,
                title = "历史记录",
                value = "看看过去的日子",
                onClick = onOpenHistory,
                testTag = "records_history",
            )
        }

        Text(
            text = "只记录事实，不打分，不做分析。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordEntry(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
