package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.HistoryDao
import com.zhixu.studyos.core.data.local.KnowledgeDao
import com.zhixu.studyos.core.data.local.KnowledgeDependencyEntity
import com.zhixu.studyos.core.data.local.KnowledgeNodeEntity
import com.zhixu.studyos.core.data.local.KnowledgeProblemLinkEntity
import com.zhixu.studyos.core.data.local.ProblemBankDao
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.TaskEventEntity
import com.zhixu.studyos.core.knowledge.KnowledgeDependency
import com.zhixu.studyos.core.knowledge.KnowledgeDependencySuggestion
import com.zhixu.studyos.core.knowledge.KnowledgeGraph
import com.zhixu.studyos.core.knowledge.KnowledgeGraphEngine
import com.zhixu.studyos.core.knowledge.KnowledgeNode
import com.zhixu.studyos.core.knowledge.KnowledgeNodeDetail
import com.zhixu.studyos.core.knowledge.KnowledgeSeed
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

interface KnowledgeRepository {
    fun observeGraph(): Flow<KnowledgeGraph>
    suspend fun ensureSeeded()
    suspend fun getGraph(): KnowledgeGraph
    suspend fun getNode(id: Long): KnowledgeNode?
    suspend fun nodeDetail(nodeId: Long): KnowledgeNodeDetail?
    suspend fun saveNode(node: KnowledgeNode): Long
    suspend fun deleteNode(nodeId: Long)
    suspend fun updateNote(nodeId: Long, note: String, mastery: Int?)
    suspend fun addDependency(fromNodeId: Long, toNodeId: Long): String?
    suspend fun deleteDependency(dependencyId: Long)
    suspend fun applyAiSuggestions(
        suggestions: List<KnowledgeDependencySuggestion>,
    ): Pair<Int, List<String>>
    suspend fun syncProblemLinks(): Int
    suspend fun linkedProblemIds(nodeId: Long): List<Long>
    suspend fun linkProblem(nodeId: Long, problemId: Long)
    suspend fun unlinkProblem(nodeId: Long, problemId: Long)
    suspend fun applyAttemptSignal(
        subject: String,
        knowledgeNames: List<String>,
        correct: Boolean,
        independent: Boolean,
        hintLevel: Int,
    )
}

