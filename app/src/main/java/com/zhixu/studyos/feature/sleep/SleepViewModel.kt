package com.zhixu.studyos.feature.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.local.SleepChangeHistoryEntity
import com.zhixu.studyos.core.data.repository.SleepOverview
import com.zhixu.studyos.core.data.repository.SleepRepository
import com.zhixu.studyos.core.data.repository.SleepSnapshot
import com.zhixu.studyos.core.sleep.SleepDateOverride
import com.zhixu.studyos.core.sleep.SleepRecord
import com.zhixu.studyos.core.sleep.SleepSchedule
import com.zhixu.studyos.core.reminder.SleepModeScheduler
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SleepUiState(
    val isLoading: Boolean = true,
    val snapshot: SleepSnapshot = SleepSnapshot(),
    val overview: SleepOverview? = null,
    val history: List<SleepChangeHistoryEntity> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val errorMessage: String? = null,
    val notice: String? = null,
)

class SleepViewModel(
    private val repository: SleepRepository,
    private val clock: Clock,
    private val sleepModeScheduler: SleepModeScheduler? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(SleepUiState(today = LocalDate.now(clock)))
    val uiState: StateFlow<SleepUiState> = mutable

    init {
        viewModelScope.launch {
            repository.observeSnapshot().collect { snapshot ->
                val today = LocalDate.now(clock)
                val minute = LocalTime.now(clock).let { it.hour * 60 + it.minute }
                val overview = runCatching { repository.overview(today, minute) }.getOrNull()
                mutable.value = mutable.value.copy(
                    isLoading = false,
                    snapshot = snapshot,
                    overview = overview,
                    today = today,
                )
            }
        }
        viewModelScope.launch {
            repository.observeChangeHistory().collect { history ->
                mutable.value = mutable.value.copy(history = history)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.getSchedule() to repository.getOverrides() }
                .onSuccess {
                    mutable.value = mutable.value.copy(errorMessage = null)
                }
        }
    }

    fun saveSchedule(schedule: SleepSchedule, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveSchedule(schedule) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "睡眠设置已保存，历史修改已记录")
                    sleepModeScheduler?.let { scheduler ->
                        viewModelScope.launch { runCatching { scheduler.sync() } }
                    }
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存睡眠设置失败") }
        }
    }

    fun saveOverride(override: SleepDateOverride, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveOverride(override) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "特殊日期作息已保存")
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存特殊日期失败") }
        }
    }

    fun deleteOverride(date: LocalDate) {
        viewModelScope.launch {
            runCatching { repository.deleteOverride(date) }
                .onSuccess { mutable.value = mutable.value.copy(notice = "特殊日期已删除") }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除失败") }
        }
    }

    fun saveRecord(record: SleepRecord, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveRecord(record) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "睡眠记录已保存")
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存睡眠记录失败") }
        }
    }

    fun deleteRecord(date: LocalDate) {
        viewModelScope.launch {
            runCatching { repository.deleteRecord(date) }
                .onSuccess { mutable.value = mutable.value.copy(notice = "睡眠记录已删除") }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除记录失败") }
        }
    }

    fun consumeNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }
}
