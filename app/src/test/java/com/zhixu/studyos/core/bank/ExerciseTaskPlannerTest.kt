package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemTaskPlanRequest
import com.zhixu.studyos.core.model.ProblemTaskSelection
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTaskPlannerTest {

    private fun problem(id: Long, chapter: String, number: String, minutes: Int?) = ProblemBankEntry(
        id = id,
        documentId = 1L,
        pageIndex = 0,
        courseId = null,
        courseName = "数学分析",
        textbook = "数学分析习题册",
        chapter = chapter,
        problemNumber = number,
        statement = "题干",
        estimatedMinutes = minutes,
    )

    private fun request(perDay: Int, start: LocalDate) = ProblemTaskPlanRequest(
        selection = ProblemTaskSelection(documentId = 1L, chapter = null, maxDifficulty = null),
        problemsPerDay = perDay,
        startDate = start,
        courseName = "数学分析",
        textbook = "数学分析习题册",
    )

    @Test
    fun problems_splitIntoDailyGroups() {
        val problems = (1..7).map { problem(it.toLong(), "第一章", it.toString(), 10) }

        val plan = ExerciseTaskPlanner.plan(request(3, LocalDate.of(2026, 10, 26)), problems)

        assertEquals(3, plan.dayCount)
        assertEquals(7, plan.selectedCount)
        assertEquals(listOf("1", "2", "3"), plan.items[0].problemNumbers)
        assertEquals(listOf("4", "5", "6"), plan.items[1].problemNumbers)
        assertEquals(listOf("7"), plan.items[2].problemNumbers)
    }

    @Test
    fun dates_areConsecutiveFromStart() {
        val problems = (1..4).map { problem(it.toLong(), "第一章", it.toString(), 10) }

        val plan = ExerciseTaskPlanner.plan(request(2, LocalDate.of(2026, 10, 26)), problems)

        assertEquals(LocalDate.of(2026, 10, 26), plan.items[0].date)
        assertEquals(LocalDate.of(2026, 10, 27), plan.items[1].date)
    }

    @Test
    fun estimatedMinutes_summedWithDefault() {
        val problems = listOf(
            problem(1, "第一章", "1", 15),
            problem(2, "第一章", "2", null),
        )

        val plan = ExerciseTaskPlanner.plan(request(10, LocalDate.of(2026, 10, 26)), problems)

        assertEquals(15 + ExerciseTaskPlanner.DEFAULT_MINUTES_PER_PROBLEM, plan.items.single().estimatedMinutes)
    }

    @Test
    fun title_containsTextbookAndChapterAndNumbers() {
        val problems = listOf(
            problem(1, "第二章", "1", 10),
            problem(2, "第二章", "2", 10),
            problem(3, "第二章", "3", 10),
            problem(4, "第二章", "4", 10),
        )

        val plan = ExerciseTaskPlanner.plan(request(4, LocalDate.of(2026, 10, 26)), problems)

        val title = plan.items.single().title
        assertTrue(title.contains("数学分析习题册"))
        assertTrue(title.contains("第二章"))
        assertTrue(title.contains("1–4"))
    }

    @Test
    fun problems_sortedByChapterThenNaturalNumber() {
        val problems = listOf(
            problem(1, "第二章", "10", 10),
            problem(2, "第一章", "2", 10),
            problem(3, "第二章", "9", 10),
            problem(4, "第一章", "1", 10),
        )

        val plan = ExerciseTaskPlanner.plan(request(10, LocalDate.of(2026, 10, 26)), problems)

        assertEquals(
            listOf("1", "2", "9", "10"),
            plan.items.single().problemNumbers,
        )
    }

    @Test
    fun invalidPerDay_throws() {
        val problems = listOf(problem(1, "第一章", "1", 10))

        try {
            ExerciseTaskPlanner.plan(request(0, LocalDate.of(2026, 10, 26)), problems)
            throw AssertionError("应当抛出异常")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("每日题量"))
        }
    }

    @Test
    fun emptySelection_throws() {
        try {
            ExerciseTaskPlanner.plan(request(5, LocalDate.of(2026, 10, 26)), emptyList())
            throw AssertionError("应当抛出异常")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("至少一道"))
        }
    }
}
