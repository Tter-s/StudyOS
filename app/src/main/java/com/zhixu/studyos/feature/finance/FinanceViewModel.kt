package com.zhixu.studyos.feature.finance

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.export.FinanceDocumentStore
import com.zhixu.studyos.core.data.local.STUDYOS_DATABASE_VERSION
import com.zhixu.studyos.core.data.preferences.UserPreferencesRepository
import com.zhixu.studyos.core.data.repository.FinanceRepository
import com.zhixu.studyos.core.finance.FinanceBackup
import com.zhixu.studyos.core.finance.FinanceCalculator
import com.zhixu.studyos.core.finance.FinanceCsv
import com.zhixu.studyos.core.finance.FinanceForecast
import com.zhixu.studyos.core.finance.FinanceForecastEngine
import com.zhixu.studyos.core.finance.CategoryAnomaly
import com.zhixu.studyos.core.finance.SavingsSuggestion
import com.zhixu.studyos.core.finance.FinanceSearch
import com.zhixu.studyos.core.model.CategorySpending
import com.zhixu.studyos.core.model.FinanceCategory
import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTrendBucket
import com.zhixu.studyos.core.model.MonthFinanceSummary
import com.zhixu.studyos.core.reminder.BudgetReminderScheduler
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FinanceUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val selectedMonth: YearMonth = YearMonth.now(),
    val summary: MonthFinanceSummary? = null,
    val categories: List<FinanceCategory> = emptyList(),
    val transactions: List<FinanceTransaction> = emptyList(),
    val categoryBreakdown: List<CategorySpending> = emptyList(),
    val weeklyTrend: List<FinanceTrendBucket> = emptyList(),
    val monthlyTrend: List<FinanceTrendBucket> = emptyList(),
    val previousMonthBudgetFen: Long? = null,
    val searchQuery: String = "",
    val filter: FinanceTransactionFilter = FinanceTransactionFilter.ALL,
    val reminderEnabled: Boolean = false,
    val searchAllMonths: Boolean = false,
    val forecast: FinanceForecast? = null,
    val dailyTrend: List<Pair<LocalDate, Long>> = emptyList(),
    val trendWindowDays: Int = 7,
    val anomalies: List<CategoryAnomaly> = emptyList(),
    val savingsSuggestions: List<SavingsSuggestion> = emptyList(),
    val errorMessage: String? = null,
    val notice: String? = null,
) {
    val isCurrentMonth: Boolean get() = selectedMonth == YearMonth.from(today)
}

private data class FinanceLoad(
    val snapshot: FinanceSnapshot = FinanceSnapshot(),
    val errorMessage: String? = null,
)

