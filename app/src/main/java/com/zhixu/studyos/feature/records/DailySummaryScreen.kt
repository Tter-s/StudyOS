package com.zhixu.studyos.feature.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.DateNavigator
import com.zhixu.studyos.core.designsystem.component.DetailTopBar
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.model.MAX_DAILY_SUMMARY_LENGTH

@Composable
fun DailySummaryScreen(
    viewModel: DailySummaryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var text by remember(state.date) { mutableStateOf("") }

    LaunchedEffect(state.date, state.summary) {
        text = state.summary
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailTopBar(title = "每日总结", onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .testTag("screen_daily_summary"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DateNavigator(
                date = state.date,
                canGoNext = state.date < viewModel.today,
                onPrevious = viewModel::previousDay,
                onNext = viewModel::nextDay,
            )

            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "完成 ${state.completed} / ${state.total}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "这个数字来自当天的待办，不需要手填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (state.total == 0) 0f else state.completed.toFloat() / state.total
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            if (state.tasks.isNotEmpty()) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "当天的待办",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    state.tasks.forEach { task ->
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (task.isCompleted) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = if (task.isCompleted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (task.isCompleted) {
                                    TextDecoration.LineThrough
                                } else {
                                    TextDecoration.None
                                },
                                color = if (task.isCompleted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(MAX_DAILY_SUMMARY_LENGTH)
                    viewModel.clearNotice()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("summary_input"),
                label = { Text("今天实际做了什么") },
                placeholder = { Text("记下做过的事，不用评价好坏。") },
                minLines = 7,
                maxLines = 14,
                supportingText = {
                    Text("${text.length} / $MAX_DAILY_SUMMARY_LENGTH")
                },
            )

            PrimaryButton(
                text = "保存总结",
                onClick = { viewModel.save(text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("summary_save"),
            )

            state.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notice == "已保存") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.testTag("summary_notice"),
                )
            }
        }
    }
}
