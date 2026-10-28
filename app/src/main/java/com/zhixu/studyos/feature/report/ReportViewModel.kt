package com.zhixu.studyos.feature.report

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.ReportRepository
import com.zhixu.studyos.core.data.repository.SavedWeeklySummary
import com.zhixu.studyos.core.growth.GrowthAnalytics
import com.zhixu.studyos.core.growth.GrowthAnalyticsEngine
import com.zhixu.studyos.core.growth.GrowthRange
import com.zhixu.studyos.core.growth.GrowthRangeType
import com.zhixu.studyos.core.report.AiReportSections
import com.zhixu.studyos.core.report.WeeklyReport
import com.zhixu.studyos.core.report.WeeklyReportEngine
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ReportTab { WEEKLY, GROWTH }

data class ReportUiState(
    val tab: ReportTab = ReportTab.WEEKLY,
    val weekStart: LocalDate = WeeklyReportEngine.weekRange(LocalDate.now()).start,
    val weekly: WeeklyReport? = null,
    val weeklyAi: AiReportSections? = null,
    val weeklyAiMeta: String? = null,
    val growth: GrowthAnalytics? = null,
    val growthAi: AiReportSections? = null,
    val growthAiMeta: String? = null,
    val rangeType: GrowthRangeType = GrowthRangeType.WEEK,
    val customStart: LocalDate = LocalDate.now().minusDays(29),
    val customEnd: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val isAiLoading: Boolean = false,
    val isExporting: Boolean = false,
    val saved: List<SavedWeeklySummary> = emptyList(),
    val errorMessage: String? = null,
    val notice: String? = null,
)

class ReportViewModel(
    private val repository: ReportRepository,
    private val clock: Clock,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        ReportUiState(
            weekStart = WeeklyReportEngine.weekRange(LocalDate.now(clock)).start,
            customStart = LocalDate.now(clock).minusDays(29),
            customEnd = LocalDate.now(clock),
        ),
    )
    val uiState: StateFlow<ReportUiState> = mutable

    private var started = false

    fun start() {
        if (started) return
        started = true
        loadWeekly()
        loadGrowth()
        loadSaved()
    }

    fun setTab(tab: ReportTab) {
        mutable.value = mutable.value.copy(tab = tab)
    }

    fun shiftWeek(offset: Long) {
        mutable.value = mutable.value.copy(weekStart = mutable.value.weekStart.plusWeeks(offset))
        loadWeekly()
    }

    fun goToThisWeek() {
        mutable.value = mutable.value.copy(
            weekStart = WeeklyReportEngine.weekRange(LocalDate.now(clock)).start,
        )
        loadWeekly()
    }

    fun setRangeType(type: GrowthRangeType) {
        mutable.value = mutable.value.copy(rangeType = type)
        loadGrowth()
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        mutable.value = mutable.value.copy(
            customStart = if (end.isBefore(start)) start else start,
            customEnd = if (end.isBefore(start)) start else end,
            rangeType = GrowthRangeType.CUSTOM,
        )
        loadGrowth()
    }

    fun refresh() {
        loadWeekly()
        loadGrowth()
        loadSaved()
    }

    private fun loadWeekly() {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isLoading = true, errorMessage = null)
            runCatching {
                val report = repository.weeklyReport(mutable.value.weekStart)
                repository.saveWeeklyReport(report)
                report to repository.savedWeeklySummaries(12)
            }.onSuccess { (report, saved) ->
                mutable.value = mutable.value.copy(
                    isLoading = false,
                    weekly = report,
                    weeklyAi = null,
                    weeklyAiMeta = null,
                    saved = saved,
                )
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isLoading = false,
                    errorMessage = it.message ?: "无法生成周报",
                )
            }
        }
    }

    private fun loadGrowth() {
        viewModelScope.launch {
            val range = GrowthAnalyticsEngine.currentRange(
                type = mutable.value.rangeType,
                today = LocalDate.now(clock),
                customStart = mutable.value.customStart,
                customEnd = mutable.value.customEnd,
            )
            val growth = runCatching { repository.growthAnalytics(range, semesterStart = null) }
            growth.onSuccess {
                mutable.value = mutable.value.copy(growth = it, errorMessage = null)
            }.onFailure {
                mutable.value = mutable.value.copy(errorMessage = it.message ?: "无法生成成长分析")
            }
        }
    }

    private fun loadSaved() {
        viewModelScope.launch {
            runCatching { repository.savedWeeklySummaries(12) }
                .onSuccess { mutable.value = mutable.value.copy(saved = it) }
        }
    }

    fun requestWeeklyAi(forceRefresh: Boolean = false) {
        val report = mutable.value.weekly ?: return
        if (mutable.value.isAiLoading) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isAiLoading = true, notice = null)
            runCatching { repository.aiWeeklyReport(report, forceRefresh) }
                .onSuccess { outcome ->
                    mutable.value = mutable.value.copy(
                        isAiLoading = false,
                        weekly = outcome.report,
                        weeklyAi = outcome.sections,
                        weeklyAiMeta = "${outcome.model} · " +
                            (if (outcome.usedCache) "缓存结果，未产生新费用" else "本次费用估算 ${outcome.costMicros} 微元"),
                        saved = repository.savedWeeklySummaries(12),
                    )
                }
                .onFailure {
                    mutable.value = mutable.value.copy(
                        isAiLoading = false,
                        notice = "AI 周报失败：${it.message ?: "未知错误"}",
                    )
                }
        }
    }

    fun requestGrowthAi(forceRefresh: Boolean = false) {
        val growth = mutable.value.growth ?: return
        if (mutable.value.isAiLoading) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isAiLoading = true, notice = null)
            runCatching { repository.aiGrowthReport(growth, forceRefresh) }
                .onSuccess { outcome ->
                    mutable.value = mutable.value.copy(
                        isAiLoading = false,
                        growthAi = outcome.sections,
                        growthAiMeta = "${outcome.model} · " +
                            (if (outcome.usedCache) "缓存结果，未产生新费用" else "本次费用估算 ${outcome.costMicros} 微元"),
                    )
                }
                .onFailure {
                    mutable.value = mutable.value.copy(
                        isAiLoading = false,
                        notice = "AI 成长报告失败：${it.message ?: "未知错误"}",
                    )
                }
        }
    }

    fun exportWeeklyPdf(uri: Uri) {
        val report = mutable.value.weekly ?: return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isExporting = true)
            runCatching { repository.exportWeeklyPdf(uri, report) }
                .onSuccess {
                    mutable.value = mutable.value.copy(
                        isExporting = false,
                        notice = "周报 PDF 已导出到所选位置",
                    )
                }
                .onFailure {
                    mutable.value = mutable.value.copy(
                        isExporting = false,
                        notice = "导出 PDF 失败：${it.message ?: "无法写入文件"}",
                    )
                }
        }
    }

    fun consumeNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }
}
