package com.zhixu.studyos.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGraphEngineTest {
    private fun node(id: Long, name: String, subject: String = "数学分析", mastery: Int = 0) =
        KnowledgeNode(id = id, subject = subject, chapter = "第1章", name = name, mastery = mastery)

    private val graph = KnowledgeGraph(
        nodes = listOf(
            node(1, "数列极限"),
            node(2, "函数极限"),
            node(3, "连续性"),
            node(4, "导数"),
        ),
        dependencies = listOf(
            KnowledgeDependency(id = 1, fromNodeId = 1, toNodeId = 2),
            KnowledgeDependency(id = 2, fromNodeId = 2, toNodeId = 3),
            KnowledgeDependency(id = 3, fromNodeId = 3, toNodeId = 4),
        ),
    )

    @Test
    fun validateDependency_rejectsSelfDuplicateAndCycle() {
        assertNotNull(KnowledgeGraphEngine.validateDependency(graph, 1, 1))
        assertNotNull(KnowledgeGraphEngine.validateDependency(graph, 1, 2))
        assertNotNull(KnowledgeGraphEngine.validateDependency(graph, 4, 1))
        assertNull(KnowledgeGraphEngine.validateDependency(graph, 1, 4))
    }

    @Test
    fun prerequisiteClosure_returnsAllUpstreamNodes() {
        assertEquals(
            setOf("数列极限", "函数极限", "连续性"),
            KnowledgeGraphEngine.prerequisiteClosure(graph, 4).map { it.name }.toSet(),
        )
    }

    @Test
    fun layout_assignsTopologicalLevels() {
        val layout = KnowledgeGraphEngine.layout(graph)
        assertEquals(4, layout.levels)
        assertEquals(0, layout.nodes.first { it.node.id == 1L }.level)
        assertEquals(3, layout.nodes.first { it.node.id == 4L }.level)
    }

    @Test
    fun suggestions_neverApplyUnknownOrCyclicEdges() {
        val suggestions = listOf(
            KnowledgeDependencySuggestion(1, 2, "重复"),
            KnowledgeDependencySuggestion(4, 2, "成环"),
            KnowledgeDependencySuggestion(1, 4, "合理补充"),
        )
        val (accepted, rejected) = KnowledgeGraphEngine.validateSuggestions(graph, suggestions)
        assertEquals(1, accepted.size)
        assertEquals(2, rejected.size)
        assertEquals(1L to 4L, accepted.single().fromNodeId to accepted.single().toNodeId)
    }

    @Test
    fun reviewAdvisor_requiresAtLeastTwoFailures() {
        val strongGraph = graph.copy(
            nodes = graph.nodes.map {
                if (it.id == 2L || it.id == 3L) it.copy(mastery = 1) else it
            },
        )
        assertNull(KnowledgeReviewAdvisor.suggest(strongGraph.nodeById(4)!!, strongGraph, 1))
        val suggestion = KnowledgeReviewAdvisor.suggest(strongGraph.nodeById(4)!!, strongGraph, 2)
        assertNotNull(suggestion)
        assertTrue(suggestion!!.prerequisites.isNotEmpty())
        assertTrue(suggestion.message.contains("不代表你一定没有掌握"))
    }
}
