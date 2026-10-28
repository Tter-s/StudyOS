package com.zhixu.studyos.feature.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.ai.AiPlanParser
import com.zhixu.studyos.core.ai.AiTaskRequest
import com.zhixu.studyos.core.ai.AiTaskService
import com.zhixu.studyos.core.ai.AiTaskType
import com.zhixu.studyos.core.data.preferences.AiConfigStore
import com.zhixu.studyos.core.data.repository.KnowledgeRepository
import com.zhixu.studyos.core.knowledge.KnowledgeDependencySuggestion
import com.zhixu.studyos.core.knowledge.KnowledgeGraph
import com.zhixu.studyos.core.knowledge.KnowledgeGraphEngine
import com.zhixu.studyos.core.knowledge.KnowledgeGraphLayout
import com.zhixu.studyos.core.knowledge.KnowledgeNode
import com.zhixu.studyos.core.knowledge.KnowledgeNodeDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class KnowledgeUiState(
    val isLoading: Boolean = true,
    val graph: KnowledgeGraph = KnowledgeGraph(),
    val subject: String = "",
    val layout: KnowledgeGraphLayout = KnowledgeGraphLayout(emptyList(), 0),
    val detail: KnowledgeNodeDetail? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
    val isAiLoading: Boolean = false,
    val aiSuggestions: List<KnowledgeDependencySuggestion> = emptyList(),
    val aiRejected: List<String> = emptyList(),
    val aiDialogVisible: Boolean = false,
) {
    val subjects: List<String> get() = graph.nodes.map { it.subject }.distinct().sorted()
    val nodeCount: Int get() = graph.nodes.size
    val dependencyCount: Int get() = graph.dependencies.size
}

