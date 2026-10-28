package com.zhixu.studyos.core.bank

/**
 * 把一页文本切分为题目片段。
 *
 * 支持的题号形态（中文习题册常见格式）：
 * - 行首阿拉伯数字 + 分隔符：「1.」「12．」「3、」「5)」「7）」
 * - 行首「第 12 题」「第12题」
 * - 行首（1）（2）等带括号子项不会触发切分，保留在题干内。
 *
 * 章节上下文来自「第一章」「第 2 章」「第 2.3 节」「习题 2.1」「练习 2-1」等标题行。
 *
 * 防误切规则：题号必须大体递增（允许 OCR 漏题造成的小幅跳号，上限 5）；
 * 否则该行视为上一题的延续内容。页首无法归属的行输出为「题号未识别」草稿，
 * 由用户在预览中修正，不静默丢弃。
 */
object ExerciseTextSplitter {

    private val NUMBERED_START = Regex("^\\s*(\\d{1,3})\\s*[.．、)）]\\s*")
    private val DI_TI_START = Regex("^\\s*第\\s*(\\d{1,3})\\s*题")
    private val CHAPTER_CN = Regex("^\\s*第\\s*([一二三四五六七八九十百]+)\\s*[章节篇]")
    private val CHAPTER_NUM = Regex("^\\s*第\\s*(\\d+(?:[.．]\\d+)?)\\s*[章节]")
    private val EXERCISE_SET = Regex("^\\s*(习题|练习)\\s*(\\d+(?:[-.．]\\d+)?)")
    private val PAGE_NOISE = Regex(
        "^\\s*(?:[-–—·•]\\s*\\d+\\s*[-–—·•]|第\\s*\\d+\\s*页|\\(\\s*\\d+\\s*\\)\\s*$|\\d{1,4}\\s*$)",
    )

    private val CN_DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    data class Segment(
        val problemNumber: String?,
        val chapter: String?,
        val lines: List<String>,
    )

    data class SplitResult(
        val segments: List<Segment>,
        /** 页面中识别出的最后章节上下文（如「第二章」「习题 2.1」）。 */
        val chapter: String?,
        val warnings: List<String>,
    )

    fun split(pageText: String, firstNumberHint: Int? = null): SplitResult {
        val lines = pageText.lines().map { it.trimEnd() }
        var chapter: String? = null
        var lastNumber = firstNumberHint?.minus(1)
        val segments = mutableListOf<Segment>()
        var currentLines = mutableListOf<String>()
        var currentNumber: Int? = null
        var currentChapter: String? = null
        val warnings = mutableListOf<String>()
        var sawNoise = false

        fun flush() {
            val body = currentLines
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
            if (body.isNotEmpty()) {
                segments += Segment(
                    problemNumber = currentNumber?.toString(),
                    chapter = currentChapter,
                    lines = body.toList(),
                )
            }
            currentLines = mutableListOf()
        }

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                if (currentLines.isNotEmpty()) currentLines += ""
                return@forEach
            }
            if (PAGE_NOISE.containsMatchIn(line) && !NUMBERED_START.containsMatchIn(line)) {
                sawNoise = true
                return@forEach
            }
            chapterHeader(line)?.let { header ->
                flush()
                chapter = header
                currentChapter = header
                return@forEach
            }
            val start = problemStart(line)
            if (start != null && isSequential(start.number, lastNumber)) {
                flush()
                currentNumber = start.number
                lastNumber = start.number
                currentLines += start.remainder.ifBlank { line }
                return@forEach
            }
            if (start != null && !isSequential(start.number, lastNumber)) {
                warnings += "题号 ${start.number} 出现在题号 ${lastNumber ?: "无"} 之后，已并入上一题题干"
            }
            currentLines += line
        }
        flush()

        if (sawNoise) warnings += "已过滤页码/页眉等噪声行"
        if (segments.isEmpty() && pageText.isNotBlank()) {
            warnings += "本页未识别出题号，请人工检查"
        }
        return SplitResult(segments = segments, chapter = chapter, warnings = warnings)
    }

    /** 页面开头没有题号的部分（可能是上一页题目的延续）。 */
    fun leadingOrphan(result: SplitResult): Segment? {
        val first = result.segments.firstOrNull() ?: return null
        return if (first.problemNumber == null) first else null
    }

    private fun chapterHeader(line: String): String? {
        // 标题行整体作为章节上下文（如「第二章 导数与微分」「习题 2.1」），用户可再编辑
        if (CHAPTER_CN.containsMatchIn(line) && line.length <= 40) return line
        if (CHAPTER_NUM.containsMatchIn(line) && line.length <= 40) return line
        EXERCISE_SET.find(line)?.let { match ->
            val rest = line.substring(match.value.length).trim()
            if (rest.length <= 30) return "${match.groupValues[1]} ${match.groupValues[2]}"
        }
        return null
    }

    private fun problemStart(line: String): Start? {
        DI_TI_START.find(line)?.let { match ->
            return Start(number = match.groupValues[1].toInt(), remainder = line.substring(match.value.length).trim())
        }
        NUMBERED_START.find(line)?.let { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return null
            // 排除年份/页码等误判：题号合理范围 1..300
            if (number !in 1..300) return null
            return Start(number = number, remainder = line.substring(match.value.length).trim())
        }
        return null
    }

    private fun isSequential(number: Int, lastNumber: Int?): Boolean {
        if (lastNumber == null) return true
        return number > lastNumber && number - lastNumber <= 5
    }

    /** 中文数字章节号转数字，便于排序显示；无法解析返回 null。 */
    fun chineseNumberToInt(text: String): Int? {
        var total = 0
        var current = 0
        text.forEach { ch ->
            when {
                CN_DIGITS.containsKey(ch) -> current = CN_DIGITS.getValue(ch)
                ch == '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                }
                ch == '百' -> {
                    total += (if (current == 0) 1 else current) * 100
                    current = 0
                }
                ch.isDigit() -> current = current * 10 + (ch - '0')
                else -> return null
            }
        }
        return total + current
    }

    private data class Start(val number: Int, val remainder: String)
}
