package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {
    private val date = LocalDate.of(2026, 10, 26)
    private val completedAt = Instant.parse("2026-10-26T12:00:00Z")

    @Test
    fun scheduledTask_completesAndClearsCompletionWhenUndone() {
        val task = scheduledTask()

        val completed = TaskStateMachine.complete(task, completedAt)
        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertEquals(completedAt, completed.completedAt)

        val reopened = TaskStateMachine.undoCompletion(completed)
        assertEquals(TaskStatus.SCHEDULED, reopened.status)
        assertNull(reopened.completedAt)
        assertTrue(reopened.sessions.isNotEmpty())
    }

    @Test
    fun backlogTask_canCompleteThenReturnsToBacklogWhenUndone() {
        val task = scheduledTask().copy(status = TaskStatus.BACKLOG, sessions = emptyList())

        val completed = TaskStateMachine.complete(task, completedAt)
        val reopened = TaskStateMachine.undoCompletion(completed)

        assertEquals(TaskStatus.BACKLOG, reopened.status)
        assertNull(reopened.completedAt)
    }

    @Test
    fun startAndPause_onlyApplyToPlannedStates() {
        val started = TaskStateMachine.start(scheduledTask())
        val paused = TaskStateMachine.pause(started)

        assertEquals(TaskStatus.IN_PROGRESS, started.status)
        assertEquals(TaskStatus.SCHEDULED, paused.status)
        assertThrows(IllegalArgumentException::class.java) {
            TaskStateMachine.start(scheduledTask().copy(status = TaskStatus.BACKLOG, sessions = emptyList()))
        }
    }

    @Test
    fun replanning_rederivesBacklogOrScheduledButPreservesCompleted() {
        val backlog = TaskStateMachine.afterReplan(scheduledTask().copy(sessions = emptyList()))
        val scheduled = TaskStateMachine.afterReplan(scheduledTask().copy(status = TaskStatus.BACKLOG))
        val completed = scheduledTask().copy(status = TaskStatus.COMPLETED, completedAt = completedAt)
        val editedCompleted = TaskStateMachine.afterReplan(completed.copy(sessions = emptyList()))

        assertEquals(TaskStatus.BACKLOG, backlog.status)
        assertEquals(TaskStatus.SCHEDULED, scheduled.status)
        assertEquals(TaskStatus.COMPLETED, editedCompleted.status)
        assertEquals(completedAt, editedCompleted.completedAt)
    }

    @Test
    fun contradictoryCompletionState_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskStateMachine.validateInvariants(scheduledTask().copy(status = TaskStatus.COMPLETED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskStateMachine.validateInvariants(scheduledTask().copy(completedAt = completedAt))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskStateMachine.validateInvariants(
                scheduledTask().copy(status = TaskStatus.SCHEDULED, sessions = emptyList()),
            )
        }
    }

    private fun scheduledTask() = StudyTask(
        id = 1,
        title = "数学分析复习",
        priority = TaskPriority.HIGH,
        estimatedMinutes = 60,
        status = TaskStatus.SCHEDULED,
        sessions = listOf(TaskSession(date = date, startMinute = 19 * 60, endMinuteExclusive = 20 * 60)),
    )
}
