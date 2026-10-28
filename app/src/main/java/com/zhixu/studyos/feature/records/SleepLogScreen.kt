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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.DetailTopBar
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.chineseDateWithWeekday
import com.zhixu.studyos.core.sleep.SleepRecord
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("H:mm")

@Composable
fun SleepLogScreen(
    viewModel: SleepLogViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editorTarget by remember { mutableStateOf<SleepEditorTarget?>(null) }
    val zone = viewModel.zone()
    val today = LocalDate.now(zone)

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailTopBar(title = "睡眠记录", onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("screen_sleep"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 4.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PrimaryButton(
                    text = "记录睡眠",
                    onClick = { editorTarget = SleepEditorTarget.New },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sleep_add"),
                )
            }
            item { LastSevenDaysCard(state, today, zone) }
            item {
                Text(
                    text = "全部记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.records.isEmpty()) {
                item {
                    Text(
                        text = "还没有睡眠记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(items = state.records, key = { it.date.toEpochDay() }) { record ->
                    SleepRow(
                        record = record,
                        zone = zone,
                        onClick = { editorTarget = SleepEditorTarget.Edit(record) },
                    )
                }
            }
        }
    }

    when (val target = editorTarget) {
        SleepEditorTarget.New -> SleepEditorDialog(
            initialDate = today,
            initialBed = LocalTime.of(23, 0),
            initialWake = LocalTime.of(7, 0),
            onDismiss = { editorTarget = null },
            onSave = { date, bed, wake ->
                viewModel.save(date, bed, wake)
            },
        )
        is SleepEditorTarget.Edit -> {
            val bed = target.record.bedAt?.atZone(zone)?.toLocalTime() ?: LocalTime.of(23, 0)
            val wake = target.record.wakeAt?.atZone(zone)?.toLocalTime() ?: LocalTime.of(7, 0)
            SleepEditorDialog(
                initialDate = target.record.date,
                initialBed = bed,
                initialWake = wake,
                onDismiss = { editorTarget = null },
                onSave = { date, bedTime, wakeTime ->
                    viewModel.save(date, bedTime, wakeTime)
                },
                onDelete = {
                    viewModel.delete(target.record.date)
                    editorTarget = null
                },
            )
        }
        null -> Unit
    }
}

private sealed interface SleepEditorTarget {
    data object New : SleepEditorTarget
    data class Edit(val record: SleepRecord) : SleepEditorTarget
}

@Composable
private fun LastSevenDaysCard(
    state: SleepLogUiState,
    today: LocalDate,
    zone: ZoneId,
) {
    val days = (0L..6L).map { today.minusDays(it) }
    val records = days.map { date -> date to state.recordOn(date) }
    val known = records.mapNotNull { it.second?.durationMinutes }
    val average = if (known.isEmpty()) null else known.average().toLong()

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "最近 7 天",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (average == null) "还没有足够记录" else "平均 ${formatDuration(average)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        records.forEachIndexed { index, (date, record) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = date.chineseDateWithWeekday(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (record == null) {
                        "未记录"
                    } else {
                        "${timeOf(record.bedAt, zone)}–${timeOf(record.wakeAt, zone)} · " +
                            formatDuration(record.durationMinutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepRow(
    record: SleepRecord,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.date.chineseDateWithWeekday(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${timeOf(record.bedAt, zone)} 放下手机 · ${timeOf(record.wakeAt, zone)} 起床",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatDuration(record.durationMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun timeOf(instant: java.time.Instant?, zone: ZoneId): String =
    instant?.atZone(zone)?.toLocalTime()?.label() ?: "--:--"

@Composable
private fun SleepEditorDialog(
    initialDate: LocalDate,
    initialBed: LocalTime,
    initialWake: LocalTime,
    onDismiss: () -> Unit,
    onSave: (LocalDate, LocalTime, LocalTime) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var dateInput by remember { mutableStateOf(initialDate.toString()) }
    var bedInput by remember { mutableStateOf(initialBed.label()) }
    var wakeInput by remember { mutableStateOf(initialWake.label()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
        if (date == null) {
            error = "日期格式应为 yyyy-MM-dd"
            return
        }
        val bed = runCatching { LocalTime.parse(bedInput.trim(), timeFormatter) }.getOrNull()
        val wake = runCatching { LocalTime.parse(wakeInput.trim(), timeFormatter) }.getOrNull()
        if (bed == null || wake == null) {
            error = "时间格式应为 HH:mm，例如 23:30"
            return
        }
        onSave(date, bed, wake)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录睡眠") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = dateInput,
                    onValueChange = { dateInput = it.take(10) },
                    label = { Text("日期（起床当天）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sleep_date"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = bedInput,
                        onValueChange = { bedInput = it.take(5) },
                        label = { Text("放下手机") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sleep_bed"),
                    )
                    OutlinedTextField(
                        value = wakeInput,
                        onValueChange = { wakeInput = it.take(5) },
                        label = { Text("早上起床") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sleep_wake"),
                    )
                }
                Text(
                    text = "只记两个时间：放下手机、早上起床；日期填起床那天。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
