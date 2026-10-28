package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.HistoryDao
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.TaskEventEntity
import com.zhixu.studyos.core.data.local.VocabularyDao
import com.zhixu.studyos.core.data.local.VocabularyReviewEntity
import com.zhixu.studyos.core.data.local.VocabularySettingsEntity
import com.zhixu.studyos.core.data.local.VocabularyWordEntity
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.vocabulary.Sm2Scheduler
import com.zhixu.studyos.core.vocabulary.VocabularyCsvParser
import com.zhixu.studyos.core.vocabulary.VocabularyImportResult
import com.zhixu.studyos.core.vocabulary.VocabularyRating
import com.zhixu.studyos.core.vocabulary.VocabularyReview
import com.zhixu.studyos.core.vocabulary.VocabularySettings
import com.zhixu.studyos.core.vocabulary.VocabularyStats
import com.zhixu.studyos.core.vocabulary.VocabularyStatus
import com.zhixu.studyos.core.vocabulary.VocabularyTaskPlanner
import com.zhixu.studyos.core.vocabulary.VocabularyWord
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class VocabularySummary(
    val totalWords: Int = 0,
    val dueWords: Int = 0,
    val settings: VocabularySettings = VocabularySettings(),
)

sealed interface VocabularyTaskResult {
    data class Created(val taskId: Long, val title: String, val minutes: Int) : VocabularyTaskResult
    data class AlreadyExists(val taskId: Long) : VocabularyTaskResult
    data object Disabled : VocabularyTaskResult
    data object NothingDue : VocabularyTaskResult
}

sealed interface VocabularyImportResultSummary {
    data class Saved(
        val inserted: Int,
        val skippedDuplicates: Int,
        val invalid: Int,
        val parsed: VocabularyImportResult,
    ) : VocabularyImportResultSummary
    data class Rejected(val message: String) : VocabularyImportResultSummary
}

interface VocabularyRepository {
    fun observeSummary(today: LocalDate): Flow<VocabularySummary>
    fun observeSettings(): Flow<VocabularySettings>
    suspend fun getSettings(): VocabularySettings
    suspend fun saveSettings(settings: VocabularySettings)
    suspend fun pageWords(listName: String, search: String, limit: Int, offset: Int): List<VocabularyWord>
    suspend fun countWords(listName: String, search: String): Int
    suspend fun listNames(): List<String>
    suspend fun getWord(id: Long): VocabularyWord?
    suspend fun addWord(word: VocabularyWord): Long
    suspend fun updateWord(word: VocabularyWord)
    suspend fun deleteWord(id: Long)
    suspend fun importCsv(text: String): VocabularyImportResultSummary
    suspend fun dueWords(today: LocalDate): List<VocabularyWord>
    suspend fun review(wordId: Long, rating: VocabularyRating, responseTimeMillis: Long?, now: Instant): VocabularyReview
    suspend fun reviewsForWord(wordId: Long): List<VocabularyReview>
    suspend fun stats(today: LocalDate): VocabularyStats
    suspend fun generateDailyTask(today: LocalDate, now: Instant): VocabularyTaskResult
}

