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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.DateNavigator
import com.zhixu.studyos.core.designsystem.component.DetailTopBar
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.chineseDateWithWeekday
import com.zhixu.studyos.core.model.DayRecord

@Composable
fun EntertainmentScreen(
    viewModel: EntertainmentViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var minutesInput by remember(state.date) { mutableStateOf("0") }

    LaunchedEffect(state.date, state.minutes) {
        minutesInput = (state.minutes ?: 0).toString()
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailTopBar(title = "娱乐时间", onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .testTag("screen_entertainment"),
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
                    text = "今日娱乐时间",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "玩手机、游戏等，自己估一个大概就好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = minutesInput,
                    onValueChange = { value ->
                        minutesInput = value.filter(Char::isDigit).take(4)
                    },
                    label = { Text("今天玩了多久（分钟）") },
                    placeholder = { Text("例如 90") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("entertainment_minutes"),
                )
                val total = minutesInput.toIntOrNull() ?: 0
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "合计 ${formatDuration(total.toLong())}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = "保存",
                        onClick = {
                            viewModel.save(hours = total / 60, minutes = total % 60)
                        },
                        modifier = Modifier.testTag("entertainment_save"),
                    )
                    if (state.minutes != null) {
                        TextButton(onClick = viewModel::delete) { Text("删除记录") }
                    }
                }
            }

            Text(
                text = "最近记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.recentWithData.isEmpty()) {
                Text(
                    text = "还没有娱乐时间记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    state.recentWithData.take(14).forEachIndexed { index, record ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        EntertainmentRow(record, onClick = { viewModel.setDate(record.date) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EntertainmentRow(record: DayRecord, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = record.date.chineseDateWithWeekday(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDuration(record.entertainmentMinutes?.toLong()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
