package com.zhixu.studyos.core.learning

import com.zhixu.studyos.core.model.BlockerReason
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.TimingSession
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearningAnalyticsCalculatorTest {
    private val math = Subject(1, "数学分析", true)
    private val english = Subject(2, "大学英语", true)
    private val date = LocalDate.of(2026, 10, 25)
    private val now = Instant.parse("2026-10-25T10:00:00Z")

    @Test
    fun subjectStatsSeparateStudyTasksProblemsAndIndependentWork() {
        val tasks = listOf(
            task("数学分析", completed = true),
            task("大学英语", completed = false),
        )
        val timings = listOf(
            timing(math.id, 30, StudyTimerStatus.COMPLETED),
            timing(english.id, 60, StudyTimerStatus.PAUSED),
        )
        val problems = listOf(
            problem(math.id, completed = true, independent = true, difficult = true),
            problem(math.id, completed = true, independent = false, difficult = false),
            problem(english.id, completed = false, independent = null, difficult = true),
        )

        val result = LearningAnalyticsCalculator.subjectAnalytics(
            subjects = listOf(math, english),
            tasks = tasks,
            timings = timings,
            problems = problems,
        )

        val mathStats = result.stats.first { it.subject.id == math.id }
        assertEquals(30 * 60_000L, mathStats.studyMillis)
        assertEquals(1, mathStats.taskTotal)
        assertEquals(1, mathStats.taskCompleted)
        assertEquals(1f, mathStats.taskCompletionRate)
        assertEquals(2, mathStats.problemCompleted)
        assertEquals(1, mathStats.difficultProblemCount)
        assertEquals(1, mathStats.independentProblemCount)
        assertEquals(0.5f, mathStats.independentCompletionRate)

        val englishStats = result.stats.first { it.subject.id == english.id }
        assertEquals(0L, englishStats.studyMillis)
        assertEquals(0f, englishStats.taskCompletionRate)
        assertEquals(0, englishStats.problemCompleted)
        assertEquals(1, englishStats.difficultProblemCount)
        assertNull(englishStats.independentCompletionRate)
    }

    private fun task(subject: String, completed: Boolean) = StudyTask(
        title = "任务",
        subject = subject,
        status = if (completed) TaskStatus.COMPLETED else TaskStatus.SCHEDULED,
        estimatedMinutes = 60,
        sessions = listOf(
            com.zhixu.studyos.core.model.TaskSession(
                date = date,
                startMinute = 9 * 60,
                endMinuteExclusive = 10 * 60,
            ),
        ),
    )

    private fun timing(subjectId: Long, minutes: Int, status: StudyTimerStatus) = TimingSession(
        subjectId = subjectId,
        studyDate = date,
        mode = StudyTimerMode.STOPWATCH,
        status = status,
        accumulatedMillis = minutes * 60_000L,
        startedAt = now,
    )

    private fun problem(
        subjectId: Long,
        completed: Boolean,
        independent: Boolean?,
        difficult: Boolean,
    ) = Problem(
        subjectId = subjectId,
        problemNumber = "1.1",
        status = if (completed) ProblemStatus.COMPLETED else ProblemStatus.TODO,
        blockerReason = if (difficult) BlockerReason.CALCULATION else null,
        isDifficult = difficult,
        independentCompleted = independent,
        completedAt = if (completed) now else null,
        createdAt = now,
        updatedAt = now,
    )
}
