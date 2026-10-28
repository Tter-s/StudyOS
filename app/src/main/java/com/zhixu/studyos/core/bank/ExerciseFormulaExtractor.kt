package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemFormula
import kotlin.math.min

/**
 * 从页面文本中提取数学公式的 LaTeX 候选。
 *
 * 规则（V0.8 第三节）：
 * - 显式 LaTeX 定界符（$...$、$$...$$、\(...\)、\[...\]）视为高置信度，可直接预览；
 * - 其余仅做「裸数学片段」推断（Unicode 数学符号、^/_、\frac 等），一律 needsReview，
 *   latex 保留原文片段，交由用户在预览中修改；
 * - 提取失败或不可靠时保留原文并标记人工校对，绝不静默丢弃。
 */
object ExerciseFormulaExtractor {

    private const val MAX_FORMULA_SNIPPET = 200

    private val unicodeMathChars = setOf(
        '≤', '≥', '≠', '≈', '±', '×', '÷', '√', '∑', '∏', '∫', '∬', '∮', '∞',
        '∈', '∉', '⊂', '⊃', '⊆', '⊇', '∪', '∩', '∅', '∀', '∃', '→', '←', '⇒', '⇔',
        'α', 'β', 'γ', 'δ', 'ε', 'ζ', 'η', 'θ', 'λ', 'μ', 'ξ', 'π', 'ρ', 'σ', 'τ',
        'φ', 'χ', 'ψ', 'ω', 'Γ', 'Δ', 'Θ', 'Λ', 'Ξ', 'Π', 'Σ', 'Φ', 'Ψ', 'Ω',
        '∂', '∇', '⊥', '∥', '∠', '△', '⊙', '°', '′', '″',
    )

    private val bareLaTeXCommands = listOf(
        "\\frac", "\\sqrt", "\\lim", "\\sum", "\\int", "\\sin", "\\cos", "\\tan",
        "\\log", "\\ln", "\\partial", "\\begin{", "\\left", "\\right", "\\alpha",
        "\\beta", "\\gamma", "\\delta", "\\pi", "\\theta", "\\lambda", "\\mu",
    )

    /**
     * 返回按出现顺序排列的公式候选；同一段去重后只保留一次。
     */
    fun extract(text: String): List<ProblemFormula> {
        if (text.isBlank()) return emptyList()
        val found = mutableListOf<ProblemFormula>()
        var order = 0

        // $$...$$ 与 \[...\]（显示公式），再 $...$ 与 \(...\)（行内公式）。
        val patterns = listOf(
            Regex("\\$\\$(.+?)\\$\\$", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("\\\\\\[(.+?)\\\\]", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("\\\$(.+?)\\\$", setOf(RegexOption.DOT_MATCHES_ALL)),
            Regex("\\\\\\((.+?)\\\\\\)", setOf(RegexOption.DOT_MATCHES_ALL)),
        )
        val consumed = mutableListOf<Pair<IntRange, String>>()
        patterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val group = match.groupValues[1]
                if (group.isNotBlank() && consumed.none { it.first.intersects(match.range) }) {
                    consumed += match.range to group
                    found += ProblemFormula(
                        orderIndex = order++,
                        latex = normalizeLatex(group),
                        confidence = 0.9,
                        needsReview = false,
                        originalSnippet = snippet(text, match.range),
                    )
                }
            }
        }

        // 裸数学片段：逐行推断，跳过已被显式公式覆盖的行。
        text.lines().forEachIndexed { lineIndex, line ->
            val lineRange = lineRangeOf(text, lineIndex)
            if (lineRange != null && consumed.any { it.first.intersects(lineRange) }) return@forEachIndexed
            if (looksLikeMath(line)) {
                val latex = normalizeLatex(line.trim())
                if (found.none { it.originalSnippet == snippet(line, 0..line.length) || it.latex == latex }) {
                    found += ProblemFormula(
                        orderIndex = order++,
                        latex = latex,
                        confidence = 0.5,
                        needsReview = true,
                        originalSnippet = line.trim().take(MAX_FORMULA_SNIPPET),
                    )
                }
            }
        }

        return found.sortedBy { it.orderIndex }.mapIndexed { index, formula -> formula.copy(orderIndex = index) }
    }

    /** 判断一行是否可能是数学内容（用于无显式定界符的扫描版/提取质量差的页面）。 */
    fun looksLikeMath(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3 || trimmed.length > MAX_FORMULA_SNIPPET) return false
        if (trimmed.any { it in unicodeMathChars }) return true
        if (bareLaTeXCommands.any { trimmed.contains(it) }) return true
        // 上标/下标结构，如 x^2、a_1、10^{-3}
        if (Regex("[A-Za-z0-9)）]\\^\\{?[A-Za-z0-9+-]{1,6}\\}?").containsMatchIn(trimmed)) return true
        if (Regex("[A-Za-z0-9)）]_[\\{]?[A-Za-z0-9]{1,6}[\\}]?").containsMatchIn(trimmed)) return true
        // 成对的 |..| 绝对值，如 |x-1|>2
        if (Regex("\\|[^|]{1,20}\\|").containsMatchIn(trimmed) &&
            trimmed.any { it.isDigit() || it.isLetter() }
        ) {
            return true
        }
        return false
    }

    private fun normalizeLatex(raw: String): String = raw.trim().take(MAX_FORMULA_SNIPPET)

    private fun snippet(text: String, range: IntRange): String =
        text.substring(
            min(range.first, text.length - 1).coerceAtLeast(0),
            (range.last + 1).coerceAtMost(text.length),
        ).trim().take(MAX_FORMULA_SNIPPET)

    private fun lineRangeOf(text: String, lineIndex: Int): IntRange? {
        var index = 0
        val lines = text.lines()
        if (lineIndex >= lines.size) return null
        for (i in 0 until lineIndex) index += lines[i].length + 1
        if (index >= text.length) return null
        return index until (index + lines[lineIndex].length)
    }

    private infix fun IntRange.intersects(other: IntRange): Boolean =
        first <= other.last && other.first <= last
}
