package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemAnalysisEngine
import com.zhixu.studyos.core.model.ProblemAnalysisResult
import com.zhixu.studyos.core.model.ProblemAnalysisSource
import com.zhixu.studyos.core.model.ProblemFormula

/**
 * 本地规则分析引擎（V0.8 的默认实现）。
 *
 * 只使用确定性的关键词与文本统计规则，不联网、不调用 AI。
 * 结果仅作为预览草稿的建议值，用户可在确认前修改；分析来源标记为 HEURISTIC。
 * V0.9 的 AIProvider 实现同一 [ProblemAnalysisEngine] 接口即可替换，不影响解析与入库流程。
 */
class HeuristicProblemAnalyzer : ProblemAnalysisEngine {

    override val name: String = "本地规则分析（离线）"

    override fun analyze(
        statement: String,
        formulas: List<ProblemFormula>,
        chapter: String,
    ): ProblemAnalysisResult {
        val haystack = statement + "\n" + formulas.joinToString("\n") { it.latex }
        val knowledge = KNOWLEDGE_DICTIONARY
            .filter { entry -> entry.keywords.any { haystack.contains(it) } }
            .map { entry -> entry.point }
            .distinct()
            .take(MAX_KNOWLEDGE_POINTS)

        val difficulty = estimateDifficulty(statement, formulas, knowledge)
        val minutes = difficultyToMinutes(difficulty)
        val suggestion = buildSuggestion(knowledge, difficulty, statement)
        val dependsOn = findDependencies(statement)
        return ProblemAnalysisResult(
            knowledgePoints = knowledge,
            estimatedDifficulty = difficulty,
            estimatedMinutes = minutes,
            learningSuggestion = suggestion,
            dependsOn = dependsOn,
            source = ProblemAnalysisSource.HEURISTIC,
        )
    }

    private fun estimateDifficulty(
        statement: String,
        formulas: List<ProblemFormula>,
        knowledge: List<String>,
    ): Int {
        var score = 1
        val length = statement.length
        when {
            length >= 240 -> score += 2
            length >= 120 -> score += 1
        }
        score += (formulas.size.coerceAtMost(4) + 1) / 2
        PROOF_MARKERS.forEach { marker -> if (statement.contains(marker)) score += 1 }
        HARD_MARKERS.forEach { marker -> if (knowledge.contains(marker) || statement.contains(marker)) score += 1 }
        // 多小问（（1）（2）（3）…）提高难度
        val subQuestionCount = SUB_QUESTION_REGEX.findAll(statement).count()
        if (subQuestionCount >= 3) score += 1
        return score.coerceIn(1, 5)
    }

    private fun difficultyToMinutes(difficulty: Int): Int = when (difficulty) {
        1 -> 8
        2 -> 12
        3 -> 20
        4 -> 30
        else -> 45
    }

    private fun buildSuggestion(knowledge: List<String>, difficulty: Int, statement: String): String {
        val base = when {
            knowledge.isNotEmpty() ->
                "本题涉及「${knowledge.joinToString("、")}」，建议先复习相关定义与基本结论，再独立完成。"
            statement.length >= 120 ->
                "题目较长，建议先划出已知条件与所求，再分步求解。"
            else ->
                "建议先通读题目，标记已知条件与目标，再独立完成。"
        }
        val tail = when {
            difficulty >= 4 -> "难度较高，可先尝试 ${difficultyToMinutes(difficulty)} 分钟，卡住时记录思路再求助或对照解答复盘。"
            else -> "完成后核对关键步骤，并把错因记入学习中心。"
        }
        return "$base$tail"
    }

    private fun findDependencies(statement: String): List<String> {
        val result = mutableListOf<String>()
        DEPENDENCY_PATTERNS.forEach { regex ->
            regex.findAll(statement).forEach { match ->
                val ref = match.groupValues[1]
                if (ref.isNotEmpty() && ref !in result) result += ref
            }
        }
        return result.take(5)
    }

    private data class KnowledgeEntry(val point: String, val keywords: List<String>)

