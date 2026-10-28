package com.zhixu.studyos.feature.finance

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.CategorySpending
import com.zhixu.studyos.core.model.FinanceCategory

@Composable
fun FinanceCategoriesTab(
    state: FinanceUiState,
    onAddCategory: () -> Unit,
    onRenameCategory: (FinanceCategory) -> Unit,
    onDeleteCategory: (FinanceCategory) -> Unit,
    onSetCategoryBudget: (FinanceCategory) -> Unit,
    onOpenBudget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val monthWord = if (state.isCurrentMonth) "本月" else "该月"
    val totalBudget = state.summary?.totalBudgetFen
    val categoryLimitTotal = state.categoryBreakdown
        .mapNotNull { it.limitFen }
        .sum()
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("finance_categories"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("分类与预算", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "预设分类覆盖日常生活开支，也可以添加自己的分类。" +
                        "分类预算按月份保存，只作提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "月度总预算",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            state.summary?.totalBudgetFen?.let(Money::formatFen) ?: "未设置",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "分类预算合计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Money.formatFen(categoryLimitTotal),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (totalBudget != null && categoryLimitTotal > totalBudget) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "分类预算合计高于月度总预算；分类预算只用于提醒，不会阻止记账。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenBudget) { Text("调整月度预算") }
                    PrimaryButton(
                        text = "新增分类",
                        onClick = onAddCategory,
                        modifier = Modifier.testTag("finance_add_category"),
                    )
                }
            }
        }
        items(
            items = state.categoryBreakdown,
            key = { it.category.id },
        ) { spending ->
            CategoryRow(
                spending = spending,
                monthWord = monthWord,
                onSetBudget = { onSetCategoryBudget(spending.category) },
                onRename = { onRenameCategory(spending.category) },
                onDelete = { onDeleteCategory(spending.category) },
            )
        }
        item(key = "rules") {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("分类规则", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "七个预设分类不能重命名或删除；自定义分类只有在没有任何记账记录时才能删除。" +
                        "删除记账记录不会删除分类。月度预算与分类预算相互独立。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    spending: CategorySpending,
    monthWord: String,
    onSetBudget: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(spending.category.id) { mutableStateOf(false) }
    val limit = spending.limitFen
    val overLimit = limit != null && spending.netSpentFen > limit
    val nearLimit = limit != null &&
        spending.netSpentFen > 0L &&
        (spending.limitUsagePercent ?: 0) >= 80
    val progress = if (limit != null && limit > 0L) {
        (spending.netSpentFen.coerceAtLeast(0L).toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("finance_category_${spending.category.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(financeCategoryColor(spending.category.id), CircleShape),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = spending.category.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!spending.category.isPreset) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "自定义",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(monthWord)
                        append("净支出 ")
                        append(Money.formatFen(spending.netSpentFen))
                        if (spending.refundFen > 0L) {
                            append(" · 退款 ")
                            append(Money.formatFen(spending.refundFen))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!spending.category.isPreset) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "分类操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("设置${monthWord}预算") },
                            onClick = {
                                menuExpanded = false
                                onSetBudget()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            enabled = spending.transactionCount == 0,
                            text = {
                                Text(
                                    if (spending.transactionCount == 0) {
                                        "删除分类"
                                    } else {
                                        "删除（已有 ${spending.transactionCount} 条记录）"
                                    },
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
        if (limit == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "未设置${monthWord}分类预算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSetBudget) {
                    Text(if (spending.category.isPreset) "设置分类预算" else "设置预算")
                }
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = if (overLimit) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("预算 ")
                        append(Money.formatFen(limit))
                        append(" · 已用 ")
                        append(spending.limitUsagePercent ?: 0)
                        append('%')
                        if (overLimit && limit != null) {
                            append(" · 超出 ")
                            append(Money.formatFen(spending.netSpentFen - limit))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        overLimit -> MaterialTheme.colorScheme.error
                        nearLimit -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSetBudget) { Text("调整") }
            }
        }
    }
}
