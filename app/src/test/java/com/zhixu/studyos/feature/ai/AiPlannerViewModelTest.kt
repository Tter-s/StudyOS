package com.zhixu.studyos.feature.ai

import com.zhixu.studyos.core.ai.AiAnalysisOutcome
import com.zhixu.studyos.core.ai.AiPlanCoordinator
import com.zhixu.studyos.core.ai.AiPlanConstraintChecker
import com.zhixu.studyos.core.ai.AiSnapshotBuilder
import com.zhixu.studyos.core.ai.AiStudyDataSource
import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.data.preferences.AiConfigStore
import com.zhixu.studyos.core.data.repository.AiRepository
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.model.AiCachedAnalysis
import com.zhixu.studyos.core.model.AiDayStat
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiGoalSummary
import com.zhixu.studyos.core.model.AiPlan
import com.zhixu.studyos.core.model.AiPlanRecord
import com.zhixu.studyos.core.model.AiPlanReview
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiProviderException
import com.zhixu.studyos.core.model.AiSchedulingSummary
import com.zhixu.studyos.core.model.AiStudySnapshot
import com.zhixu.studyos.core.model.AiSubjectStat
import com.zhixu.studyos.core.model.AiTaskEstimate
import com.zhixu.studyos.core.model.AiTaskStat
import com.zhixu.studyos.core.model.AiUsage
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskSaveResult
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiPlannerViewModelTest {

    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), zone)
    private val today = LocalDate.of(2026, 10, 25)

    private val enabledConfig = AiProviderConfig(
        enabled = true,
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        dailyCallLimit = 5,
        dailyCostLimitMicros = 0,
    )

    private fun task(id: Long, title: String, minutes: Int = 60) = StudyTask(
        id = id,
        title = title,
        subject = "数学分析",
        estimatedMinutes = minutes,
        status = com.zhixu.studyos.core.model.TaskStatus.BACKLOG,
    )

    private fun snapshot(generatedAt: Instant = Instant.parse("2026-10-25T08:00:00Z")) = AiStudySnapshot(
        generatedAt = generatedAt,
        windowStart = LocalDate.of(2026, 10, 19),
        windowEnd = today,
        subjects = listOf(AiSubjectStat("数学分析", 120, 2, 3)),
        recentDays = (0 until 7).map { AiDayStat(LocalDate.of(2026, 10, 19).plusDays(it.toLong()), 20, 0) },
        upcomingTasks = listOf(
            AiTaskStat(42, "数学分析", "任务A", 60, null, "MEDIUM", 0, 0),
            AiTaskStat(43, "数学分析", "任务B", 60, null, "MEDIUM", 0, 0),
        ),
        difficultProblems = emptyList(),
        goals = AiGoalSummary(null, null, null),
        scheduling = AiSchedulingSummary(240, 480, 1320, 10),
    )

    private fun review(
        estimates: List<AiTaskEstimate> = listOf(
            AiTaskEstimate(42, 75, "题目多"),
            AiTaskEstimate(43, 30, "较简单"),
        ),
    ): AiPlanReview = AiPlanConstraintChecker.check(
        plan = AiPlan(summary = "本周建议", taskEstimates = estimates),
        upcomingTasks = snapshot().upcomingTasks,
        knownSubjects = setOf("数学分析"),
        dailyLimitMinutes = 240,
        subjectNamesAvailable = true,
    )

    private class FakeConfigStore(initial: AiProviderConfig) : AiConfigStore {
        val flow = MutableStateFlow(initial)
        var lastPrivacy: Triple<Boolean, Boolean, Boolean>? = null
        override val config: Flow<AiProviderConfig> = flow
        override suspend fun getConfig(): AiProviderConfig = flow.value
        override suspend fun saveConfig(config: AiProviderConfig) {
            flow.value = config
        }

        override suspend fun updatePrivacy(
            includeTaskTitles: Boolean,
            includeSubjectNames: Boolean,
            includeProblemStatements: Boolean,
        ) {
            lastPrivacy = Triple(includeTaskTitles, includeSubjectNames, includeProblemStatements)
            flow.value = flow.value.copy(
                includeTaskTitles = includeTaskTitles,
                includeSubjectNames = includeSubjectNames,
                includeProblemStatements = includeProblemStatements,
            )
        }
    }

    private class FakeKeyStore(var key: String? = "sk-test") : ApiKeyStore {
        override suspend fun save(key: String) {
            this.key = key
        }

        override suspend fun load(): String? = key
        override suspend fun clear() {
            key = null
        }

        override suspend fun hasKey(): Boolean = key != null
    }

    private class FakeStudyDataSource(var value: AiStudySnapshot) : AiStudyDataSource {
        override suspend fun load(today: LocalDate): AiStudySnapshot = value
    }

    private class FakeRepository : AiRepository {
        val usageFlow = MutableStateFlow<List<AiUsage>>(emptyList())
        val recordFlow = MutableStateFlow<List<AiPlanRecord>>(emptyList())
        override suspend fun getUsage(date: LocalDate): AiUsage = AiUsage(date)
        override fun observeUsage(limit: Int): Flow<List<AiUsage>> = usageFlow
        override suspend fun recordUsage(
            date: LocalDate,
            promptTokens: Int,
            completionTokens: Int,
            costMicros: Long,
        ): AiUsage = AiUsage(date)

        override suspend fun findCache(cacheKey: String, maxAgeHours: Long): AiCachedAnalysis? = null
        override suspend fun saveCache(cached: AiCachedAnalysis) = Unit
        override suspend fun pruneCache(olderThanDays: Long) = Unit
        override fun observePlanRecords(limit: Int): Flow<List<AiPlanRecord>> = recordFlow
        override suspend fun savePlanRecord(record: AiPlanRecord): Long = 1L
    }

    private class FakeCoordinator(
        var outcome: AiAnalysisOutcome? = null,
        var failure: Throwable? = null,
        val recorded: MutableList<Triple<AiAnalysisOutcome, Int, Boolean>> = mutableListOf(),
    ) : AiPlanCoordinator {
        var prepareCalls = 0
        var lastForceRefresh: Boolean? = null

        override suspend fun prepare(
            config: AiProviderConfig,
            snapshot: AiStudySnapshot,
            forceRefresh: Boolean,
        ): AiAnalysisOutcome {
            prepareCalls += 1
            lastForceRefresh = forceRefresh
            failure?.let { throw it }
            return requireNotNull(outcome)
        }

        override suspend fun useCache(
            cacheKey: String,
            snapshot: AiStudySnapshot,
            config: AiProviderConfig,
        ): AiAnalysisOutcome.Cached {
            failure?.let { throw it }
            return outcome as AiAnalysisOutcome.Cached
        }

        override fun cacheKeyFor(config: AiProviderConfig, snapshot: AiStudySnapshot): String =
            "key-${snapshot.generatedAt}-${config.model}-${config.includeTaskTitles}"

        override suspend fun findCached(config: AiProviderConfig, snapshot: AiStudySnapshot): AiCachedAnalysis? = null

        override suspend fun recordConfirmedPlan(
            outcome: AiAnalysisOutcome,
            appliedTaskCount: Int,
            usedCache: Boolean,
        ): Long {
            recorded += Triple(outcome, appliedTaskCount, usedCache)
            return 1L
        }
    }

    private class FakeTaskRepository : TaskRepository {
        val tasks = mutableMapOf<Long, StudyTask>()
        val saved = mutableListOf<StudyTask>()

        override fun observeAllTasks(): Flow<List<StudyTask>> = MutableStateFlow(tasks.values.toList())
        override suspend fun saveTask(task: StudyTask): TaskSaveResult {
            saved += task
            tasks[task.id] = task
            return TaskSaveResult(listOf(task.id))
        }

        override suspend fun getTask(taskId: Long): StudyTask? = tasks[taskId]
        override fun observeTasks(epochDay: Long): Flow<List<StudyTask>> = MutableStateFlow(emptyList())
        override suspend fun setTaskCompleted(taskId: Long, completed: Boolean) = Unit
        override suspend fun deleteTask(taskId: Long) = Unit
        override suspend fun deleteSeries(seriesId: String) = Unit
        override suspend fun startTask(taskId: Long) = Unit
        override suspend fun pauseTask(taskId: Long) = Unit
        override suspend fun postponeTask(taskId: Long, days: Long): StudyTask = throw UnsupportedOperationException()
        override suspend fun reorderTasks(orderedTaskIds: List<Long>) = Unit
        override suspend fun setSessionLocked(taskId: Long, sessionId: Long, locked: Boolean) = Unit
    }

    private fun viewModel(
        configStore: FakeConfigStore = FakeConfigStore(enabledConfig),
        coordinator: FakeCoordinator = FakeCoordinator(),
        dataSource: FakeStudyDataSource = FakeStudyDataSource(snapshot()),
        taskRepository: FakeTaskRepository = FakeTaskRepository(),
    ) = AiPlannerViewModel(
        repository = FakeRepository(),
        planningService = coordinator,
        settingsRepository = configStore,
        keyStore = FakeKeyStore(),
        studyDataSource = dataSource,
        snapshotBuilder = AiSnapshotBuilder(clock, zone),
        taskRepository = taskRepository,
        clock = clock,
        zoneId = zone,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsSnapshotPayloadAndKeyState() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.hasApiKey)
        assertNotNull(state.snapshot)
        assertTrue(state.payloadPreview.contains("\"taskId\":42") || state.payloadPreview.contains("\"id\":42"))
        assertEquals(2, state.snapshot!!.upcomingTasks.size)
        assertNotNull(state.currentCacheKey)
        assertTrue(state.privacySummary.isNotBlank())
    }

    @Test
    fun requestAnalysis_populatesReviewAndSelectsAllAccepted() = runTest {
        val coordinator = FakeCoordinator(outcome = freshOutcome(review()))
        val vm = viewModel(coordinator = coordinator)
        advanceUntilIdle()

        vm.requestAnalysis(forceRefresh = true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, coordinator.prepareCalls)
        assertEquals(true, coordinator.lastForceRefresh)
        assertNotNull(state.review)
        assertEquals(setOf(42L, 43L), state.selectedTaskIds)
        assertEquals(75, state.review!!.acceptedTaskEstimates.first().estimatedMinutes)
    }

    @Test
    fun confirmAndApply_appliesOnlySelectedEstimatesAndRecordsPlan() = runTest {
        val coordinator = FakeCoordinator(outcome = freshOutcome(review()))
        val taskRepository = FakeTaskRepository().apply {
            tasks[42L] = task(42, "任务A")
            tasks[43L] = task(43, "任务B")
        }
        val vm = viewModel(coordinator = coordinator, taskRepository = taskRepository)
        advanceUntilIdle()
        vm.requestAnalysis(forceRefresh = true)
        advanceUntilIdle()

        vm.toggleTaskEstimate(43)
        vm.confirmAndApply()
        advanceUntilIdle()

        assertEquals(listOf(42L), taskRepository.saved.map { it.id })
        assertEquals(75, taskRepository.saved.single().estimatedMinutes)
        assertEquals(1, coordinator.recorded.size)
        assertEquals(1, coordinator.recorded.single().second)
        assertTrue(vm.uiState.value.notice!!.contains("更新 1 个任务"))
        assertNull(vm.uiState.value.review)
    }

    @Test
    fun confirmAndApply_withNoSelection_onlySavesPlan() = runTest {
        val coordinator = FakeCoordinator(outcome = freshOutcome(review()))
        val taskRepository = FakeTaskRepository().apply {
            tasks[42L] = task(42, "任务A")
            tasks[43L] = task(43, "任务B")
        }
        val vm = viewModel(coordinator = coordinator, taskRepository = taskRepository)
        advanceUntilIdle()
        vm.requestAnalysis(forceRefresh = true)
        advanceUntilIdle()

        vm.toggleTaskEstimate(42)
        vm.toggleTaskEstimate(43)
        vm.confirmAndApply()
        advanceUntilIdle()

        assertTrue(taskRepository.saved.isEmpty())
        assertEquals(0, coordinator.recorded.single().second)
        assertTrue(vm.uiState.value.notice!!.contains("已保存建议"))
    }

    @Test
    fun dataChange_clearsStaleReview() = runTest {
        val coordinator = FakeCoordinator(outcome = freshOutcome(review()))
        val dataSource = FakeStudyDataSource(snapshot())
        val vm = viewModel(coordinator = coordinator, dataSource = dataSource)
        advanceUntilIdle()
        vm.requestAnalysis(forceRefresh = true)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.review)

        dataSource.value = snapshot(generatedAt = Instant.parse("2026-10-25T09:00:00Z"))
        vm.refreshSnapshot()
        advanceUntilIdle()

        assertNull(vm.uiState.value.review)
        assertTrue(vm.uiState.value.selectedTaskIds.isEmpty())
    }

    @Test
    fun privacyChange_persistsAndRefreshesPayload() = runTest {
        val configStore = FakeConfigStore(enabledConfig)
        val vm = viewModel(configStore = configStore)
        advanceUntilIdle()

        vm.setPrivacy(includeTaskTitles = false, includeSubjectNames = true, includeProblemStatements = false)
        advanceUntilIdle()

        assertEquals(Triple(false, true, false), configStore.lastPrivacy)
        assertTrue(vm.uiState.value.payloadPreview.contains("\"taskTitlesMasked\":true"))
    }

    @Test
    fun failure_mapsToErrorKind() = runTest {
        val coordinator = FakeCoordinator(
            failure = AiProviderException(AiFailureKind.OFFLINE, "当前处于离线状态"),
        )
        val vm = viewModel(coordinator = coordinator)
        advanceUntilIdle()

        vm.requestAnalysis(forceRefresh = true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AiFailureKind.OFFLINE, state.errorKind)
        assertTrue(state.errorMessage!!.contains("离线"))
        assertNull(state.review)
    }

    @Test
    fun cachedOutcome_isMarkedFromCache() = runTest {
        val cached = AiAnalysisOutcome.Cached(
            review = review(),
            planJson = "{}",
            model = "test-model",
            costMicros = 900,
            cachedAt = Instant.parse("2026-10-24T08:00:00Z"),
        )
        val coordinator = FakeCoordinator(outcome = cached)
        val vm = viewModel(coordinator = coordinator)
        advanceUntilIdle()

        vm.useCachedResult()
        advanceUntilIdle()

        val meta = vm.uiState.value.outcomeMeta
        assertNotNull(meta)
        assertTrue(meta!!.fromCache)
        assertEquals(0, meta.promptTokens)
    }

    private fun freshOutcome(review: AiPlanReview) = AiAnalysisOutcome.Fresh(
        review = review,
        planJson = "{}",
        model = "test-model",
        costMicros = 900,
        promptTokens = 500,
        completionTokens = 200,
        tokensEstimated = false,
        latencyMillis = 800,
        usageToday = AiUsage(today, requestCount = 1),
    )
}
