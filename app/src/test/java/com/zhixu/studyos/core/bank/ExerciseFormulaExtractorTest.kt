package com.zhixu.studyos.core.bank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseFormulaExtractorTest {

    @Test
    fun explicitInlineMath_isHighConfidenceAndNoReview() {
        val text = "已知 \$x^2 + y^2 = 1\$ 表示单位圆。"

        val formulas = ExerciseFormulaExtractor.extract(text)

        assertEquals(1, formulas.size)
        assertEquals("x^2 + y^2 = 1", formulas[0].latex)
        assertEquals(0.9, formulas[0].confidence, 0.001)
        assertFalse(formulas[0].needsReview)
    }

    @Test
    fun displayMathBrackets_areExtracted() {
        val text = "结论如下 \\[\n\\int_0^1 x \\,dx = \\frac{1}{2}\n\\]"

        val formulas = ExerciseFormulaExtractor.extract(text)

        assertEquals(1, formulas.size)
        assertTrue(formulas[0].latex.contains("\\int_0^1"))
        assertFalse(formulas[0].needsReview)
    }

    @Test
    fun parenthesizedMath_isExtracted() {
        val text = "满足 \\(a_n \\le b_n\\) 的条件。"

        val formulas = ExerciseFormulaExtractor.extract(text)

        assertEquals(1, formulas.size)
        assertEquals("a_n \\le b_n", formulas[0].latex)
    }

    @Test
    fun bareUnicodeMath_isLowConfidenceAndNeedsReview() {
        val text = "因为 x ≥ 0 且 x ≠ 1，所以 f(x) = √x 连续。"

        val formulas = ExerciseFormulaExtractor.extract(text)

        assertTrue(formulas.isNotEmpty())
        assertTrue(formulas.all { it.needsReview })
        assertTrue(formulas.all { it.confidence < 0.9 })
        assertTrue(formulas[0].originalSnippet.contains("≥"))
    }

    @Test
    fun bareLatexCommands_areDetected() {
        val text = "计算 \\frac{1}{2} 的值。"

        val formulas = ExerciseFormulaExtractor.extract(text)

        assertTrue(formulas.any { it.latex.contains("\\frac") })
        assertTrue(formulas.all { it.needsReview })
    }

    @Test
    fun subSuperscripts_areDetected() {
        assertTrue(ExerciseFormulaExtractor.looksLikeMath("x^2 + y^2 = r^2"))
        assertTrue(ExerciseFormulaExtractor.looksLikeMath("a_1 + a_2 + ... + a_n"))
        assertFalse(ExerciseFormulaExtractor.looksLikeMath("这是一个普通句子，没有数学内容"))
    }

    @Test
    fun duplicateSnippets_areNotDuplicated() {
        val text = "x ≥ 0\nx ≥ 0\nx ≥ 0"

        val formulas = ExerciseFormulaExtractor.extract(text)

        assertEquals(1, formulas.size)
    }

    @Test
    fun plainText_producesNoFormulas() {
        val formulas = ExerciseFormulaExtractor.extract("请证明该结论，并说明理由。")

        assertTrue(formulas.isEmpty())
    }

    @Test
    fun formulas_areOrderedByAppearance() {
        val text = "第一 \$a+b\$ 然后 \$c\\cdot d\$。"

        val formulas = ExerciseFormulaExtractor.extract(text)

        assertEquals(2, formulas.size)
        assertEquals(0, formulas[0].orderIndex)
        assertEquals(1, formulas[1].orderIndex)
        assertEquals("a+b", formulas[0].latex)
        assertEquals("c\\cdot d", formulas[1].latex)
    }
}