private data class FinanceQueryState(
    val month: YearMonth,
    val search: String,
    val filter: FinanceTransactionFilter,
    val today: LocalDate,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModel(
    private val repository: FinanceRepository,
    private val clock: Clock,
    private val documentStore: FinanceDocumentStore,
    private val preferences: UserPreferencesRepository,
    private val reminderScheduler: BudgetReminderScheduler,
    private val appVersion: String,
    initialMonth: YearMonth = YearMonth.now(clock),
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val selectedMonth = MutableStateFlow(initialMonth)
    private val today = MutableStateFlow(LocalDate.now(clock))
    private val searchQuery = MutableStateFlow("")
    private val filter = MutableStateFlow(FinanceTransactionFilter.ALL)
    private val notice = MutableStateFlow<String?>(null)
    private val trendWindowDays = MutableStateFlow(7)
    private val _customTrend = MutableStateFlow<List<Pair<LocalDate, Long>>>(emptyList())
    val customTrend: StateFlow<List<Pair<LocalDate, Long>>> = _customTrend.asStateFlow()

    private val queryState = combine(selectedMonth, searchQuery, filter, today, trendWindowDays) {
            month, search, selected, date, windowDays ->
        FinanceQueryState(month = month, search = search, filter = selected, today = date) to windowDays
    }

    private val loadFlow = refresh.flatMapLatest {
        repository.observeSnapshot()
            .map { FinanceLoad(snapshot = it) }
            .catch {
                emit(
                    FinanceLoad(
                        errorMessage = "无法读取本地财务数据，请重试。",
                    ),
                )
            }
    }

    private val reminderEnabledFlow = preferences.budgetReminderEnabled
        .catch { emit(false) }

    val uiState: StateFlow<FinanceUiState> = combine(
        loadFlow,
        queryState,
        reminderEnabledFlow,
        notice,
    ) { load, queryWithWindow, reminderEnabled, actionNotice ->
        val (query, windowDays) = queryWithWindow
        val snapshot = load.snapshot
        val search = query.search.trim()
        val categoryNames = snapshot.categories.associate { it.id to it.name }
        val monthTransactions = snapshot.transactions.filter {
            YearMonth.from(it.date) == query.month
        }
        val source = if (search.isEmpty()) monthTransactions else snapshot.transactions
        val visible = source.filter { transaction ->
            query.filter.matches(transaction.type) &&
                (
                    search.isEmpty() ||
                        FinanceSearch.matches(
                            transaction = transaction,
                            categoryName = categoryNames[transaction.categoryId],
                            typeLabel = FinanceCsv.typeLabel(transaction.type),
                            query = search,
                        )
                    )
        }
        FinanceUiState(
            isLoading = false,
            today = query.today,
            selectedMonth = query.month,
            summary = FinanceCalculator.monthSummary(snapshot, query.month, query.today),
            categories = snapshot.categories,
            transactions = visible,
            categoryBreakdown = FinanceCalculator.categoryBreakdown(snapshot, query.month, query.today),
            weeklyTrend = FinanceCalculator.weeklyTrend(snapshot, query.month, query.today),
            monthlyTrend = FinanceCalculator.monthlyTrend(snapshot, query.month),
            previousMonthBudgetFen = FinanceCalculator.previousMonthBudgetFen(snapshot, query.month),
            searchQuery = query.search,
            filter = query.filter,
            reminderEnabled = reminderEnabled,
            searchAllMonths = search.isNotEmpty(),
            forecast = FinanceForecastEngine.forecast(snapshot, query.month, query.today),
            dailyTrend = FinanceForecastEngine.dailyNetSeries(
                snapshot,
                query.today.minusDays((windowDays - 1).toLong()),
                query.today,
            ),
            trendWindowDays = windowDays,
            anomalies = FinanceForecastEngine.detectAnomalies(snapshot, query.month, query.today),
            savingsSuggestions = FinanceForecastEngine.savingsSuggestions(
                FinanceForecastEngine.forecast(snapshot, query.month, query.today),
                snapshot,
                query.month,
            ),
            errorMessage = load.errorMessage,
            notice = actionNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FinanceUiState(
            isLoading = true,
            today = LocalDate.now(clock),
            selectedMonth = initialMonth,
        ),
    )

    fun retry() {
        refresh.value += 1
    }

    fun refreshToday() {
        today.value = LocalDate.now(clock)
    }

    fun selectMonth(month: YearMonth) {
        selectedMonth.value = month
        refreshToday()
    }

    fun previousMonth() {
        selectedMonth.value = selectedMonth.value.minusMonths(1)
        refreshToday()
    }

    fun nextMonth() {
        selectedMonth.value = selectedMonth.value.plusMonths(1)
        refreshToday()
    }

    fun goToCurrentMonth() {
        refreshToday()
        selectedMonth.value = YearMonth.from(today.value)
    }

    fun setSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun setFilter(value: FinanceTransactionFilter) {
        filter.value = value
    }

    fun setTrendWindowDays(days: Int) {
        trendWindowDays.value = if (days == 30) 30 else 7
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        if (end.isBefore(start)) {
            notice.value = "自定义结束日期不能早于开始日期"
            return
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 730) {
            notice.value = "自定义区间最长 730 天"
            return
        }
        viewModelScope.launch {
            runCatching {
                val snapshot = repository.getSnapshot()
                _customTrend.value = FinanceForecastEngine.dailyNetSeries(snapshot, start, end)
            }.onSuccess {
                notice.value = "已生成 $start 至 $end 的每日净支出"
            }.onFailure {
                notice.value = it.message ?: "自定义区间读取失败"
            }
        }
    }

    fun consumeNotice() {
        notice.value = null
    }

    fun saveTransaction(transaction: FinanceTransaction, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveTransaction(transaction, clock.instant()) }
                .onSuccess { saved ->
                    selectedMonth.value = YearMonth.from(saved.date)
                    today.value = LocalDate.now(clock)
                    notice.value = if (transaction.id == 0L) "记录已保存" else "记录已更新"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存记录失败") }
        }
    }

    fun deleteTransaction(transactionId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteTransaction(transactionId) }
                .onSuccess { notice.value = "记录已删除" }
                .onFailure { notice.value = it.message ?: "删除记录失败" }
        }
    }

    fun markExpectedExpense(transactionId: Long, expected: Boolean = true) {
        viewModelScope.launch {
            runCatching { repository.setExpectedExpense(transactionId, expected, clock.instant()) }
                .onSuccess { notice.value = "已标记为正常特殊开支，不再重复提示" }
                .onFailure { notice.value = it.message ?: "更新标记失败" }
        }
    }

    fun saveMonthlyBudget(month: YearMonth, totalBudgetFen: Long?, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveMonthlyBudget(month, totalBudgetFen, clock.instant()) }
                .onSuccess {
                    notice.value = if (totalBudgetFen == null) "本月预算已清除" else "本月预算已更新"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存预算失败") }
        }
    }

    fun saveCategoryBudget(
        month: YearMonth,
        categoryId: Long,
        limitFen: Long?,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { repository.saveCategoryBudget(month, categoryId, limitFen, clock.instant()) }
                .onSuccess {
                    notice.value = if (limitFen == null) "分类预算已清除" else "分类预算已更新"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存分类预算失败") }
        }
    }

    fun addCategory(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.addCategory(name) }
                .onSuccess {
                    notice.value = "分类已添加"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "添加分类失败") }
        }
    }

    fun renameCategory(categoryId: Long, name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.renameCategory(categoryId, name) }
                .onSuccess {
                    notice.value = "分类已重命名"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "重命名分类失败") }
        }
    }

    fun deleteCategory(category: FinanceCategory) {
        viewModelScope.launch {
            runCatching { repository.deleteCategory(category.id) }
                .onSuccess { notice.value = "分类已删除" }
                .onFailure { notice.value = it.message ?: "删除分类失败" }
        }
    }

    fun setBudgetReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val saved = runCatching { preferences.setBudgetReminderEnabled(enabled) }
                .onFailure { notice.value = "保存提醒设置失败" }
                .isSuccess
            if (!saved) return@launch
            runCatching { reminderScheduler.sync(enabled) }
                .onFailure {
                    notice.value = "提醒设置已保存，但系统任务调度失败；仍会显示应用内提醒"
                    return@launch
                }
            notice.value = if (enabled) {
                "预算提醒已开启：每月最多提醒两次"
            } else {
                "预算提醒已关闭"
            }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val snapshot = repository.getSnapshot()
                documentStore.writeText(uri, FinanceCsv.encode(snapshot))
            }.onSuccess {
                notice.value = "CSV 已导出到所选位置"
            }.onFailure {
                notice.value = "导出 CSV 失败：${it.message ?: "无法写入文件"}"
            }
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val snapshot = repository.getSnapshot()
                val content = FinanceBackup.encode(
                    snapshot = snapshot,
                    exportedAt = clock.instant(),
                    zoneId = clock.zone,
                    appVersion = appVersion,
                    databaseVersion = STUDYOS_DATABASE_VERSION,
                )
                documentStore.writeText(uri, content)
            }.onSuccess {
                notice.value = "本地备份已导出"
            }.onFailure {
                notice.value = "导出备份失败：${it.message ?: "无法写入文件"}"
            }
        }
    }
}
