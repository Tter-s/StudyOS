package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemDraftField
import com.zhixu.studyos.core.model.ProblemDraftState
import com.zhixu.studyos.core.model.ProblemPageTextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseProblemParserTest {

    private val parser = ExerciseProblemParser(analysisEngine = HeuristicProblemAnalyzer())

    @Test
    fun parse_producesDraftsWithNumbersAndStatements() {
        val page = parser.parse(
            pageIndex = 2,
            pageText = "第二章 导数与微分\n1. 求函数 f(x)=x^2 的导数。\n2. 证明函数连续性定理。",
            textSource = ProblemPageTextSource.EMBEDDED,
            courseName = "数学分析",
            textbook = "数学分析习题册",
        )

        assertEquals(2, page.problems.size)
        assertEquals(2, page.pageIndex)
        val first = page.problems[0]
        assertEquals("1", first.problemNumber)
        assertEquals("第二章 导数与微分", first.chapter)
        assertEquals("数学分析", first.courseName)
        assertEquals("数学分析习题册", first.textbook)
        assertTrue(first.statement.contains("f(x)=x^2"))
        assertTrue(first.localId.startsWith("p2_"))
    }

    @Test
    fun parse_ocrPagesFlagNumberForReview() {
        val page = parser.parse(
            pageIndex = 0,
            pageText = "1. 求下列函数在原点处的极限值。",
            textSource = ProblemPageTextSource.OCR,
            courseName = "",
            textbook = "",
        )

        val draft = page.problems.single()
        // 题号已识别则不需要校对题号本身
        assertTrue(draft.needsReview.contains(ProblemDraftField.NUMBER).not())
        // OCR 页面统一提示核对
        assertTrue(page.warnings.any { it.contains("OCR") })
        assertTrue(draft.warnings.isNotEmpty())
    }

    @Test
    fun parse_problemWithoutNumber_isMarkedIncomplete() {
        val page = parser.parse(
            pageIndex = 0,
            pageText = "没有题号的一段题目内容，可能是上一页的延续。",
            textSource = ProblemPageTextSource.EMBEDDED,
            courseName = "",
            textbook = "",
        )

        val draft = page.problems.single()
        assertEquals("", draft.problemNumber)
        assertTrue(draft.needsReview.contains(ProblemDraftField.NUMBER))
    }

    @Test
    fun parse_formulasCarriedIntoDrafts() {
        val page = parser.parse(
            pageIndex = 0,
            pageText = "1. 已知 \$x^2+y^2=1\$，求轨迹方程。",
            textSource = ProblemPageTextSource.EMBEDDED,
            courseName = "空间解析几何",
            textbook = "",
        )

        val draft = page.problems.single()
        assertEquals(1, draft.formulas.size)
        assertEquals("x^2+y^2=1", draft.formulas[0].latex)
        assertTrue(draft.knowledgePoints.isNotEmpty() || draft.estimatedDifficulty != null)
    }

    @Test
    fun parse_blankPage_returnsEmptyWithWarning() {
        val page = parser.parse(
            pageIndex = 5,
            pageText = "   ",
            textSource = ProblemPageTextSource.NONE,
            courseName = "",
            textbook = "",
        )

        assertTrue(page.problems.isEmpty())
        assertTrue(page.warnings.any { it.contains("没有可识别的文本") })
    }

    @Test
    fun deduplication_analyzeAll_marksDuplicatesAfterFirst() {
        fun parse(pageIndex: Int, text: String) = parser.parse(
            pageIndex = pageIndex,
            pageText = text,
            textSource = ProblemPageTextSource.EMBEDDED,
            courseName = "",
            textbook = "教材",
        ).problems.single()
        val drafts = listOf(
            parse(0, "1. 这是第一道题目的完整题干内容。"),
            parse(1, "1. 这是同号重复题目的完整题干内容。"),
            parse(2, "2. 这是第二道题目的完整题干内容。"),
        )
        // 同一来源（documentId 相同）下 1、1、2
        val normalized = drafts.map { it.copy(documentId = 9L) }

        val analyses = ProblemDeduplicator.analyzeAll(normalized, existingKeys = emptySet(), suspectedKeys = emptySet())

        assertEquals(ProblemDraftState.READY, analyses[normalized[0].localId]!!.state)
        assertEquals(ProblemDraftState.DUPLICATE, analyses[normalized[1].localId]!!.state)
        assertEquals(ProblemDraftState.READY, analyses[normalized[2].localId]!!.state)
    }
}
