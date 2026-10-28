package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.model.TaskPriority
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OfflineSchedulingRepositoryTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), zoneId)
    private val today = LocalDate.of(2026, 10, 26)
    private lateinit var database: StudyOSDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var repository: OfflineSchedulingRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        taskRepository = OfflineTaskRepository(database, database.studyTaskDao(), clock)
        repository = OfflineSchedulingRepository(
            database = database,
            schedulingDao = database.schedulingDao(),
            taskDao = database.studyTaskDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun applyPlan_replacesAutoSessionsPreservesLocksAndResetsUnscheduledTasks() = runTest {
        val backlogId = taskRepository.saveTask(task("待安排", emptyList())).createdTaskIds.single()
        val scheduledId = taskRepository.saveTask(
            task(
                "已有计划",
                listOf(
                    session(8 * 60, 9 * 60, locked = false),
                    session(10 * 60, 11 * 60, locked = true),
                ),
            ),
        ).createdTaskIds.single()
        val clearedId = taskRepository.saveTask(
            task("将被清除", listOf(session(13 * 60, 14 * 60, locked = false))),
        ).createdTaskIds.single()

        val result = repository.applyPlan(
            request = SchedulingSaveRequest(
                candidateTaskIds = setOf(backlogId, scheduledId, clearedId),
                sessions = listOf(
                    PlannedSession(
                        taskId = backlogId,
                        date = today,
                        startMinute = 9 * 60,
                        endMinuteExclusive = 10 * 60,
                        isLocked = false,
                    ),
                    PlannedSession(
                        taskId = scheduledId,
                        date = today,
                        startMinute = 11 * 60,
                        endMinuteExclusive = 12 * 60,
                        isLocked = true,
                    ),
                ),
            ),
            today = today,
            now = clock.instant(),
        )

        val tasks = taskRepository.observeAllTasks().first().associateBy { it.id }
        val backlog = tasks.getValue(backlogId)
        assertEquals(TaskStatus.SCHEDULED, backlog.status)
        assertEquals(listOf(9 * 60 to 10 * 60), backlog.sessions.map {
            it.startMinute to it.endMinuteExclusive
        })
        assertFalse(backlog.sessions.single().isLocked)

        val scheduled = tasks.getValue(scheduledId)
        assertEquals(TaskStatus.SCHEDULED, scheduled.status)
        assertEquals(2, scheduled.sessions.size)
        assertTrue(scheduled.sessions.all { it.isLocked })
        assertEquals(
            listOf(10 * 60, 11 * 60),
            scheduled.sessions.map { it.startMinute }.sorted(),
        )

        val cleared = tasks.getValue(clearedId)
        assertEquals(TaskStatus.BACKLOG, cleared.status)
        assertTrue(cleared.sessions.isEmpty())
        assertEquals(2, result.savedSessions)
        assertEquals(3, result.updatedTasks)
    }

    @Test
    fun applyPlan_rejectsCrossTaskLockConflictAtomically() = runTest {
        val autoTaskId = taskRepository.saveTask(
            task("自动任务", listOf(session(9 * 60, 10 * 60, locked = false))),
        ).createdTaskIds.single()
        val lockedTaskId = taskRepository.saveTask(
            task("锁定任务", listOf(session(10 * 60 + 30, 11 * 60 + 30, locked = true))),
        ).createdTaskIds.single()
        val before = taskRepository.observeAllTasks().first().associateBy { it.id }

        val result = runCatching {
            repository.applyPlan(
                request = SchedulingSaveRequest(
                    candidateTaskIds = setOf(autoTaskId, lockedTaskId),
                    sessions = listOf(
                        PlannedSession(
                            taskId = autoTaskId,
                            date = today,
                            startMinute = 10 * 60,
                            endMinuteExclusive = 11 * 60,
                            isLocked = false,
                        ),
                    ),
                ),
                today = today,
                now = clock.instant(),
            )
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("锁定任务"))

        val after = taskRepository.observeAllTasks().first().associateBy { it.id }
        assertEquals(before.getValue(autoTaskId).sessions, after.getValue(autoTaskId).sessions)
        assertEquals(before.getValue(lockedTaskId).sessions, after.getValue(lockedTaskId).sessions)
    }

    @Test
    fun applyPlan_rejectsCompletedInProgressMissingAndInvalidCandidates() = runTest {
        val completedId = taskRepository.saveTask(
            task("已完成", listOf(session(9 * 60, 10 * 60, locked = false))),
        ).createdTaskIds.single()
        taskRepository.setTaskCompleted(completedId, true)
        val inProgressId = taskRepository.saveTask(
            task("进行中", listOf(session(11 * 60, 12 * 60, locked = false))),
        ).createdTaskIds.single()
        taskRepository.startTask(inProgressId)

        listOf(completedId, inProgressId).forEach { taskId ->
            val result = runCatching {
                repository.applyPlan(
                    request = SchedulingSaveRequest(setOf(taskId), emptyList()),
                    today = today,
                    now = clock.instant(),
                )
            }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("状态已变化"))
        }

        val missing = runCatching {
            repository.applyPlan(
                request = SchedulingSaveRequest(setOf(999_999L), emptyList()),
                today = today,
                now = clock.instant(),
            )
        }
        assertTrue(missing.isFailure)

        val taskId = taskRepository.saveTask(task("验证", emptyList())).createdTaskIds.single()
        val overlap = runCatching {
            repository.applyPlan(
                request = SchedulingSaveRequest(
                    candidateTaskIds = setOf(taskId),
                    sessions = listOf(
                        PlannedSession(taskId, today, 9 * 60, 10 * 60, false),
                        PlannedSession(taskId, today, 9 * 60 + 30, 10 * 60 + 30, false),
                    ),
                ),
                today = today,
                now = clock.instant(),
            )
        }
        assertTrue(overlap.isFailure)

        val past = runCatching {
            repository.applyPlan(
                request = SchedulingSaveRequest(
                    candidateTaskIds = setOf(taskId),
                    sessions = listOf(
                        PlannedSession(taskId, today.minusDays(1), 9 * 60, 10 * 60, false),
                    ),
                ),
                today = today,
                now = clock.instant(),
            )
        }
        assertTrue(past.isFailure)
    }

    @Test
    fun settingsAndSubjectPreferences_persistAndRejectInvalidValues() = runTest {
        assertEquals(SchedulingSettings.Default, repository.getSettings())
        assertEquals(SchedulingSettings.Default, repository.observeSettings().first())

        val settings = SchedulingSettings(
            dailyLimitMinutes = 300,
            bufferMinutes = 15,
            studyStartMinute = 7 * 60,
            studyEndMinuteExclusive = 23 * 60,
            planReminderEnabled = true,
            planReminderHour = 8,
            planReminderMinute = 15,
        )
        val preferences = mapOf(
            "数学分析" to SubjectPreference.HIGHER,
            "大学英语" to SubjectPreference.LOWER,
        )
        repository.saveConfiguration(settings, preferences, clock.instant())
        assertEquals(settings, repository.getSettings())
        assertEquals(preferences, repository.observeSubjectPreferences().first())

        val invalid = settings.copy(dailyLimitMinutes = 0)
        val result = runCatching {
            repository.saveConfiguration(invalid, emptyMap(), clock.instant())
        }
        assertTrue(result.isFailure)
        assertEquals(settings, repository.getSettings())
        assertEquals(preferences, repository.observeSubjectPreferences().first())
    }

    @Test
    fun setSessionLocked_persistsForTaskSessions() = runTest {
        val taskId = taskRepository.saveTask(
            task("锁定测试", listOf(session(9 * 60, 10 * 60, locked = false))),
        ).createdTaskIds.single()
        val session = taskRepository.getTask(taskId)?.sessions?.single()
            ?: error("session missing")
        assertFalse(session.isLocked)

        taskRepository.setSessionLocked(taskId, session.id, true)
        assertTrue(taskRepository.getTask(taskId)!!.sessions.single().isLocked)

        taskRepository.setSessionLocked(taskId, session.id, false)
        assertFalse(taskRepository.getTask(taskId)!!.sessions.single().isLocked)
    }

    private fun task(title: String, sessions: List<TaskSession>) = StudyTask(
        title = title,
        priority = TaskPriority.MEDIUM,
        estimatedMinutes = sessions.sumOf { it.endMinuteExclusive - it.startMinute }
            .takeIf { it > 0 } ?: 60,
        status = if (sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED,
        sessions = sessions,
    )

    private fun session(start: Int, end: Int, locked: Boolean) = TaskSession(
        date = today,
        startMinute = start,
        endMinuteExclusive = end,
        isLocked = locked,
    )
}
