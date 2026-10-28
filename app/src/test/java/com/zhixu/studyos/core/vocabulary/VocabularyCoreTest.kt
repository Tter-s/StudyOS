package com.zhixu.studyos.core.vocabulary

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyCoreTest {
    private val today = LocalDate.of(2026, 10, 25)

    private fun word(
        repetition: Int = 0,
        interval: Int = 0,
        ease: Double = 2.5,
        status: VocabularyStatus = VocabularyStatus.NEW,
        familiarity: Int = 0,
    ) = VocabularyWord(
        id = 1,
        word = "analysis",
        meaning = "分析",
        status = status,
        repetition = repetition,
        intervalDays = interval,
        easeFactor = ease,
        dueDate = today,
        familiarity = familiarity,
    )

    @Test
    fun firstGood_reviewSchedulesTomorrowAndStaysLearning() {
        val result = Sm2Scheduler.review(word(), VocabularyRating.GOOD, today)
        assertEquals(1, result.repetition)
        assertEquals(1, result.intervalDays)
        assertEquals(today.plusDays(1), result.dueDate)
        assertEquals(VocabularyStatus.LEARNING, result.status)
        assertTrue(result.recalled)
    }

    @Test
    fun secondGood_reviewSchedulesSixDays() {
        val result = Sm2Scheduler.review(
            word(repetition = 1, interval = 1, status = VocabularyStatus.LEARNING),
            VocabularyRating.GOOD,
            today,
        )
        assertEquals(2, result.repetition)
        assertEquals(6, result.intervalDays)
        assertEquals(today.plusDays(6), result.dueDate)
    }

    @Test
    fun thirdGood_usesEaseAndMarksReviewing() {
        val result = Sm2Scheduler.review(
            word(repetition = 2, interval = 6, status = VocabularyStatus.LEARNING),
            VocabularyRating.GOOD,
            today,
        )
        assertEquals(15, result.intervalDays)
        assertEquals(VocabularyStatus.REVIEWING, result.status)
    }

    @Test
    fun again_resetsIntervalAndEaseWithDueToday() {
        val result = Sm2Scheduler.review(
            word(repetition = 3, interval = 20, ease = 2.5, status = VocabularyStatus.REVIEWING),
            VocabularyRating.AGAIN,
            today,
        )
        assertEquals(0, result.repetition)
        assertEquals(0, result.intervalDays)
        assertEquals(today, result.dueDate)
        assertEquals(2.3, result.easeFactor, 0.0001)
        assertEquals(VocabularyStatus.LEARNING, result.status)
        assertFalse(result.recalled)
    }

    @Test
    fun easy_givesLongerIntervalAndHigherEase() {
        val result = Sm2Scheduler.review(
            word(repetition = 2, interval = 6, status = VocabularyStatus.LEARNING),
            VocabularyRating.EASY,
            today,
        )
        assertTrue(result.intervalDays >= 19)
        assertTrue(result.easeFactor > 2.5)
    }

    @Test
    fun longStableReview_becomesMasteredAndEaseIsBounded() {
        val result = Sm2Scheduler.review(
            word(
                repetition = 4,
                interval = 90,
                ease = 2.9,
                status = VocabularyStatus.REVIEWING,
                familiarity = 5,
            ),
            VocabularyRating.EASY,
            today,
        )
        assertEquals(VocabularyStatus.MASTERED, result.status)
        assertTrue(result.easeFactor <= Sm2Scheduler.MAX_EASE)
    }

    @Test
    fun taskPlannerCapsDailyLoadAndReportsBacklog() {
        val settings = VocabularySettings(dailyNewLimit = 10, dailyReviewLimit = 20, dailyTimeBudgetMinutes = 30)
        val plan = VocabularyTaskPlanner.build(today, dueWords = 60, newWords = 30, settings = settings)
        assertEquals(20, plan.dueCount)
        assertEquals(10, plan.newCount)
        assertTrue(plan.plannedMinutes <= 30)
        assertTrue(plan.description.contains("积压"))
    }

    @Test
    fun csvParser_handlesChineseHeadersQuotesAndDuplicates() {
        val csv = """
            单词,释义,音标,词性,例句,词表
            analysis,"分析, 解析",əˈnæləsɪs,n,"An analysis, of data.","考研"
            ANALYSIS,分析,_,_,_,考研
            ,空,_,_,_,考研
        """.trimIndent()
        val result = VocabularyCsvParser.parse(csv)
        assertEquals(',', result.detectedDelimiter)
        assertEquals(3, result.rows.size)
        assertEquals("analysis", result.rows[0].word)
        assertEquals("分析, 解析", result.rows[0].meaning)
        assertEquals("An analysis, of data.", result.rows[0].example)
        assertEquals("重复单词", result.rows[1].error)
        assertEquals("单词为空", result.rows[2].error)
        assertEquals(listOf("ANALYSIS"), result.duplicateWords)
    }

    @Test
    fun csvParser_supportsTsvWithoutHeader() {
        val result = VocabularyCsvParser.parse("apple\t苹果\nbanana\t香蕉")
        assertEquals('\t', result.detectedDelimiter)
        assertEquals(2, result.rows.size)
        assertEquals("苹果", result.rows[0].meaning)
        assertNull(result.rows[0].error)
    }

    @Test
    fun settingsValidation_isBounded() {
        assertNotNull(VocabularySettings(dailyNewLimit = -1).validationError())
        assertNotNull(VocabularySettings(dailyReviewLimit = 999).validationError())
        assertNull(VocabularySettings().validationError())
    }
}
