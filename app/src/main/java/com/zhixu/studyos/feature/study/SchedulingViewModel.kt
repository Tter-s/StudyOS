package com.zhixu.studyos.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.PlannedSession
import com.zhixu.studyos.core.data.repository.ScheduleRepository
import com.zhixu.studyos.core.data.repository.SchedulingRepository
import com.zhixu.studyos.core.data.repository.SleepRepository
import com.zhixu.studyos.core.data.repository.SchedulingSaveRequest
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.model.PRESET_SUBJECTS
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.reminder.PlanReminderScheduler
import com.zhixu.studyos.core.scheduling.AutoScheduler
import com.zhixu.studyos.core.scheduling.SchedulingInput
import com.zhixu.studyos.core.scheduling.SchedulingPreview
import com.zhixu.studyos.core.scheduling.SchedulingPreviewEditor
import com.zhixu.studyos.core.sleep.SleepInterval
import com.zhixu.studyos.core.sleep.SleepProtectionEngine
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchedulingUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val settings: SchedulingSettings = SchedulingSettings.Default,
    val subjectPreferences: Map<String, SubjectPreference> = emptyMap(),
    val tasks: List<StudyTask> = emptyList(),
    val schedule: ScheduleSnapshot = ScheduleSnapshot(),
    val sleepIntervals: Map<LocalDate, List<SleepInterval>> = emptyMap(),
    val subjectOptions: List<String> = emptyList(),
    val horizonDays: Int = 1,
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val preview: SchedulingPreview? = null,
    val previewStale: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
)

/**
 * 把页面状态转换为排程引擎输入。
 *
 * 单独抽出来是为了让「睡眠保护是否真的传入 AutoScheduler」有可测试的接线点；
 * 之前这里漏传 [SchedulingUiState.sleepIntervals] 时，引擎单测仍然全绿。
 */
internal fun SchedulingUiState.toSchedulingInput(
    today: LocalDate,
    nowMinuteOfDay: Int,
): SchedulingInput = SchedulingInput(
    today = today,
    nowMinuteOfDay = nowMinuteOfDay,
    horizonDays = horizonDays,
    schedule = schedule,
    tasks = tasks,
    settings = settings,
    subjectPreferences = subjectPreferences,
    zoneId = zoneId,
    sleepIntervals = sleepIntervals,
)

private data class SchedulingTaskLoad(
    val tasks: List<StudyTask> = emptyList(),
    val errorMessage: String? = null,
)

private data class SchedulingScheduleLoad(
    val snapshot: ScheduleSnapshot = ScheduleSnapshot(),
    val errorMessage: String? = null,
)

private data class SchedulingData(
    val tasks: List<StudyTask> = emptyList(),
    val schedule: ScheduleSnapshot = ScheduleSnapshot(),
    val settings: SchedulingSettings = SchedulingSettings.Default,
    val preferences: Map<String, SubjectPreference> = emptyMap(),
    val sleepIntervals: Map<LocalDate, List<SleepInterval>> = emptyMap(),
    val errorMessage: String? = null,
)

private data class PreviewState(
    val preview: SchedulingPreview,
    val signature: String,
)

