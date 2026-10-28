package com.zhixu.studyos.feature.finance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.model.FinanceCategory
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.reminder.BudgetNotifier
import kotlinx.coroutines.launch

@Composable
fun FinanceScreen(viewModel: FinanceViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val customTrend by viewModel.customTrend.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var sectionName by rememberSaveable { mutableStateOf(FinanceSection.RECORDS.name) }
    var notificationsAllowed by remember {
        mutableStateOf(BudgetNotifier.notificationsAllowed(context))
    }

    var showEditor by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<FinanceTransaction?>(null) }
    var deletingTransaction by remember { mutableStateOf<FinanceTransaction?>(null) }
    var showBudgetEditor by remember { mutableStateOf(false) }
    var showCategoryEditor by remember { mutableStateOf(false) }
    var renamingCategory by remember { mutableStateOf<FinanceCategory?>(null) }
    var settingCategoryBudget by remember { mutableStateOf<FinanceCategory?>(null) }
    var deletingCategory by remember { mutableStateOf<FinanceCategory?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = BudgetNotifier.notificationsAllowed(context)
        if (!granted) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("未授予通知权限：预算提醒会显示在应用内")
            }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        uri?.let(viewModel::exportCsv)
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(viewModel::exportBackup)
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
                notificationsAllowed = BudgetNotifier.notificationsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val section = runCatching { FinanceSection.valueOf(sectionName) }
        .getOrDefault(FinanceSection.RECORDS)
    val addEnabled = !state.isLoading && state.errorMessage == null && state.categories.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_finance"),
        ) {
            FinanceHeader(
                state = state,
                addEnabled = addEnabled,
                onAdd = {
                    editingTransaction = null
                    showEditor = true
                },
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onCurrentMonth = viewModel::goToCurrentMonth,
            )
            when {
                state.isLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在读取本地财务数据")
                    }
                }
                state.errorMessage != null -> {
                    Column(modifier = Modifier.padding(20.dp)) {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            EmptyState(
                                title = "财务数据暂时无法加载",
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                    ) {
                        FinanceSection.entries.forEach { value ->
                            FilterChip(
                                selected = section == value,
                                onClick = { sectionName = value.name },
                                label = { Text(value.label) },
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .testTag("finance_tab_${value.name.lowercase()}"),
                            )
                        }
                    }
                    when (section) {
                        FinanceSection.RECORDS -> FinanceRecordsTab(
                            state = state,
                            notificationsAllowed = notificationsAllowed,
                            onAdd = {
                                editingTransaction = null
                                showEditor = true
                            },
                            onEdit = { transaction ->
                                editingTransaction = transaction
                                showEditor = true
                            },
                            onOpenBudget = { showBudgetEditor = true },
                            onSearch = viewModel::setSearchQuery,
                            onFilter = viewModel::setFilter,
                            onClearFilters = {
                                viewModel.setSearchQuery("")
                                viewModel.setFilter(FinanceTransactionFilter.ALL)
                            },
                            onToggleReminder = { enabled ->
                                if (enabled) {
                                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
                                viewModel.setBudgetReminderEnabled(enabled)
                            },
                            onExportCsv = {
                                csvLauncher.launch("StudyOS-finance-${state.today}.csv")
                            },
                            onExportBackup = {
                                backupLauncher.launch("StudyOS-finance-backup-${state.today}.json")
                            },
                        )
                        FinanceSection.ANALYTICS -> FinanceAnalyticsTab(
                            state = state,
                            onMarkExpected = viewModel::markExpectedExpense,
                            onTrendWindowChange = viewModel::setTrendWindowDays,
                            customTrend = customTrend,
                            onCustomRange = viewModel::setCustomRange,
                        )
                        FinanceSection.CATEGORIES -> FinanceCategoriesTab(
                            state = state,
                            onAddCategory = { showCategoryEditor = true },
                            onRenameCategory = { renamingCategory = it },
                            onDeleteCategory = { deletingCategory = it },
                            onSetCategoryBudget = { settingCategoryBudget = it },
                            onOpenBudget = { showBudgetEditor = true },
                        )
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

    if (showEditor) {
        TransactionEditorDialog(
            initial = editingTransaction,
            categories = state.categories,
            defaultDate = state.today,
            onDismiss = {
                showEditor = false
                editingTransaction = null
            },
            onSave = { transaction, onResult -> viewModel.saveTransaction(transaction, onResult) },
            onDeleteRequest = if (editingTransaction != null) {
                { transaction ->
                    showEditor = false
                    editingTransaction = null
                    deletingTransaction = transaction
                }
            } else {
                null
            },
        )
    }
    deletingTransaction?.let { transaction ->
        TransactionDeleteConfirmDialog(
            transaction = transaction,
            categoryName = state.categories.firstOrNull { it.id == transaction.categoryId }?.name,
            onDismiss = { deletingTransaction = null },
            onConfirm = { viewModel.deleteTransaction(transaction.id) },
        )
    }
    if (showBudgetEditor) {
        MonthlyBudgetDialog(
            month = state.selectedMonth,
            currentBudgetFen = state.summary?.totalBudgetFen,
            previousBudgetFen = state.previousMonthBudgetFen,
            onDismiss = { showBudgetEditor = false },
            onSave = { total, onResult ->
                viewModel.saveMonthlyBudget(state.selectedMonth, total, onResult)
            },
        )
    }
    if (showCategoryEditor) {
        CategoryEditorDialog(
            initialName = null,
            onDismiss = { showCategoryEditor = false },
            onSave = { name, onResult -> viewModel.addCategory(name, onResult) },
        )
    }
    renamingCategory?.let { category ->
        CategoryEditorDialog(
            initialName = category.name,
            onDismiss = { renamingCategory = null },
            onSave = { name, onResult -> viewModel.renameCategory(category.id, name, onResult) },
        )
    }
    settingCategoryBudget?.let { category ->
        CategoryBudgetDialog(
            category = category,
            month = state.selectedMonth,
            spending = state.categoryBreakdown.firstOrNull { it.category.id == category.id },
            onDismiss = { settingCategoryBudget = null },
            onSave = { limit, onResult ->
                viewModel.saveCategoryBudget(state.selectedMonth, category.id, limit, onResult)
            },
        )
    }
    deletingCategory?.let { category ->
        CategoryDeleteConfirmDialog(
            category = category,
            onDismiss = { deletingCategory = null },
            onConfirm = { viewModel.deleteCategory(category) },
        )
    }
}

@Composable
private fun FinanceHeader(    state: FinanceUiState,
    addEnabled: Boolean,
    onAdd: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("财务", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "完全离线 · 生活费记账",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrimaryButton(
                text = "记一笔",
                onClick = onAdd,
                enabled = addEnabled,
                modifier = Modifier.testTag("finance_add"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.testTag("finance_month_prev"),
            ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "上个月")
            }
            Text(
                text = formatFinanceMonth(state.selectedMonth),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onNextMonth,
                modifier = Modifier.testTag("finance_month_next"),
            ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "下个月")
            }
            TextButton(
                onClick = onCurrentMonth,
                enabled = !state.isCurrentMonth,
                modifier = Modifier.testTag("finance_month_current"),
            ) {
                Text("本月")
            }
        }
    }
}
