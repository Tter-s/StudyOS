package com.zhixu.studyos.feature.finance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.MonthFinanceSummary
import java.time.LocalDate

@Composable
fun FinanceRecordsTab(
    state: FinanceUiState,
    notificationsAllowed: Boolean,
    onAdd: () -> Unit,
    onEdit: (FinanceTransaction) -> Unit,
    onOpenBudget: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (FinanceTransactionFilter) -> Unit,
    onClearFilters: () -> Unit,
    onToggleReminder: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onExportBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandedDays = remember { mutableStateMapOf<LocalDate, Boolean>() }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("finance_records"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.summary?.let { summary ->
            item(key = "budget") {
                BudgetSummaryCard(
                    summary = summary,
                    isCurrentMonth = state.isCurrentMonth,
                    reminderEnabled = state.reminderEnabled,
                    onOpenBudget = onOpenBudget,
                )
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                label = { Text("搜索记录") },
                placeholder = { Text("备注、分类、金额或日期") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearch("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("finance_search"),
            )
        }
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FinanceTransactionFilter.entries.forEach { value ->
                        FilterChip(
                            selected = state.filter == value,
                            onClick = { onFilter(value) },
                            label = { Text(value.label) },
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .testTag("finance_filter_${value.name.lowercase()}"),
                        )
                    }
                }
                if (state.searchAllMonths) {
                    Text(
                        "正在搜索全部月份的记录；月份选择只影响预算和统计。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.transactions.isEmpty()) {
            item(key = "empty") {
                FinanceRecordsEmptyState(
                    hasSearchOrFilter = state.searchQuery.isNotBlank() ||
                        state.filter != FinanceTransactionFilter.ALL,
                    onAdd = onAdd,
                    onClearFilters = onClearFilters,
                )
            }
        } else {
            val forceExpand = state.searchQuery.isNotBlank() ||
                state.filter != FinanceTransactionFilter.ALL
            state.transactions.groupBy { it.date }.forEach { (date, dayTransactions) ->
                val expanded = forceExpand || expandedDays[date] == true
                item(key = "day-$date") {
                    DayGroupHeader(
                        date = date,
                        transactions = dayTransactions,
                        includeYear = state.searchAllMonths,
                        expanded = expanded,
                        onToggle = { expandedDays[date] = !expanded },
                    )
                }
                if (expanded) {
                    items(items = dayTransactions, key = { it.id }) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            categoryName = state.categories
                                .firstOrNull { it.id == transaction.categoryId }?.name,
                            onClick = { onEdit(transaction) },
                        )
                    }
                }
            }
        }
        item(key = "data") {
            FinanceDataCard(
                reminderEnabled = state.reminderEnabled,
                notificationsAllowed = notificationsAllowed,
                onToggleReminder = onToggleReminder,
                onExportCsv = onExportCsv,
                onExportBackup = onExportBackup,
            )
        }
    }
}

