package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OfflineTaskRepositoryTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), zoneId)
    private val date = LocalDate.of(2026, 10, 26)
    private lateinit var database: StudyOSDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = OfflineTaskRepository(database, database.studyTaskDao(), clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun taskLifecycle_persistsSessionsCompletionPostponeAndDeletion() = runTest {
        val created = repository.saveTask(task(sessions = listOf(session(date, 19 * 60, 20 * 60))))
        val taskId = created.createdTaskIds.single()
        val initial = repository.observeAllTasks().first().single()

        assertEquals(TaskStatus.SCHEDULED, initial.status)
        assertEquals(1, initial.sessions.size)

        repository.setTaskCompleted(taskId, true)
        val completed = repository.observeAllTasks().first().single()
        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertNotNull(completed.completedAt)

        repository.setTaskCompleted(taskId, false)
        val reopened = repository.observeAllTasks().first().single()
        assertEquals(TaskStatus.SCHEDULED, reopened.status)
        assertNull(reopened.completedAt)

        val postponed = repository.postponeTask(taskId)
        assertEquals(date.plusDays(1), postponed.sessions.single().date)
        assertEquals(TaskStatus.SCHEDULED, postponed.status)
        assertTrue(postponed.sessions.all { it.isLocked })

        repository.startTask(taskId)
        assertEquals(TaskStatus.IN_PROGRESS, repository.observeAllTasks().first().single().status)
        repository.pauseTask(taskId)
        assertEquals(TaskStatus.SCHEDULED, repository.observeAllTasks().first().single().status)

        repository.deleteTask(taskId)
        assertTrue(repository.observeAllTasks().first().isEmpty())
    }

    @Test
    fun recurringTask_createsStableSeriesAndDeleteSeriesRemovesAll() = runTest {
        val created = repository.saveTask(
            task(
                recurrence = TaskRecurrence.DAILY,
                recurrenceUntil = date.plusDays(2),
                sessions = listOf(session(date, 8 * 60, 9 * 60)),
            ),
        )

        val tasks = repository.observeAllTasks().first()
        assertEquals(3, created.createdTaskIds.size)
        assertEquals(3, tasks.size)
        assertEquals(1, tasks.mapNotNull { it.seriesId }.distinct().size)
        assertEquals(
            listOf(date, date.plusDays(1), date.plusDays(2)),
            tasks.map { it.sessions.single().date }.sorted(),
        )

        repository.deleteSeries(requireNotNull(tasks.first().seriesId))
        assertTrue(repository.observeAllTasks().first().isEmpty())
    }

    @Test
    fun backlogTaskCanBeScheduledAndReordered() = runTest {
        val firstId = repository.saveTask(task(sessions = emptyList())).createdTaskIds.single()
        val secondId = repository.saveTask(task(sessions = listOf(session(date, 20 * 60, 21 * 60)))).createdTaskIds.single()

        val backlog = repository.observeAllTasks().first().single { it.id == firstId }
        assertEquals(TaskStatus.BACKLOG, backlog.status)

        repository.saveTask(backlog.copy(sessions = listOf(session(date, 9 * 60, 10 * 60))))
        assertEquals(TaskStatus.SCHEDULED, repository.observeAllTasks().first().single { it.id == firstId }.status)

        repository.reorderTasks(listOf(secondId, firstId))
        val reordered = repository.observeAllTasks().first()
        assertEquals(listOf(secondId, firstId), reordered.map { it.id })
    }

    private fun task(
        recurrence: TaskRecurrence = TaskRecurrence.NONE,
        recurrenceUntil: LocalDate? = null,
        sessions: List<TaskSession>,
    ) = StudyTask(
        title = "数学分析习题",
        description = "完成教材第 3 章",
        subject = "数学分析",
        estimatedMinutes = 60,
        status = if (sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED,
        recurrence = recurrence,
        recurrenceUntil = recurrenceUntil,
        sessions = sessions,
    )

    private fun session(date: LocalDate, start: Int, end: Int) = TaskSession(
        date = date,
        startMinute = start,
        endMinuteExclusive = end,
    )
}
