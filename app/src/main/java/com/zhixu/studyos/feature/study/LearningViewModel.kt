package com.zhixu.studyos.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.LearningRepository
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.learning.LearningAnalyticsCalculator
import com.zhixu.studyos.core.learning.SelfControlCalculator
import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.LearningSnapshot
import com.zhixu.studyos.core.model.LearningSubjectAnalytics
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProcrastinationReason
import com.zhixu.studyos.core.model.SelfControlEntry
import com.zhixu.studyos.core.model.SelfControlOverview
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TimerInterruptionReason
import com.zhixu.studyos.core.model.TimingSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LearningSection {
    TASKS,
    SCHEDULING,
    TIMER,
    PROBLEMS,
    BANK,
    VOCABULARY,
    KNOWLEDGE,
    MATH,
    AI,
    ANALYTICS,
    REPORT,
    EXAM_GOAL,
    SELF_CONTROL,
}

data class LearningUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val now: Instant = Instant.now(),
    val subjects: List<Subject> = emptyList(),
    val tasks: List<StudyTask> = emptyList(),
    val activeTimer: TimingSession? = null,
    val recentTimings: List<TimingSession> = emptyList(),
    val problems: List<Problem> = emptyList(),
    val subjectAnalytics: LearningSubjectAnalytics = LearningSubjectAnalytics(),
    val selfControl: SelfControlOverview? = null,
    val selfControlEntries: List<SelfControlEntry> = emptyList(),
    val goals: LearningGoals = LearningGoals(),
    val errorMessage: String? = null,
    val notice: String? = null,
)

private data class LearningData(
    val snapshot: LearningSnapshot = LearningSnapshot(),
    val tasks: List<StudyTask> = emptyList(),
    val errorMessage: String? = null,
)

private data class LearningLoad(
    val snapshot: LearningSnapshot = LearningSnapshot(),
    val errorMessage: String? = null,
)

