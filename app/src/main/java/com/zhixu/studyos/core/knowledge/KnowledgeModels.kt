package com.zhixu.studyos.core.knowledge

import java.time.Instant

data class KnowledgeNode(
    val id: Long = 0,
    val subject: String,
    val chapter: String,
    val name: String,
    val definition: String = "",
    val theorems: String = "",
    val note: String = "",
    val mastery: Int = 0,
    val masterySource: String = "USER",
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
    val revision: Int = 1,
) {
    val masteryLabel: String
        get() = when (mastery) {
            0 -> "未评估"
            1 -> "薄弱"
            2 -> "初步了解"
            3 -> "基本掌握"
            4 -> "熟练"
            5 -> "牢固掌握"
            else -> "未评估"
        }
}

/** from 是前置知识，to 是后续知识。 */
data class KnowledgeDependency(
    val id: Long = 0,
    val fromNodeId: Long,
    val toNodeId: Long,
    val source: String = "USER",
)

data class KnowledgeGraph(
    val nodes: List<KnowledgeNode> = emptyList(),
    val dependencies: List<KnowledgeDependency> = emptyList(),
) {
    fun nodeById(id: Long): KnowledgeNode? = nodes.firstOrNull { it.id == id }
    val dependencyCount: Int get() = dependencies.size
}

data class KnowledgeNodeDetail(
    val node: KnowledgeNode,
    val prerequisites: List<KnowledgeNode>,
    val successors: List<KnowledgeNode>,
    val linkedProblemIds: List<Long>,
    val relatedNodeNamesFromExercises: List<String>,
)

/** 可视化节点：level 为拓扑层级，order 为同层顺序。 */
data class KnowledgeGraphLayoutNode(
    val node: KnowledgeNode,
    val level: Int,
    val order: Int,
)

data class KnowledgeGraphLayout(
    val nodes: List<KnowledgeGraphLayoutNode>,
    val levels: Int,
)

data class KnowledgeDependencySuggestion(
    val fromNodeId: Long,
    val toNodeId: Long,
    val reason: String,
)

object KnowledgeGraphEngine {
    /**
     * 建立“前置 → 后续”的有向无环图。重复边被忽略，自依赖和成环依赖被拒绝。
     * 允许跨科目依赖，但 UI 会提示用户确认。
     */
    fun validateDependency(
        graph: KnowledgeGraph,
        fromNodeId: Long,
        toNodeId: Long,
    ): String? {
        if (fromNodeId == toNodeId) return "知识点不能依赖自身"
        if (graph.nodeById(fromNodeId) == null) return "前置知识点不存在"
        if (graph.nodeById(toNodeId) == null) return "后续知识点不存在"
        if (graph.dependencies.any { it.fromNodeId == fromNodeId && it.toNodeId == toNodeId }) {
            return "这条依赖已经存在"
        }
        if (wouldCreateCycle(graph, fromNodeId, toNodeId)) {
            return "该依赖会形成环，知识图谱必须是有向无环图"
        }
        return null
    }

