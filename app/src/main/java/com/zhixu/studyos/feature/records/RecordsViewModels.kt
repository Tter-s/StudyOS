package com.zhixu.studyos.feature.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.DailyLogRepository
import com.zhixu.studyos.core.data.repository.SleepRepository
import com.zhixu.studyos.core.model.DailyTask
import com.zhixu.studyos.core.model.DayRecord
import com.zhixu.studyos.core.sleep.SleepRecord
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

fun formatDuration(minutes: Long?): String {
    if (minutes == null) return "未记录"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "$rest 分钟"
        rest == 0L -> "$hours 小时"
        else -> "$hours 小时 $rest 分钟"
    }
}

fun formatMinutesOfDay(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

fun LocalTime.label(): String = "%02d:%02d".format(hour, minute)

// ---------------- 记录首页 ----------------

data class RecordsHubUiState(
    val date: LocalDate,
    val summary: String? = null,
    val entertainmentMinutes: Int? = null,
    val lastSleep: SleepRecord? = null,
    val isLoading: Boolean = true,
) {
    val lastSleepMinutes: Long? get() = lastSleep?.durationMinutes
}

class RecordsHubViewModel(
    repository: DailyLogRepository,
    sleepRepository: SleepRepository,
    private val clock: Clock,
) : ViewModel() {
    private val today = LocalDate.now(clock)

    val uiState: StateFlow<RecordsHubUiState> = combine(
        repository.observeSummary(today),
        repository.observeEntertainment(today),
        sleepRepository.observeSnapshot(),
    ) { summary, entertainment, sleep ->
        RecordsHubUiState(
            date = today,
            summary = summary?.takeIf { it.isNotBlank() },
            entertainmentMinutes = entertainment,
            lastSleep = sleep.records.firstOrNull {
                (it.date == today || it.date == today.minusDays(1)) && it.durationMinutes != null
            },
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordsHubUiState(date = today),
    )
}

// ---------------- 每日总结 ----------------

data class DailySummaryUiState(
    val date: LocalDate,
    val tasks: List<DailyTask> = emptyList(),
    val summary: String = "",
    val notice: String? = null,
    val isLoading: Boolean = true,
) {
    val total: Int get() = tasks.size
    val completed: Int get() = tasks.count { it.isCompleted }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DailySummaryViewModel(
    private val repository: DailyLogRepository,
    private val clock: Clock,
    initialDate: LocalDate = LocalDate.now(clock),
) : ViewModel() {
    private val selectedDate = MutableStateFlow(initialDate)
    private val notice = MutableStateFlow<String?>(null)
    val today: LocalDate = LocalDate.now(clock)

    val uiState: StateFlow<DailySummaryUiState> = selectedDate.flatMapLatest { day ->
        combine(
            repository.observeTasks(day),
            repository.observeSummary(day),
            notice,
        ) { tasks, summary, message ->
            DailySummaryUiState(
                date = day,
                tasks = tasks,
                summary = summary.orEmpty(),
                notice = message,
                isLoading = false,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DailySummaryUiState(date = initialDate),
    )

    fun setDate(date: LocalDate) {
        selectedDate.value = date.coerceAtMost(today)
    }

    fun previousDay() = setDate(selectedDate.value.minusDays(1))

    fun nextDay() {
        if (selectedDate.value < today) setDate(selectedDate.value.plusDays(1))
    }

    fun save(content: String) {
        viewModelScope.launch {
            runCatching { repository.saveSummary(selectedDate.value, content) }
                .onSuccess { notice.value = "已保存" }
                .onFailure { notice.value = it.message ?: "保存失败，请重试" }
        }
    }

    fun clearNotice() {
        notice.value = null
    }

    fun consumeNotice() {
        notice.value = null
    }
}

// ---------------- 娱乐时间 ----------------

data class EntertainmentUiState(
    val date: LocalDate,
    val minutes: Int? = null,
    val recent: List<DayRecord> = emptyList(),
    val notice: String? = null,
    val isLoading: Boolean = true,
) {
    val recentWithData: List<DayRecord> get() = recent.filter { it.entertainmentMinutes != null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EntertainmentViewModel(
    private val repository: DailyLogRepository,
    private val clock: Clock,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now(clock))
    private val notice = MutableStateFlow<String?>(null)
    val today: LocalDate = LocalDate.now(clock)

    val uiState: StateFlow<EntertainmentUiState> = combine(
        selectedDate,
        selectedDate.flatMapLatest { repository.observeEntertainment(it) },
        repository.observeHistory(),
        notice,
    ) { day, minutes, history, message ->
        EntertainmentUiState(
            date = day,
            minutes = minutes,
            recent = history,
            notice = message,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EntertainmentUiState(date = today),
    )

    fun setDate(date: LocalDate) {
        selectedDate.value = date.coerceAtMost(today)
    }

    fun previousDay() = setDate(selectedDate.value.minusDays(1))

    fun nextDay() {
        if (selectedDate.value < today) setDate(selectedDate.value.plusDays(1))
    }

    fun save(hours: Int, minutes: Int) {
        val total = hours * 60 + minutes
        if (total !in 0..(24 * 60)) {
            notice.value = "请输入 0–24 小时之间的娱乐时间"
            return
        }
        viewModelScope.launch {
            runCatching { repository.saveEntertainment(selectedDate.value, total) }
                .onSuccess { notice.value = "已记录 ${formatDuration(total.toLong())}" }
                .onFailure { notice.value = it.message ?: "保存失败，请重试" }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { repository.deleteEntertainment(selectedDate.value) }
                .onSuccess { notice.value = "已删除当天的娱乐记录" }
                .onFailure { notice.value = it.message ?: "删除失败，请重试" }
        }
    }

    fun consumeNotice() {
        notice.value = null
    }
}

// ---------------- 睡眠记录 ----------------

data class SleepLogUiState(
    val records: List<SleepRecord> = emptyList(),
    val notice: String? = null,
    val isLoading: Boolean = true,
) {
    fun recordOn(date: LocalDate): SleepRecord? = records.firstOrNull { it.date == date }
}

class SleepLogViewModel(
    private val repository: SleepRepository,
    private val clock: Clock,
) : ViewModel() {
    private val notice = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SleepLogUiState> = combine(
        repository.observeSnapshot(),
        notice,
    ) { snapshot, message ->
        SleepLogUiState(
            records = snapshot.records
                .filter { it.bedAt != null && it.wakeAt != null }
                .sortedByDescending { it.date },
            notice = message,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SleepLogUiState(),
    )

    fun save(date: LocalDate, bedTime: LocalTime, wakeTime: LocalTime) {
        viewModelScope.launch {
            runCatching {
                val zone = clock.zone
                val bedDate = if (bedTime.hour >= 12) date.minusDays(1) else date
                val bedAt = bedDate.atTime(bedTime).atZone(zone).toInstant()
                val wakeAt = date.atTime(wakeTime).atZone(zone).toInstant()
                val minutes = Duration.between(bedAt, wakeAt).toMinutes()
                require(minutes in 1..(16 * 60)) { "睡眠时长应在 1 分钟到 16 小时之间，请检查日期和时间" }
                repository.saveRecord(
                    SleepRecord(
                        date = date,
                        bedAt = bedAt,
                        wakeAt = wakeAt,
                    ),
                )
            }.onSuccess {
                notice.value = "睡眠记录已保存"
            }.onFailure {
                notice.value = it.message ?: "保存失败，请重试"
            }
        }
    }

    fun delete(date: LocalDate) {
        viewModelScope.launch {
            runCatching { repository.deleteRecord(date) }
                .onSuccess { notice.value = "已删除这条睡眠记录" }
                .onFailure { notice.value = it.message ?: "删除失败，请重试" }
        }
    }

    fun consumeNotice() {
        notice.value = null
    }

    fun zone(): ZoneId = clock.zone
}

// ---------------- 历史记录 ----------------

data class HistoryUiState(
    val days: List<DayRecord> = emptyList(),
    val isLoading: Boolean = true,
)

class HistoryViewModel(
    repository: DailyLogRepository,
    sleepRepository: SleepRepository,
) : ViewModel() {
    val uiState: StateFlow<HistoryUiState> = combine(
        repository.observeHistory(),
        sleepRepository.observeSnapshot(),
    ) { dailyRecords, sleep ->
        val sleepByDate = sleep.records
            .filter { it.durationMinutes != null }
            .associateBy { it.date }
        val dailyByDate = dailyRecords.associateBy { it.date }
        val dates = buildSet {
            dailyRecords.forEach { add(it.date) }
            sleepByDate.keys.forEach { add(it) }
        }
        HistoryUiState(
            days = dates.sortedDescending().map { date ->
                val record = dailyByDate[date] ?: DayRecord(date, 0, 0, null, null)
                record.copy(sleepMinutes = sleepByDate[date]?.durationMinutes)
            },
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(),
    )
}