private data class TaskLoadV4(
    val tasks: List<StudyTask> = emptyList(),
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LearningViewModel(
    private val repository: LearningRepository,
    private val taskRepository: TaskRepository,
    private val clock: Clock,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val ticker = MutableStateFlow(clock.instant())
    private val notice = MutableStateFlow<String?>(null)
    private val timerMutex = Mutex()
    private var foreground = true
    private var lastHeartbeatAtMillis = 0L
    private var autoFinishedTimerId: Long? = null

    private val dataFlow = refresh.flatMapLatest {
        combine(
            repository.observeSnapshot()
                .map { LearningLoad(snapshot = it) }
                .catch { emit(LearningLoad(errorMessage = "无法读取学习数据，请重试。")) },
            taskRepository.observeAllTasks()
                .map { TaskLoadV4(tasks = it) }
                .catch { emit(TaskLoadV4(errorMessage = "无法读取任务数据，学习分析暂不可用。")) },
        ) { learning, tasks ->
            LearningData(
                snapshot = learning.snapshot,
                tasks = tasks.tasks,
                errorMessage = learning.errorMessage ?: tasks.errorMessage,
            )
        }
    }

    val uiState: StateFlow<LearningUiState> = combine(dataFlow, ticker, notice) { data, now, actionNotice ->
        val snapshot = data.snapshot
        val today = now.atZone(clock.zone).toLocalDate()
        val analytics = LearningAnalyticsCalculator.subjectAnalytics(
            subjects = snapshot.subjects,
            tasks = data.tasks,
            timings = snapshot.timings,
            problems = snapshot.problems,
        )
        val overview = SelfControlCalculator.calculate(
            tasks = data.tasks,
            timings = snapshot.timings,
            entries = snapshot.selfControlEntries,
            goals = snapshot.goals,
            today = today,
        )
        LearningUiState(
            isLoading = false,
            today = today,
            zoneId = clock.zone,
            now = now,
            subjects = snapshot.subjects,
            tasks = data.tasks,
            activeTimer = snapshot.timings.firstOrNull {
                it.status == StudyTimerStatus.RUNNING || it.status == StudyTimerStatus.PAUSED
            },
            recentTimings = snapshot.timings
                .filter { it.status == StudyTimerStatus.COMPLETED }
                .sortedByDescending { it.startedAt }
                .take(60),
            problems = snapshot.problems,
            subjectAnalytics = analytics,
            selfControl = overview,
            selfControlEntries = snapshot.selfControlEntries,
            goals = snapshot.goals,
            errorMessage = data.errorMessage,
            notice = actionNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LearningUiState(
            today = LocalDate.now(clock),
            zoneId = clock.zone,
            now = clock.instant(),
        ),
    )

    init {
        viewModelScope.launch {
            runCatching { repository.recoverRunningSessions(clock.instant()) }
            while (isActive) {
                delay(1_000)
                val now = clock.instant()
                ticker.value = now
                if (foreground) {
                    tickActiveTimer(now)
                }
            }
        }
    }

    fun retry() {
        refresh.value += 1
    }

    fun consumeNotice() {
        notice.value = null
    }

    fun onAppForeground() {
        foreground = true
    }

    fun onAppBackground() {
        foreground = false
        viewModelScope.launch {
            val active = runCatching { repository.getActiveTiming() }.getOrNull()
            if (active?.status == StudyTimerStatus.RUNNING) {
                timerMutex.withLock {
                    runCatching {
                        repository.pauseTiming(active.id, TimerInterruptionReason.APP_BACKGROUND, clock.instant())
                    }
                }
                notice.value = "离开应用，计时已自动暂停"
            }
        }
    }

    fun addSubject(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.addSubject(name) }
                .onSuccess {
                    notice.value = "科目已添加"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "添加科目失败") }
        }
    }

    fun startTimer(
        subjectId: Long,
        taskId: Long?,
        mode: StudyTimerMode,
        plannedDurationMinutes: Int?,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                timerMutex.withLock {
                    repository.startTiming(
                        subjectId = subjectId,
                        taskId = taskId,
                        mode = mode,
                        plannedDurationMinutes = plannedDurationMinutes,
                        now = clock.instant(),
                    )
                }
            }.onSuccess {
                autoFinishedTimerId = null
                lastHeartbeatAtMillis = 0L
                onResult(null)
            }.onFailure { onResult(it.message ?: "无法开始计时") }
        }
    }

    fun pauseTimer() {
        val active = uiState.value.activeTimer ?: return
        if (active.status != StudyTimerStatus.RUNNING) return
        viewModelScope.launch {
            runCatching {
                timerMutex.withLock {
                    repository.pauseTiming(active.id, TimerInterruptionReason.USER, clock.instant())
                }
            }.onSuccess { notice.value = "计时已暂停" }
                .onFailure { notice.value = it.message ?: "暂停失败" }
        }
    }

    fun resumeTimer() {
        val active = uiState.value.activeTimer ?: return
        if (active.status != StudyTimerStatus.PAUSED) return
        viewModelScope.launch {
            runCatching {
                timerMutex.withLock { repository.resumeTiming(active.id, clock.instant()) }
            }.onSuccess {
                lastHeartbeatAtMillis = 0L
                autoFinishedTimerId = null
            }.onFailure { notice.value = it.message ?: "继续计时失败" }
        }
    }

    fun finishTimer() {
        val active = uiState.value.activeTimer ?: return
        viewModelScope.launch {
            runCatching {
                timerMutex.withLock { repository.finishTiming(active.id, clock.instant()) }
            }.onSuccess { session ->
                autoFinishedTimerId = session.id
                notice.value = "本次学习 ${formatLearningDuration(session.accumulatedMillis)}"
            }.onFailure { notice.value = it.message ?: "结束计时失败" }
        }
    }

    fun addManualTiming(
        subjectId: Long,
        taskId: Long?,
        studyDate: LocalDate,
        startedAt: Instant,
        durationMinutes: Int,
        note: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.addManualTiming(
                    subjectId = subjectId,
                    taskId = taskId,
                    studyDate = studyDate,
                    startedAt = startedAt,
                    durationMinutes = durationMinutes,
                    note = note,
                    now = clock.instant(),
                )
            }.onSuccess {
                notice.value = "学习记录已补录"
                onResult(null)
            }.onFailure { onResult(it.message ?: "补录失败") }
        }
    }

    fun correctTiming(sessionId: Long, durationMinutes: Int, note: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.correctTiming(sessionId, durationMinutes, note, clock.instant()) }
                .onSuccess {
                    notice.value = "学习记录已修正"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "修正失败") }
        }
    }

    fun deleteTiming(sessionId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteTiming(sessionId) }
                .onSuccess { notice.value = "学习记录已删除" }
                .onFailure { notice.value = it.message ?: "删除失败" }
        }
    }

    fun saveProblem(problem: Problem, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveProblem(problem, clock.instant()) }
                .onSuccess {
                    notice.value = "习题记录已保存"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存习题失败") }
        }
    }

    fun retryProblem(problemId: Long) {
        viewModelScope.launch {
            runCatching { repository.retryProblem(problemId, clock.instant()) }
                .onSuccess { notice.value = "已加入重新练习，完成后记录是否独立完成" }
                .onFailure { notice.value = it.message ?: "无法重新练习" }
        }
    }

    fun deleteProblem(problemId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteProblem(problemId) }
                .onSuccess { notice.value = "习题记录已删除" }
                .onFailure { notice.value = it.message ?: "删除失败" }
        }
    }

    fun addSelfControlEntry(
        date: LocalDate,
        reason: ProcrastinationReason?,
        voluntaryInterruption: Boolean,
        note: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.addSelfControlEntry(date, reason, voluntaryInterruption, note, clock.instant())
            }.onSuccess {
                notice.value = "自控记录已保存"
                onResult(null)
            }.onFailure { onResult(it.message ?: "保存记录失败") }
        }
    }

    fun deleteSelfControlEntry(entryId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteSelfControlEntry(entryId) }
                .onSuccess { notice.value = "记录已删除" }
                .onFailure { notice.value = it.message ?: "删除失败" }
        }
    }

    fun saveGoals(goals: LearningGoals, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveGoals(goals, clock.instant()) }
                .onSuccess {
                    notice.value = "学习目标已更新"
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存目标失败") }
        }
    }

    private suspend fun tickActiveTimer(now: Instant) {
        val active = runCatching { repository.getActiveTiming() }.getOrNull() ?: run {
            autoFinishedTimerId = null
            return
        }
        if (active.status != StudyTimerStatus.RUNNING) return
        if (active.id == autoFinishedTimerId) return
        val elapsed = active.elapsedMillis(now)
        val plannedMillis = active.plannedDurationMinutes?.times(60_000L)
        if (active.mode == StudyTimerMode.POMODORO && plannedMillis != null && elapsed >= plannedMillis) {
            timerMutex.withLock {
                runCatching {
                    val finished = repository.finishTiming(active.id, now)
                    autoFinishedTimerId = finished.id
                    notice.value = "番茄钟完成，已记录 ${formatLearningDuration(finished.accumulatedMillis)}"
                }
            }
            return
        }
        val nowMillis = now.toEpochMilli()
        if (lastHeartbeatAtMillis == 0L || nowMillis - lastHeartbeatAtMillis >= 5_000L) {
            timerMutex.withLock {
                runCatching { repository.checkpointTiming(active.id, now) }
            }
            lastHeartbeatAtMillis = nowMillis
        }
    }
}

fun formatLearningDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun elapsedStudyMinutes(session: TimingSession, now: Instant): Long =
    Duration.ofMillis(session.elapsedMillis(now)).toMinutes()