    fun wouldCreateCycle(
        graph: KnowledgeGraph,
        fromNodeId: Long,
        toNodeId: Long,
    ): Boolean {
        // 添加 from → to 后成环，当且仅当已存在 to → ... → from 的路径。
        val adjacency = graph.dependencies.groupBy({ it.fromNodeId }, { it.toNodeId })
        val stack = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        stack.addLast(toNodeId)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current == fromNodeId) return true
            if (!visited.add(current)) continue
            adjacency[current].orEmpty().forEach(stack::addLast)
        }
        return false
    }

    fun prerequisitesOf(graph: KnowledgeGraph, nodeId: Long): List<KnowledgeNode> {
        val ids = graph.dependencies.filter { it.toNodeId == nodeId }.map { it.fromNodeId }
        return ids.distinct().mapNotNull(graph::nodeById)
    }

    fun successorsOf(graph: KnowledgeGraph, nodeId: Long): List<KnowledgeNode> {
        val ids = graph.dependencies.filter { it.fromNodeId == nodeId }.map { it.toNodeId }
        return ids.distinct().mapNotNull(graph::nodeById)
    }

    /** 全部前置知识（递归），用于“学不会这个，先补哪些”。 */
    fun prerequisiteClosure(graph: KnowledgeGraph, nodeId: Long): List<KnowledgeNode> {
        val visited = mutableSetOf<Long>()
        val result = mutableListOf<KnowledgeNode>()
        val stack = ArrayDeque<Long>()
        prerequisitesOf(graph, nodeId).forEach { stack.addLast(it.id) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!visited.add(current)) continue
            graph.nodeById(current)?.let(result::add)
            prerequisitesOf(graph, current).forEach { stack.addLast(it.id) }
        }
        return result
    }

    /** 拓扑分层：没有前置知识的节点在第 0 层。孤立节点也单独显示。 */
    fun layout(graph: KnowledgeGraph, subject: String? = null): KnowledgeGraphLayout {
        val filtered = if (subject == null) graph else graph.copy(
            nodes = graph.nodes.filter { it.subject == subject },
            dependencies = graph.dependencies.filter { dependency ->
                graph.nodes.any { it.id == dependency.fromNodeId && it.subject == subject } &&
                    graph.nodes.any { it.id == dependency.toNodeId && it.subject == subject }
            },
        )
        val ids = filtered.nodes.map { it.id }.toSet()
        val indegree = filtered.nodes.associate { node ->
            node.id to filtered.dependencies.count { it.toNodeId == node.id && it.fromNodeId in ids }
        }.toMutableMap()
        val outgoing = filtered.dependencies
            .filter { it.fromNodeId in ids && it.toNodeId in ids }
            .groupBy { it.fromNodeId }
        val levels = mutableMapOf<Long, Int>()
        val queue = ArrayDeque<Long>()
        indegree.filterValues { it == 0 }.keys.forEach { nodeId ->
            levels[nodeId] = 0
            queue.addLast(nodeId)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val level = levels.getValue(current)
            outgoing[current].orEmpty().forEach { dependency ->
                val target = dependency.toNodeId
                levels[target] = maxOf(levels[target] ?: 0, level + 1)
                val remaining = (indegree[target] ?: 1) - 1
                indegree[target] = remaining
                if (remaining <= 0) queue.addLast(target)
            }
        }
        // 理论上无环；若数据异常仍有未分层节点，追加到最后一层而不是丢失。
        filtered.nodes.filter { it.id !in levels }.forEach { node ->
            levels[node.id] = (levels.values.maxOrNull() ?: -1) + 1
        }
        val nodes = filtered.nodes
            .sortedWith(compareBy({ levels[it.id] ?: 0 }, { it.chapter }, { it.name }))
            .mapIndexed { index, node ->
                val level = levels[node.id] ?: 0
                val order = filtered.nodes
                    .filter { (levels[it.id] ?: 0) == level }
                    .sortedWith(compareBy({ it.chapter }, { it.name }))
                    .indexOfFirst { it.id == node.id }
                KnowledgeGraphLayoutNode(node = node, level = level, order = order.coerceAtLeast(0))
            }
        return KnowledgeGraphLayout(
            nodes = nodes,
            levels = (levels.values.maxOrNull() ?: 0) + 1,
        )
    }

    /**
     * 校验 AI 给出的依赖建议：只能引用已存在节点、不能自依赖、不能成环、不能重复。
     * 返回可应用的候选；任何一条不合法的建议都会被拒绝并说明原因。
     */
    fun validateSuggestions(
        graph: KnowledgeGraph,
        suggestions: List<KnowledgeDependencySuggestion>,
    ): Pair<List<KnowledgeDependencySuggestion>, List<String>> {
        val accepted = mutableListOf<KnowledgeDependencySuggestion>()
        val rejected = mutableListOf<String>()
        val working = graph.dependencies.toMutableList()
        suggestions.forEach { suggestion ->
            val draftGraph = graph.copy(dependencies = working)
            val error = validateDependency(draftGraph, suggestion.fromNodeId, suggestion.toNodeId)
            if (error != null) {
                rejected += "${suggestion.fromNodeId} → ${suggestion.toNodeId}：$error"
            } else {
                accepted += suggestion
                working += KnowledgeDependency(
                    fromNodeId = suggestion.fromNodeId,
                    toNodeId = suggestion.toNodeId,
                    source = "AI_SUGGESTION",
                )
            }
        }
        return accepted to rejected
    }
}

/**
 * 多次做错后的复习建议。至少两次失败才建议前置知识，不用单题失败断定未掌握。
 */
object KnowledgeReviewAdvisor {
    data class Suggestion(
        val node: KnowledgeNode,
        val prerequisites: List<KnowledgeNode>,
        val message: String,
    )

    fun suggest(
        node: KnowledgeNode,
        graph: KnowledgeGraph,
        failedAttempts: Int,
    ): Suggestion? {
        if (failedAttempts < 2) return null
        val prerequisites = KnowledgeGraphEngine.prerequisiteClosure(graph, node.id)
            .filter { it.mastery in 0..2 }
            .take(4)
        if (prerequisites.isEmpty()) return null
        return Suggestion(
            node = node,
            prerequisites = prerequisites,
            message = "「${node.name}」已连续 $failedAttempts 次未通过；可先回顾：" +
                prerequisites.joinToString("、") { it.name } +
                "。这只是基于记录的复习建议，不代表你一定没有掌握。",
        )
    }
}
