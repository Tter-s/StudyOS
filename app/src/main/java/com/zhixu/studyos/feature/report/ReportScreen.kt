package com.zhixu.studyos.feature.report

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.growth.GrowthAnalytics
import com.zhixu.studyos.core.growth.GrowthRangeType
import com.zhixu.studyos.core.report.AiReportSections
import com.zhixu.studyos.core.report.WeeklyReport
import java.time.LocalDate

@Composable
fun ReportScreen(viewModel: ReportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    val snackbar = remember { SnackbarHostState() }
    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) viewModel.exportWeeklyPdf(uri)
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("screen_report"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text("学习报告", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "离线统计始终可用；AI 分析只在你主动请求时发送聚合数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.tab == ReportTab.WEEKLY,
                            onClick = { viewModel.setTab(ReportTab.WEEKLY) },
                            label = { Text("学习周报") },
                        )
                        FilterChip(
                            selected = state.tab == ReportTab.GROWTH,
                            onClick = { viewModel.setTab(ReportTab.GROWTH) },
                            label = { Text("成长分析") },
                        )
                    }
                }
            }
            if (state.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            state.errorMessage?.let { message ->
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            title = "报告暂时无法生成",
                            message = message,
                            icon = Icons.Rounded.AutoAwesome,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryButton(
                            text = "重试",
                            onClick = viewModel::refresh,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            when (state.tab) {
                ReportTab.WEEKLY -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.shiftWeek(-1) }) {
                                Icon(Icons.Rounded.ChevronLeft, contentDescription = "上一周")
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "${state.weekStart} 至 ${state.weekStart.plusDays(6)}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                TextButton(onClick = viewModel::goToThisWeek) { Text("回到本周") }
                            }
                            IconButton(onClick = { viewModel.shiftWeek(1) }) {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = "下一周")
                            }
                        }
                    }
                    val report = state.weekly
                    if (report != null) {
                        item { WeeklyOverviewCard(report) }
                        item { WeeklySubjectsCard(report) }
                        item { WeeklyDailyCard(report) }
                        item { WeeklyExecutionCard(report) }
                        item { WeeklyExerciseCard(report) }
                        item { WeeklyVocabularySleepCard(report) }
                        item { WeeklyBacklogCard(report) }
                        item { WeeklySuggestionsCard(report) }
                        item {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("AI 周报分析", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "发送内容：学习时长、任务完成、习题、词汇与睡眠“聚合值”；" +
                                                "不发送财务流水、睡眠原始时刻或任务标题。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                PrimaryButton(
                                    text = if (state.isAiLoading) "请求中…" else "请求 AI 分析",
                                    onClick = { viewModel.requestWeeklyAi() },
                                    enabled = !state.isAiLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                state.weeklyAiMeta?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                state.weeklyAi?.let { AiSectionsView(it) }
                            }
                        }
                        item { WeeklyDefinitionsCard(report) }
                        item {
                            SecondaryButton(
                                text = if (state.isExporting) "导出中…" else "导出周报 PDF",
                                onClick = {
                                    pdfLauncher.launch("StudyOS-weekly-${report.weekStart}.pdf")
                                },
                                enabled = !state.isExporting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (state.saved.isNotEmpty()) {
                        item { SectionHeader(title = "已保存周报") }
                        item {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                state.saved.forEach { saved ->
                                    Text(
                                        "${saved.weekStart} · 学习 ${saved.studyMinutes} 分钟 · " +
                                            "完成 ${saved.completedTasks}/${saved.scheduledTasks}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    saved.aiSummary?.let {
                                        Text(
                                            it.take(160),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
                ReportTab.GROWTH -> {
                    item { GrowthRangeSelector(state, viewModel) }
                    val growth = state.growth
                    if (growth != null) {
                        item { GrowthMetricsCard(growth) }
                        item { GrowthSubjectCard(growth) }
                        item { GrowthMonthlyCard(growth) }
                        item { GrowthExerciseCard(growth) }
                        item { GrowthWellbeingCard(growth) }
                        item { GrowthSemesterCard(growth) }
                        item { GrowthGoalsCard(growth) }
                        item {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text("AI 成长报告", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "AI 只收到所选区间的聚合指标，不包含财务原始流水、睡眠原始记录或题目原文。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    PrimaryButton(
                                        text = if (state.isAiLoading) "请求中…" else "请求成长总结",
                                        onClick = { viewModel.requestGrowthAi() },
                                        enabled = !state.isAiLoading,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    state.growthAiMeta?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    state.growthAi?.let { AiSectionsView(it) }
                                }
                            }
                        }
                        item { GrowthNotesCard(growth) }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun WeeklyOverviewCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("本周学习概览", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "${report.overview.studyMinutes} 分钟 · 活跃 ${report.overview.activeDays} 天",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "任务 ${report.overview.completedTasks}/${report.overview.scheduledTasks} 完成" +
                report.overview.completionRatePercent?.let { "（$it%）" }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "习题 ${report.overview.exerciseAttempts} 次 · 独立率 " +
                (report.overview.independentRatePercent?.let { "$it%" } ?: "数据不足"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "词汇复习 ${report.overview.vocabularyReviews} 次 · 平均睡眠 " +
                (report.overview.averageSleepMinutes?.let { "${it / 60} 小时 ${it % 60} 分" } ?: "数据不足"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WeeklySubjectsCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("各科目时间分配", style = MaterialTheme.typography.titleMedium)
        if (report.subjects.isEmpty()) {
            Text("本期没有科目记录。", style = MaterialTheme.typography.bodySmall)
            return@AppCard
        }
        val max = report.subjects.maxOf { it.studyMinutes }.coerceAtLeast(1)
        report.subjects.forEach { subject ->
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subject.subject, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(subject.studyMinutes.toFloat() / max)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
                Text(
                    "${subject.studyMinutes} 分",
                    modifier = Modifier.width(72.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "计划 ${subject.scheduledTasks} · 完成 ${subject.completedTasks}" +
                    (subject.independentRatePercent?.let { " · 独立 $it%" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 88.dp),
            )
        }
    }
}

@Composable
private fun WeeklyDailyCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("学习效率趋势（每日计时）", style = MaterialTheme.typography.titleMedium)
        val max = report.daily.maxOfOrNull { it.studyMinutes }?.coerceAtLeast(1) ?: 1
        report.daily.forEach { day ->
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${day.date.monthValue}/${day.date.dayOfMonth}", modifier = Modifier.width(40.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(day.studyMinutes.toFloat() / max)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
                Text("${day.studyMinutes} 分", modifier = Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WeeklyExecutionCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("计划执行情况", style = MaterialTheme.typography.titleMedium)
        Text(
            "计划 ${report.planExecution.plannedMinutes} 分钟 · 实际 ${report.planExecution.actualMinutes} 分钟 · " +
                "偏差 ${report.planExecution.deviationMinutes} 分钟" +
                (report.planExecution.deviationPercent?.let { "（$it%）" }.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            report.planExecution.interpretation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeeklyExerciseCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("数学习题表现", style = MaterialTheme.typography.titleMedium)
        Text(
            "尝试 ${report.exercise.attempts} 次 · 首次尝试 ${report.exercise.firstAttempts} 次",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "独立完成 ${report.exercise.independentAttempts} 次 · 首次独立率 " +
                (report.exercise.firstAttemptIndependentRatePercent?.let { "$it%" } ?: "数据不足"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "使用提示 ${report.exercise.hintUseCount} 次" +
                (report.exercise.averageHintLevel?.let { " · 平均最高 Level $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "高等级提示题成功率 " +
                (report.exercise.difficultSuccessRatePercent?.let { "$it%（${report.exercise.difficultAttempts} 次）" }
                    ?: "数据不足"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WeeklyVocabularySleepCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("英语词汇与睡眠", style = MaterialTheme.typography.titleMedium)
        Text(
            "词汇复习 ${report.vocabulary.reviews} 次 · 回忆率 " +
                (report.vocabulary.recallRatePercent?.let { "$it%" } ?: "数据不足") +
                " · 新增 ${report.vocabulary.newWords} 个",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "睡眠记录 ${report.sleep.recordedDays} 天 · 平均 " +
                (report.sleep.averageDurationMinutes?.let { "${it / 60} 小时 ${it % 60} 分" } ?: "数据不足") +
                (report.sleep.regularityMinutes?.let { " · 波动约 ±$it 分钟" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "中断 ${report.selfControl.interruptions} 次 · 主动中断 ${report.selfControl.voluntaryInterruptions} 次",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WeeklyBacklogCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("任务积压情况", style = MaterialTheme.typography.titleMedium)
        Text("本期延期 ${report.backlog.postponedTasks} 项", style = MaterialTheme.typography.bodyMedium)
        Text("长期积压（延期 ≥3 次）${report.backlog.longBacklogTasks} 项", style = MaterialTheme.typography.bodyMedium)
        Text("临近/超过截止的未完成任务 ${report.backlog.overdueTasks} 项", style = MaterialTheme.typography.bodyMedium)
        Text(
            "积压不会自动缩短睡眠或无限加码；可以选择完成、改期或删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeeklySuggestionsCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("下周改进建议（离线规则）", style = MaterialTheme.typography.titleMedium)
        report.suggestions.forEach {
            Spacer(modifier = Modifier.height(6.dp))
            Text("· $it", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WeeklyDefinitionsCard(report: WeeklyReport) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("指标定义", style = MaterialTheme.typography.titleMedium)
        report.metrics.forEach { metric ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "${metric.label}：${metric.value ?: "数据不足"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                metric.definition,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.dataNotes.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            report.dataNotes.forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GrowthRangeSelector(state: ReportUiState, viewModel: ReportViewModel) {
    var start by remember(state.customStart) { mutableStateOf(state.customStart.toString()) }
    var end by remember(state.customEnd) { mutableStateOf(state.customEnd.toString()) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("分析区间", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                GrowthRangeType.DAY,
                GrowthRangeType.WEEK,
                GrowthRangeType.MONTH,
                GrowthRangeType.SEMESTER,
                GrowthRangeType.ACADEMIC_YEAR,
                GrowthRangeType.CUSTOM,
            ).forEach { type ->
                FilterChip(
                    selected = state.rangeType == type,
                    onClick = { viewModel.setRangeType(type) },
                    label = { Text(type.label) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        if (state.rangeType == GrowthRangeType.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("开始") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("结束") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            SecondaryButton(
                text = "应用自定义区间",
                onClick = {
                    val startDate = runCatching { LocalDate.parse(start) }.getOrNull()
                    val endDate = runCatching { LocalDate.parse(end) }.getOrNull()
                    if (startDate != null && endDate != null) {
                        viewModel.setCustomRange(startDate, endDate)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.growth?.let {
            Text(
                it.range.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GrowthMetricsCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("关键指标（${growth.range.label}）", style = MaterialTheme.typography.titleMedium)
        growth.metrics.forEach { metric ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metric.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    metric.value ?: "数据不足",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (metric.dataSufficient) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (metric.detail.isNotBlank()) {
                Text(
                    metric.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                metric.definition,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GrowthSubjectCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("各科目投入比例", style = MaterialTheme.typography.titleMedium)
        if (growth.subjectShares.isEmpty()) {
            Text("没有计时记录。", style = MaterialTheme.typography.bodySmall)
            return@AppCard
        }
        val max = growth.subjectShares.maxOf { it.minutes }.coerceAtLeast(1)
        growth.subjectShares.forEach { share ->
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(share.subject, modifier = Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
                Box(
                    modifier = Modifier.weight(1f).height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(share.minutes.toFloat() / max)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
                Text(
                    "${share.sharePercent}% · ${share.minutes} 分",
                    modifier = Modifier.width(100.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun GrowthMonthlyCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("近 12 个月学习时长", style = MaterialTheme.typography.titleMedium)
        val max = growth.monthlyTrend.maxOfOrNull { it.studyMinutes }?.coerceAtLeast(1) ?: 1
        growth.monthlyTrend.forEach { bucket ->
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bucket.label, modifier = Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier.weight(1f).height(7.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bucket.studyMinutes.toFloat() / max)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
                Text(
                    "${bucket.studyMinutes} 分",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun GrowthExerciseCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("习题与词汇趋势", style = MaterialTheme.typography.titleMedium)
        if (growth.exerciseTrend.isEmpty()) {
            Text("没有习题趋势数据。", style = MaterialTheme.typography.bodySmall)
        }
        growth.exerciseTrend.forEach { point ->
            Text(
                "${point.label}：尝试 ${point.attempts} · 独立率 " +
                    (point.independentRatePercent?.let { "$it%" } ?: "—") +
                    " · 平均最高提示 ${point.averageHintLevel ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        growth.vocabularyTrend.forEach { point ->
            Text(
                "${point.label}：词汇复习 ${point.reviews} · 回忆 ${point.recalled} · 新增 ${point.newWords}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GrowthWellbeingCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("睡眠与生活费趋势", style = MaterialTheme.typography.titleMedium)
        if (growth.sleepTrend.isEmpty()) {
            Text("没有睡眠记录。", style = MaterialTheme.typography.bodySmall)
        }
        growth.sleepTrend.forEach { point ->
            Text(
                "${point.label}：平均 ${point.averageMinutes?.let { "${it / 60} 小时 ${it % 60} 分" } ?: "数据不足"}" +
                    " · 记录 ${point.recordedDays} 天" +
                    (point.regularityMinutes?.let { " · 波动 ±$it 分" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        growth.financeTrend.forEach { point ->
            Text(
                "${point.label}：净支出 ${Money.formatFen(point.netSpentFen)}" +
                    (point.averageDailyFen?.let { " · 日均 ${Money.formatFen(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "睡眠与学习的关联不解释为因果关系；数据不足时不生成结论。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GrowthSemesterCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("跨学期对比", style = MaterialTheme.typography.titleMedium)
        if (growth.semesterComparison.isEmpty()) {
            Text("记录还不足以形成学期对比。", style = MaterialTheme.typography.bodySmall)
            return@AppCard
        }
        Text(
            "按 9–1 月秋季、2–7 月春季划分，8 月不计入学期；这是应用内观察口径，不是学校教务统计。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        growth.semesterComparison.forEach { semester ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(semester.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "学习 ${semester.bucket.studyMinutes} 分钟 · 任务完成率 " +
                    (semester.bucket.completionRatePercent?.let { "$it%" } ?: "数据不足") +
                    " · 独立率 " +
                    (semester.bucket.independentRatePercent?.let { "$it%" } ?: "数据不足"),
                style = MaterialTheme.typography.bodySmall,
            )
            semester.notes.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GrowthGoalsCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("长期目标进度", style = MaterialTheme.typography.titleMedium)
        Text(
            "活跃目标 ${growth.goals.activeGoals} · 已完成 ${growth.goals.completedGoals} · " +
                "区间内完成里程碑 ${growth.goals.milestonesCompletedInRange}/${growth.goals.totalMilestones}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "总体里程碑进度 " + (growth.goals.progressPercent?.let { "$it%" } ?: "数据不足"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "目标进度只来自里程碑完成情况，不预测成绩或排名。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GrowthNotesCard(growth: GrowthAnalytics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("数据说明", style = MaterialTheme.typography.titleMedium)
        growth.notes.forEach {
            Spacer(modifier = Modifier.height(4.dp))
            Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiSectionsView(sections: AiReportSections) {
    Spacer(modifier = Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))
    if (sections.facts.isNotEmpty()) {
        Text("客观数据复述", style = MaterialTheme.typography.labelLarge)
        sections.facts.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (sections.speculation.isNotEmpty()) {
        Text("推测（不是已证明事实）", style = MaterialTheme.typography.labelLarge)
        sections.speculation.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (sections.suggestions.isNotEmpty()) {
        Text("建议", style = MaterialTheme.typography.labelLarge)
        sections.suggestions.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
    }
    Text(
        sections.disclaimer,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
