package com.zhixu.studyos.feature.finance

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.PageTitle
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType

@Composable
fun FinanceHomeScreen(viewModel: FinanceViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FinanceTransaction?>(null) }
    var deleting by remember { mutableStateOf<FinanceTransaction?>(null) }
    val categoryNames = state.categories.associate { it.id to it.name }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("screen_finance"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PageTitle(
                    title = "财务",
                    subtitle = "记账，看看钱去了哪里。",
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = "上个月")
                    }
                    Text(
                        text = formatFinanceMonth(state.selectedMonth),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = viewModel::nextMonth) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "下个月")
                    }
                }
            }
            item {
                val summary = state.summary
                val income = summary?.incomeFen ?: 0L
                val netSpent = summary?.netSpentFen ?: 0L
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "本月结余",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = Money.formatFen(income - netSpent),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        FinanceMetric("收入", income)
                        FinanceMetric("支出", netSpent)
                    }
                }
            }
            item {
                PrimaryButton(
                    text = "记一笔",
                    onClick = {
                        editing = null
                        showEditor = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("finance_add"),
                )
            }
            item {
                Text(
                    text = "最近账目",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.transactions.isEmpty()) {
                item {
                    Text(
                        text = "这个月还没有账目。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        state.transactions.take(40).forEachIndexed { index, transaction ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            FinanceRow(
                                transaction = transaction,
                                categoryName = categoryNames[transaction.categoryId],
                                onClick = {
                                    editing = transaction
                                    showEditor = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        TransactionEditorDialog(
            initial = editing,
            categories = state.categories,
            defaultDate = state.today,
            onDismiss = { showEditor = false },
            onSave = { transaction, callback ->
                viewModel.saveTransaction(transaction, callback)
            },
            onDeleteRequest = { transaction ->
                deleting = transaction
                showEditor = false
            },
        )
    }
    deleting?.let { transaction ->
        TransactionDeleteConfirmDialog(
            transaction = transaction,
            categoryName = categoryNames[transaction.categoryId],
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.deleteTransaction(transaction.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun FinanceMetric(label: String, fen: Long) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.formatFen(fen),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun FinanceRow(
    transaction: FinanceTransaction,
    categoryName: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = financeTransactionTitle(transaction, categoryName),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${transaction.date} · ${financeTransactionSubtitle(transaction)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = financeAmountText(transaction),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (transaction.type == FinanceTransactionType.EXPENSE) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}
