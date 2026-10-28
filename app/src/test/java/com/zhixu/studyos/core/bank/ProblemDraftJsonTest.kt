package com.zhixu.studyos.core.bank

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.model.ProblemAnalysisSource
import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemDraftField
import com.zhixu.studyos.core.model.ProblemFormula
import com.zhixu.studyos.core.model.ProblemKnowledgePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProblemDraftJsonTest {

    private fun sample() = ProblemDraft(
        localId = "p0_s0-abcdef12",
        documentId = 7L,
        pageIndex = 3,
        problemNumber = "12",
        statement = "设函数 f(x) 连续，证明结论。",
        chapter = "第二章",
        courseName = "数学分析",
        textbook = "数学分析习题册",
        formulas = listOf(
            ProblemFormula(orderIndex = 0, latex = "\\int_0^1 f = 1", confidence = 0.9, needsReview = false, originalSnippet = "\\int_0^1 f = 1"),
            ProblemFormula(orderIndex = 1, latex = "x ≥ 0", confidence = 0.5, needsReview = true, originalSnippet = "x ≥ 0"),
        ),
        knowledgePoints = listOf(
            ProblemKnowledgePoint(name = "一元积分", source = ProblemAnalysisSource.HEURISTIC),
        ),
        estimatedDifficulty = 3,
        estimatedMinutes = 20,
        learningSuggestion = "建议先复习积分定义。",
        dependsOn = listOf("11"),
        needsReview = setOf(ProblemDraftField.FORMULA),
        warnings = listOf("已过滤页码/页眉等噪声行"),
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val draft = sample()

        val decoded = ProblemDraftJson.decode(ProblemDraftJson.encode(draft), documentId = 7L)

        assertEquals(draft.localId, decoded.localId)
        assertEquals(draft.pageIndex, decoded.pageIndex)
        assertEquals(draft.problemNumber, decoded.problemNumber)
        assertEquals(draft.statement, decoded.statement)
        assertEquals(draft.chapter, decoded.chapter)
        assertEquals(draft.courseName, decoded.courseName)
        assertEquals(draft.textbook, decoded.textbook)
        assertEquals(draft.formulas, decoded.formulas)
        assertEquals(draft.knowledgePoints, decoded.knowledgePoints)
        assertEquals(draft.estimatedDifficulty, decoded.estimatedDifficulty)
        assertEquals(draft.estimatedMinutes, decoded.estimatedMinutes)
        assertEquals(draft.learningSuggestion, decoded.learningSuggestion)
        assertEquals(draft.dependsOn, decoded.dependsOn)
        assertEquals(draft.needsReview, decoded.needsReview)
        assertEquals(draft.warnings, decoded.warnings)
    }

    @Test
    fun nullOptionals_surviveRoundTrip() {
        val draft = sample().copy(
            estimatedDifficulty = null,
            estimatedMinutes = null,
            learningSuggestion = null,
            dependsOn = emptyList(),
        )

        val decoded = ProblemDraftJson.decode(ProblemDraftJson.encode(draft), documentId = 7L)

        assertEquals(null, decoded.estimatedDifficulty)
        assertEquals(null, decoded.estimatedMinutes)
        assertEquals(null, decoded.learningSuggestion)
        assertTrue(decoded.dependsOn.isEmpty())
    }

    @Test
    fun documentId_takenFromParameter_notFromJson() {
        val encoded = ProblemDraftJson.encode(sample())

        val decoded = ProblemDraftJson.decode(encoded, documentId = 99L)

        assertEquals(99L, decoded.documentId)
    }

    @Test
    fun draftLists_roundTripInOrder() {
        val drafts = listOf(sample(), sample().copy(localId = "p1_s0-ffffff00", pageIndex = 1, problemNumber = "13"))

        val encoded = ProblemDraftJson.encodeDrafts(drafts)
        val decoded = ProblemDraftJson.decodeDrafts(encoded, documentId = 7L)

        assertEquals(2, decoded.size)
        assertEquals("12", decoded[0].problemNumber)
        assertEquals("13", decoded[1].problemNumber)
    }

    @Test
    fun blankJson_decodesToEmptyList() {
        assertTrue(ProblemDraftJson.decodeDrafts("", documentId = 1L).isEmpty())
    }
}
