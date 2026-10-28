package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemAnalysisSource
import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemDraftField
import com.zhixu.studyos.core.model.ProblemFormula
import com.zhixu.studyos.core.model.ProblemKnowledgePoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * 题目草稿 ↔ JSON 编解码。
 * 草稿在流水线处理完一页后立即持久化，进程结束或取消后可从数据库恢复预览，
 * 避免「识别完成但未确认」的结果丢失。使用 Android 内置 org.json，不引入新依赖。
 */
object ProblemDraftJson {

    private const val FORMAT_VERSION = 1

    fun encode(draft: ProblemDraft): String {
        val formulas = JSONArray()
        draft.formulas.forEach { formula ->
            formulas.put(
                JSONObject()
                    .put("latex", formula.latex)
                    .put("confidence", formula.confidence)
                    .put("needsReview", formula.needsReview)
                    .put("snippet", formula.originalSnippet),
            )
        }
        val points = JSONArray()
        draft.knowledgePoints.forEach { point ->
            points.put(
                JSONObject()
                    .put("name", point.name)
                    .put("source", point.source.name),
            )
        }
        val review = JSONArray()
        draft.needsReview.forEach { review.put(it.name) }
        val warnings = JSONArray()
        draft.warnings.forEach { warnings.put(it) }
        val depends = JSONArray()
        draft.dependsOn.forEach { depends.put(it) }
        return JSONObject()
            .put("v", FORMAT_VERSION)
            .put("localId", draft.localId)
            .put("pageIndex", draft.pageIndex)
            .put("number", draft.problemNumber)
            .put("statement", draft.statement)
            .put("chapter", draft.chapter)
            .put("courseName", draft.courseName)
            .put("textbook", draft.textbook)
            .put("formulas", formulas)
            .put("knowledgePoints", points)
            .put("difficulty", draft.estimatedDifficulty ?: JSONObject.NULL)
            .put("estimatedMinutes", draft.estimatedMinutes ?: JSONObject.NULL)
            .put("suggestion", draft.learningSuggestion ?: JSONObject.NULL)
            .put("needsReview", review)
            .put("warnings", warnings)
            .put("dependsOn", depends)
            .toString()
    }

    fun decode(json: String, documentId: Long): ProblemDraft {
        val root = JSONObject(json)
        val version = root.optInt("v", -1)
        require(version == FORMAT_VERSION) { "草稿数据版本不受支持：$version" }
        val formulas = mutableListOf<ProblemFormula>()
        root.optJSONArray("formulas")?.forEachIndexed { index, item ->
            val obj = item as JSONObject
            formulas += ProblemFormula(
                id = 0,
                orderIndex = index,
                latex = obj.getString("latex"),
                confidence = obj.getDouble("confidence"),
                needsReview = obj.getBoolean("needsReview"),
                originalSnippet = obj.optString("snippet"),
            )
        }
        val points = mutableListOf<ProblemKnowledgePoint>()
        root.optJSONArray("knowledgePoints")?.forEachIndexed { _, item ->
            val obj = item as JSONObject
            points += ProblemKnowledgePoint(
                id = 0,
                name = obj.getString("name"),
                source = runCatching {
                    ProblemAnalysisSource.valueOf(obj.getString("source"))
                }.getOrDefault(ProblemAnalysisSource.HEURISTIC),
            )
        }
        val needsReview = mutableSetOf<ProblemDraftField>()
        root.optJSONArray("needsReview")?.forEachIndexed { _, item ->
            runCatching { ProblemDraftField.valueOf(item as String) }.getOrNull()?.let { needsReview += it }
        }
        val warnings = mutableListOf<String>()
        root.optJSONArray("warnings")?.forEachIndexed { _, item -> warnings += item.toString() }
        val dependsOn = mutableListOf<String>()
        root.optJSONArray("dependsOn")?.forEachIndexed { _, item -> dependsOn += item.toString() }
        return ProblemDraft(
            localId = root.getString("localId"),
            documentId = documentId,
            pageIndex = root.getInt("pageIndex"),
            problemNumber = root.getString("number"),
            statement = root.getString("statement"),
            chapter = root.getString("chapter"),
            courseName = root.getString("courseName"),
            textbook = root.getString("textbook"),
            formulas = formulas,
            knowledgePoints = points,
            estimatedDifficulty = root.optIntOrNull("difficulty"),
            estimatedMinutes = root.optIntOrNull("estimatedMinutes"),
            learningSuggestion = root.optStringOrNull("suggestion"),
            dependsOn = dependsOn,
            needsReview = needsReview,
            warnings = warnings,
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    private inline fun JSONArray.forEachIndexed(action: (index: Int, value: Any) -> Unit) {
        for (index in 0 until length()) action(index, get(index))
    }

    /** 一页可能有多个题目草稿；按数组整体编码以持久化到批次条目。 */
    fun encodeDrafts(drafts: List<ProblemDraft>): String {
        val array = JSONArray()
        drafts.forEach { draft -> array.put(JSONObject(encode(draft))) }
        return array.toString()
    }

    fun decodeDrafts(json: String, documentId: Long): List<ProblemDraft> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            decode(array.getJSONObject(index).toString(), documentId)
        }
    }
}
