package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemAnalysisSource
import com.zhixu.studyos.core.model.ProblemFormula
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicProblemAnalyzerTest {

    private val analyzer = HeuristicProblemAnalyzer()

    @Test
    fun analysis_markedAsHeuristic_notAi() {
        val result = analyzer.analyze("求函数的极限。", emptyList(), "第一章")

        assertEquals(ProblemAnalysisSource.HEURISTIC, result.source)
        assertEquals("本地规则分析（离线）", analyzer.name)
    }

    @Test
    fun determinantProblem_isClassifiedAsLinearAlgebra() {
        val result = analyzer.analyze(
            "计算四阶行列式 |A| 的值，其中 A 为分块矩阵。",
            emptyList(),
            "第二章",
        )

        assertTrue(result.knowledgePoints.contains("行列式"))
        assertTrue(result.knowledgePoints.contains("矩阵"))
    }

    @Test
    fun analysisProblem_getsDifficultyBoost() {
        val easy = analyzer.analyze("求 f(x) = x 的导数。", emptyList(), "")
        val proof = analyzer.analyze(
            "证明：若函数在闭区间上连续，则存在中值定理所述的点。",
            emptyList(),
            "",
        )

        assertTrue(proof.estimatedDifficulty!! > easy.estimatedDifficulty!!)
    }

    @Test
    fun difficulty_staysWithinBounds() {
        val longHard = analyzer.analyze(
            "证明一致收敛的级数可以逐项积分，并讨论一致连续、隐函数存在定理的条件。" +
                "（1）证明收敛性。（2）证明可积性。（3）给出反例。（4）讨论推广。（5）总结方法。",
            (1..6).map { ProblemFormula(orderIndex = it, latex = "\\frac{$it}{x}", confidence = 0.9, needsReview = false, originalSnippet = "") },
            "",
        )

        assertTrue(longHard.estimatedDifficulty in 1..5)
        assertEquals(5, longHard.estimatedDifficulty)
    }

    @Test
    fun estimatedMinutes_matchDifficulty() {
        val easy = analyzer.analyze("求导数。", emptyList(), "")
        val hard = analyzer.analyze(
            "证明：级数一致收敛时满足中值定理的推广形式，讨论正定性。",
            emptyList(),
            "",
        )

        assertNotNull(easy.estimatedMinutes)
        assertTrue(hard.estimatedMinutes!! > easy.estimatedMinutes!!)
    }

    @Test
    fun suggestion_referencesKnowledgePoints() {
        val result = analyzer.analyze("计算矩阵的特征值与特征向量。", emptyList(), "")

        assertNotNull(result.learningSuggestion)
        assertTrue(result.learningSuggestion!!.contains("特征值"))
    }

    @Test
    fun dependencyReferences_areDetected() {
        val result = analyzer.analyze("利用第 3 题的结论证明本题。", emptyList(), "")

        assertTrue(result.dependsOn.contains("3"))
    }

    @Test
    fun subQuestionDependency_isDetected() {
        val result = analyzer.analyze("结合（1）的结论，证明（2）。", emptyList(), "")

        assertTrue(result.dependsOn.contains("1"))
    }

    @Test
    fun knowledgePoints_cappedAtThree() {
        val result = analyzer.analyze(
            "利用行列式、矩阵、线性方程组、向量组的秩、特征值和二次型的知识求解。",
            emptyList(),
            "",
        )

        assertTrue(result.knowledgePoints.size <= 3)
    }
}
