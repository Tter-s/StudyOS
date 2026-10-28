package com.zhixu.studyos.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.ExamGoalRepository
import com.zhixu.studyos.core.data.repository.ExamGoalSnapshot
import com.zhixu.studyos.core.data.repository.ExerciseRepository
import com.zhixu.studyos.core.data.repository.FinanceRepository
import com.zhixu.studyos.core.data.repository.HomeCardId
import com.zhixu.studyos.core.data.repository.HomeCardPreference
import com.zhixu.studyos.core.data.repository.HomeCardRepository
import com.zhixu.studyos.core.data.repository.HomeRepository
import com.zhixu.studyos.core.data.repository.ScheduleRepository
import com.zhixu.studyos.core.data.repository.SleepRepository
import com.zhixu.studyos.core.data.repository.SleepSnapshot
import com.zhixu.studyos.core.data.repository.VocabularyRepository
import com.zhixu.studyos.core.data.repository.VocabularySummary
import com.zhixu.studyos.core.exam.ExamCountdown
import com.zhixu.studyos.core.exam.ExamCountdownCalculator
import com.zhixu.studyos.core.finance.FinanceCalculator
import com.zhixu.studyos.core.model.MonthFinanceSummary
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.schedule.SemesterCalendar
import com.zhixu.studyos.core.model.StudyBlockKind
import com.zhixu.studyos.core.model.StudyDayBlock
import com.zhixu.studyos.core.model.StudyDayPlan
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.sleep.SleepPhase
import com.zhixu.studyos.core.sleep.SleepProtectionEngine
import com.zhixu.studyos.core.sleep.SleepStatus
import com.zhixu.studyos.core.sleep.formatSleepMinute
import com.zhixu.studyos.core.task.StudyDayPlanner
import com.zhixu.studyos.core.task.TaskPlanner
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class BudgetLoad(
    val summary: MonthFinanceSummary? = null,
    val failed: Boolean = false,
)

data class NextClassInfo(
    val title: String,
    val detail: String,
    val startMinute: Int,
    val endMinuteExclusive: Int,
) {
    fun timeLabel(): String =
        "${formatSleepMinute(startMinute)}–${formatSleepMinute(endMinuteExclusive)}"
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val dateLabel: String = "",
    val greeting: String = "",
    val semesterLabel: String = "",
    val tasks: List<StudyTask> = emptyList(),
    val todayPlan: StudyDayPlan? = null,
    val isScheduleConfigured: Boolean = false,
    val budgetSummary: MonthFinanceSummary? = null,
    val budgetLoadFailed: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
    val sleepStatus: SleepStatus? = null,
    val sleepDescription: String? = null,
    val nextExam: ExamCountdown? = null,
    val vocabularyDue: Int? = null,
    val exerciseReviewDue: Int? = null,
    val nextClass: NextClassInfo? = null,
    val currentTask: StudyTask? = null,
    val homeCards: List<HomeCardPreference> = HomeCardId.DefaultOrder.map {
        HomeCardPreference(id = it)
    },
    val aiSuggestion: String = "",
) {
    val completedTaskCount: Int get() = tasks.count(StudyTask::isCompleted)
    val plannedMinutes: Int get() = todayPlan?.taskMinutes ?: 0
    val progress: Float
        get() = if (tasks.isEmpty()) 0f else completedTaskCount.toFloat() / tasks.size
    val timelineItems: List<StudyDayBlock>
        get() = todayPlan?.timeline.orEmpty()

    fun cardVisible(id: HomeCardId): Boolean =
        homeCards.firstOrNull { it.id == id }?.visible ?: true
}