@Composable
private fun BudgetSummaryCard(
    summary: MonthFinanceSummary,
    isCurrentMonth: Boolean,
    reminderEnabled: Boolean,
    onOpenBudget: () -> Unit,
) {
    val budget = summary.totalBudgetFen
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("finance_budget_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "剩余预算",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (budget == null) "未设置" else Money.formatFen(summary.remainingFen ?: 0L),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (summary.status == BudgetStatus.EXCEEDED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = budget?.let { "月度预算 ${Money.formatFen(it)}" } ?: "设置后显示每日可用额度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BudgetStatusBadge(
                status = summary.status,
                atLimit = summary.totalBudgetFen != null && summary.remainingFen == 0L,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (budget != null) {
            val progress = ((summary.usagePercent).coerceIn(0, 100)) / 100f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (summary.status == BudgetStatus.EXCEEDED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = budgetStatusText(summary),
                style = MaterialTheme.typography.bodyMedium,
                color = if (summary.status == BudgetStatus.EXCEEDED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else {
            Text(
                text = "设置月度预算后，这里会显示剩余金额、日均可用和是否接近上限。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryStat("支出", summary.expenseFen, Modifier.weight(1f))
            SummaryStat("退款", summary.refundFen, Modifier.weight(1f))
            SummaryStat("收入", summary.incomeFen, Modifier.weight(1f))
        }
        if (budget != null) {
            Spacer(modifier = Modifier.height(12.dp))
            if (summary.remainingDays != null && summary.dailyAllowanceFen != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "剩余 ${summary.remainingDays} 天 · 日均可用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = Money.formatFen(summary.dailyAllowanceFen),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    text = "日均可用只在本月计算；切换月份时按该月实际记录查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (reminderEnabled && summary.status != BudgetStatus.HEALTHY && summary.status != BudgetStatus.NOT_SET) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = buildString {
                        if (summary.status == BudgetStatus.EXCEEDED) {
                            append("本月净支出已超出预算 ")
                            append(Money.formatFen(-(summary.remainingFen ?: 0L)))
                            append("。可以调整预算，或查看本月明细。")
                        } else {
                            append("已使用 ${summary.usagePercent}%")
                            summary.remainingDays?.let { append("，本月还有 $it 天") }
                            append("。按自己的节奏安排即可。")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onOpenBudget,
                modifier = Modifier.align(Alignment.End),
            ) { Text("调整预算") }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            PrimaryButton(
                text = "设置月度预算",
                onClick = onOpenBudget,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("finance_set_budget"),
            )
        }
        if (!isCurrentMonth) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "正在查看历史或未来月份；剩余天数只对本月计算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun budgetStatusText(summary: MonthFinanceSummary): String {
    val remaining = summary.remainingFen ?: 0L
    return when {
        summary.status == BudgetStatus.EXCEEDED -> buildString {
            append("已用 ${summary.usagePercent}%，超出 ")
            append(Money.formatFen(-remaining.coerceAtMost(0L)))
        }
        remaining == 0L -> "已用 ${summary.usagePercent}%，本月预算已用完"
        summary.status == BudgetStatus.NEAR_LIMIT -> "已用 ${summary.usagePercent}%，接近本月预算上限"
        else -> "已用 ${summary.usagePercent}%，尚未接近预算上限"
    }
}

@Composable
private fun SummaryStat(label: String, fen: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.formatFen(fen),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
fun BudgetStatusBadge(status: BudgetStatus, atLimit: Boolean = false) {
    val background = when (status) {
        BudgetStatus.EXCEEDED -> MaterialTheme.colorScheme.errorContainer
        BudgetStatus.NEAR_LIMIT -> MaterialTheme.colorScheme.primaryContainer
        BudgetStatus.HEALTHY, BudgetStatus.NOT_SET -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (status) {
        BudgetStatus.EXCEEDED -> MaterialTheme.colorScheme.onErrorContainer
        BudgetStatus.NEAR_LIMIT -> MaterialTheme.colorScheme.onPrimaryContainer
        BudgetStatus.HEALTHY, BudgetStatus.NOT_SET -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val badgeText = if (status == BudgetStatus.NEAR_LIMIT && atLimit) "已达上限" else status.label
    Text(
        text = badgeText,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        modifier = Modifier
            .background(background, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun DayGroupHeader(
    date: LocalDate,
    transactions: List<FinanceTransaction>,
    includeYear: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val expenseFen = transactions
        .filter { it.type == FinanceTransactionType.EXPENSE }
        .sumOf { it.amountFen }
    val incomeFen = transactions
        .filter { it.type == FinanceTransactionType.INCOME }
        .sumOf { it.amountFen }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "financeDayArrow",
    )
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag("finance_day_${date}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateHeader(date, includeYear),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append("${transactions.size} 笔")
                        if (incomeFen > 0L) {
                            append(" · 收入 +")
                            append(Money.formatFen(incomeFen))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "支出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = Money.formatFen(expenseFen),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起当天记录" else "展开当天记录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .rotate(arrowRotation),
            )
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: FinanceTransaction,
    categoryName: String?,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("finance_transaction_${transaction.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = transaction.categoryId?.let(::financeCategoryColor)
                            ?: MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = financeTransactionTitle(transaction, categoryName),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = financeTransactionSubtitle(transaction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = financeAmountText(transaction),
                style = MaterialTheme.typography.titleMedium,
                color = if (transaction.type == FinanceTransactionType.EXPENSE) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun FinanceRecordsEmptyState(
    hasSearchOrFilter: Boolean,
    onAdd: () -> Unit,
    onClearFilters: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        EmptyState(
            title = if (hasSearchOrFilter) "没有匹配的记录" else "还没有记账记录",
            message = if (hasSearchOrFilter) {
                "换一个关键词或类型再试，也可以清除搜索范围。"
            } else {
                "记下第一笔收入、支出或退款，预算与统计会立即更新。"
            },
            icon = Icons.AutoMirrored.Rounded.ReceiptLong,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasSearchOrFilter) {
            SecondaryButton(
                text = "清除搜索与筛选",
                onClick = onClearFilters,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            PrimaryButton(
                text = "记一笔",
                onClick = onAdd,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun FinanceDataCard(
    reminderEnabled: Boolean,
    notificationsAllowed: Boolean,
    onToggleReminder: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onExportBackup: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("预算提醒", style = MaterialTheme.typography.titleMedium)
                Text(
                    "接近上限或超支时提醒；每天最多检查一次，同一级别每月最多一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = reminderEnabled,
                onCheckedChange = onToggleReminder,
                modifier = Modifier.testTag("finance_reminder_switch"),
            )
        }
        if (reminderEnabled && !notificationsAllowed) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "系统通知未开启，提醒只会在应用内显示。可在系统设置中允许通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text("数据导出", style = MaterialTheme.typography.titleMedium)
        Text(
            "CSV 包含全部记账记录；JSON 备份包含分类、月度预算、分类预算和记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "导出 CSV",
                onClick = onExportCsv,
                modifier = Modifier
                    .weight(1f)
                    .testTag("finance_export_csv"),
            )
            SecondaryButton(
                text = "导出备份",
                onClick = onExportBackup,
                modifier = Modifier
                    .weight(1f)
                    .testTag("finance_export_backup"),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "备份恢复尚未实现；本轮仅提供本地导出。备份为未加密 JSON，请妥善保管。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dateHeader(date: java.time.LocalDate, includeYear: Boolean): String {
    val datePart = if (includeYear) {
        "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    } else {
        "${date.monthValue}月${date.dayOfMonth}日"
    }
    return "$datePart ${financeWeekday(date)}"
}
