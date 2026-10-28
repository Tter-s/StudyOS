package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ParsedExercisePage
import com.zhixu.studyos.core.model.ProblemAnalysisEngine
import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemDraftField
import com.zhixu.studyos.core.model.ProblemPageTextSource
import java.util.UUID

/**
 * 单页解析：页面文本 → 可编辑题目草稿。
 * 纯 Kotlin，只依赖 [ExerciseTextSplitter]、[ExerciseFormulaExtractor] 与分析引擎接口。
 */
class ExerciseProblemParser(
    private val splitter: ExerciseTextSplitter = ExerciseTextSplitter,
    private val analysisEngine: ProblemAnalysisEngine,
) {
    fun parse(
        pageIndex: Int,
        pageText: String,
        textSource: ProblemPageTextSource,
        courseName: String,
        textbook: String,
        firstNumberHint: Int? = null,
    ): ParsedExercisePage {
        val split = splitter.split(pageText, firstNumberHint)
        val pageWarnings = split.warnings.toMutableList()
        if (textSource == ProblemPageTextSource.OCR) {
            pageWarnings += "第 ${pageIndex + 1} 页来自 OCR，请核对题号与文字"
        }
        if (pageText.isBlank()) {
            pageWarnings += "本页没有可识别的文本"
        }
        val problems = split.segments.mapIndexedNotNull { index, segment ->
            val body = segment.lines.joinToString("\n").trim()
            if (body.isEmpty()) return@mapIndexedNotNull null
            val formulas = ExerciseFormulaExtractor.extract(body)
            val analysis = analysisEngine.analyze(
                statement = body,
                formulas = formulas,
                chapter = segment.chapter ?: split.chapter.orEmpty(),
            )
            val needsReview = mutableSetOf<ProblemDraftField>()
            if (segment.problemNumber == null) needsReview += ProblemDraftField.NUMBER
            if (body.length < MIN_STATEMENT_LENGTH) needsReview += ProblemDraftField.STATEMENT
            if (formulas.any { it.needsReview }) needsReview += ProblemDraftField.FORMULA
            ProblemDraft(
                localId = "p${pageIndex}_s$index-${UUID.randomUUID().toString().take(8)}",
                documentId = -1L,
                pageIndex = pageIndex,
                problemNumber = segment.problemNumber ?: "",
                statement = body,
                chapter = segment.chapter ?: split.chapter.orEmpty(),
                courseName = courseName,
                textbook = textbook,
                formulas = formulas,
                knowledgePoints = analysis.knowledgePoints
                    .ifEmpty { emptyList() }
                    .map { com.zhixu.studyos.core.model.ProblemKnowledgePoint(name = it, source = analysis.source) },
                estimatedDifficulty = analysis.estimatedDifficulty,
                estimatedMinutes = analysis.estimatedMinutes,
                learningSuggestion = analysis.learningSuggestion,
                dependsOn = analysis.dependsOn,
                needsReview = needsReview,
                warnings = pageWarnings.toList(),
            )
        }
        return ParsedExercisePage(
            pageIndex = pageIndex,
            problems = problems,
            textSource = textSource,
            textLength = pageText.length,
            warnings = pageWarnings.distinct(),
        )
    }

    companion object {
        const val MIN_STATEMENT_LENGTH = 6
    }
}
