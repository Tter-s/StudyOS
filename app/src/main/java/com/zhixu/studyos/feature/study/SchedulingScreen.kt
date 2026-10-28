package com.zhixu.studyos.feature.study

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.model.TASK_COLOR_ARGB
import com.zhixu.studyos.core.reminder.PlanReminderNotifier
import com.zhixu.studyos.core.scheduling.ProposedSession
import com.zhixu.studyos.core.scheduling.SchedulingDay
import com.zhixu.studyos.core.scheduling.SchedulingFixedBlock
import com.zhixu.studyos.core.scheduling.SchedulingWarning
import com.zhixu.studyos.core.scheduling.SchedulingWarningType
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun SchedulingScreen(viewModel: SchedulingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsAllowed by remember {
        mutableStateOf(PlanReminderNotifier.notificationsAllowed(context))
    }
    var showSettings by remember { mutableStateOf(false) }
    var adjustingBlockId by remember { mutableStateOf<Long?>(null) }
    var showReminderTime by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = PlanReminderNotifier.notificationsAllowed(context)
        if (!granted) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("未授予通知权限：每日计划提醒不会弹出")
            }
        }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshToday()
                notificationsAllowed = PlanReminderNotifier.notificationsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestRegenerate = {
        if (state.preview == null) {
            viewModel.generate()
        } else {
            showRegenerateConfirm = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_scheduling"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("自动排程", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "离线、确定性、可解释的本地启发式排程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.isLoading -> {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("正在读取任务与课表")
                        }
                    }
                }
                state.errorMessage != null -> {
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            EmptyState(
                                title = "暂时无法排程",
                                message = state.errorMessage.orEmpty(),
                                icon = Icons.Rounded.ErrorOutline,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PrimaryButton(
                                text = "重试",
                                onClick = viewModel::retry,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                }
                else -> {
                    item {
                        SchedulingScopeCard(
                            state = state,
                            onHorizon = viewModel::setHorizon,
                            onSettings = { showSettings = true },
                            onGenerate = requestRegenerate,
                        )
                    }
                    item {
                        SchedulingReminderCard(
                            state = state,
                            notificationsAllowed = notificationsAllowed,
                            onToggle = { enabled ->
                                if (enabled) {
                                    val needsPermission =
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PackageManager.PERMISSION_GRANTED
                                    if (needsPermission) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    }
                                }
                                viewModel.setPlanReminderEnabled(enabled)
                            },
                            onEditTime = { showReminderTime = true },
                        )
                    }
                    val preview = state.preview
                    if (preview == null) {
                        item {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                Text("还没有生成计划", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "生成时会读取课程、临时事务、睡眠/用餐/固定事务、锁定与进行中的时段、" +
                                        "任务截止日期、优先级、预计时长、科目偏好和顺延未完成任务，然后按固定规则排出学习时段。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "计划只是预览：你可以手动调整、移除或锁定时段，确认保存后才会写入任务。" +
                                        "未安排的剩余时长会明确列出，不会自动作假。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "进行中任务不会被自动重排；暂停后才会重新成为候选。已完成任务不参与排程。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        item {
                            SchedulingPreviewSummaryCard(
                                state = state,
                                onSave = viewModel::savePlan,
                                onRegenerate = requestRegenerate,
                            )
                        }
                        val warnings = preview.warnings.filter {
                            it.type != SchedulingWarningType.PARTIAL &&
                                it.type != SchedulingWarningType.CONFLICT
                        }
                        if (warnings.isNotEmpty()) {
                            item { SchedulingWarningsCard(warnings) }
                        }
                        if (preview.conflicts.isNotEmpty()) {
                            item { SchedulingConflictsCard(preview.conflicts) }
                        }
                        item { SchedulingRankingCard(state) }
                        if (preview.unscheduled.isNotEmpty()) {
                            item { SchedulingUnscheduledCard(preview.unscheduled) }
                        }
                        items(
                            items = preview.result.days,
                            key = { it.date },
                        ) { day ->
                            SchedulingDayCard(
                                day = day,
                                proposed = preview.proposedBlocksByDate[day.date].orEmpty(),
                                today = state.today,
                                onToggleLock = viewModel::toggleBlockLock,
                                onAdjust = { adjustingBlockId = it.id },
                            )
                        }
                        item {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryButton(
                                        text = "保存计划",
                                        onClick = viewModel::savePlan,
                                        enabled = !state.previewStale &&
                                            !state.isSaving &&
                                            preview.blocks.isNotEmpty(),
                                        modifier = Modifier.weight(1f),
                                    )
                                    SecondaryButton(
                                        text = "重新生成",
                                        onClick = requestRegenerate,
                                        enabled = !state.isGenerating && !state.isSaving,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (state.isSaving) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("正在保存计划", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    if (showSettings) {
        SchedulingSettingsDialog(
            settings = state.settings,
            subjectPreferences = state.subjectPreferences,
            subjectOptions = state.subjectOptions,
            onDismiss = { showSettings = false },
            onSave = { settings, preferences, onResult ->
                viewModel.saveSettings(settings, preferences, onResult)
            },
        )
    }
    val adjustingBlock = adjustingBlockId?.let { id ->
        state.preview?.blocks?.firstOrNull { it.id == id }
    }
    if (adjustingBlock != null) {
        SchedulingAdjustDialog(
            block = adjustingBlock,
            onDismiss = { adjustingBlockId = null },
            onSave = { date, start, end, onResult ->
                viewModel.adjustBlock(adjustingBlock.id, date, start, end, onResult)
            },
            onRemove = { viewModel.removeBlock(adjustingBlock.id) },
        )
    }
    if (showReminderTime) {
        SchedulingReminderTimeDialog(
            initialHour = state.settings.planReminderHour,
            initialMinute = state.settings.planReminderMinute,
            onDismiss = { showReminderTime = false },
            onSave = { hour, minute, onResult ->
                viewModel.setPlanReminderTime(hour, minute, onResult)
            },
        )
    }
    if (showRegenerateConfirm) {
        SchedulingRegenerateConfirmDialog(
            onDismiss = { showRegenerateConfirm = false },
            onConfirm = { viewModel.generate() },
        )
    }
}

@Composable
private fun SchedulingScopeCard(
    state: SchedulingUiState,
    onHorizon: (Int) -> Unit,
    onSettings: () -> Unit,
    onGenerate: () -> Unit,
) {
    val settings = state.settings
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("排程范围", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.horizonDays == 1,
                onClick = { onHorizon(1) },
                label = { Text("今天") },
                modifier = Modifier.testTag("scheduling_horizon_today"),
            )
            FilterChip(
                selected = state.horizonDays == 7,
                onClick = { onHorizon(7) },
                label = { Text("今天起 7 天") },
                modifier = Modifier.testTag("scheduling_horizon_week"),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "每日上限 ${formatTaskDuration(settings.dailyLimitMinutes.toLong())} · " +
                "缓冲 ${settings.bufferMinutes} 分钟 · " +
                "窗口 ${formatScheduleMinute(settings.studyStartMinute)}–" +
                formatScheduleMinute(settings.studyEndMinuteExclusive),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
            onClick = onSettings,
            modifier = Modifier.testTag("scheduling_settings"),
        ) { Text("调整排程设置与科目偏好") }
        PrimaryButton(
            text = if (state.preview == null) "一键生成计划" else "重新生成计划",
            onClick = onGenerate,
            enabled = !state.isGenerating && !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scheduling_generate"),
        )
        if (state.isGenerating) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在生成排程预览", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "生成只是预览，不会自动写入；锁定时段始终保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SchedulingReminderCard(
    state: SchedulingUiState,
    notificationsAllowed: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit,
) {
    val settings = state.settings
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("每日计划提醒", style = MaterialTheme.typography.titleMedium)
                Text(
                    "在设定时间提醒当天已保存的学习计划；每天最多一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.planReminderEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("scheduling_reminder_switch"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "提醒时间 ${formatScheduleMinute(settings.planReminderHour * 60 + settings.planReminderMinute)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onEditTime,
                modifier = Modifier.testTag("scheduling_reminder_time"),
            ) { Text("修改时间") }
        }
        if (settings.planReminderEnabled && !notificationsAllowed) {
            Text(
                "系统通知未开启：提醒不会弹出。可在系统设置中允许通知后继续使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "使用系统推荐的后台调度；重启后由系统恢复任务，省电策略可能造成延迟，不是精确闹钟。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SchedulingPreviewSummaryCard(
    state: SchedulingUiState,
    onSave: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val preview = state.preview ?: return
    val result = preview.result
    AppCard(modifier = Modifier.fillMaxWidth().testTag("scheduling_preview_summary")) {
        Text("本次预览", style = MaterialTheme.typography.titleMedium)
        Text(
            "已安排 ${preview.blocks.size} 个时段 · ${preview.totalProposedMinutes} 分钟；" +
                "候选任务 ${result.taskPlans.size} 项。",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (preview.unscheduled.isNotEmpty()) {
            Text(
                "未完全安排 ${preview.unscheduled.size} 项，共 " +
                    "${preview.unscheduled.sumOf { it.remainingMinutes }} 分钟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "所有候选任务均已安排（含锁定覆盖）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "范围 ${result.horizonStart} 至 ${result.horizonEnd}；固定学习 " +
                "${result.totalFixedStudyMinutes} 分钟。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.taskPlans.isEmpty()) {
            Text(
                "当前没有需要安排的候选任务；下方只显示已有固定安排。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (preview.blocks.isEmpty()) {
            Text(
                "当前范围没有可安排的新学习时段；可在任务页手动安排，或调整范围与上限后重新生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.previewStale) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "任务、课表、设置或日期已变化，当前预览不能保存；请重新生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = "保存计划",
                onClick = onSave,
                enabled = !state.previewStale && !state.isSaving && preview.blocks.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("scheduling_save"),
            )
            SecondaryButton(
                text = "重新生成",
                onClick = onRegenerate,
                enabled = !state.isGenerating && !state.isSaving,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "保存后未锁定的自动时段可在下次生成时被替换；锁定时段保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SchedulingWarningsCard(warnings: List<SchedulingWarning>) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("提示", style = MaterialTheme.typography.titleSmall)
        warnings.forEach { warning ->
            Text(
                warning.message,
                style = MaterialTheme.typography.bodySmall,
                color = when (warning.type) {
                    SchedulingWarningType.CONFLICT,
                    SchedulingWarningType.FIXED_OVER_LIMIT,
                    SchedulingWarningType.LOCKED_AFTER_DUE,
                    -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SchedulingConflictsCard(conflicts: List<com.zhixu.studyos.core.scheduling.SchedulingConflict>) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("固定安排冲突", style = MaterialTheme.typography.titleSmall)
        conflicts.take(5).forEach { conflict ->
            Text(
                "${conflict.date} ${formatScheduleRange(conflict.startMinute, conflict.endMinuteExclusive)} " +
                    "「${fixedBlockKindLabel(conflict.firstKind)}·${conflict.firstTitle}」与" +
                    "「${fixedBlockKindLabel(conflict.secondKind)}·${conflict.secondTitle}」重叠",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (conflicts.size > 5) {
            Text(
                "还有 ${conflicts.size - 5} 处冲突未显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "这些冲突来自已保存的锁定/进行中时段；请到任务页或课表页调整。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SchedulingRankingCard(state: SchedulingUiState) {
    val preview = state.preview ?: return
    val plans = preview.result.taskPlans
    if (plans.isEmpty()) return
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("任务排序依据", style = MaterialTheme.typography.titleSmall)
        Text(
            "固定评分：截止日期 → 优先级 → 科目偏好 → 顺延未完成；分数只影响顺序。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plans.take(8).forEach { plan ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = plan.title,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${plan.score.total} 分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = if (plan.remainingMinutes == 0) {
                    "已由锁定时段覆盖；${plan.score.reasons.joinToString(" · ")}"
                } else {
                    buildString {
                        append("剩余 ${plan.remainingMinutes} 分钟")
                        if (plan.fixedMinutes > 0) {
                            append(" · 已有锁定 ${plan.fixedMinutes} 分钟")
                        }
                        append(" · ${plan.score.reasons.joinToString(" · ")}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plans.size > 8) {
            Text(
                "还有 ${plans.size - 8} 项任务未列出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SchedulingUnscheduledCard(
    unscheduled: List<com.zhixu.studyos.core.scheduling.UnscheduledTask>,
) {
    AppCard(modifier = Modifier.fillMaxWidth().testTag("scheduling_unscheduled")) {
        Text("未安排任务（${unscheduled.size}）", style = MaterialTheme.typography.titleSmall)
        unscheduled.take(20).forEach { task ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "剩余 ${task.remainingMinutes} 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    task.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (unscheduled.size > 20) {
            Text(
                "还有 ${unscheduled.size - 20} 项未安排任务未显示；缩小范围或调整上限后可查看更完整的结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "可在任务页手动安排未完成部分，或调整范围/上限后重新生成。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SchedulingDayCard(
    day: SchedulingDay,
    proposed: List<ProposedSession>,
    today: LocalDate,
    onToggleLock: (Long) -> Unit,
    onAdjust: (ProposedSession) -> Unit,
) {
    val proposedMinutes = proposed.sumOf(ProposedSession::durationMinutes)
    val entries = buildList {
        day.fixedBlocks.forEach { block ->
            add(
                SchedulingTimelineEntry(
                    startMinute = block.startMinute,
                    endMinuteExclusive = block.endMinuteExclusive,
                    fixed = block,
                    proposed = null,
                ),
            )
        }
        proposed.forEach { block ->
            add(
                SchedulingTimelineEntry(
                    startMinute = block.startMinute,
                    endMinuteExclusive = block.endMinuteExclusive,
                    fixed = null,
                    proposed = block,
                ),
            )
        }
    }.sortedWith(
        compareBy(
            { it.startMinute },
            { it.endMinuteExclusive },
            { if (it.fixed == null) 1 else 0 },
        ),
    )
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scheduling_day_${day.date}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(scheduleDateHeader(day.date, today), style = MaterialTheme.typography.titleMedium)
            Text(
                "已排 $proposedMinutes 分钟",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "固定学习 ${day.fixedStudyMinutes} 分钟 · 新排 $proposedMinutes / 上限 " +
                "${day.dailyLimitMinutes} 分钟 · 初始可用 ${day.availableMinutes} 分钟",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (entries.isEmpty()) {
            Text(
                "该日没有固定安排，也没有新排学习任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { entry ->
                val fixed = entry.fixed
                val proposedBlock = entry.proposed
                if (fixed != null) {
                    SchedulingFixedRow(fixed)
                } else if (proposedBlock != null) {
                    SchedulingProposedRow(
                        block = proposedBlock,
                        onToggleLock = { onToggleLock(proposedBlock.id) },
                        onAdjust = { onAdjust(proposedBlock) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedulingFixedRow(block: SchedulingFixedBlock) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatScheduleMinute(block.startMinute),
            modifier = Modifier.width(42.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(34.dp)
                .background(Color(block.colorArgb), RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                block.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOf(
                    fixedBlockKindLabel(block.kind),
                    block.subtitle.takeIf(String::isNotBlank),
                ).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatScheduleRange(block.startMinute, block.endMinuteExclusive),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SchedulingProposedRow(
    block: ProposedSession,
    onToggleLock: () -> Unit,
    onAdjust: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatScheduleMinute(block.startMinute),
            modifier = Modifier.width(42.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(44.dp)
                .background(Color(TASK_COLOR_ARGB), RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(block.taskTitle, style = MaterialTheme.typography.bodyMedium)
            Text(
                block.score.reasons.take(3).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${formatScheduleRange(block.startMinute, block.endMinuteExclusive)} · " +
                    if (block.isLocked) "已锁定" else "自动可调",
                style = MaterialTheme.typography.labelSmall,
                color = if (block.isLocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(
            onClick = onToggleLock,
            modifier = Modifier
                .size(36.dp)
                .testTag("scheduling_block_lock_${block.id}"),
        ) {
            Icon(
                imageVector = if (block.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                contentDescription = if (block.isLocked) "解锁时段" else "锁定时段",
                tint = if (block.isLocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(
            onClick = onAdjust,
            modifier = Modifier.testTag("scheduling_block_adjust_${block.id}"),
        ) { Text("调整") }
    }
}

private data class SchedulingTimelineEntry(
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val fixed: SchedulingFixedBlock?,
    val proposed: ProposedSession?,
)
