package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemDraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemDeduplicatorTest {

    private fun draft(
        localId: String,
        documentId: Long = 1L,
        chapter: String = "第一章",
        number: String,
        textbook: String = "教材A",
    ) = ProblemDraft(
        localId = localId,
        documentId = documentId,
        pageIndex = 0,
        problemNumber = number,
        statement = "题干内容 $localId",
        chapter = chapter,
        courseName = "数学分析",
        textbook = textbook,
    )

    @Test
    fun sameSourceSameChapterSameNumber_isDuplicate() {
        val analyses = ProblemDeduplicator.analyzeAll(
            drafts = listOf(draft("a", number = "1"), draft("b", number = "1")),
            existingKeys = emptySet(),
            suspectedKeys = emptySet(),
        )

        assertEquals(ProblemDraftState.READY, analyses.getValue("a").state)
        assertEquals(ProblemDraftState.DUPLICATE, analyses.getValue("b").state)
        assertTrue(analyses.getValue("b").duplicateOf!!.contains("本批"))
    }

    @Test
    fun existingKey_blocksImport() {
        val analyses = ProblemDeduplicator.analyzeAll(
            drafts = listOf(draft("a", number = "5")),
            existingKeys = setOf(ProblemDeduplicator.key(1L, "第一章", "5")),
            suspectedKeys = emptySet(),
        )

        assertEquals(ProblemDraftState.DUPLICATE, analyses.getValue("a").state)
        assertTrue(analyses.getValue("a").duplicateOf!!.contains("已存在"))
    }

    @Test
    fun crossSourceSameTextbook_isSuspectedButSelectable() {
        val analyses = ProblemDeduplicator.analyzeAll(
            drafts = listOf(draft("a", documentId = 2L, number = "3")),
            existingKeys = emptySet(),
            suspectedKeys = setOf(ProblemDeduplicator.suspectedKey("教材A", "第一章", "3")),
        )

        assertEquals(ProblemDraftState.SUSPECTED_DUPLICATE, analyses.getValue("a").state)
        assertTrue(analyses.getValue("a").isSelectable)
    }

    @Test
    fun differentChapter_isNotDuplicate() {
        val analyses = ProblemDeduplicator.analyzeAll(
            drafts = listOf(draft("a", number = "1", chapter = "第一章"), draft("b", number = "1", chapter = "第二章")),
            existingKeys = emptySet(),
            suspectedKeys = emptySet(),
        )

        assertEquals(ProblemDraftState.READY, analyses.getValue("a").state)
        assertEquals(ProblemDraftState.READY, analyses.getValue("b").state)
    }

    @Test
    fun invalidDrafts_areIncomplete() {
        val analyses = ProblemDeduplicator.analyzeAll(
            drafts = listOf(draft("a", number = ""), draft("b", number = "1").copy(statement = "")),
            existingKeys = emptySet(),
            suspectedKeys = emptySet(),
        )

        assertEquals(ProblemDraftState.INCOMPLETE, analyses.getValue("a").state)
        assertEquals(ProblemDraftState.INCOMPLETE, analyses.getValue("b").state)
        assertNull(analyses.getValue("a").duplicateOf)
    }

    @Test
    fun duplicateKeepsFirstOccurrence_evenWhenFirstHasWarning() {
        // 第一条 SUSPECTED（仍可选），第二条完全同键 → 重复
        val analyses = ProblemDeduplicator.analyzeAll(
            drafts = listOf(draft("a", number = "7"), draft("b", number = "7")),
            existingKeys = emptySet(),
            suspectedKeys = setOf(ProblemDeduplicator.suspectedKey("教材A", "第一章", "7")),
        )

        assertEquals(ProblemDraftState.SUSPECTED_DUPLICATE, analyses.getValue("a").state)
        assertEquals(ProblemDraftState.DUPLICATE, analyses.getValue("b").state)
    }
}