class OfflineKnowledgeRepository(
    private val database: StudyOSDatabase,
    private val dao: KnowledgeDao,
    private val problemBankDao: ProblemBankDao,
    private val historyDao: HistoryDao,
    private val clock: Clock,
) : KnowledgeRepository {
    override fun observeGraph(): Flow<KnowledgeGraph> = combine(
        dao.observeNodes(),
        dao.observeDependencies(),
    ) { nodes, dependencies ->
        KnowledgeGraph(
            nodes = nodes.map { it.toDomainModel() },
            dependencies = dependencies.map { it.toDomainModel() },
        )
    }.onStart { ensureSeeded() }

    override suspend fun ensureSeeded() {
        if (dao.nodeCount() > 0) {
            syncProblemLinks()
            return
        }
        database.withTransaction {
            val now = clock.millis()
            val idByName = mutableMapOf<String, Long>()
            KnowledgeSeed.nodes.forEach { seed ->
                val existing = dao.findNode(seed.subject, seed.name)
                val id = existing?.id ?: dao.insertNode(
                    KnowledgeNodeEntity(
                        subject = seed.subject,
                        chapter = seed.chapter,
                        name = seed.name,
                        definition = seed.definition,
                        theorems = seed.theorems,
                        note = "",
                        mastery = 0,
                        masterySource = "USER",
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                        revision = 1,
                    ),
                )
                idByName[seed.name] = id
            }
            KnowledgeSeed.dependencies.forEach { (fromName, toName) ->
                val from = idByName[fromName] ?: return@forEach
                val to = idByName[toName] ?: return@forEach
                dao.insertDependency(
                    KnowledgeDependencyEntity(
                        fromNodeId = from,
                        toNodeId = to,
                        source = "SEED",
                        createdAtEpochMillis = now,
                    ),
                )
            }
            linkProblemsByName(now)
        }
    }

    override suspend fun getGraph(): KnowledgeGraph {
        ensureSeeded()
        return KnowledgeGraph(
            nodes = dao.getNodes().map { it.toDomainModel() },
            dependencies = dao.getDependencies().map { it.toDomainModel() },
        )
    }

    override suspend fun getNode(id: Long): KnowledgeNode? = dao.getNode(id)?.toDomainModel()

    override suspend fun nodeDetail(nodeId: Long): KnowledgeNodeDetail? {
        val node = dao.getNode(nodeId)?.toDomainModel() ?: return null
        val graph = getGraph()
        return KnowledgeNodeDetail(
            node = node,
            prerequisites = KnowledgeGraphEngine.prerequisitesOf(graph, nodeId),
            successors = KnowledgeGraphEngine.successorsOf(graph, nodeId),
            linkedProblemIds = dao.linksForNode(nodeId).map { it.problemId },
            relatedNodeNamesFromExercises = emptyList(),
        )
    }

    override suspend fun saveNode(node: KnowledgeNode): Long {
        val subject = node.subject.trim()
        val name = node.name.trim()
        require(subject.isNotEmpty()) { "请填写所属科目" }
        require(name.isNotEmpty()) { "请填写知识点名称" }
        require(name.length <= 80) { "知识点名称过长" }
        require(node.mastery in 0..5) { "掌握程度应在 0–5 之间" }
        val now = clock.millis()
        return database.withTransaction {
            if (node.id == 0L) {
                require(dao.findNode(subject, name) == null) { "同一科目下已存在知识点「$name」" }
                dao.insertNode(
                    KnowledgeNodeEntity(
                        subject = subject,
                        chapter = node.chapter.trim(),
                        name = name,
                        definition = node.definition.trim(),
                        theorems = node.theorems.trim(),
                        note = node.note.trim(),
                        mastery = node.mastery,
                        masterySource = "USER",
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                        revision = 1,
                    ),
                )
            } else {
                val existing = dao.getNode(node.id) ?: throw IllegalArgumentException("知识点不存在，可能已被删除")
                if (existing.subject != subject || existing.name != name) {
                    val clash = dao.findNode(subject, name)
                    require(clash == null || clash.id == node.id) { "同一科目下已存在知识点「$name」" }
                }
                dao.updateNode(
                    existing.copy(
                        subject = subject,
                        chapter = node.chapter.trim(),
                        name = name,
                        definition = node.definition.trim(),
                        theorems = node.theorems.trim(),
                        note = node.note.trim(),
                        mastery = node.mastery.coerceIn(0, 5),
                        masterySource = "USER",
                        updatedAtEpochMillis = now,
                        revision = existing.revision + 1,
                    ),
                )
                historyDao.insert(
                    TaskEventEntity(
                        objectType = "KNOWLEDGE",
                        objectId = node.id,
                        eventType = "KNOWLEDGE_UPDATED",
                        occurredAtEpochMillis = now,
                        beforeJson = null,
                        afterJson = org.json.JSONObject()
                            .put("subject", subject)
                            .put("name", name)
                            .put("mastery", node.mastery)
                            .toString(),
                        source = "USER",
                        reason = "用户编辑知识点",
                        sessionId = null,
                        revision = existing.revision + 1,
                    ),
                )
                node.id
            }
        }
    }

    override suspend fun deleteNode(nodeId: Long) {
        database.withTransaction {
            val node = dao.getNode(nodeId) ?: return@withTransaction
            historyDao.insert(
                TaskEventEntity(
                    objectType = "KNOWLEDGE",
                    objectId = nodeId,
                    eventType = "KNOWLEDGE_UPDATED",
                    occurredAtEpochMillis = clock.millis(),
                    beforeJson = """{"subject":${org.json.JSONObject.quote(node.subject)},"name":${org.json.JSONObject.quote(node.name)}}""",
                    afterJson = null,
                    source = "USER",
                    reason = "用户删除知识点",
                    sessionId = null,
                    revision = node.revision,
                ),
            )
            dao.deleteNode(nodeId)
        }
    }

    override suspend fun updateNote(nodeId: Long, note: String, mastery: Int?) {
        database.withTransaction {
            val existing = dao.getNode(nodeId) ?: throw IllegalArgumentException("知识点不存在")
            require(note.length <= 5000) { "笔记过长" }
            mastery?.let { require(it in 0..5) { "掌握程度应在 0–5 之间" } }
            dao.updateNode(
                existing.copy(
                    note = note.trim(),
                    mastery = mastery ?: existing.mastery,
                    masterySource = if (mastery != null) "USER" else existing.masterySource,
                    updatedAtEpochMillis = clock.millis(),
                    revision = existing.revision + 1,
                ),
            )
        }
    }

    override suspend fun addDependency(fromNodeId: Long, toNodeId: Long): String? {
        ensureSeeded()
        val graph = getGraph()
        val error = KnowledgeGraphEngine.validateDependency(graph, fromNodeId, toNodeId)
        if (error != null) return error
        val now = clock.millis()
        return database.withTransaction {
            dao.insertDependency(
                KnowledgeDependencyEntity(
                    fromNodeId = fromNodeId,
                    toNodeId = toNodeId,
                    source = "USER",
                    createdAtEpochMillis = now,
                ),
            )
            historyDao.insert(
                TaskEventEntity(
                    objectType = "KNOWLEDGE",
                    objectId = toNodeId,
                    eventType = "KNOWLEDGE_DEPENDENCY_CHANGED",
                    occurredAtEpochMillis = now,
                    beforeJson = null,
                    afterJson = """{"from":$fromNodeId,"to":$toNodeId}""",
                    source = "USER",
                    reason = "添加前置知识依赖",
                    sessionId = null,
                    revision = 0,
                ),
            )
            null
        }
    }

    override suspend fun deleteDependency(dependencyId: Long) {
        database.withTransaction {
            val dependency = dao.getDependency(dependencyId) ?: return@withTransaction
            dao.deleteDependency(dependencyId)
            historyDao.insert(
                TaskEventEntity(
                    objectType = "KNOWLEDGE",
                    objectId = dependency.toNodeId,
                    eventType = "KNOWLEDGE_DEPENDENCY_CHANGED",
                    occurredAtEpochMillis = clock.millis(),
                    beforeJson = """{"from":${dependency.fromNodeId},"to":${dependency.toNodeId}}""",
                    afterJson = null,
                    source = "USER",
                    reason = "删除知识依赖",
                    sessionId = null,
                    revision = 0,
                ),
            )
        }
    }

    override suspend fun applyAiSuggestions(
        suggestions: List<KnowledgeDependencySuggestion>,
    ): Pair<Int, List<String>> {
        ensureSeeded()
        val graph = getGraph()
        val (accepted, rejected) = KnowledgeGraphEngine.validateSuggestions(graph, suggestions)
        if (accepted.isEmpty()) return 0 to rejected
        val now = clock.millis()
        return database.withTransaction {
            var inserted = 0
            accepted.forEach { suggestion ->
                inserted += dao.insertDependency(
                    KnowledgeDependencyEntity(
                        fromNodeId = suggestion.fromNodeId,
                        toNodeId = suggestion.toNodeId,
                        source = "AI_SUGGESTION",
                        createdAtEpochMillis = now,
                    ),
                ).takeIf { it > 0 }?.let { 1 } ?: 0
            }
            historyDao.insert(
                TaskEventEntity(
                    objectType = "KNOWLEDGE",
                    objectId = 0,
                    eventType = if (inserted > 0) "AI_SUGGESTION_ACCEPTED" else "AI_SUGGESTION_REJECTED",
                    occurredAtEpochMillis = now,
                    beforeJson = null,
                    afterJson = org.json.JSONArray(accepted.map { "${it.fromNodeId}->${it.toNodeId}" }).toString(),
                    source = "USER",
                    reason = "用户确认 AI 知识关联建议，共 $inserted 条",
                    sessionId = null,
                    revision = 0,
                ),
            )
            inserted to rejected
        }
    }

    override suspend fun syncProblemLinks(): Int {
        val existing = dao.getNodes()
        if (existing.isEmpty()) return 0
        val now = clock.millis()
        var inserted = 0
        database.withTransaction {
            linkProblemsByName(now).also { inserted += it }
        }
        return inserted
    }

    private suspend fun linkProblemsByName(now: Long): Int {
        val nodesByName = dao.getNodes().groupBy { it.name }
        if (nodesByName.isEmpty()) return 0
        var inserted = 0
        problemBankDao.allKnowledgePointLinks().forEach { link ->
            nodesByName[link.name].orEmpty().forEach { node ->
                val id = dao.insertProblemLink(
                    KnowledgeProblemLinkEntity(
                        nodeId = node.id,
                        problemId = link.problemId,
                        source = "HEURISTIC",
                        createdAtEpochMillis = now,
                    ),
                )
                if (id > 0) inserted += 1
            }
        }
        return inserted
    }

    override suspend fun linkedProblemIds(nodeId: Long): List<Long> =
        dao.linksForNode(nodeId).map { it.problemId }

    override suspend fun linkProblem(nodeId: Long, problemId: Long) {
        dao.insertProblemLink(
            KnowledgeProblemLinkEntity(
                nodeId = nodeId,
                problemId = problemId,
                source = "USER",
                createdAtEpochMillis = clock.millis(),
            ),
        )
    }

    override suspend fun unlinkProblem(nodeId: Long, problemId: Long) {
        dao.deleteProblemLink(nodeId, problemId)
    }

    /**
     * 多次失败才降低掌握度；单次失败不会直接把知识点标成未掌握。
     * 独立、无提示地完成一次会提升掌握度；依赖提示的完成不提升。
     */
    override suspend fun applyAttemptSignal(
        subject: String,
        knowledgeNames: List<String>,
        correct: Boolean,
        independent: Boolean,
        hintLevel: Int,
    ) {
        if (knowledgeNames.isEmpty()) return
        val now = clock.millis()
        database.withTransaction {
            knowledgeNames.distinct().forEach { name ->
                val node = dao.getNodes().firstOrNull {
                    it.name == name && (subject.isBlank() || it.subject == subject)
                } ?: dao.getNodes().firstOrNull { it.name == name } ?: return@forEach
                val delta = when {
                    !correct -> -1
                    independent && hintLevel == 0 -> 1
                    else -> 0
                }
                if (delta == 0) return@forEach
                val updatedMastery = (node.mastery + delta).coerceIn(0, 5)
                dao.updateNode(
                    node.copy(
                        mastery = updatedMastery,
                        masterySource = "ATTEMPT",
                        updatedAtEpochMillis = now,
                        revision = node.revision + 1,
                    ),
                )
                historyDao.insert(
                    TaskEventEntity(
                        objectType = "KNOWLEDGE",
                        objectId = node.id,
                        eventType = "KNOWLEDGE_UPDATED",
                        occurredAtEpochMillis = now,
                        beforeJson = """{"mastery":${node.mastery}}""",
                        afterJson = """{"mastery":$updatedMastery}""",
                        source = "SYSTEM",
                        reason = if (correct) "独立完成习题" else "习题尝试未通过",
                        sessionId = null,
                        revision = node.revision + 1,
                    ),
                )
            }
        }
    }
}

private fun KnowledgeNodeEntity.toDomainModel() = KnowledgeNode(
    id = id,
    subject = subject,
    chapter = chapter,
    name = name,
    definition = definition,
    theorems = theorems,
    note = note,
    mastery = mastery,
    masterySource = masterySource,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    revision = revision,
)

private fun KnowledgeDependencyEntity.toDomainModel() = KnowledgeDependency(
    id = id,
    fromNodeId = fromNodeId,
    toNodeId = toNodeId,
    source = source,
)
