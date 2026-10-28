package com.zhixu.studyos.feature.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.CategorySpending
import com.zhixu.studyos.core.model.FinanceCategory
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun TransactionEditorDialog(
    initial: FinanceTransaction?,
    categories: List<FinanceCategory>,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (FinanceTransaction, (String?) -> Unit) -> Unit,
    onDeleteRequest: ((FinanceTransaction) -> Unit)?,
) {
    var type by remember { mutableStateOf(initial?.type ?: FinanceTransactionType.EXPENSE) }
    var amountInput by remember {
        mutableStateOf(initial?.let { Money.formatPlainYuan(it.amountFen) }.orEmpty())
    }
    var categoryId by remember {
        mutableStateOf(initial?.categoryId ?: categories.firstOrNull()?.id)
    }
    var dateInput by remember { mutableStateOf((initial?.date ?: defaultDate).toString()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "新增记录" else "编辑记录") },
        text = {
            FinanceDialogColumn {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FinanceTransactionType.entries.forEach { value ->
                        FilterChip(
                            selected = type == value,
                            onClick = {
                                type = value
                                if (value != FinanceTransactionType.INCOME && categoryId == null) {
                                    categoryId = categories.firstOrNull()?.id
                                }
                            },
                            label = { Text(financeTypeLabel(value)) },
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it.take(16) },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("最多两位小数；退款请选择“退款”并填写正数。")
                    },
                )
                if (type != FinanceTransactionType.INCOME) {
                    Text("分类", style = MaterialTheme.typography.labelLarge)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        categories.forEach { category ->
                            FilterChip(
                                selected = categoryId == category.id,
                                onClick = { categoryId = category.id },
                                label = { Text(category.name) },
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                    if (categories.isEmpty()) {
                        Text(
                            "暂无可选分类，请先在“分类”中新增。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                OutlinedTextField(
                    value = dateInput,
                    onValueChange = { dateInput = it.take(10) },
                    label = { Text("日期（yyyy-MM-dd）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { dateInput = defaultDate.toString() }) { Text("今天") }
                    TextButton(onClick = { dateInput = defaultDate.minusDays(1).toString() }) { Text("昨天") }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text("备注（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${note.length}/200") },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (initial != null && onDeleteRequest != null) {
                    TextButton(
                        enabled = !saving,
                        onClick = { onDeleteRequest(initial) },
                    ) {
                        Text("删除这条记录", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val amountFen = Money.parseYuanToFen(amountInput)
                    if (amountFen == null) {
                        error = "请输入大于 0、最多两位小数的金额；退款请选择“退款”并填写正数。"
                        return@TextButton
                    }
                    val date = runCatching { LocalDate.parse(dateInput.trim()) }.getOrNull()
                    if (date == null) {
                        error = "日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    if (date.year !in 1900..2999) {
                        error = "请选择合理的记账日期"
                        return@TextButton
                    }
                    if (type != FinanceTransactionType.INCOME && categoryId == null) {
                        error = "请选择分类"
                        return@TextButton
                    }
                    val transaction = FinanceTransaction(
                        id = initial?.id ?: 0L,
                        date = date,
                        type = type,
                        amountFen = amountFen,
                        categoryId = if (type == FinanceTransactionType.INCOME) null else categoryId,
                        note = note.trim(),
                        createdAt = initial?.createdAt,
                    )
                    saving = true
                    onSave(transaction) { message ->
                        saving = false
                        error = message
                        if (message == null) onDismiss()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
fun MonthlyBudgetDialog(
    month: YearMonth,
    currentBudgetFen: Long?,
    previousBudgetFen: Long?,
    onDismiss: () -> Unit,
    onSave: (Long?, (String?) -> Unit) -> Unit,
) {
    var amountInput by remember {
        mutableStateOf(currentBudgetFen?.let(Money::formatPlainYuan).orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("设置 ${formatFinanceMonth(month)}预算") },
        text = {
            FinanceDialogColumn {
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it.take(16) },
                    label = { Text("月度生活费预算（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (previousBudgetFen != null) {
                    TextButton(
                        onClick = { amountInput = Money.formatPlainYuan(previousBudgetFen) },
                    ) {
                        Text("沿用上月预算 ${Money.formatFen(previousBudgetFen)}")
                    }
                }
                Text(
                    "预算只约束生活费支出：收入不冲抵预算，退款会减少当月净支出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (currentBudgetFen != null) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            onSave(null) { message ->
                                saving = false
                                error = message
                                if (message == null) onDismiss()
                            }
                        },
                    ) {
                        Text("清除本月预算", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val amountFen = Money.parseYuanToFen(amountInput)
                    if (amountFen == null) {
                        error = "请输入大于 0、最多两位小数的预算金额"
                        return@TextButton
                    }
                    saving = true
                    onSave(amountFen) { message ->
                        saving = false
                        error = message
                        if (message == null) onDismiss()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
fun CategoryBudgetDialog(
    category: FinanceCategory,
    month: YearMonth,
    spending: CategorySpending?,
    onDismiss: () -> Unit,
    onSave: (Long?, (String?) -> Unit) -> Unit,
) {
    var amountInput by remember {
        mutableStateOf(spending?.limitFen?.let(Money::formatPlainYuan).orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("分类预算 · ${category.name}") },
        text = {
            FinanceDialogColumn {
                Text(
                    "${formatFinanceMonth(month)}净支出 ${Money.formatFen(spending?.netSpentFen ?: 0L)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it.take(16) },
                    label = { Text("本月分类预算（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("留空保存表示本月不设该分类预算。") },
                )
                Text(
                    "分类预算只作提醒，不会阻止记账，也不需要与月度总预算相加一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (spending?.limitFen != null) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            onSave(null) { message ->
                                saving = false
                                error = message
                                if (message == null) onDismiss()
                            }
                        },
                    ) {
                        Text("清除分类预算", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val trimmed = amountInput.trim()
                    val limit = if (trimmed.isEmpty()) {
                        null
                    } else {
                        Money.parseYuanToFen(trimmed)
                    }
                    if (trimmed.isNotEmpty() && limit == null) {
                        error = "请输入大于 0、最多两位小数的预算金额"
                        return@TextButton
                    }
                    saving = true
                    onSave(limit) { message ->
                        saving = false
                        error = message
                        if (message == null) onDismiss()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
fun CategoryEditorDialog(
    initialName: String?,
    onDismiss: () -> Unit,
    onSave: (String, (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initialName == null) "新增分类" else "重命名分类") },
        text = {
            FinanceDialogColumn {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${name.length}/12；分类名称不能重复。") },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (name.isBlank()) {
                        error = "请输入分类名称"
                        return@TextButton
                    }
                    saving = true
                    onSave(name) { message ->
                        saving = false
                        error = message
                        if (message == null) onDismiss()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
fun TransactionDeleteConfirmDialog(
    transaction: FinanceTransaction,
    categoryName: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条记录？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${transaction.date} · ${financeTransactionTitle(transaction, categoryName)} · " +
                        Money.formatFen(transaction.amountFen),
                )
                Text("删除后不会进入回收站，也无法恢复。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun CategoryDeleteConfirmDialog(
    category: FinanceCategory,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除分类？") },
        text = {
            Text("将删除自定义分类「${category.name}」。该分类没有记账记录，此操作不可撤销。")
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FinanceDialogColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}
