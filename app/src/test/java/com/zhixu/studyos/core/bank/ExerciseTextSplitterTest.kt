package com.zhixu.studyos.core.bank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTextSplitterTest {

    @Test
    fun numberedProblems_areSplitSequentially() {
        val text = """
            1. 求下列函数的极限。
            (1) lim x→0 sin x / x
            2．证明数列 {a_n} 单调有界。
            3、设 f(x) 在 [0,1] 上连续。
        """.trimIndent()

        val result = ExerciseTextSplitter.split(text)

        assertEquals(3, result.segments.size)
        assertEquals("1", result.segments[0].problemNumber)
        assertEquals("2", result.segments[1].problemNumber)
        assertEquals("3", result.segments[2].problemNumber)
        assertTrue(result.segments[0].lines.joinToString("\n").contains("lim"))
        assertTrue(result.segments[1].lines.joinToString("\n").contains("单调有界"))
    }

    @Test
    fun subQuestions_doNotStartNewProblem() {
        val text = """
            1. 讨论下列命题是否成立。
            (1) 一切连续函数都可导。
            （2）一切可导函数都连续。
            2. 求积分。
        """.trimIndent()

        val result = ExerciseTextSplitter.split(text)

        assertEquals(2, result.segments.size)
        assertEquals(3, result.segments[0].lines.size)
    }

    @Test
    fun chapterHeaders_updateContext() {
        val text = """
            第二章 导数与微分
            1. 求导数。
            2. 证明中值定理。
            习题 2.3
            1. 计算积分。
        """.trimIndent()

        val result = ExerciseTextSplitter.split(text)

        assertEquals("第二章 导数与微分", result.segments[0].chapter)
        assertEquals("习题 2.3", result.segments[2].chapter)
        assertEquals("习题 2.3", result.chapter)
    }

    @Test
    fun pageNumberNoise_isFiltered() {
        val text = """
            1. 求极限。
            -----------
            25
            第 3 页
            2. 求导数。
        """.trimIndent()

        val result = ExerciseTextSplitter.split(text)

        assertEquals(2, result.segments.size)
        assertTrue(result.warnings.any { it.contains("噪声") })
        assertFalse(result.segments[0].lines.joinToString("\n").contains("25"))
    }

    @Test
    fun nonSequentialNumber_isMergedIntoPreviousStatement() {
        val text = """
            1. 求极限。
            1. 重复出现的题号应并入上一题
            2. 求导数。
        """.trimIndent()

        val result = ExerciseTextSplitter.split(text)

        assertEquals(2, result.segments.size)
        assertTrue(result.segments[0].lines.joinToString("\n").contains("重复出现"))
        assertTrue(result.warnings.any { it.contains("并入上一题") })
    }

    @Test
    fun leadingOrphan_withoutNumber_isMarkedNeedsReview() {
        val text = """
            这是上一页题目的延续内容，没有题号。
            1. 求极限。
        """.trimIndent()

        val result = ExerciseTextSplitter.split(text)
        val orphan = ExerciseTextSplitter.leadingOrphan(result)

        assertNotNull(orphan)
        assertNull(orphan!!.problemNumber)
        assertTrue(orphan.lines.joinToString("\n").contains("延续"))
    }

    @Test
    fun missingNumberJump_withinTolerance_isAccepted() {
        val text = """
            1. 第一题。
            4. 第四题（OCR 漏了 2、3）。
        """.trimIndent()

        val result = ExerciseTextSplitter.split(text)

        assertEquals(2, result.segments.size)
        assertEquals("4", result.segments[1].problemNumber)
    }

    @Test
    fun chineseChapterNumber_convertsToDigits() {
        assertEquals(1, ExerciseTextSplitter.chineseNumberToInt("一"))
        assertEquals(12, ExerciseTextSplitter.chineseNumberToInt("十二"))
        assertEquals(23, ExerciseTextSplitter.chineseNumberToInt("二十三"))
        assertEquals(105, ExerciseTextSplitter.chineseNumberToInt("一百零五"))
        assertNull(ExerciseTextSplitter.chineseNumberToInt("函数"))
    }

    @Test
    fun emptyPage_producesNoSegments() {
        val result = ExerciseTextSplitter.split("   \n  \n")

        assertTrue(result.segments.isEmpty())
    }
}
