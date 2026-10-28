package com.zhixu.studyos.core.report

import com.zhixu.studyos.core.growth.GrowthAnalyticsEngine
import com.zhixu.studyos.core.growth.GrowthRangeType
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportEngineTest {
    private val monday = LocalDate.of(2026, 10, 19)

    private fun task(
        id: Long,
        date: LocalDate = monday,
        completed: Boolean = true,
        estimated: Int = 60,
        actualScheduled: Int = 50,
        postpone: Int = 0,
        subject: String = "数学分析",
    ) = TaskFact(
        id = id,
        title = "任务 $id",
        subject = subject,
        estimatedMinutes = estimated,
        scheduledMinutes = actualScheduled,
        scheduledDate = date,
        completed = completed,
        completedDate = if (completed) date else null,
        dueDate = date,
        postponeCount = postpone,
        sourceType = "USER",
    )

    private fun attempt(day: Long, independent: Boolean, hint: Int = 0) = AttemptFact(
        date = monday.plusDays(day),
        subject = "数学分析",
        chapter = "极限",
        independent = independent,
        correct = true,
        highestHintLevel = hint,
        durationMinutes = 20,
        attemptIndex = 1,
    )

    private val facts = LearningFactBundle(
        tasks = listOf(
            task(1),
            task(2, completed = false),
            task(3, postpone = 3, completed = false),
        ),
        timings = listOf(
            TimingFact(monday, "数学分析", 120),
            TimingFact(monday.plusDays(1), "数学分析", 60),
        ),
        attempts = listOf(
            attempt(0, independent = true),
            attempt(1, independent = false, hint = 3),
            attempt(2, independent = true),
        ),
        hints = listOf(HintFact(monday, 3, false)),
        vocabularyReviews = listOf(
            VocabularyReviewFact(monday, true, "GOOD"),
            VocabularyReviewFact(monday.plusDays(1), false, "AGAIN"),
        ),
        vocabulary = listOf(
            VocabularyFact(1, "analysis", "自定义", monday, "LEARNING"),
        ),
        sleep = listOf(
            SleepFact(monday, 420, 4, 4),
            SleepFact(monday.plusDays(1), 400, 3, 4),
            SleepFact(monday.plusDays(2), 440, 4, 5),
        ),
        transactions = listOf(TransactionFact(monday, 2_000, "餐饮")),
    )

    @Test
    fun weeklyReport_computesRatesAndDefinitions() {
        val report = WeeklyReportEngine.build(facts, monday, Instant.EPOCH)
        assertEquals(3, report.overview.scheduledTasks)
        assertEquals(1, report.overview.completedTasks)
        assertEquals(33, report.overview.completionRatePercent)
        assertEquals(3, report.exercise.attempts)
        assertEquals(66, report.exercise.independentRatePercent)
        assertEquals(2, report.vocabulary.reviews)
        assertEquals(50, report.vocabulary.recallRatePercent)
        assertEquals(150, report.planExecution.plannedMinutes)
        assertEquals(180, report.planExecution.actualMinutes)
        assertEquals(30, report.planExecution.deviationMinutes)
        assertTrue(report.suggestions.isNotEmpty())
        assertTrue(report.metrics.first { it.label == "计划完成率" }.definition.contains("任务总数"))
        assertTrue(report.metrics.first { it.label == "独立完成率" }.definition.contains("提示"))
    }

    @Test
    fun multiSessionTask_attributesOnlySessionsInsideTheWeek() {
        val crossWeek = TaskFact(
            id = 10,
            title = "跨周任务",
            subject = "数学分析",
            estimatedMinutes = 100,
            scheduledMinutes = 100,
            scheduledDate = monday,
            completed = false,
            completedDate = null,
            dueDate = null,
            postponeCount = 0,
            sourceType = "USER",
            sessions = listOf(
                TaskSessionFact(monday, 50),
                TaskSessionFact(monday.plusDays(7), 50),
            ),
        )

        val report = WeeklyReportEngine.build(
            LearningFactBundle(tasks = listOf(crossWeek)),
            monday,
            Instant.EPOCH,
        )

        assertEquals(1, report.overview.scheduledTasks)
        assertEquals(50, report.planExecution.plannedMinutes)
        assertEquals(1, report.daily.first().scheduledTasks)
        assertEquals(0, report.daily.last().scheduledTasks)
    }

    @Test
    fun missingData_isReportedAsInsufficientInsteadOfZero() {
        val empty = WeeklyReportEngine.build(LearningFactBundle(), monday, Instant.EPOCH)
        assertNull(empty.overview.completionRatePercent)
        assertNull(empty.overview.independentRatePercent)
        assertNull(empty.sleep.averageDurationMinutes)
        assertTrue(empty.dataNotes.any { it.contains("数据不足") || it.contains("没有") })
        assertFalse(empty.metrics.first { it.label == "独立完成率" }.available)
    }

    @Test
    fun aiSections_areSeparatedByTags() {
        val sections = WeeklyReportEngine.parseAiSections(
            "[数据] 本周学习 100 分钟\n[数据] 完成 1 个任务\n[推测] 可能因为考试临近\n[建议] 下周保持",
        )
        assertEquals(2, sections.facts.size)
        assertEquals(1, sections.speculation.size)
        assertEquals(1, sections.suggestions.size)
        assertTrue(sections.disclaimer.contains("推测"))
    }

    @Test
    fun growthAnalytics_onlyUsesRealDataAndHasDefinitions() {
        val range = GrowthAnalyticsEngine.currentRange(GrowthRangeType.WEEK, monday.plusDays(2))
        val growth = GrowthAnalyticsEngine.build(facts, range, monday.plusDays(2))
        assertEquals(180, growth.metrics.first { it.key == "study_minutes" }.value?.substringBefore(" ")?.toInt())
        val hintMetric = growth.metrics.first { it.key == "hint_dependency" }
        assertEquals("33%", hintMetric.value)
        assertTrue(growth.metrics.all { it.definition.isNotBlank() })
        assertTrue(growth.notes.any { it.contains("不预测成绩") || it.contains("相关性") })
    }

    @Test
    fun semesterBuckets_areExplicitAboutObservationalDefinition() {
        val buckets = GrowthAnalyticsEngine.buildSemesterBuckets(facts, LocalDate.of(2026, 10, 25))
        assertNotNull(buckets)
        assertEquals(1, buckets.size)
        assertEquals("2026 秋季学期", buckets.single().label)
        assertTrue(buckets.single().bucket.studyMinutes >= 0)
    }
}