class KnowledgeViewModel(
    private val repository: KnowledgeRepository,
    private val aiTaskService: AiTaskService,
    private val configStore: AiConfigStore,
) : ViewModel() {
    private val mutable = MutableStateFlow(KnowledgeUiState())
    val uiState: StateFlow<KnowledgeUiState> = mutable

    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            repository.observeGraph()
                .catch {
                    mutable.value = mutable.value.copy(
                        isLoading = false,
                        errorMessage = it.message ?: "无法读取知识图谱",
                    )
                }
                .collect { graph ->
                    mutable.value = mutable.value.copy(
                        isLoading = false,
                        graph = graph,
                        layout = KnowledgeGraphEngine.layout(graph, mutable.value.subject.ifBlank { null }),
                        detail = mutable.value.detail?.let { detail ->
                            graph.nodeById(detail.node.id)?.let { updated ->
                                detail.copy(node = updated)
                            }
                        },
                    )
                }
        }
    }

    fun setSubject(subject: String) {
        mutable.value = mutable.value.copy(
            subject = subject,
            layout = KnowledgeGraphEngine.layout(
                mutable.value.graph,
                subject.ifBlank { null },
            ),
        )
    }

    fun openNode(nodeId: Long) {
        viewModelScope.launch {
            runCatching { repository.nodeDetail(nodeId) }
                .onSuccess { mutable.value = mutable.value.copy(detail = it) }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "无法读取知识点") }
        }
    }

    fun closeNode() {
        mutable.value = mutable.value.copy(detail = null)
    }

    fun saveNode(node: KnowledgeNode, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveNode(node) }
                .onSuccess { id ->
                    mutable.value = mutable.value.copy(notice = "知识点已保存")
                    if (id > 0) openNode(id)
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存知识点失败") }
        }
    }

    fun deleteNode(nodeId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteNode(nodeId) }
                .onSuccess {
                    mutable.value = mutable.value.copy(
                        detail = null,
                        notice = "知识点已删除；相关依赖与题目关联也已删除",
                    )
                }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除失败") }
        }
    }

    fun saveNote(nodeId: Long, note: String, mastery: Int?, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.updateNote(nodeId, note, mastery) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "笔记与掌握程度已保存")
                    openNode(nodeId)
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存失败") }
        }
    }

    fun addDependency(fromNodeId: Long, toNodeId: Long) {
        viewModelScope.launch {
            runCatching { repository.addDependency(fromNodeId, toNodeId) }
                .onSuccess { error ->
                    mutable.value = mutable.value.copy(
                        notice = error ?: "依赖已添加",
                    )
                    if (error == null) openNode(toNodeId)
                }
                .onFailure {
                    mutable.value = mutable.value.copy(notice = it.message ?: "添加依赖失败")
                }
        }
    }

    fun deleteDependency(fromNodeId: Long, toNodeId: Long) {
        viewModelScope.launch {
            val dependency = mutable.value.graph.dependencies.firstOrNull {
                it.fromNodeId == fromNodeId && it.toNodeId == toNodeId
            } ?: return@launch
            runCatching { repository.deleteDependency(dependency.id) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "依赖已删除")
                    openNode(toNodeId)
                }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除依赖失败") }
        }
    }

    fun requestAiSuggestions() {
        if (mutable.value.isAiLoading) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isAiLoading = true, notice = null)
            runCatching {
                val graph = mutedGraph()
                if (graph.nodes.size < 2) throw IllegalStateException("知识点太少，无法提供有意义的关联建议")
                val payload = graphPayload(graph)
                val config = configStore.getConfig()
                val cacheKey = aiTaskService.cacheKey(
                    AiTaskType.KNOWLEDGE_LINK,
                    PROMPT_VERSION,
                    config.model,
                    payload,
                )
                val prompts = prompts(payload)
                val result = aiTaskService.run(
                    request = AiTaskRequest(
                        taskType = AiTaskType.KNOWLEDGE_LINK,
                        promptVersion = PROMPT_VERSION,
                        systemPrompt = prompts.first,
                        userPrompt = prompts.second,
                        cacheKey = cacheKey,
                        objectType = "KNOWLEDGE",
                        objectId = null,
                        maxOutputTokens = 1500,
                        note = "AI 知识点关联建议（未应用）",
                    ),
                    validateContent = { content -> parseSuggestions(content, graph) },
                )
                val (accepted, rejected) = parseSuggestions(result.content, graph)
                Triple(accepted, rejected, result)
            }.onSuccess { (accepted, rejected, result) ->
                mutable.value = mutable.value.copy(
                    isAiLoading = false,
                    aiSuggestions = accepted,
                    aiRejected = rejected,
                    aiDialogVisible = true,
                    notice = if (result.usedCache) "使用缓存的 AI 建议（未产生新费用）" else null,
                )
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isAiLoading = false,
                    notice = "AI 关联建议失败：${it.message ?: "未知错误"}",
                )
            }
        }
    }

    fun applyAiSuggestions() {
        val suggestions = mutable.value.aiSuggestions
        if (suggestions.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.applyAiSuggestions(suggestions) }
                .onSuccess { (inserted, rejected) ->
                    mutable.value = mutable.value.copy(
                        aiDialogVisible = false,
                        aiSuggestions = emptyList(),
                        aiRejected = rejected,
                        notice = "已添加 $inserted 条依赖" +
                            if (rejected.isEmpty()) "" else "；${rejected.size} 条未通过结构校验",
                    )
                }
                .onFailure {
                    mutable.value = mutable.value.copy(notice = it.message ?: "应用建议失败")
                }
        }
    }

    fun dismissAiDialog() {
        mutable.value = mutable.value.copy(aiDialogVisible = false)
    }

    fun consumeNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }

    private suspend fun mutedGraph(): KnowledgeGraph = mutable.value.graph

    private fun graphPayload(graph: KnowledgeGraph): String {
        val nodes = JSONArray()
        graph.nodes.forEach { node ->
            nodes.put(
                JSONObject()
                    .put("subject", node.subject)
                    .put("chapter", node.chapter)
                    .put("name", node.name),
            )
        }
        val edges = JSONArray()
        graph.dependencies.forEach { dependency ->
            val from = graph.nodeById(dependency.fromNodeId)?.name ?: return@forEach
            val to = graph.nodeById(dependency.toNodeId)?.name ?: return@forEach
            edges.put(JSONObject().put("from", from).put("to", to))
        }
        return JSONObject().put("nodes", nodes).put("existingDependencies", edges).toString()
    }

    private fun prompts(payload: String): Pair<String, String> {
        val system = """
你是数学知识图谱助手。你会收到已有知识点与已有依赖（from 是前置，to 是后续）。
只提出“前置 → 后续”的补充建议，必须：
- 只引用输入中存在的知识点名称；
- 不重复已有依赖；
- 不提出会形成环的依赖；
- 每条建议给出简短理由；
- 不覆盖用户手动建立的图。
只输出 JSON，不要 Markdown 围栏：
{"suggestions":[{"from":"前置知识点名称","to":"后续知识点名称","reason":"简短理由"}]}
最多 10 条。不确定时返回更少，不要编造不存在的知识点。
        """.trimIndent()
        return system to "知识点图谱：\n$payload"
    }

    private fun parseSuggestions(
        content: String,
        graph: KnowledgeGraph,
    ): Pair<List<KnowledgeDependencySuggestion>, List<String>> {
        val root = JSONObject(AiPlanParser.extractJson(content))
        val array = root.optJSONArray("suggestions") ?: JSONArray()
        val byName = graph.nodes.associateBy { it.name }
        val raw = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val from = byName[item.optString("from")] ?: return@mapNotNull null
            val to = byName[item.optString("to")] ?: return@mapNotNull null
            KnowledgeDependencySuggestion(
                fromNodeId = from.id,
                toNodeId = to.id,
                reason = item.optString("reason").take(200),
            )
        }
        return KnowledgeGraphEngine.validateSuggestions(graph, raw)
    }

    companion object {
        const val PROMPT_VERSION = "knowledge-link-v1"
    }
}