private data class ActionState(
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingViewModel(
    private val taskRepository: TaskRepository,
    private val scheduleRepository: ScheduleRepository,
    private val schedulingRepository: SchedulingRepository,
    private val sleepRepository: SleepRepository,
    private val planReminderScheduler: PlanReminderScheduler,
    private val clock: Clock,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val horizonDays = MutableStateFlow(1)
    private val preview = MutableStateFlow<PreviewState?>(null)
    private val actions = MutableStateFlow(ActionState())
    private val todayRefresh = MutableStateFlow(0)

    private val taskLoad = refresh.flatMapLatest {
        taskRepository.observeAllTasks()
            .map { SchedulingTaskLoad(tasks = it) }
            .catch { emit(SchedulingTaskLoad(errorMessage = "无法读取本地任务，请重试。")) }
    }
    private val scheduleLoad = refresh.flatMapLatest {
        scheduleRepository.observeSnapshot()
            .map { SchedulingScheduleLoad(snapshot = it) }
            .catch { emit(SchedulingScheduleLoad(errorMessage = "课表读取失败，无法可靠排程。")) }
    }
    private val settingsFlow = schedulingRepository.observeSettings()
        .catch { emit(SchedulingSettings.Default) }
    private val preferencesFlow = schedulingRepository.observeSubjectPreferences()
        .catch { emit(emptyMap()) }
    private val sleepFlow = sleepRepository.observeSnapshot()
        .catch { emit(com.zhixu.studyos.core.data.repository.SleepSnapshot()) }

    private val dataFlow = combine(
        taskLoad,
        scheduleLoad,
        settingsFlow,
        preferencesFlow,
        sleepFlow,
    ) { tasks, schedule, settings, preferences, sleep ->
        val zone = schedule.snapshot.semester?.zoneId ?: clock.zone
        val today = LocalDate.now(clock.withZone(zone))
        val intervals = (0..6).associate { offset ->
            val date = today.plusDays(offset.toLong())
            date to SleepProtectionEngine.intervalsOn(date, sleep.schedule, sleep.overrides)
        }
        SchedulingData(
            tasks = tasks.tasks,
            schedule = schedule.snapshot,
            settings = settings,
            preferences = preferences,
            sleepIntervals = intervals,
            errorMessage = tasks.errorMessage ?: schedule.errorMessage,
        )
    }

    val uiState: StateFlow<SchedulingUiState> = combine(
        dataFlow,
        horizonDays,
        preview,
        actions,
        todayRefresh,
    ) { data, horizon, previewState, action, _ ->
        val zoneId = data.schedule.semester?.zoneId ?: clock.zone
        val today = LocalDate.now(clock.withZone(zoneId))
        val signature = signatureFor(
            tasks = data.tasks,
            schedule = data.schedule,
            settings = data.settings,
            preferences = data.preferences,
            sleepIntervals = data.sleepIntervals,
            horizonDays = horizon,
            today = today,
        )
        SchedulingUiState(
            isLoading = false,
            today = today,
            zoneId = zoneId,
            settings = data.settings,
            subjectPreferences = data.preferences,
            tasks = data.tasks,
            schedule = data.schedule,
            sleepIntervals = data.sleepIntervals,
            subjectOptions = subjectOptions(data.tasks, data.preferences),
            horizonDays = horizon,
            isGenerating = action.isGenerating,
            isSaving = action.isSaving,
            preview = previewState?.preview,
            previewStale = previewState != null && previewState.signature != signature,
            errorMessage = data.errorMessage,
            notice = action.notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SchedulingUiState(
            today = LocalDate.now(clock),
            zoneId = clock.zone,
        ),
    )

    init {
        viewModelScope.launch {
            runCatching { schedulingRepository.getSettings() }
                .onSuccess { settings ->
                    runCatching {
                        planReminderScheduler.sync(
                            enabled = settings.planReminderEnabled,
                            hour = settings.planReminderHour,
                            minute = settings.planReminderMinute,
                            replaceExisting = false,
                        )
                    }
                }
        }
    }

    fun retry() {
        preview.value = null
        refresh.value += 1
    }

    fun refreshToday() {
        todayRefresh.value += 1
    }

    fun setHorizon(days: Int) {
        val normalized = if (days == 7) 7 else 1
        if (horizonDays.value == normalized) return
        horizonDays.value = normalized
        if (preview.value != null) {
            preview.value = null
            actions.update { it.copy(notice = "排程范围已切换，请重新生成计划") }
        }
    }

    fun generate() {
        val state = uiState.value
        if (state.isLoading || state.errorMessage != null || actions.value.isGenerating) return
        val today = LocalDate.now(clock.withZone(state.zoneId))
        val nowMinute = nowMinuteOf(state.zoneId)
        val signature = signatureFor(
            tasks = state.tasks,
            schedule = state.schedule,
            settings = state.settings,
            preferences = state.subjectPreferences,
            sleepIntervals = state.sleepIntervals,
            horizonDays = state.horizonDays,
            today = today,
        )
        val input = state.toSchedulingInput(
            today = today,
            nowMinuteOfDay = nowMinute,
        )
        actions.update { it.copy(isGenerating = true, notice = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) { AutoScheduler.schedule(input) }
            }.onSuccess { result ->
                val previewValue = SchedulingPreviewEditor.fromResult(result)
                preview.value = PreviewState(preview = previewValue, signature = signature)
                actions.update {
                    it.copy(
                        isGenerating = false,
                        notice = if (result.taskPlans.isEmpty()) {
                            "当前没有需要安排的学习任务"
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                actions.update {
                    it.copy(
                        isGenerating = false,
                        notice = error.message?.takeIf(String::isNotBlank) ?: "生成计划失败",
                    )
                }
            }
        }
    }

    fun toggleBlockLock(blockId: Long) {
        val current = preview.value ?: return
        preview.value = current.copy(
            preview = SchedulingPreviewEditor.toggleLock(current.preview, blockId),
        )
    }

    fun adjustBlock(
        blockId: Long,
        date: LocalDate,
        startMinute: Int,
        endMinuteExclusive: Int,
        onResult: (String?) -> Unit,
    ) {
        val current = preview.value ?: run {
            onResult("当前没有可调整的排程预览")
            return
        }
        val state = uiState.value
        val today = LocalDate.now(clock.withZone(state.zoneId))
        val outcome = SchedulingPreviewEditor.adjustBlock(
            preview = current.preview,
            blockId = blockId,
            date = date,
            startMinute = startMinute,
            endMinuteExclusive = endMinuteExclusive,
            today = today,
            nowMinuteOfDay = nowMinuteOf(state.zoneId),
        )
        if (outcome.error != null || outcome.preview == null) {
            onResult(outcome.error ?: "无法调整该时段")
            return
        }
        val adjusted = outcome.preview.blocks.firstOrNull { it.id == blockId }
            ?: run {
                onResult("无法调整该时段")
                return
            }
        val sleepOverlap = state.sleepIntervals[adjusted.date].orEmpty().any {
            adjusted.startMinute < it.endMinuteExclusive && it.startMinute < adjusted.endMinuteExclusive
        }
        if (sleepOverlap) {
            onResult("该时段与睡眠保护重叠；睡眠是排程硬约束，请换一个时间或在睡眠设置中明确调整作息")
            return
        }
        val task = state.tasks.firstOrNull { it.id == adjusted.taskId }
        val latestDate = task?.dueDate?.let { maxOf(it, today) }
        if (latestDate != null && adjusted.date.isAfter(latestDate)) {
            onResult("该时段晚于任务截止日期 $latestDate，不能保存")
            return
        }
        val fixedMinutes = current.preview.result.days
            .firstOrNull { it.date == adjusted.date }
            ?.fixedStudyMinutes
            ?: 0
        val otherMinutes = current.preview.blocks
            .filter { it.date == adjusted.date && it.id != blockId }
            .sumOf { it.durationMinutes }
        val dayTotal = fixedMinutes + otherMinutes + adjusted.durationMinutes
        if (dayTotal > state.settings.dailyLimitMinutes) {
            onResult("调整后当天学习 ${dayTotal} 分钟，超过每日上限 ${state.settings.dailyLimitMinutes} 分钟")
            return
        }
        preview.value = current.copy(preview = outcome.preview)
        actions.update { it.copy(notice = "时段已调整并锁定，保存后生效") }
        onResult(null)
    }

    fun removeBlock(blockId: Long) {
        val current = preview.value ?: return
        preview.value = current.copy(
            preview = SchedulingPreviewEditor.removeBlock(current.preview, blockId),
        )
        actions.update { it.copy(notice = "时段已从预览移除，保存后生效") }
    }

    fun savePlan() {
        val current = preview.value ?: return
        val state = uiState.value
        if (actions.value.isSaving) return
        val today = LocalDate.now(clock.withZone(state.zoneId))
        val nowMinute = nowMinuteOf(state.zoneId)
        val currentSignature = signatureFor(
            tasks = state.tasks,
            schedule = state.schedule,
            settings = state.settings,
            preferences = state.subjectPreferences,
            sleepIntervals = state.sleepIntervals,
            horizonDays = state.horizonDays,
            today = today,
        )
        if (current.signature != currentSignature ||
            current.preview.result.horizonStart != today
        ) {
            actions.update { it.copy(notice = "任务、课表、日期或时间已变化，请重新生成计划") }
            return
        }
        if (current.preview.blocks.isEmpty()) {
            actions.update { it.copy(notice = "没有可保存的学习时段") }
            return
        }
        val pastBlock = current.preview.blocks.firstOrNull { block ->
            block.date.isBefore(today) || (block.date == today && block.startMinute < nowMinute)
        }
        if (pastBlock != null) {
            actions.update { it.copy(notice = "预览中有已经过去的时段，请重新生成计划") }
            return
        }
        val invalidBlock = current.preview.blocks.firstOrNull { block ->
            val task = state.tasks.firstOrNull { it.id == block.taskId }
            val latestDate = task?.dueDate?.let { maxOf(it, today) }
            latestDate != null && block.date.isAfter(latestDate)
        }
        if (invalidBlock != null) {
            actions.update { it.copy(notice = "预览中有晚于任务截止日期的时段，请重新生成或调整") }
            return
        }
        val overLimitDay = current.preview.blocks.groupBy { it.date }.entries.firstOrNull { (date, blocks) ->
            val fixed = current.preview.result.days.firstOrNull { it.date == date }?.fixedStudyMinutes ?: 0
            fixed + blocks.sumOf { it.durationMinutes } > state.settings.dailyLimitMinutes
        }
        if (overLimitDay != null) {
            actions.update { it.copy(notice = "${overLimitDay.key} 的学习安排超过每日上限，请调整") }
            return
        }
        val request = SchedulingSaveRequest(
            candidateTaskIds = current.preview.result.candidateTaskIds.toSet(),
            sessions = current.preview.blocks.map { block ->
                PlannedSession(
                    taskId = block.taskId,
                    date = block.date,
                    startMinute = block.startMinute,
                    endMinuteExclusive = block.endMinuteExclusive,
                    isLocked = block.isLocked,
                )
            },
        )
        actions.update { it.copy(isSaving = true, notice = null) }
        viewModelScope.launch {
            runCatching {
                schedulingRepository.applyPlan(
                    request = request,
                    today = today,
                    now = clock.instant(),
                )
            }.onSuccess { result ->
                preview.value = null
                actions.update {
                    it.copy(
                        isSaving = false,
                        notice = "计划已保存：${result.savedSessions} 个学习时段，" +
                            "${result.updatedTasks} 项任务",
                    )
                }
            }.onFailure { error ->
                actions.update {
                    it.copy(
                        isSaving = false,
                        notice = "保存计划失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun saveSettings(
        settings: SchedulingSettings,
        preferences: Map<String, SubjectPreference>,
        onResult: (String?) -> Unit,
    ) {
        val error = settings.validationError()
        if (error != null) {
            onResult(error)
            return
        }
        val previous = uiState.value.settings
        viewModelScope.launch {
            runCatching {
                schedulingRepository.saveConfiguration(settings, preferences, clock.instant())
            }.onSuccess {
                if (settings.planReminderEnabled != previous.planReminderEnabled ||
                    settings.planReminderHour != previous.planReminderHour ||
                    settings.planReminderMinute != previous.planReminderMinute
                ) {
                    runCatching {
                        planReminderScheduler.sync(
                            enabled = settings.planReminderEnabled,
                            hour = settings.planReminderHour,
                            minute = settings.planReminderMinute,
                            replaceExisting = true,
                        )
                    }
                }
                preview.value = null
                actions.update { it.copy(notice = "排程设置已保存，请重新生成计划") }
                onResult(null)
            }.onFailure { failure ->
                onResult(failure.message?.takeIf(String::isNotBlank) ?: "保存排程设置失败")
            }
        }
    }

    fun setPlanReminderEnabled(enabled: Boolean) {
        val state = uiState.value
        val settings = state.settings.copy(planReminderEnabled = enabled)
        viewModelScope.launch {
            runCatching {
                schedulingRepository.saveConfiguration(
                    settings,
                    state.subjectPreferences,
                    clock.instant(),
                )
            }.onSuccess {
                runCatching {
                    planReminderScheduler.sync(
                        enabled = enabled,
                        hour = settings.planReminderHour,
                        minute = settings.planReminderMinute,
                        replaceExisting = true,
                    )
                }.onFailure {
                    actions.update {
                        it.copy(notice = "提醒设置已保存，但系统任务调度失败")
                    }
                    return@onSuccess
                }
                actions.update {
                    it.copy(notice = if (enabled) "每日计划提醒已开启" else "每日计划提醒已关闭")
                }
            }.onFailure { error ->
                actions.update {
                    it.copy(notice = error.message ?: "保存提醒设置失败")
                }
            }
        }
    }

    fun setPlanReminderTime(hour: Int, minute: Int, onResult: (String?) -> Unit) {
        if (hour !in 0..23 || minute !in 0..59) {
            onResult("请输入 00:00–23:59 之间的提醒时间")
            return
        }
        val state = uiState.value
        val settings = state.settings.copy(planReminderHour = hour, planReminderMinute = minute)
        viewModelScope.launch {
            runCatching {
                schedulingRepository.saveConfiguration(
                    settings,
                    state.subjectPreferences,
                    clock.instant(),
                )
            }.onSuccess {
                if (settings.planReminderEnabled) {
                    runCatching {
                        planReminderScheduler.sync(
                            enabled = true,
                            hour = hour,
                            minute = minute,
                            replaceExisting = true,
                        )
                    }
                }
                actions.update { it.copy(notice = "提醒时间已更新") }
                onResult(null)
            }.onFailure { error ->
                onResult(error.message?.takeIf(String::isNotBlank) ?: "保存提醒时间失败")
            }
        }
    }

    fun consumeNotice() {
        actions.update { it.copy(notice = null) }
    }

    private fun subjectOptions(
        tasks: List<StudyTask>,
        preferences: Map<String, SubjectPreference>,
    ): List<String> = (PRESET_SUBJECTS.map { it.name } + tasks.map { it.subject } + preferences.keys)
        .map { it.trim() }
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun nowMinuteOf(zoneId: ZoneId): Int {
        val time = clock.instant().atZone(zoneId).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private fun signatureFor(
        tasks: List<StudyTask>,
        schedule: ScheduleSnapshot,
        settings: SchedulingSettings,
        preferences: Map<String, SubjectPreference>,
        sleepIntervals: Map<LocalDate, List<SleepInterval>>,
        horizonDays: Int,
        today: LocalDate,
    ): String = buildString {
        append(today).append('|').append(horizonDays).append('|')
        append(settings.dailyLimitMinutes).append(',')
        append(settings.bufferMinutes).append(',')
        append(settings.studyStartMinute).append(',')
        append(settings.studyEndMinuteExclusive)
        preferences.entries
            .sortedWith(compareBy { it.key.lowercase(Locale.ROOT) })
            .forEach { (subject, preference) ->
                append('|').append(subject.trim().lowercase(Locale.ROOT)).append('=').append(preference)
            }
        append('|').append(schedule)
        append('|').append(sleepIntervals.toSortedMap())
        tasks.sortedBy { it.id }.forEach { task ->
            append('\n').append(task)
        }
    }
}
