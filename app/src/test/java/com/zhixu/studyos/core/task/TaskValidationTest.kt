package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskValidationTest {
    private val date = LocalDate.of(2026, 10, 26)

    @Test
    fun validSplitSessions_passValidation() {
        val task = baseTask().copy(
            sessions = listOf(
                TaskSession(date = date, startMinute = 9 * 60, endMinuteExclusive = 10 * 60),
                TaskSession(date = date, startMinute = 10 * 60, endMinuteExclusive = 11 * 60),
            ),
        )

        assertNull(TaskValidation.errorOrNull(task))
    }

    @Test
    fun overlappingSessions_areRejected() {
        val task = baseTask().copy(
            sessions = listOf(
                TaskSession(date = date, startMinute = 9 * 60, endMinuteExclusive = 10 * 60),
                TaskSession(date = date, startMinute = 9 * 60 + 30, endMinuteExclusive = 11 * 60),
            ),
        )

        assertEquals("同一任务的学习会话不能互相重叠", TaskValidation.errorOrNull(task))
    }

    @Test
    fun recurringTaskRequiresSessionsAndEndDate() {
        val noSessions = baseTask().copy(
            status = TaskStatus.BACKLOG,
            sessions = emptyList(),
            recurrence = TaskRecurrence.DAILY,
            recurrenceUntil = date.plusDays(2),
        )
        val noEnd = baseTask().copy(recurrence = TaskRecurrence.WEEKLY, recurrenceUntil = null)

        assertNotNull(TaskValidation.errorOrNull(noSessions))
        assertEquals("请选择周期结束日期", TaskValidation.errorOrNull(noEnd))
    }

    @Test
    fun completedStateMustHaveCompletionTime() {
        val invalid = baseTask().copy(status = TaskStatus.COMPLETED, completedAt = null)

        assertEquals("已完成任务必须有实际完成时间", TaskValidation.errorOrNull(invalid))
    }

    private fun baseTask() = StudyTask(
        id = 1,
        title = "线性代数习题",
        estimatedMinutes = 60,
        status = TaskStatus.SCHEDULED,
        sessions = listOf(TaskSession(date = date, startMinute = 9 * 60, endMinuteExclusive = 10 * 60)),
    )
}
