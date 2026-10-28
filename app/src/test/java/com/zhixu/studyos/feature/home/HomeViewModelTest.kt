package com.zhixu.studyos.feature.home

import com.zhixu.studyos.core.data.repository.HomeRepository
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-24T15:00:00Z"), zoneId)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyTasks_showsRealEmptyState() = runTest {
        val repository = FakeHomeRepository()
        val viewModel = HomeViewModel(repository, clock)
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(emptyList<StudyTask>(), state.tasks)
        assertEquals(0f, state.progress)
        assertEquals("10月24日 星期六", state.dateLabel)
        assertNull(state.errorMessage)
        assertEquals(LocalDate.of(2026, 10, 24).toEpochDay(), repository.requestedEpochDay)
        collection.cancel()
    }

    @Test
    fun tasks_calculateProgressAndPlannedMinutes() = runTest {
        val repository = FakeHomeRepository(
            listOf(
                task(id = 1, title = "数学分析习题", start = 9 * 60, end = 10 * 60, completed = true),
                task(id = 2, title = "英语阅读", start = 10 * 60, end = 10 * 60 + 30, completed = false),
            ),
        )
        val viewModel = HomeViewModel(repository, clock)
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.tasks.size)
        assertEquals(1, state.completedTaskCount)
        assertEquals(0.5f, state.progress)
        assertEquals(90, state.plannedMinutes)
        collection.cancel()
    }

    @Test
    fun readFailure_canRetryAndRecover() = runTest {
        var subscriptions = 0
        val repository = object : HomeRepository {
            override fun observeTasks(epochDay: Long): Flow<List<StudyTask>> {
                subscriptions += 1
                return if (subscriptions == 1) {
                    flow { throw IllegalStateException("read failed") }
                } else {
                    flowOf(emptyList())
                }
            }

            override suspend fun setTaskCompleted(taskId: Long, completed: Boolean) = Unit
        }
        val viewModel = HomeViewModel(repository, clock)
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()
        assertEquals("无法读取本地任务，请重试。", viewModel.uiState.value.errorMessage)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, subscriptions)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
        collection.cancel()
    }

    private class FakeHomeRepository(
        tasks: List<StudyTask> = emptyList(),
    ) : HomeRepository {
        private val tasksFlow = MutableStateFlow(tasks)
        var requestedEpochDay: Long? = null

        override fun observeTasks(epochDay: Long): Flow<List<StudyTask>> {
            requestedEpochDay = epochDay
            return tasksFlow
        }

        override suspend fun setTaskCompleted(taskId: Long, completed: Boolean) = Unit
    }

    private fun task(
        id: Long,
        title: String,
        start: Int,
        end: Int,
        completed: Boolean,
    ) = StudyTask(
        id = id,
        title = title,
        estimatedMinutes = end - start,
        status = if (completed) TaskStatus.COMPLETED else TaskStatus.SCHEDULED,
        completedAt = if (completed) Instant.parse("2026-10-24T04:00:00Z") else null,
        sortOrder = id.toInt(),
        sessions = listOf(
            TaskSession(
                date = LocalDate.of(2026, 10, 24),
                startMinute = start,
                endMinuteExclusive = end,
            ),
        ),
    )
}