class OfflineVocabularyRepository(
    private val database: StudyOSDatabase,
    private val dao: VocabularyDao,
    private val taskRepository: TaskRepository,
    private val historyDao: HistoryDao,
    private val clock: Clock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : VocabularyRepository {

    override fun observeSummary(today: LocalDate): Flow<VocabularySummary> = combine(
        dao.observeWordCount(),
        dao.observeDueCount(today.toEpochDay()),
        dao.observeSettings(),
    ) { total, due, settings ->
        VocabularySummary(
            totalWords = total,
            dueWords = due,
            settings = settings?.toDomainModel() ?: VocabularySettings(),
        )
    }

    override fun observeSettings(): Flow<VocabularySettings> =
        dao.observeSettings().map { it?.toDomainModel() ?: VocabularySettings() }

    override suspend fun getSettings(): VocabularySettings =
        dao.getSettings()?.toDomainModel() ?: VocabularySettings()

    override suspend fun saveSettings(settings: VocabularySettings) {
        settings.validationError()?.let { throw IllegalArgumentException(it) }
        dao.upsertSettings(settings.toEntity(clock.millis()))
    }

    override suspend fun pageWords(
        listName: String,
        search: String,
        limit: Int,
        offset: Int,
    ): List<VocabularyWord> {
        require(limit in 1..200) { "每页数量应为 1–200" }
        require(offset >= 0) { "分页偏移不能为负" }
        return dao.pageWords(listName.trim(), search.trim(), limit, offset).map { it.toDomainModel() }
    }

    override suspend fun countWords(listName: String, search: String): Int =
        dao.countWords(listName.trim(), search.trim())

    override suspend fun listNames(): List<String> = dao.listNames()

    override suspend fun getWord(id: Long): VocabularyWord? = dao.getWord(id)?.toDomainModel()

    override suspend fun addWord(word: VocabularyWord): Long {
        val text = word.word.trim()
        require(text.isNotEmpty()) { "请输入单词" }
        require(text.length <= 80) { "单词不能超过 80 个字符" }
        val normalized = VocabularyCsvParser.normalizeWord(text)
        if (dao.findWord(normalized) != null) throw IllegalArgumentException("单词「$text」已存在")
        val now = clock.millis()
        return dao.insertWord(
            VocabularyWordEntity(
                normalizedWord = normalized,
                word = text,
                phonetic = word.phonetic.trim(),
                partOfSpeech = word.partOfSpeech.trim(),
                meaning = word.meaning.trim(),
                example = word.example.trim(),
                listName = word.listName.trim().ifEmpty { "自定义" },
                note = word.note.trim(),
                status = VocabularyStatus.NEW.name,
                familiarity = 0,
                repetition = 0,
                intervalDays = 0,
                easeFactor = 2.5,
                dueDateEpochDay = LocalDate.now(clock.withZone(zoneId)).toEpochDay(),
                lastReviewedAtEpochMillis = null,
                reviewCount = 0,
                lapseCount = 0,
                source = "USER",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                revision = 1,
            ),
        )
    }

    override suspend fun updateWord(word: VocabularyWord) {
        require(word.id > 0) { "单词不存在" }
        val existing = dao.getWord(word.id) ?: throw IllegalArgumentException("单词不存在，可能已被删除")
        require(word.meaning.length <= 500) { "释义过长" }
        val normalized = VocabularyCsvParser.normalizeWord(
            word.word.trim().ifEmpty { existing.normalizedWord },
        )
        if (normalized != existing.normalizedWord) {
            val clash = dao.findWord(normalized)
            require(clash == null || clash.id == existing.id) { "单词「${word.word.trim()}」已存在" }
        }
        dao.updateWord(
            existing.copy(
                normalizedWord = normalized,
                word = word.word.trim().ifEmpty { existing.word },
                meaning = word.meaning.trim(),
                phonetic = word.phonetic.trim(),
                partOfSpeech = word.partOfSpeech.trim(),
                example = word.example.trim(),
                listName = word.listName.trim().ifEmpty { existing.listName },
                note = word.note.trim(),
                status = word.status.name,
                familiarity = word.familiarity.coerceIn(0, 5),
                updatedAtEpochMillis = clock.millis(),
                revision = existing.revision + 1,
            ),
        )
    }

    override suspend fun deleteWord(id: Long) = dao.deleteWord(id)

    override suspend fun importCsv(text: String): VocabularyImportResultSummary {
        if (text.isBlank()) return VocabularyImportResultSummary.Rejected("文件内容为空")
        if (text.length > MAX_CSV_CHARS) {
            return VocabularyImportResultSummary.Rejected("文件过大，单次导入上限约 5 MB")
        }
        val parsed = VocabularyCsvParser.parse(text)
        if (parsed.rows.isEmpty()) {
            return VocabularyImportResultSummary.Rejected("没有解析到任何词汇行，请检查表头和分隔符")
        }
        if (parsed.rows.size > MAX_IMPORT_ROWS) {
            return VocabularyImportResultSummary.Rejected("单次最多导入 $MAX_IMPORT_ROWS 个单词，请拆分文件")
        }
        val now = clock.millis()
        val today = LocalDate.now(clock.withZone(zoneId)).toEpochDay()
        var inserted = 0
        var skipped = 0
        var invalid = 0
        database.withTransaction {
            parsed.rows.forEach { row ->
                if (row.error != null) {
                    invalid += 1
                    return@forEach
                }
                val normalized = VocabularyCsvParser.normalizeWord(row.word)
                if (dao.findWord(normalized) != null) {
                    skipped += 1
                    return@forEach
                }
                dao.insertWord(
                    VocabularyWordEntity(
                        normalizedWord = normalized,
                        word = row.word,
                        phonetic = row.phonetic,
                        partOfSpeech = row.partOfSpeech,
                        meaning = row.meaning,
                        example = row.example,
                        listName = row.listName,
                        note = row.note,
                        status = VocabularyStatus.NEW.name,
                        familiarity = 0,
                        repetition = 0,
                        intervalDays = 0,
                        easeFactor = 2.5,
                        dueDateEpochDay = today,
                        lastReviewedAtEpochMillis = null,
                        reviewCount = 0,
                        lapseCount = 0,
                        source = "CSV",
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                        revision = 1,
                    ),
                )
                inserted += 1
            }
        }
        return VocabularyImportResultSummary.Saved(
            inserted = inserted,
            skippedDuplicates = skipped,
            invalid = invalid,
            parsed = parsed,
        )
    }

    override suspend fun dueWords(today: LocalDate): List<VocabularyWord> {
        val settings = getSettings()
        val epoch = today.toEpochDay()
        val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        // 每日额度要在“当天累计”上扣减，而不是每次查询都重新给满额，
        // 否则结束一轮后再次点“开始复习”可以无限续杯。
        val firstReviewsToday = dao.countFirstReviewsBetween(dayStart, dayEnd)
        val reviewedWordsToday = dao.countReviewedWordsBetween(dayStart, dayEnd)
        val newRemaining = (settings.dailyNewLimit - firstReviewsToday).coerceAtLeast(0)
        val reviewDone = (reviewedWordsToday - firstReviewsToday).coerceAtLeast(0)
        val reviewRemaining = (settings.dailyReviewLimit - reviewDone).coerceAtLeast(0)
        val newWords = dao.newWords(epoch, newRemaining)
        val reviews = dao.reviewWords(epoch, reviewRemaining)
        return (newWords + reviews).map { it.toDomainModel() }
    }

    override suspend fun review(
        wordId: Long,
        rating: VocabularyRating,
        responseTimeMillis: Long?,
        now: Instant,
    ): VocabularyReview {
        val today = LocalDate.now(clock.withZone(zoneId))
        return database.withTransaction {
            val entity = dao.getWord(wordId) ?: throw IllegalArgumentException("单词不存在，可能已被删除")
            if (entity.status == VocabularyStatus.SUSPENDED.name) {
                throw IllegalArgumentException("该单词已暂停复习，请先恢复")
            }
            val word = entity.toDomainModel()
            val result = Sm2Scheduler.review(word, rating, today)
            val nowMillis = now.toEpochMilli()
            dao.updateWord(
                entity.copy(
                    status = result.status.name,
                    repetition = result.repetition,
                    intervalDays = result.intervalDays,
                    easeFactor = result.easeFactor,
                    familiarity = result.familiarity,
                    dueDateEpochDay = result.dueDate.toEpochDay(),
                    lastReviewedAtEpochMillis = nowMillis,
                    reviewCount = entity.reviewCount + 1,
                    lapseCount = entity.lapseCount + if (result.recalled) 0 else 1,
                    updatedAtEpochMillis = nowMillis,
                    revision = entity.revision + 1,
                ),
            )
            val reviewId = dao.insertReview(
                VocabularyReviewEntity(
                    wordId = wordId,
                    reviewedAtEpochMillis = nowMillis,
                    rating = rating.name,
                    recalled = result.recalled,
                    previousIntervalDays = entity.intervalDays,
                    newIntervalDays = result.intervalDays,
                    nextDueEpochDay = result.dueDate.toEpochDay(),
                    responseTimeMillis = responseTimeMillis,
                    createdAtEpochMillis = nowMillis,
                ),
            )
            historyDao.insert(
                TaskEventEntity(
                    objectType = "VOCABULARY",
                    objectId = wordId,
                    eventType = if (result.recalled) "REVIEW_SUCCEEDED" else "REVIEW_FAILED",
                    occurredAtEpochMillis = nowMillis,
                    beforeJson = """{"intervalDays":${entity.intervalDays},"ease":${entity.easeFactor}}""",
                    afterJson = """{"intervalDays":${result.intervalDays},"ease":${result.easeFactor},"due":"${result.dueDate}"}""",
                    source = "USER",
                    reason = "${word.word} · ${rating.label} · 下次复习 ${result.dueDate}",
                    sessionId = null,
                    revision = entity.revision + 1,
                ),
            )
            VocabularyReview(
                id = reviewId,
                wordId = wordId,
                reviewedAt = now,
                rating = rating,
                recalled = result.recalled,
                previousIntervalDays = entity.intervalDays,
                newIntervalDays = result.intervalDays,
                nextDueDate = result.dueDate,
                responseTimeMillis = responseTimeMillis,
            )
        }
    }

    override suspend fun reviewsForWord(wordId: Long): List<VocabularyReview> =
        dao.reviewsForWord(wordId).map {
            VocabularyReview(
                id = it.id,
                wordId = it.wordId,
                reviewedAt = Instant.ofEpochMilli(it.reviewedAtEpochMillis),
                rating = runCatching { VocabularyRating.valueOf(it.rating) }.getOrDefault(VocabularyRating.GOOD),
                recalled = it.recalled,
                previousIntervalDays = it.previousIntervalDays,
                newIntervalDays = it.newIntervalDays,
                nextDueDate = LocalDate.ofEpochDay(it.nextDueEpochDay),
                responseTimeMillis = it.responseTimeMillis,
            )
        }

    override suspend fun stats(today: LocalDate): VocabularyStats {
        val total = dao.countAllWords()
        val mastered = dao.masteredCount()
        val learning = dao.countLearningWords()
        val newWords = dao.countNewWords()
        val due = dao.countDue(today.toEpochDay())
        val recent = dao.recentReviews(500)
        val rangeStart = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val recentWeek = recent.filter { it.reviewedAtEpochMillis >= rangeStart }
        val recallRate = if (recentWeek.isEmpty()) {
            null
        } else {
            (recentWeek.count { it.recalled } * 100 / recentWeek.size).coerceIn(0, 100)
        }
        val reviewDates = recent.map {
            Instant.ofEpochMilli(it.reviewedAtEpochMillis).atZone(zoneId).toLocalDate()
        }.toSet()
        var streak = 0
        var cursor = today
        if (cursor !in reviewDates) cursor = cursor.minusDays(1)
        while (cursor in reviewDates) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        val answered = recentWeek.mapNotNull { it.responseTimeMillis }
        val avgMinutes = if (answered.isEmpty()) {
            null
        } else {
            (answered.sum() / answered.size / 60_000L).toInt()
        }
        return VocabularyStats(
            totalWords = total,
            learnedWords = learning + mastered,
            dueWords = due,
            masteredWords = mastered,
            newWords = newWords,
            recentReviews = recentWeek.size,
            recentRecallRatePercent = recallRate,
            streakDays = streak,
            averageReviewMinutesPerDay = avgMinutes,
            listNames = dao.listNames(),
        )
    }

    override suspend fun generateDailyTask(today: LocalDate, now: Instant): VocabularyTaskResult {
        val settings = getSettings()
        if (!settings.taskGenerationEnabled) return VocabularyTaskResult.Disabled
        val sourceId = today.toString()
        val existing = database.studyTaskDao().findBySource("VOCABULARY", sourceId)
        if (existing != null) return VocabularyTaskResult.AlreadyExists(existing.id)
        val epoch = today.toEpochDay()
        val dueReview = dao.countDueReview(epoch)
        val newAvailable = dao.countNewWords()
        val plan = VocabularyTaskPlanner.build(today, dueReview, newAvailable, settings)
        if (!plan.hasWork) return VocabularyTaskResult.NothingDue
        val result = taskRepository.saveTask(
            StudyTask(
                title = plan.title,
                description = plan.description,
                subject = "大学英语",
                estimatedMinutes = plan.plannedMinutes.coerceAtLeast(5),
                dueDate = today,
                sourceType = "VOCABULARY",
                sourceId = sourceId,
            ),
        )
        dao.upsertSettings(settings.copy(lastTaskGenerated = today).toEntity(clock.millis()))
        return VocabularyTaskResult.Created(
            taskId = result.createdTaskIds.first(),
            title = plan.title,
            minutes = plan.plannedMinutes.coerceAtLeast(5),
        )
    }

    companion object {
        const val MAX_IMPORT_ROWS = 5000
        const val MAX_CSV_CHARS = 5 * 1024 * 1024
    }
}

private fun VocabularyWordEntity.toDomainModel() = VocabularyWord(
    id = id,
    word = word,
    phonetic = phonetic,
    partOfSpeech = partOfSpeech,
    meaning = meaning,
    example = example,
    listName = listName,
    note = note,
    status = runCatching { VocabularyStatus.valueOf(status) }.getOrDefault(VocabularyStatus.NEW),
    familiarity = familiarity,
    repetition = repetition,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    dueDate = LocalDate.ofEpochDay(dueDateEpochDay),
    lastReviewedAt = lastReviewedAtEpochMillis?.let(Instant::ofEpochMilli),
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    source = source,
    revision = revision,
)

private fun VocabularySettings.toEntity(now: Long) = VocabularySettingsEntity(
    id = 1,
    dailyNewLimit = dailyNewLimit,
    dailyReviewLimit = dailyReviewLimit,
    dailyTimeBudgetMinutes = dailyTimeBudgetMinutes,
    examDateEpochDay = examDate?.toEpochDay(),
    taskGenerationEnabled = taskGenerationEnabled,
    lastTaskGeneratedEpochDay = lastTaskGenerated?.toEpochDay(),
    updatedAtEpochMillis = now,
)

private fun VocabularySettingsEntity.toDomainModel() = VocabularySettings(
    dailyNewLimit = dailyNewLimit,
    dailyReviewLimit = dailyReviewLimit,
    dailyTimeBudgetMinutes = dailyTimeBudgetMinutes,
    examDate = examDateEpochDay?.let(LocalDate::ofEpochDay),
    taskGenerationEnabled = taskGenerationEnabled,
    lastTaskGenerated = lastTaskGeneratedEpochDay?.let(LocalDate::ofEpochDay),
)
