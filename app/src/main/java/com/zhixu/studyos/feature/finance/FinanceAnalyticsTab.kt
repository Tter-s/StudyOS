package com.zhixu.studyos.feature.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.CategorySpending
import com.zhixu.studyos.core.model.FinanceTrendBucket
import com.zhixu.studyos.core.model.MonthFinanceSummary
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart

@Composable
fun FinanceAnalyticsTab(
    state: FinanceUiState,
    modifier: Modifier = Modifier,
    onMarkExpected: (Long) -> Unit = {},
    onTrendWindowChange: (Int) -> Unit = {},
    customTrend: List<Pair<java.time.LocalDate, Long>> = emptyList(),
    onCustomRange: (java.time.LocalDate, java.time.LocalDate) -> Unit = { _, _ -> },
) {
    val summary = state.summary
    val hasSpending = summary != null && (summary.expenseFen > 0L || summary.refundFen > 0L)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("finance_analytics"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (summary != null) {
            item(key = "summary") {
                AnalyticsSummaryCard(state = state, summary = summary)
            }
            state.forecast?.let { forecast ->
                item(key = "forecast") { ForecastCard(forecast = forecast) }
            }
        }
        if (!hasSpending) {
            item(key = "empty") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "还没有消费数据",
                        message = if (state.isCurrentMonth) {
                            "本月还没有支出或退款记录；记录第一笔消费后会生成分类占比与趋势。"
                        } else {
                            "该月没有支出或退款记录，暂无可分析的消费数据。"
                        },
                        icon = Icons.Rounded.BarChart,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            item(key = "category") {
                CategoryShareCard(breakdown = state.categoryBreakdown)
            }
            item(key = "weekly") {
                SpendingTrendCard(
                    title = "每周消费趋势",
                    subtitle = "周一至周日；首尾周只统计本月内的日期。",
                    buckets = state.weeklyTrend,
                )
            }
            item(key = "monthly") {
                SpendingTrendCard(
                    title = "每月消费趋势",
                    subtitle = "截至所选月份的近 6 个月净支出。",
                    buckets = state.monthlyTrend,
                )
            }
            item(key = "daily") {
                DailySpendingCard(state = state, onWindowChange = onTrendWindowChange)
            }
            item(key = "custom") {
                CustomRangeCard(customTrend = customTrend, onApply = onCustomRange)
            }
            if (state.anomalies.isNotEmpty()) {
                item(key = "anomalies") {
                    AnomalyCard(anomalies = state.anomalies, onMarkExpected = onMarkExpected)
                }
            }
            if (state.savingsSuggestions.isNotEmpty()) {
                item(key = "suggestions") {
                    SavingsCard(suggestions = state.savingsSuggestions)
                }
            }
            item(key = "notes") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text("统计口径", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "净支出 = 支出 − 退款，收入不参与消费趋势。退款计入记录当月的分类和趋势，" +
                            "不会追溯修改原支出月份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyticsSummaryCard(
    state: FinanceUiState,
    summary: MonthFinanceSummary,
) {
    val monthWord = if (state.isCurrentMonth) "本月" else "该月"
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${formatFinanceMonth(state.selectedMonth)} · 消费分析",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "${monthWord}净支出",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.formatFen(summary.netSpentFen),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            AnalyticsStat("支出", summary.expenseFen, Modifier.weight(1f))
            AnalyticsStat("退款", summary.refundFen, Modifier.weight(1f))
            AnalyticsStat("收入", summary.incomeFen, Modifier.weight(1f))
        }
        if (summary.totalBudgetFen != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "月度预算 ${Money.formatFen(summary.totalBudgetFen)} · " +
                    "已用 ${summary.usagePercent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnalyticsStat(label: String, fen: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.formatFen(fen),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun CategoryShareCard(breakdown: List<CategorySpending>) {
    val positives = breakdown
        .filter { it.netSpentFen > 0L }
        .sortedWith(compareByDescending<CategorySpending> { it.netSpentFen }.thenBy { it.category.sortOrder })
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("分类消费占比", style = MaterialTheme.typography.titleMedium)
        Text(
            "按所选月份的净支出计算，退款会减少对应分类的支出。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (positives.isEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "本月没有可展示的分类支出；退款可能已抵消对应支出。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppCard
        }
        val total = positives.sumOf { it.netSpentFen }
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .size(168.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 22.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset(
                    x = (size.width - diameter) / 2f,
                    y = (size.height - diameter) / 2f,
                )
                val arcSize = Size(diameter, diameter)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                var start = -90f
                positives.forEach { spending ->
                    val sweep = spending.netSpentFen.toFloat() / total.toFloat() * 360f
                    drawArc(
                        color = financeCategoryColor(spending.category.id),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "分类净支出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Money.formatFen(total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        positives.forEach { spending ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(financeCategoryColor(spending.category.id), CircleShape),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = spending.category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${spending.sharePercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp),
                )
                Text(
                    text = Money.formatFen(spending.netSpentFen),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(90.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (breakdown.any { it.netSpentFen < 0L }) {
            Text(
                "部分分类的退款超过支出，净额为负，不计入圆环与占比。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "占比四舍五入到整数，合计可能不是 100%。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpendingTrendCard(
    title: String,
    subtitle: String,
    buckets: List<FinanceTrendBucket>,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        val max = buckets.maxOfOrNull { it.netSpentFen.coerceAtLeast(0L) } ?: 0L
        buckets.forEach { bucket ->
            val fraction = if (max > 0L) {
                (bucket.netSpentFen.coerceAtLeast(0L).toFloat() / max.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(84.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                        )
                    }
                }
                Text(
                    text = Money.formatFen(bucket.netSpentFen),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bucket.netSpentFen < 0L) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(88.dp),
                )
            }
        }
        if (buckets.isEmpty()) {
            Text(
                "暂无趋势数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
