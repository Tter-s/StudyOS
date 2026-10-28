package com.zhixu.studyos.feature.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.finance.BudgetRisk
import com.zhixu.studyos.core.finance.CategoryAnomaly
import com.zhixu.studyos.core.finance.FinanceForecast
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.finance.SavingsSuggestion
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate

@Composable
internal fun ForecastCard(forecast: FinanceForecast) {
    val riskLabel = when (forecast.risk) {
        BudgetRisk.NO_BUDGET -> "未设置预算"
        BudgetRisk.SAFE -> "预计在预算内"
        BudgetRisk.WATCH -> "需要留意"
        BudgetRisk.LIKELY_OVER -> "预计可能超出"
        BudgetRisk.OVER -> "已经超出预算"
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("生活费预测", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            ForecastStat(
                label = "预计月底余额",
                value = forecast.projectedRemainingFen?.let(Money::formatFen) ?: "未设置预算",
                modifier = Modifier.weight(1f),
            )
            ForecastStat(
                label = "预计总支出",
                value = Money.formatFen(forecast.projectedTotalFen),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            ForecastStat(
                label = "日均支出",
                value = forecast.averageDailySpendFen?.let(Money::formatFen) ?: "暂无",
                modifier = Modifier.weight(1f),
            )
            ForecastStat(
                label = "剩余日均可用",
                value = forecast.safeDailyAllowanceFen?.let(Money::formatFen) ?: "未设置",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            riskLabel,
            style = MaterialTheme.typography.labelLarge,
            color = when (forecast.risk) {
                BudgetRisk.OVER, BudgetRisk.LIKELY_OVER -> MaterialTheme.colorScheme.error
                BudgetRisk.WATCH -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            forecast.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (forecast.plannedFixedFen > 0L) {
            Text(
                "分类预算合计（计划固定/专项支出参考）：${Money.formatFen(forecast.plannedFixedFen)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForecastStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
internal fun DailySpendingCard(
    state: FinanceUiState,
    onWindowChange: (Int) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("每日净支出", style = MaterialTheme.typography.titleMedium)
                Text(
                    "截止今天的最近 ${state.trendWindowDays} 天；无记录日期显示为 0。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = state.trendWindowDays == 7,
                    onClick = { onWindowChange(7) },
                    label = { Text("7 天") },
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = state.trendWindowDays == 30,
                    onClick = { onWindowChange(30) },
                    label = { Text("30 天") },
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        val max = state.dailyTrend.maxOfOrNull { it.second.coerceAtLeast(0L) } ?: 0L
        if (max == 0L) {
            Text(
                "该区间没有支出记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.dailyTrend.forEach { (date, net) ->
                    val fraction = (net.coerceAtLeast(0L).toFloat() / max.toFloat()).coerceIn(0.02f, 1f)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction)
                                .background(
                                    if (net < 0L) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                ),
                        )
                        if (state.trendWindowDays <= 7) {
                            Text(
                                "${date.monthValue}/${date.dayOfMonth}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CustomRangeCard(
    customTrend: List<Pair<LocalDate, Long>>,
    onApply: (LocalDate, LocalDate) -> Unit,
) {
    var start by remember { mutableStateOf(LocalDate.now().minusDays(29).toString()) }
    var end by remember { mutableStateOf(LocalDate.now().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("自定义时间区间", style = MaterialTheme.typography.titleMedium)
        Text(
            "统计区间内每日净支出（支出 − 退款），最长 730 天。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = start,
                onValueChange = { start = it },
                label = { Text("开始 yyyy-MM-dd") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = end,
                onValueChange = { end = it },
                label = { Text("结束 yyyy-MM-dd") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = {
                val startDate = runCatching { LocalDate.parse(start) }.getOrNull()
                val endDate = runCatching { LocalDate.parse(end) }.getOrNull()
                if (startDate == null || endDate == null) {
                    error = "日期格式应为 yyyy-MM-dd"
                } else {
                    error = null
                    onApply(startDate, endDate)
                }
            },
        ) { Text("生成自定义趋势") }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (customTrend.isNotEmpty()) {
            Text(
                "${customTrend.first().first} 至 ${customTrend.last().first} · " +
                    "净支出合计 ${Money.formatFen(customTrend.sumOf { it.second })}",
                style = MaterialTheme.typography.bodySmall,
            )
            val max = customTrend.maxOfOrNull { it.second.coerceAtLeast(0L) } ?: 0L
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                customTrend.forEach { (_, net) ->
                    val fraction = if (max == 0L) 0.02f else {
                        (net.coerceAtLeast(0L).toFloat() / max.toFloat()).coerceIn(0.02f, 1f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction)
                            .background(
                                if (net < 0L) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AnomalyCard(
    anomalies: List<CategoryAnomaly>,
    onMarkExpected: (Long) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("大额消费提示", style = MaterialTheme.typography.titleMedium)
        Text(
            "只与该分类自身的历史习惯比较；金额高不等于浪费，可标记为正常特殊开支。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        anomalies.forEach { anomaly ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "${anomaly.date} · ${anomaly.categoryName} · ${Money.formatFen(anomaly.amountFen)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                anomaly.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onMarkExpected(anomaly.transactionId) }) {
                Text("标记为正常开支")
            }
        }
    }
}

@Composable
internal fun SavingsCard(suggestions: List<SavingsSuggestion>) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("预算建议", style = MaterialTheme.typography.titleMedium)
        suggestions.forEach { suggestion ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(suggestion.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                suggestion.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
