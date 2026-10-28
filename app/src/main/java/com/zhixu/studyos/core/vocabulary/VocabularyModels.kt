package com.zhixu.studyos.core.vocabulary

import java.time.Instant
import java.time.LocalDate

enum class VocabularyStatus { NEW, LEARNING, REVIEWING, MASTERED, SUSPENDED }

enum class VocabularyRating(val label: String) {
    AGAIN("忘记"),
    HARD("困难"),
    GOOD("记得"),
    EASY("轻松"),
}

data class VocabularyWord(
    val id: Long = 0,
    val word: String,
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val meaning: String = "",
    val example: String = "",
    val listName: String = "自定义",
    val note: String = "",
    val status: VocabularyStatus = VocabularyStatus.NEW,
    val familiarity: Int = 0,
    val repetition: Int = 0,
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.5,
    val dueDate: LocalDate,
    val lastReviewedAt: Instant? = null,
    val reviewCount: Int = 0,
    val lapseCount: Int = 0,
    val source: String = "USER",
    val revision: Int = 1,
) {
    val isDue: Boolean
        get() = status != VocabularyStatus.SUSPENDED && !dueDate.isAfter(LocalDate.now())
}

data class VocabularyReview(
    val id: Long = 0,
    val wordId: Long,
    val reviewedAt: Instant,
    val rating: VocabularyRating,
    val recalled: Boolean,
    val previousIntervalDays: Int,
    val newIntervalDays: Int,
    val nextDueDate: LocalDate,
    val responseTimeMillis: Long? = null,
)

data class VocabularySettings(
    val dailyNewLimit: Int = 20,
    val dailyReviewLimit: Int = 100,
    val dailyTimeBudgetMinutes: Int = 30,
    val examDate: LocalDate? = null,
    val taskGenerationEnabled: Boolean = false,
    val lastTaskGenerated: LocalDate? = null,
) {
    fun validationError(): String? = when {
        dailyNewLimit !in 0..200 -> "每日新词数量应在 0–200 之间"
        dailyReviewLimit !in 0..500 -> "每日复习上限应在 0–500 之间"
        dailyTimeBudgetMinutes !in 0..240 -> "英语学习时间预算应在 0–240 分钟之间"
        else -> null
    }

    fun requireValid() {
        require(validationError() == null) { validationError() ?: "词汇设置无效" }
    }
}

data class VocabularyStats(
    val totalWords: Int,
    val learnedWords: Int,
    val dueWords: Int,
    val masteredWords: Int,
    val newWords: Int,
    val recentReviews: Int,
    val recentRecallRatePercent: Int?,
    val streakDays: Int,
    val averageReviewMinutesPerDay: Int?,
    val listNames: List<String> = emptyList(),
)

/**
 * 可解释的 SM-2 变体。
 *
 * - 遗忘：回到重新学习，间隔归零、当天可再次复习、ease 下降；
 * - 困难：间隔缓慢增长；
 * - 记得：前两次 1 天、6 天，之后按 ease 增长；
 * - 轻松：更长的间隔和更高的 ease；
 * - 间隔上限 3650 天，ease 限制在 1.3–3.0，避免数值漂移；
 * - 连续多次长间隔复习后标记为已掌握，但仍会按排期复习。
 */
object Sm2Scheduler {
    const val MIN_EASE = 1.3
    const val MAX_EASE = 3.0
    const val MAX_INTERVAL_DAYS = 3650
    private const val MASTERED_INTERVAL_DAYS = 60
    private const val MASTERED_REPETITIONS = 4

    data class ReviewResult(
        val status: VocabularyStatus,
        val repetition: Int,
        val intervalDays: Int,
        val easeFactor: Double,
        val familiarity: Int,
        val dueDate: LocalDate,
        val recalled: Boolean,
    )