    companion object {
        private const val MAX_KNOWLEDGE_POINTS = 3

        private val SUB_QUESTION_REGEX = Regex("[（(]\\s*[1-9一二三四五六七八九十]\\s*[)）]")

        private val PROOF_MARKERS = listOf("证明", "试证", "求证", "推导", "说明理由", "讨论")

        private val HARD_MARKERS = listOf(
            "一致收敛", "中值定理", "特征值", "若尔当", "Jordan", "正定", "上确界", "下确界",
            "一致连续", "隐函数", "条件收敛", "级数", "多重积分", "曲面积分", "曲线积分",
        )

        private val DEPENDENCY_PATTERNS = listOf(
            Regex("利用第\\s*([0-9]+(?:[.．][0-9]+)?)\\s*题"),
            Regex("由第\\s*([0-9]+(?:[.．][0-9]+)?)\\s*题"),
            Regex("结合[（(]\\s*([1-9])\\s*[)）]"),
            Regex("利用[（(]\\s*([1-9])\\s*[)）]"),
            Regex("在[（(]\\s*([1-9])\\s*[)）]的条件下"),
        )

        private val KNOWLEDGE_DICTIONARY = listOf(
            KnowledgeEntry("数列极限", listOf("数列", "lim", "收敛于", "极限存在")),
            KnowledgeEntry("函数极限与连续", listOf("极限", "连续", "间断点", "无穷小", "洛必达")),
            KnowledgeEntry("导数与微分", listOf("导数", "微分", "可导", "切线", "求导", "高阶导数")),
            KnowledgeEntry("中值定理", listOf("中值定理", "罗尔", "拉格朗日", "柯西中值")),
            KnowledgeEntry("一元积分", listOf("不定积分", "定积分", "换元", "分部积分", "积分上限函数")),
            KnowledgeEntry("级数", listOf("级数", "收敛", "发散", "幂级数", "泰勒展开", "傅里叶")),
            KnowledgeEntry("多元微分", listOf("偏导", "全微分", "多元函数", "方向导数", "梯度", "隐函数")),
            KnowledgeEntry("多重积分", listOf("重积分", "二重积分", "三重积分", "累次积分")),
            KnowledgeEntry("曲线与曲面积分", listOf("曲线积分", "曲面积分", "格林公式", "高斯公式", "斯托克斯")),
            KnowledgeEntry("行列式", listOf("行列式", "余子式", "代数余子式", "克拉默")),
            KnowledgeEntry("矩阵", listOf("矩阵", "逆矩阵", "转置", "伴随矩阵", "初等变换", "分块矩阵")),
            KnowledgeEntry("线性方程组", listOf("线性方程组", "通解", "基础解系", "增广矩阵")),
            KnowledgeEntry("向量组与秩", listOf("向量组", "线性相关", "线性无关", "极大无关组", "秩")),
            KnowledgeEntry("特征值与对角化", listOf("特征值", "特征向量", "对角化", "相似矩阵", "若尔当", "Jordan")),
            KnowledgeEntry("二次型", listOf("二次型", "正定", "惯性定理", "规范形")),
            KnowledgeEntry("多项式", listOf("多项式", "不可约", "最大公因式", "因式分解", "整除")),
            KnowledgeEntry("线性变换", listOf("线性变换", "线性映射", "核", "象", "不变子空间", "基变换")),
            KnowledgeEntry("向量与坐标", listOf("向量", "坐标", "数量积", "向量积", "混合积", "方向余弦")),
            KnowledgeEntry("平面与直线", listOf("平面方程", "直线方程", "夹角", "点到直线", "点到平面", "法向量")),
            KnowledgeEntry("常见曲面", listOf("球面", "柱面", "锥面", "旋转曲面", "二次曲面", "椭球面")),
            KnowledgeEntry("圆锥曲线", listOf("椭圆", "双曲线", "抛物线", "准线", "离心率", "焦点")),
            KnowledgeEntry("轨迹与方程", listOf("轨迹", "求方程", "曲线的方程")),
        )
    }
}
