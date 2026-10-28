package com.zhixu.studyos.core.data.repository

import com.zhixu.studyos.core.data.local.DailyLogDao
import com.zhixu.studyos.core.data.local.DailySummaryEntity
import com.zhixu.studyos.core.data.local.DailyTaskEntity
import com.zhixu.studyos.core.data.local.EntertainmentRecordEntity
import com.zhixu.studyos.core.model.DailyTask
import com.zhixu.studyos.core.model.DayRecord
import com.zhixu.studyos.core.model.MAX_DAILY_SUMMARY_LENGTH
import com.zhixu.studyos.core.model.MAX_DAILY_TASK_TITLE_LENGTH
import com.zhixu.studyos.core.model.MAX_ENTERTAINMENT_MINUTES
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * V3.1 记录层：每日待办、每日总结、娱乐时间。
 *
 * 只读写三张新表；旧任务/学习/题库数据保持原样，不在界面中暴露。
 */
interface DailyLogRepository {
    fun observeTasks(date: LocalDate): Flow<List<DailyTask>>
    fun observeSummary(date: LocalDate): Flow<String?>
    fun observeEntertainment(date: LocalDate): Flow<Int?>

    /** 所有有记录的日子，按日期倒序。睡眠时长由调用方补充。 */
    fun observeHistory(): Flow<List<DayRecord>>

    suspend fun addTask(date: LocalDate, title: String): Long
    suspend fun renameTask(taskId: Long, title: String)
    suspend fun setTaskCompleted(taskId: Long, completed: Boolean)
    suspend fun deleteTask(taskId: Long)

    suspend fun saveSummary(date: LocalDate, content: String)

    suspend fun saveEntertainment(date: LocalDate, minutes: Int)
    suspend fun deleteEntertainment(date: LocalDate)
}

class OfflineDailyLogRepository(
    private val dao: DailyLogDao,
    private val clock: Clock,
) : DailyLogRepository {

    override fun observeTasks(date: LocalDate): Flow<List<DailyTask>> =
        dao.observeTasks(date.toEpochDay()).map { rows -> rows.map { it.toDomainModel() } }

    override fun observeSummary(date: LocalDate): Flow<String?> =
        dao.observeSummary(date.toEpochDay()).map { it?.content }

    override fun observeEntertainment(date: LocalDate): Flow<Int?> =
        dao.observeEntertainment(date.toEpochDay()).map { it?.minutes }

    override fun observeHistory(): Flow<List<DayRecord>> = combine(
        dao.observeAllTasks(),
        dao.observeAllSummaries(),
        dao.observeAllEntertainment(),
    ) { tasks, summaries, entertainment ->
        val summariesByDate = summaries.associateBy { it.dateEpochDay }
        val entertainmentByDate = entertainment.associateBy { it.dateEpochDay }
        val tasksByDate = tasks.groupBy { it.dateEpochDay }
        val dates = buildSet {
            tasks.forEach { add(it.dateEpochDay) }
            summaries.forEach { add(it.dateEpochDay) }
            entertainment.forEach { add(it.dateEpochDay) }
        }
        // 历史只展示今天及以前；未来的待办属于计划，不作为已经发生的记录。
        val todayEpochDay = LocalDate.now(clock).toEpochDay()
        dates.filter { it <= todayEpochDay }.sortedDescending().map { epochDay ->
            val dayTasks = tasksByDate[epochDay].orEmpty()
            val summary = summariesByDate[epochDay]?.content?.takeIf { it.isNotBlank() }
            DayRecord(
                date = LocalDate.ofEpochDay(epochDay),
                totalTasks = dayTasks.size,
                completedTasks = dayTasks.count { it.isCompleted },
                summary = summary,
                entertainmentMinutes = entertainmentByDate[epochDay]?.minutes,
            )
        }
    }

    override suspend fun addTask(date: LocalDate, title: String): Long {
        val normalized = normalizeTaskTitle(title)
        val now = clock.millis()
        return dao.insertTask(
            DailyTaskEntity(
                dateEpochDay = date.toEpochDay(),
                title = normalized,
                sortOrder = dao.maxSortOrder(date.toEpochDay()) + 1,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    override suspend fun renameTask(taskId: Long, title: String) {
        val existing = dao.task(taskId)
            ?: throw IllegalArgumentException("这条待办已不存在")
        dao.updateTask(
            existing.copy(
                title = normalizeTaskTitle(title),
                updatedAtEpochMillis = clock.millis(),
            ),
        )
    }

    override suspend fun setTaskCompleted(taskId: Long, completed: Boolean) {
        val existing = dao.task(taskId)
            ?: throw IllegalArgumentException("这条待办已不存在")
        if (existing.isCompleted == completed) return
        val now = clock.millis()
        dao.updateTask(
            existing.copy(
                isCompleted = completed,
                completedAtEpochMillis = if (completed) now else null,
                updatedAtEpochMillis = now,
            ),
        )
    }

    override suspend fun deleteTask(taskId: Long) {
        val deleted = dao.deleteTask(taskId)
        require(deleted > 0) { "这条待办已不存在" }
    }

    override suspend fun saveSummary(date: LocalDate, content: String) {
        val normalized = content.trim()
        require(normalized.length <= MAX_DAILY_SUMMARY_LENGTH) {
            "总结不能超过 $MAX_DAILY_SUMMARY_LENGTH 个字符"
        }
        val epochDay = date.toEpochDay()
        val now = clock.millis()
        val existing = dao.summary(epochDay)
        if (existing == null) {
            if (normalized.isEmpty()) return
            dao.insertSummary(
                DailySummaryEntity(
                    dateEpochDay = epochDay,
                    content = normalized,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        } else {
            dao.updateSummary(
                existing.copy(
                    content = normalized,
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }

    override suspend fun saveEntertainment(date: LocalDate, minutes: Int) {
        require(minutes in 0..MAX_ENTERTAINMENT_MINUTES) {
            "娱乐时间应在 0–${MAX_ENTERTAINMENT_MINUTES / 60} 小时之间"
        }
        val epochDay = date.toEpochDay()
        val now = clock.millis()
        val existing = dao.entertainment(epochDay)
        if (existing == null) {
            dao.insertEntertainment(
                EntertainmentRecordEntity(
                    dateEpochDay = epochDay,
                    minutes = minutes,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        } else {
            dao.updateEntertainment(
                existing.copy(
                    minutes = minutes,
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }

    override suspend fun deleteEntertainment(date: LocalDate) {
        dao.deleteEntertainment(date.toEpochDay())
    }

    private fun normalizeTaskTitle(title: String): String {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "请输入待办内容" }
        require(normalized.length <= MAX_DAILY_TASK_TITLE_LENGTH) {
            "待办内容不能超过 $MAX_DAILY_TASK_TITLE_LENGTH 个字符"
        }
        return normalized
    }
}

private fun DailyTaskEntity.toDomainModel(): DailyTask = DailyTask(
    id = id,
    date = LocalDate.ofEpochDay(dateEpochDay),
    title = title,
    isCompleted = isCompleted,
    completedAtEpochMillis = completedAtEpochMillis,
    sortOrder = sortOrder,
)