    fun review(
        word: VocabularyWord,
        rating: VocabularyRating,
        today: LocalDate,
    ): ReviewResult {
        var ease = word.easeFactor.coerceIn(MIN_EASE, MAX_EASE)
        var repetition = word.repetition.coerceAtLeast(0)
        var interval = word.intervalDays.coerceAtLeast(0)
        var familiarity = word.familiarity.coerceIn(0, 5)
        val recalled = rating != VocabularyRating.AGAIN

        when (rating) {
            VocabularyRating.AGAIN -> {
                ease = (ease - 0.20).coerceAtLeast(MIN_EASE)
                repetition = 0
                interval = 0
                familiarity = (familiarity - 1).coerceAtLeast(0)
            }
            VocabularyRating.HARD -> {
                ease = (ease - 0.15).coerceAtLeast(MIN_EASE)
                repetition += 1
                interval = when {
                    interval <= 0 -> 1
                    else -> Math.round(interval * 1.2).toInt().coerceAtLeast(1)
                }
                familiarity = (familiarity + 1).coerceAtMost(5)
            }
            VocabularyRating.GOOD -> {
                repetition += 1
                interval = when (repetition) {
                    1 -> 1
                    2 -> 6
                    else -> Math.round(interval * ease).toInt().coerceAtLeast(1)
                }
                familiarity = (familiarity + 1).coerceAtMost(5)
            }
            VocabularyRating.EASY -> {
                ease = (ease + 0.15).coerceAtMost(MAX_EASE)
                repetition += 1
                interval = when {
                    interval <= 0 -> 4
                    else -> Math.round(interval * ease * 1.3).toInt().coerceAtLeast(4)
                }
                familiarity = (familiarity + 2).coerceAtMost(5)
            }
        }
        interval = interval.coerceIn(0, MAX_INTERVAL_DAYS)
        val status = when {
            !recalled -> VocabularyStatus.LEARNING
            repetition >= MASTERED_REPETITIONS && interval >= MASTERED_INTERVAL_DAYS &&
                familiarity >= 4 -> VocabularyStatus.MASTERED
            repetition <= 1 || interval <= 1 -> VocabularyStatus.LEARNING
            else -> VocabularyStatus.REVIEWING
        }
        return ReviewResult(
            status = status,
            repetition = repetition,
            intervalDays = interval,
            easeFactor = ease,
            familiarity = familiarity,
            dueDate = today.plusDays(interval.toLong()),
            recalled = recalled,
        )
    }
}

/** 词汇每日任务计划；只生成一个提醒式任务，实际复习页负责逐词记录。 */
data class VocabularyDailyPlan(
    val date: LocalDate,
    val dueCount: Int,
    val newCount: Int,
    val plannedMinutes: Int,
    val title: String,
    val description: String,
) {
    val hasWork: Boolean get() = dueCount + newCount > 0
}

object VocabularyTaskPlanner {
    fun build(
        date: LocalDate,
        dueWords: Int,
        newWords: Int,
        settings: VocabularySettings,
    ): VocabularyDailyPlan {
        val cappedNew = minOf(newWords, settings.dailyNewLimit)
        val cappedDue = minOf(dueWords, settings.dailyReviewLimit)
        val estimate = ((cappedDue + cappedNew) * 20 / 60).coerceIn(0, settings.dailyTimeBudgetMinutes)
        val minutes = maxOf(estimate, if (cappedDue + cappedNew > 0) 5 else 0)
        return VocabularyDailyPlan(
            date = date,
            dueCount = cappedDue,
            newCount = cappedNew,
            plannedMinutes = minutes,
            title = "英语词汇复习 ${cappedDue + cappedNew} 词",
            description = buildString {
                append("待复习 $cappedDue 个 · 新词 $cappedNew 个")
                append("（每日上限：复习 ${settings.dailyReviewLimit}、新词 ${settings.dailyNewLimit}）")
                if (dueWords > cappedDue) {
                    append("；另有 ${dueWords - cappedDue} 个待复习词积压，不会无限制塞入今天")
                }
            },
        )
    }
}

/** 词表行：CSV/TSV 导入的可编辑结果。 */
data class VocabularyImportRow(
    val word: String,
    val meaning: String = "",
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val example: String = "",
    val note: String = "",
    val listName: String = "CSV 导入",
    val lineNumber: Int = 0,
    val error: String? = null,
)

data class VocabularyImportResult(
    val rows: List<VocabularyImportRow>,
    val duplicateWords: List<String>,
    val skippedEmptyLines: Int,
    val detectedDelimiter: Char,
)

/**
 * CSV/TSV 解析器。支持英文或中文表头、逗号/制表符分隔、引号包裹与双引号转义。
 * 只解析用户合法持有的文件，不内置任何商业词库。
 */
object VocabularyCsvParser {
    enum class Column { WORD, MEANING, PHONETIC, PART_OF_SPEECH, EXAMPLE, NOTE, LIST }

