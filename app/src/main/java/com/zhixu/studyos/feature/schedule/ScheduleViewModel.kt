package com.zhixu.studyos.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.DailyLogRepository
import com.zhixu.studyos.core.data.repository.ScheduleRepository
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.DailyTask
import com.zhixu.studyos.core.model.DaySchedule
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.ScheduleConflict
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.schedule.ScheduleEngine
import com.zhixu.studyos.core.schedule.SemesterCalendar
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleUiState(
    val isLoading: Boolean = true,
    val snapshot: ScheduleSnapshot = ScheduleSnapshot(),
    val selectedWeek: Int = 1,
    val weekDays: List<DaySchedule> = emptyList(),
    val todayTasks: List<DailyTask> = emptyList(),
    val errorMessage: String? = null,
    val notice: String? = null,
)

private data class SnapshotLoad(
    val snapshot: ScheduleSnapshot = ScheduleSnapshot(),
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val clock: Clock,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val requestedWeek = MutableStateFlow<Int?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private val snapshotLoad = refresh.flatMapLatest {
        repository.observeSnapshot()
            .map { SnapshotLoad(snapshot = it) }
            .catch { emit(SnapshotLoad(errorMessage = "无法读取课表数据，请重试。")) }
    }

    private val todayTasksFlow = dailyLogRepository
        .observeTasks(LocalDate.now(clock))
        .catch { emit(emptyList()) }

    val uiState: StateFlow<ScheduleUiState> = combine(
        snapshotLoad,
        requestedWeek,
        notice,
        todayTasksFlow,
    ) { load, weekRequest, operationNotice, tasks ->
        val semester = load.snapshot.semester
        val currentWeek = semester?.let {
            val today = LocalDate.now(clock.withZone(it.zoneId))
            SemesterCalendar.teachingWeek(today, it).coerceAtLeast(1)
        } ?: 1
        val selectedWeek = (weekRequest ?: currentWeek).coerceAtLeast(1)
        val monday = semester?.let { SemesterCalendar.weekStart(it, selectedWeek) }
            ?: LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = (0L..6L).map { offset ->
            ScheduleEngine.daySchedule(load.snapshot, monday.plusDays(offset), clock.zone)
        }
        ScheduleUiState(
            isLoading = false,
            snapshot = load.snapshot,
            selectedWeek = selectedWeek,
            weekDays = days,
            todayTasks = tasks,
            errorMessage = load.errorMessage,
            notice = operationNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScheduleUiState(),
    )

    val fallbackZoneId: ZoneId get() = clock.zone

    fun currentDate(): LocalDate = LocalDate.now(clock)

    fun previousWeek() {
        requestedWeek.value = (uiState.value.selectedWeek - 1).coerceAtLeast(1)
    }

    fun nextWeek() {
        requestedWeek.value = uiState.value.selectedWeek + 1
    }

    fun goToCurrentWeek() {
        requestedWeek.value = null
    }

    fun retry() {
        refresh.value += 1
    }

    fun consumeNotice() {
        notice.value = null
    }

    fun saveSemester(startDate: LocalDate, zoneId: ZoneId, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveSemester(startDate, zoneId) }
                .onSuccess {
                    notice.value = "学期设置已保存"
                    onResult(null)
                }
                .onFailure { onResult(it.userMessage("学期设置保存失败")) }
        }
    }

    fun saveCourse(course: Course, onResult: (String?) -> Unit) {
        saveWithConflicts("课程已保存", { repository.saveCourse(course) }, onResult)
    }

    fun deleteCourse(courseId: Long) {
        runOperation("课程已删除") { repository.deleteCourse(courseId) }
    }

    fun saveException(exception: CourseException, onResult: (String?) -> Unit) {
        val successMessage = if (exception.type == CourseExceptionType.CANCEL) "当日课程已停课" else "调课已保存"
        saveWithConflicts(successMessage, { repository.saveException(exception) }, onResult)
    }

    fun deleteException(exceptionId: Long) {
        runOperation("已恢复原课程安排") { repository.deleteException(exceptionId) }
    }

    fun saveEvent(event: OneOffEvent, onResult: (String?) -> Unit) {
        saveWithConflicts("一次性日程已保存", { repository.saveEvent(event) }, onResult)
    }

    fun deleteEvent(eventId: Long) {
        runOperation("日程已删除") { repository.deleteEvent(eventId) }
    }

    fun saveRoutine(routine: RoutineBlock, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveRoutine(routine) }
                .onSuccess {
                    notice.value = "作息或固定事务已保存"
                    onResult(null)
                }
                .onFailure { onResult(it.userMessage("保存失败")) }
        }
    }

    fun deleteRoutine(routineId: Long) {
        runOperation("事务已删除") { repository.deleteRoutine(routineId) }
    }

    private fun saveWithConflicts(
        successMessage: String,
        action: suspend () -> List<ScheduleConflict>,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { conflicts ->
                    if (conflicts.isEmpty()) {
                        notice.value = successMessage
                        onResult(null)
                    } else {
                        onResult(conflicts.toDisplayMessage())
                    }
                }
                .onFailure { onResult(it.userMessage("保存失败")) }
        }
    }

    private fun runOperation(successMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { notice.value = successMessage }
                .onFailure { notice.value = it.userMessage("操作失败") }
        }
    }

    private fun Throwable.userMessage(fallback: String): String = message?.takeIf(String::isNotBlank) ?: fallback

    private fun List<ScheduleConflict>.toDisplayMessage(): String {
        val first = first()
        val examples = take(2).joinToString("；") { conflict ->
            "${conflict.date} 与「${conflict.existing.title}」冲突"
        }
        return "检测到 $size 处时间冲突：$examples${if (size > 2) "等" else ""}。请调整后再保存。"
    }
}