private data class HomeBaseData(
    val tasks: List<StudyTask> = emptyList(),
    val budget: BudgetLoad = BudgetLoad(),
    val sleep: SleepSnapshot? = null,
    val exams: ExamGoalSnapshot? = null,
    val vocabulary: VocabularySummary? = null,
    val cards: List<HomeCardPreference> = HomeCardId.DefaultOrder.map {
        HomeCardPreference(id = it)
    },
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: HomeRepository,
    private val clock: Clock,
    private val scheduleRepository: ScheduleRepository? = null,
    private val financeRepository: FinanceRepository? = null,
    private val sleepRepository: SleepRepository? = null,
    private val examGoalRepository: ExamGoalRepository? = null,
    private val vocabularyRepository: VocabularyRepository? = null,
    private val exerciseRepository: ExerciseRepository? = null,
    private val homeCardRepository: HomeCardRepository? = null,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val actionNotice = MutableStateFlow<String?>(null)
    private val baseState = createBaseState(LocalDate.now(clock), clock.zone)

    private val contentState: Flow<HomeUiState> = refresh
        .flatMapLatest {
            val snapshots: Flow<ScheduleSnapshot?> =
                scheduleRepository?.observeSnapshot()?.map { it as ScheduleSnapshot? }
                    ?: flowOf(null)
            snapshots.flatMapLatest { snapshot ->
                val zoneId = snapshot?.semester?.zoneId ?: clock.zone
                val today = LocalDate.now(clock.withZone(zoneId))
                val currentBase = createBaseState(today, zoneId)
                val sleepFlow: Flow<SleepSnapshot?> =
                    sleepRepository?.observeSnapshot()?.map { it as SleepSnapshot? }
                        ?.catch { emit(null) } ?: flowOf(null)
                val examsFlow: Flow<ExamGoalSnapshot?> =
                    examGoalRepository?.observeSnapshot()?.map { it as ExamGoalSnapshot? }
                        ?.catch { emit(null) } ?: flowOf(null)
                val vocabularyFlow: Flow<VocabularySummary?> =
                    vocabularyRepository?.observeSummary(today)?.map { it as VocabularySummary? }
                        ?.catch { emit(null) } ?: flowOf(null)
                val cardsFlow = homeCardRepository?.observeCards()
                    ?.catch {
                        emit(
                            HomeCardId.DefaultOrder.map { id -> HomeCardPreference(id = id) },
                        )
                    }
                    ?: flowOf(HomeCardId.DefaultOrder.map { id -> HomeCardPreference(id = id) })
                repository.observeTasks(today.toEpochDay())
                    .combine(financeBudgetFlow(LocalDate.now(clock))) { tasks, budget ->
                        tasks to budget
                    }
                    .combine(sleepFlow) { (tasks, budget), sleep ->
                        HomeBaseData(tasks = tasks, budget = budget, sleep = sleep)
                    }
                    .combine(examsFlow) { data, exams -> data.copy(exams = exams) }
                    .combine(vocabularyFlow) { data, vocabulary -> data.copy(vocabulary = vocabulary) }
                    .combine(cardsFlow) { data, cards -> data.copy(cards = cards) }
                    .map { data ->
                        val sleepIntervals = data.sleep?.let { sleep ->
                            SleepProtectionEngine.intervalsOn(today, sleep.schedule, sleep.overrides)
                        }.orEmpty()
                        val plan = StudyDayPlanner.build(
                            date = today,
                            tasks = TaskPlanner.dayEntries(data.tasks, today),
                            schedule = snapshot,
                            fallbackZoneId = clock.zone,
                            sleepIntervals = sleepIntervals,
                        )
                        val nowMinute = LocalTime.now(clock.withZone(zoneId)).let {
                            it.hour * 60 + it.minute
                        }
                        val status = data.sleep?.let {
                            SleepProtectionEngine.statusAt(today, nowMinute, it.schedule, it.overrides)
                        }
                        val nextCourse = plan.timeline
                            .filter { it.kind == StudyBlockKind.COURSE && it.endMinuteExclusive > nowMinute }
                            .minByOrNull { it.startMinute }
                        val currentTask = data.tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
                            ?: data.tasks.firstOrNull { task ->
                                !task.isCompleted && task.sessionsVisibleOn(today).any {
                                    nowMinute >= it.startMinute && nowMinute < it.endMinuteExclusive
                                }
                            }
                        val exerciseRepo = exerciseRepository
                        val reviews = if (exerciseRepo != null) {
                            runCatching { exerciseRepo.dueReviewProblemIds(50).size }.getOrNull()
                        } else {
                            null
                        }
                        val suggestion = buildSuggestion(data.tasks, status, data.vocabulary, reviews)
                        currentBase.copy(
                            isLoading = false,
                            tasks = data.tasks,
                            semesterLabel = snapshot?.semester?.let { semester ->
                                "第 ${SemesterCalendar.teachingWeek(today, semester)} 教学周 · " +
                                    "${semester.startDate} 开学"
                            } ?: "尚未设置学期",
                            todayPlan = plan,
                            budgetSummary = data.budget.summary,
                            budgetLoadFailed = data.budget.failed,
                            isScheduleConfigured = data.tasks.isNotEmpty() || snapshot?.let {
                                it.semester != null || it.events.isNotEmpty() || it.routines.isNotEmpty()
                            } == true,
                            sleepStatus = status,
                            sleepDescription = data.sleep?.let { sleep ->
                                SleepProtectionEngine.describeDay(today, sleep.schedule, sleep.overrides)
                            },
                            nextExam = data.exams?.let { ExamCountdownCalculator.next(it.exams, today) },
                            vocabularyDue = data.vocabulary?.dueWords,
                            exerciseReviewDue = reviews,
                            nextClass = nextCourse?.let { block ->
                                NextClassInfo(
                                    title = block.title,
                                    detail = block.subtitle,
                                    startMinute = block.startMinute,
                                    endMinuteExclusive = block.endMinuteExclusive,
                                )
                            },
                            currentTask = currentTask,
                            homeCards = data.cards,
                            aiSuggestion = suggestion,
                        )
                    }
                    .catch {
                        emit(
                            currentBase.copy(
                                isLoading = false,
                                errorMessage = "无法读取本地任务，请重试。",
                            ),
                        )
                    }
            }
        }
        .catch {
            emit(baseState.copy(isLoading = false, errorMessage = "无法读取本地任务，请重试。"))
        }

    val uiState: StateFlow<HomeUiState> = contentState
        .combine(actionNotice) { state, notice -> state.copy(notice = notice) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = baseState,
        )

    fun retry() {
        refresh.value += 1
    }

    fun setTaskCompleted(taskId: Long, completed: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setTaskCompleted(taskId, completed) }
                .onFailure { actionNotice.value = it.message ?: "更新任务失败" }
        }
    }

    fun consumeNotice() {
        actionNotice.value = null
    }

    private fun financeBudgetFlow(today: LocalDate): Flow<BudgetLoad> {
        val repository = financeRepository ?: return flowOf(BudgetLoad())
        val summaryFlow: Flow<BudgetLoad> = repository.observeSnapshot().map { snapshot ->
            BudgetLoad(
                summary = FinanceCalculator.monthSummary(snapshot, YearMonth.from(today), today),
            )
        }
        return summaryFlow.catch { emit(BudgetLoad(failed = true)) }
    }

    private fun buildSuggestion(
        tasks: List<StudyTask>,
        sleepStatus: SleepStatus?,
        vocabulary: VocabularySummary?,
        exerciseReviewDue: Int?,
    ): String {
        if (sleepStatus != null && sleepStatus.phase == SleepPhase.WIND_DOWN) {
            return "进入睡前缓冲：结束当前学习会话，整理明天的少量计划即可。"
        }
        if (sleepStatus != null && sleepStatus.phase == SleepPhase.ASLEEP) {
            return "现在是睡眠保护时段，学习任务不会排入这段时间。"
        }
        if (tasks.any { it.status == TaskStatus.IN_PROGRESS }) {
            return "当前学习进行中。完成这一小段后，再决定是否继续。"
        }
        if (vocabulary != null && vocabulary.dueWords > 0) {
            return "今天有 ${vocabulary.dueWords} 个英语单词到期复习，可在“学习 → 词汇”开始。"
        }
        if (exerciseReviewDue != null && exerciseReviewDue > 0) {
            return "有 $exerciseReviewDue 道习题需要巩固；先独立尝试，再按需查看提示。"
        }
        if (tasks.isEmpty()) {
            return "今天还没有任务。先确定一个可完成的小目标，再安排时间。"
        }
        return "优先完成最重要的任务，并为复盘留出一点时间。"
    }

    private fun createBaseState(today: LocalDate, zoneId: ZoneId): HomeUiState = HomeUiState(
        dateLabel = today.format(
            DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE),
        ),
        greeting = when (LocalTime.now(clock.withZone(zoneId)).hour) {
            in 5..10 -> "早上好，保持从容"
            in 11..13 -> "中午好，稍作休整"
            in 14..17 -> "下午好，专注当下"
            in 18..22 -> "晚上好，稳步前进"
            else -> "夜深了，注意休息"
        },
    )
}
