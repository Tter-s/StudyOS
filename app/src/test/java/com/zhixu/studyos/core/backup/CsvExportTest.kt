package com.zhixu.studyos.core.backup

import com.zhixu.studyos.core.model.BlockerReason
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.TimingSession
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportTest {

    private fun task(
        id: Long,
        title: String,
        status: TaskStatus = TaskStatus.SCHEDULED,
        postponeCount: Int = 0,
        sessions: List<TaskSession> = emptyList(),
    ) = StudyTask(
        id = id,
        title = title,
        subject = "数学分析",
        priority = TaskPriority.HIGH,
        estimatedMinutes = 60,
        dueDate = LocalDate.of(2026, 10, 30),
        status = status,
        completedAt = null,
        recurrence = TaskRecurrence.NONE,
        postponeCount = postponeCount,
        sessions = sessions,
    )

    @Test
    fun tasks_csvHasBomHeaderAndEscapedFields() {
        val csv = CsvExport.tasks(
            listOf(
                task(
                    1,
                    "含\"引号\", 逗号\n与换行",
                    sessions = listOf(
                        TaskSession(
                            id = 1,
                            date = LocalDate.of(2026, 10, 26),
                            startMinute = 480,
                            endMinuteExclusive = 540,
                            isLocked = true,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(csv.startsWith(CsvExport.UTF8_BOM + "\"任务ID\",\"标题\""))
        assertTrue(csv.contains("\"含\"\"引号\"\", 逗号\n与换行\""))
        assertTrue(csv.contains("\"已安排\""))
        assertTrue(csv.contains("\"2026-10-26 08:00-09:00(锁定)\""))
        assertTrue(csv.contains("\r\n"))
    }

    @Test
    fun emptyList_stillHasHeader() {
        val csv = CsvExport.tasks(emptyList())

        assertEquals(1, csv.removePrefix(CsvExport.UTF8_BOM).trimEnd('\r', '\n').lines().size)
    }

    @Test
    fun timings_labelsAndSubjectFallback() {
        val csv = CsvExport.timings(
            listOf(
                TimingSession(
                    id = 7,
                    subjectId = 1,
                    studyDate = LocalDate.of(2026, 10, 25),
                    mode = StudyTimerMode.POMODORO,
                    status = StudyTimerStatus.COMPLETED,
                    plannedDurationMinutes = 25,
                    accumulatedMillis = 25 * 60_000L,
                    interruptionCount = 2,
                    startedAt = Instant.parse("2026-10-25T01:00:00Z"),
                    endedAt = Instant.parse("2026-10-25T01:25:00Z"),
                    note = "复习极限",
                ),
                TimingSession(
                    id = 8,
                    subjectId = 99,
                    studyDate = LocalDate.of(2026, 10, 25),
                    mode = StudyTimerMode.STOPWATCH,
                    status = StudyTimerStatus.COMPLETED,
                    accumulatedMillis = 10 * 60_000L,
                    startedAt = Instant.parse("2026-10-25T02:00:00Z"),
                ),
            ),
            subjects = listOf(Subject(id = 1, name = "数学分析", isPreset = true)),
        )

        assertTrue(csv.contains("\"数学分析\""))
        assertTrue(csv.contains("\"番茄钟\""))
        assertTrue(csv.contains("\"25\""))
        assertTrue(csv.contains("\"已删除科目\""))
        assertTrue(csv.contains("\"复习极限\""))
    }

    @Test
    fun problems_labelsBlockersAndFlags() {
        val csv = CsvExport.manualProblems(
            listOf(
                Problem(
                    id = 3,
                    subjectId = 1,
                    textbook = "数学分析",
                    chapter = "第一章",
                    problemNumber = "5",
                    knowledgePoint = "极限",
                    status = ProblemStatus.COMPLETED,
                    independentThinkingMinutes = 12,
                    blockerReason = BlockerReason.THEOREM,
                    isMistake = true,
                    isDifficult = true,
                    retryCount = 2,
                    createdAt = Instant.parse("2026-10-20T00:00:00Z"),
                    updatedAt = Instant.parse("2026-10-21T00:00:00Z"),
                ),
            ),
            subjects = listOf(Subject(id = 1, name = "数学分析", isPreset = true)),
        )

        assertTrue(csv.contains("\"已完成\""))
        assertTrue(csv.contains("\"定理不熟\""))
        assertTrue(csv.contains("\"是\""))
        assertTrue(csv.contains("\"12\""))
    }
}
