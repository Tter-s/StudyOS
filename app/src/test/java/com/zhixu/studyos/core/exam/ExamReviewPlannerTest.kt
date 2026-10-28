package com.zhixu.studyos.core.exam

import com.zhixu.studyos.core.sleep.SleepSchedule
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamReviewPlannerTest {
    private val today = LocalDate.of(2026, 10, 25)
    private val exam = Exam(
        id = 1,
        name = "数学分析期中",
        subject = "数学分析",
        date = today.plusDays(5),
        scope = "第三章",
        materials = "教材与习题册",
    )

    @Test
    fun planner_usesWeakChaptersAndUnfinishedExercises() {
        val plan = ExamReviewPlanner.build(
            exam = exam,
            today = today,
            weakChapters = listOf(
                ExamReviewPlanner.WeakChapter("第三章", 4),
                ExamReviewPlanner.WeakChapter("第二章", 2),
            ),
            unfinishedProblemCount = 8,
        )
        assertEquals(5, plan.items.size)
        assertEquals(45, plan.items.first().estimatedMinutes)
        assertTrue(plan.items.any { it.focus == "薄弱章节" })
        assertTrue(plan.items.any { it.focus == "综合回顾" })
        assertTrue(plan.items.all { !it.date.isAfter(exam.date) })
        assertTrue(plan.warnings.none { it.contains("范围") })
    }

    @Test
    fun shortExam_stillReservesOneUnfinishedExerciseSlot() {
        val plan = ExamReviewPlanner.build(
            exam = exam.copy(date = today.plusDays(1)),
            today = today,
            unfinishedProblemCount = 3,
        )

        assertEquals(1, plan.items.size)
        assertEquals("未完成习题", plan.items.single().focus)
    }

    @Test
    fun planner_flagsMissingScopeAndNoWeakData() {
        val plan = ExamReviewPlanner.build(
            exam = exam.copy(scope = ""),
            today = today,
        )
        assertTrue(plan.warnings.any { it.contains("考试范围") })
        assertTrue(plan.warnings.any { it.contains("通用回顾") })
    }

    @Test
    fun passedExam_generatesNoTasks() {
        val plan = ExamReviewPlanner.build(exam.copy(date = today.minusDays(1)), today)
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.warnings.single().contains("已经过去"))
    }

    @Test
    fun countdown_andSleepConflictsAreExplicit() {
        val countdown = ExamCountdownCalculator.build(listOf(exam), today).single()
        assertEquals(5, countdown.daysRemaining)
        assertEquals(ExamPhase.THIS_WEEK, countdown.phase)
        assertEquals("还有 5 天", countdown.label)

        val examAtNight = exam.copy(
            startMinute = 22 * 60 + 30,
            durationMinutes = 90,
        )
        val conflicts = ExamReviewPlanner.sleepConflicts(examAtNight, SleepSchedule(), today)
        assertTrue(conflicts.isNotEmpty())
        assertTrue(conflicts.first().contains("需要你自行调整"))
        assertFalse(conflicts.first().contains("已自动调整"))
        assertNotNull(ExamCountdownCalculator.next(listOf(exam), today))
    }

    @Test
    fun goalProgress_usesWeightsAndReportsInsufficientData() {
        val goal = LongTermGoal(
            id = 1,
            title = "本学期数分优秀",
            startDate = today,
            targetDate = today.plusDays(60),
        )
        val progress = GoalProgressCalculator.calculate(
            goal,
            listOf(
                GoalMilestone(id = 1, goalId = 1, title = "完成前三章", weight = 2, status = "COMPLETED"),
                GoalMilestone(
                    id = 2,
                    goalId = 1,
                    title = "期中 85+",
                    weight = 3,
                    status = "PENDING",
                    dueDate = today.minusDays(1),
                ),
            ),
            today,
        )
        assertEquals(40, progress.percent)
        assertEquals(1, progress.overdueMilestones)
        assertTrue(progress.dataSufficient)

        val empty = GoalProgressCalculator.calculate(goal, emptyList(), today)
        assertEquals(null, empty.percent)
        assertFalse(empty.dataSufficient)
    }
}
