package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemDraftAnalysis
import com.zhixu.studyos.core.model.ProblemDraftField

/**
 * 草稿去重分析（V0.8 第五节第 4 条）：
 * - 硬重复：同来源（documentId）+ 章节 + 题号。同批内保留第一份，其余标记 DUPLICATE 不可选；
 *   与该来源已入库题目重复同样标记 DUPLICATE（数据库层还有唯一索引兜底）。
 * - 疑似重复：跨来源同教材+章节+题号，仅提示（SUSPECTED_DUPLICATE），用户自行决定是否仍入库。
 */
class ProblemDeduplicator(
    private val existingKeys: Set<String>,
    private val suspectedKeys: Set<String>,
) {

    private val seenInBatch = mutableSetOf<String>()

    fun analyze(draft: ProblemDraft): ProblemDraftAnalysis {
        val validationError = validate(draft)
        val needsReview = draft.needsReview.toSet()
        val key = key(draft.documentId, draft.chapter, draft.problemNumber)
        val duplicateOf = when {
            validationError != null -> null
            key in seenInBatch -> "本批导入中已存在同章节同题号的题目"
            key in existingKeys -> "该来源中已存在「${draft.chapter} ${draft.problemNumber}」，重复导入将被跳过"
            else -> null
        }
        if (validationError == null) seenInBatch += key
        val suspectedKey = suspectedKey(draft.textbook, draft.chapter, draft.problemNumber)
        val suspectedDuplicateOf = if (validationError == null && duplicateOf == null && suspectedKey in suspectedKeys) {
            "其他来源已存在同教材同章节同题号的题目，请确认不是重复导入"
        } else {
            null
        }
        return ProblemDraftAnalysis(
            localId = draft.localId,
            validationError = validationError,
            needsReview = needsReview,
            duplicateOf = duplicateOf,
            suspectedDuplicateOf = suspectedDuplicateOf,
        )
    }

    private fun validate(draft: ProblemDraft): String? = when {
        draft.problemNumber.isBlank() -> "题号不能为空"
        draft.statement.isBlank() -> "题干不能为空"
        draft.statement.length > MAX_STATEMENT_LENGTH -> "题干过长，请精简或拆分"
        draft.formulas.size > MAX_FORMULAS -> "公式数量过多（超过 $MAX_FORMULAS 条），请检查解析结果"
        draft.formulas.any { it.latex.isBlank() } -> "存在空公式，请修改或删除"
        draft.estimatedDifficulty != null && draft.estimatedDifficulty !in 1..5 -> "难度应为 1–5"
        else -> null
    }

    companion object {
        const val MAX_STATEMENT_LENGTH = 8000
        const val MAX_FORMULAS = 30

        fun key(documentId: Long, chapter: String, problemNumber: String): String =
            "$documentId\u0000${chapter.trim()}\u0000${problemNumber.trim()}"

        fun suspectedKey(textbook: String, chapter: String, problemNumber: String): String =
            "${textbook.trim()}\u0000${chapter.trim()}\u0000${problemNumber.trim()}"

        /** 批量分析入口：先分析校验/待确认，再统一处理同批内重复（靠前的草稿胜出）。 */
        fun analyzeAll(
            drafts: List<ProblemDraft>,
            existingKeys: Set<String>,
            suspectedKeys: Set<String>,
        ): Map<String, ProblemDraftAnalysis> {
            val deduplicator = ProblemDeduplicator(existingKeys, suspectedKeys)
            val firstPass = drafts.associate { it.localId to deduplicator.analyze(it) }
            // 同批内同键重复：保留第一份 READY/SUSPECTED，后续标记 DUPLICATE
            val seen = mutableSetOf<String>()
            return drafts.associate { draft ->
                val analysis = firstPass.getValue(draft.localId)
                val key = key(draft.documentId, draft.chapter, draft.problemNumber)
                val adjusted = if (
                    analysis.validationError == null &&
                    analysis.duplicateOf == null &&
                    key in seen
                ) {
                    analysis.copy(duplicateOf = "本批导入中已存在同章节同题号的题目")
                } else {
                    analysis
                }
                if (adjusted.validationError == null && adjusted.duplicateOf == null) seen += key
                draft.localId to adjusted
            }
        }
    }
}
