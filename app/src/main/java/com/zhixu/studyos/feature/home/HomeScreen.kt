package com.zhixu.studyos.feature.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.MetricCard
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.data.repository.HomeCardId
import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.MonthFinanceSummary
import com.zhixu.studyos.core.model.StudyBlockKind
import com.zhixu.studyos.core.model.StudyDayBlock
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.task.TaskPlanner

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.notice) {
        uiState.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        HomeContent(
            state = uiState,
            onRetry = viewModel::retry,
            onToggleTask = viewModel::setTaskCompleted,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    onToggleTask: (Long, Boolean) -> Unit = { _, _ -> },
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_home"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = state.dateLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.greeting,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "把今天过得清晰一点。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.semesterLabel.isNotBlank()) {
                    Text(
                        text = state.semesterLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (state.isLoading) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("正在读取本地数据")
                }
            }
            return@LazyColumn
        }

        state.errorMessage?.let { message ->
            item {
                AppCard {
                    EmptyState(
                        title = "首页暂时无法加载",
                        message = message,
                        icon = Icons.Rounded.ErrorOutline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton(
                        text = "重试",
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
            return@LazyColumn
        }

        state.homeCards.filter { it.visible }.forEach { card ->
            item(key = "home_card_${card.id.name}") {
                when (card.id) {
                    HomeCardId.TASK_PROGRESS -> TaskProgressCard(state = state)
                    HomeCardId.FREE_TIME -> {
                        val plan = state.todayPlan
                        MetricCard(
                            title = "今日空闲时间",
                            value = if (state.isScheduleConfigured && plan != null) {
                                formatHomeDuration(plan.totalFreeMinutes)
                            } else {
                                "未设置"
                            },
                            supportingText = if (state.isScheduleConfigured && plan != null) {
                                "${plan.freeTimes.size} 个空闲段；已排除课程、任务与睡眠"
                            } else {
                                "设置课表、任务或作息后计算"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HomeCardId.NEXT_CLASS -> NextClassCard(nextClass = state.nextClass)
                    HomeCardId.CURRENT_TASK -> CurrentTaskCard(
                        task = state.currentTask,
                        plannedMinutes = state.plannedMinutes,
                    )
                    HomeCardId.SLEEP -> SleepProtectionCard(state = state)
                    HomeCardId.BUDGET -> HomeBudgetCard(
                        summary = state.budgetSummary,
                        loadFailed = state.budgetLoadFailed,
                        onRetry = onRetry,
                    )
                    HomeCardId.EXAM -> UpcomingExamCard(state = state)
                    HomeCardId.EXERCISE_REVIEW -> ExerciseReviewCard(state = state)
                    HomeCardId.VOCABULARY -> VocabularyDueCard(state = state)
                    HomeCardId.AI_SUGGESTION -> HomeSuggestionCard(state = state)
                }
            }
        }
        item {
            SectionHeader(title = "今日时间轴")
        }
        if (state.timelineItems.isEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "今天还没有安排",
                        message = "添加任务、课程或一次性日程后，这里会显示今天的时间轴。",
                        icon = Icons.Rounded.Timeline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            items(state.timelineItems) { item ->
                StudyTimelineRow(item = item)
            }
        }
        item {
            SectionHeader(title = "今日任务")
        }
        if (state.tasks.isEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "暂无今日任务",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "可在“学习”页安排今天或明天的任务；未安排任务不会显示为今日任务。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(state.tasks, key = StudyTask::id) { task ->
                TaskRow(
                    task = task,
                    date = state.todayPlan?.date ?: java.time.LocalDate.now(),
                    onToggle = { completed -> onToggleTask(task.id, completed) },
                )
            }
        }
    }
}

@Composable
private fun HomeBudgetCard(
    summary: MonthFinanceSummary?,
    loadFailed: Boolean,
    onRetry: () -> Unit,
) {
    val status = summary?.status ?: BudgetStatus.NOT_SET
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_budget"),
    ) {
        if (loadFailed) {
            Text(
                text = "本月剩余预算",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "预算数据暂时不可用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "打开“财务”页重试，或稍后再查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text("重试") }
            return@AppCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本月剩余预算",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = summary?.remainingFen?.let(Money::formatFen) ?: "未设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (status == BudgetStatus.EXCEEDED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (summary?.totalBudgetFen != null) {
                val exceeded = status == BudgetStatus.EXCEEDED
                val near = status == BudgetStatus.NEAR_LIMIT
                Text(
                    text = when {
                        exceeded -> "已超支"
                        near && summary.remainingFen == 0L -> "已达上限"
                        near -> "接近上限"
                        else -> "正常"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        exceeded -> MaterialTheme.colorScheme.onErrorContainer
                        near -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .background(
                            color = when {
                                exceeded -> MaterialTheme.colorScheme.errorContainer
                                near -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (summary?.totalBudgetFen != null) {
            LinearProgressIndicator(
                progress = {
                    ((summary.usagePercent).coerceIn(0, 100)) / 100f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (status == BudgetStatus.EXCEEDED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("已用 ${summary.usagePercent}%")
                    when (status) {
                        BudgetStatus.EXCEEDED -> {
                            append("，已超出 ")
                            append(Money.formatFen(-(summary.remainingFen ?: 0L)))
                        }
                        BudgetStatus.NEAR_LIMIT -> {
                            if (summary.remainingFen == 0L) {
                                append("，本月预算已用完")
                            } else {
                                append("，接近本月预算上限")
                            }
                        }
                        else -> Unit
                    }
                    if (summary.remainingDays != null && summary.dailyAllowanceFen != null) {
                        append(" · 剩余 ${summary.remainingDays} 天")
                        append(" · 日均可用 ")
                        append(Money.formatFen(summary.dailyAllowanceFen))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status == BudgetStatus.EXCEEDED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else {
            Text(
                text = if (summary != null && summary.expenseFen > 0L) {
                    "本月已支出 ${Money.formatFen(summary.expenseFen)}；在“财务”中设置预算后显示剩余金额。"
                } else {
                    "在“财务”中设置月度预算后显示剩余金额和接近上限提醒。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskProgressCard(state: HomeUiState) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_task_progress"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = "今日任务进度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.tasks.isEmpty()) {
                        "暂无任务"
                    } else {
                        "${state.completedTaskCount} / ${state.tasks.size}"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(
                text = "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun TaskRow(
    task: StudyTask,
    date: java.time.LocalDate,
    onToggle: (Boolean) -> Unit,
) {
    val segments = task.sessionsVisibleOn(date)
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onToggle(!task.isCompleted) }) {
                Icon(
                    imageVector = if (task.isCompleted) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = if (task.isCompleted) "取消完成" else "完成",
                    tint = if (task.isCompleted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                )
                Text(
                    text = buildString {
                        append(priorityHomeLabel(task.priority))
                        if (task.subject.isNotBlank()) append(" · ${task.subject}")
                        if (segments.isNotEmpty()) {
                            append(" · ")
                            append(
                                segments.joinToString(" / ") {
                                    "${formatTimelineMinute(it.startMinute)}–${formatTimelineMinute(it.endMinuteExclusive)}"
                                },
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${task.estimatedMinutes} 分钟",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StudyTimelineRow(item: StudyDayBlock) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Color(item.colorArgb), CircleShape),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        if (item.isContinuation) append("前日延续 · ")
                        if (item.isCompleted) append("已完成 · ")
                        append(item.title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${formatTimelineMinute(item.startMinute)}–${formatTimelineMinute(item.endMinuteExclusive)}",
                style = MaterialTheme.typography.labelLarge,
                color = if (item.kind == StudyBlockKind.REST) {
                    Color(item.colorArgb)
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

private fun priorityHomeLabel(priority: TaskPriority): String = when (priority) {
    TaskPriority.HIGH -> "高优先级"
    TaskPriority.MEDIUM -> "中优先级"
    TaskPriority.LOW -> "低优先级"
}

private fun formatTimelineMinute(minute: Int): String {
    if (minute == MINUTES_PER_DAY) return "24:00"
    return "%02d:%02d".format(minute / 60, minute % 60)
}

private fun formatHomeDuration(minutes: Long): String {
    if (minutes <= 0L) return "无"
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0L -> "$remainder 分钟"
        remainder == 0L -> "$hours 小时"
        else -> "$hours 小时 $remainder 分"
    }
}