    private val headerAliases = mapOf(
        Column.WORD to setOf("word", "单词", "词汇", "英文", "english"),
        Column.MEANING to setOf("meaning", "释义", "中文", "翻译", "解释", "definition"),
        Column.PHONETIC to setOf("phonetic", "音标", "发音", "ipa"),
        Column.PART_OF_SPEECH to setOf("partofspeech", "词性", "pos", "part of speech"),
        Column.EXAMPLE to setOf("example", "例句", "句子", "sentence"),
        Column.NOTE to setOf("note", "笔记", "备注", "memo"),
        Column.LIST to setOf("list", "listname", "词表", "词书", "分组", "book"),
    )

    fun parse(text: String, defaultListName: String = "CSV 导入"): VocabularyImportResult {
        val cleaned = text.removePrefix("\uFEFF")
        val delimiter = detectDelimiter(cleaned)
        val records = parseRecords(cleaned, delimiter)
        if (records.isEmpty()) return VocabularyImportResult(emptyList(), emptyList(), 0, delimiter)
        val headerRow = records.first()
        val mapping = detectHeader(headerRow)
        val hasHeader = mapping.isNotEmpty() && mapping.values.contains(Column.WORD)
        val startIndex = if (hasHeader) 1 else 0
        val effectiveMapping = if (hasHeader) {
            mapping
        } else {
            // 无表头时按 word, meaning, phonetic, partOfSpeech, example 顺序解释
            (0 until headerRow.size).associateWith { index ->
                when (index) {
                    0 -> Column.WORD
                    1 -> Column.MEANING
                    2 -> Column.PHONETIC
                    3 -> Column.PART_OF_SPEECH
                    4 -> Column.EXAMPLE
                    else -> Column.NOTE
                }
            }
        }
        val rows = mutableListOf<VocabularyImportRow>()
        val seen = mutableSetOf<String>()
        val duplicates = mutableListOf<String>()
        var skipped = 0
        for (index in startIndex until records.size) {
            val fields = records[index]
            if (fields.all(String::isBlank)) {
                skipped += 1
                continue
            }
            fun value(column: Column): String = effectiveMapping.entries
                .firstOrNull { it.value == column }
                ?.let { fields.getOrNull(it.key) }
                ?.trim()
                .orEmpty()
            val word = value(Column.WORD).take(80)
            val meaning = value(Column.MEANING).take(500)
            val normalized = normalizeWord(word)
            val error = when {
                word.isBlank() -> "单词为空"
                !word.any(Char::isLetter) -> "单词不包含字母"
                normalized in seen -> "重复单词"
                else -> null
            }
            if (error == "重复单词") duplicates += word
            if (error == null) seen += normalized
            rows += VocabularyImportRow(
                word = word,
                meaning = meaning,
                phonetic = value(Column.PHONETIC).take(120),
                partOfSpeech = value(Column.PART_OF_SPEECH).take(60),
                example = value(Column.EXAMPLE).take(1000),
                note = value(Column.NOTE).take(500),
                listName = value(Column.LIST).ifBlank { defaultListName }.take(60),
                lineNumber = index + 1,
                error = error,
            )
        }
        return VocabularyImportResult(
            rows = rows,
            duplicateWords = duplicates.distinct(),
            skippedEmptyLines = skipped,
            detectedDelimiter = delimiter,
        )
    }

    fun normalizeWord(word: String): String = word.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun detectDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val tabs = firstLine.count { it == '\t' }
        val commas = firstLine.count { it == ',' }
        return if (tabs > commas) '\t' else ','
    }

    private fun detectHeader(row: List<String>): Map<Int, Column> {
        if (row.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, Column>()
        row.forEachIndexed { index, raw ->
            val normalized = raw.trim().lowercase().replace(Regex("\\s+"), " ")
            val compact = normalized.replace("_", "").replace(" ", "")
            val column = headerAliases.entries.firstOrNull { (_, aliases) ->
                aliases.any { it.replace(" ", "") == compact }
            }?.key
            if (column != null && column !in result.values) result[index] = column
        }
        return result
    }

    private fun parseRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val currentRecord = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                inQuotes && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index += 1
                }
                char == '"' -> inQuotes = !inQuotes
                !inQuotes && char == delimiter -> {
                    currentRecord += field.toString()
                    field.clear()
                }
                !inQuotes && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index += 1
                    currentRecord += field.toString()
                    field.clear()
                    if (currentRecord.isNotEmpty()) {
                        records += currentRecord.toList()
                        currentRecord.clear()
                    }
                }
                else -> field.append(char)
            }
            index += 1
        }
        if (field.isNotEmpty() || currentRecord.isNotEmpty()) {
            currentRecord += field.toString()
            records += currentRecord.toList()
        }
        return records
    }
}
