package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.HistoryDao
import com.zhixu.studyos.core.data.local.ScheduleDao
import com.zhixu.studyos.core.data.local.SleepChangeHistoryEntity
import com.zhixu.studyos.core.data.local.SleepDateOverrideEntity
import com.zhixu.studyos.core.data.local.SleepRecordEntity
import com.zhixu.studyos.core.data.local.SleepDao
import com.zhixu.studyos.core.data.local.SleepScheduleEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.TaskEventEntity
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.schedule.ScheduleEngine
import com.zhixu.studyos.core.sleep.SleepBusyItem
import com.zhixu.studyos.core.sleep.SleepConflict
import com.zhixu.studyos.core.sleep.SleepDateOverride
import com.zhixu.studyos.core.sleep.SleepInterval
import com.zhixu.studyos.core.sleep.SleepProtectionEngine
import com.zhixu.studyos.core.sleep.SleepRecord
import com.zhixu.studyos.core.sleep.SleepSchedule
import com.zhixu.studyos.core.sleep.SleepStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class SleepSnapshot(
    val schedule: SleepSchedule = SleepSchedule.Default,
    val overrides: Map<LocalDate, SleepDateOverride> = emptyMap(),
    val records: List<SleepRecord> = emptyList(),
)

data class SleepOverview(
    val status: SleepStatus,
    val intervals: List<SleepInterval>,
    val description: String,
    val conflicts: List<SleepConflict>,
    val recommendations: List<String>,
)

interface SleepRepository {
    fun observeSnapshot(): Flow<SleepSnapshot>
    fun observeChangeHistory(): Flow<List<com.zhixu.studyos.core.data.local.SleepChangeHistoryEntity>>
    suspend fun getSchedule(): SleepSchedule
    suspend fun getOverrides(): Map<LocalDate, SleepDateOverride>
    suspend fun saveSchedule(schedule: SleepSchedule, reason: String = "用户修改作息")
    suspend fun saveOverride(override: SleepDateOverride)
    suspend fun deleteOverride(date: LocalDate)
    suspend fun saveRecord(record: SleepRecord)
    suspend fun deleteRecord(date: LocalDate)
    suspend fun intervalsOn(date: LocalDate): List<SleepInterval>
    suspend fun statusAt(date: LocalDate, minuteOfDay: Int): SleepStatus
    suspend fun overview(date: LocalDate, minuteOfDay: Int): SleepOverview
    suspend fun sleepConflicts(date: LocalDate): List<SleepConflict>
}

class OfflineSleepRepository(
    private val database: StudyOSDatabase,
    private val sleepDao: SleepDao,
    private val scheduleDao: ScheduleDao,
    private val historyDao: HistoryDao,
    private val clock: Clock,
) : SleepRepository {
    override fun observeSnapshot(): Flow<SleepSnapshot> = combine(
        sleepDao.observeSchedule(),
        sleepDao.observeOverrides(),
        sleepDao.observeRecords(3_650),
    ) { schedule, overrides, records ->
        SleepSnapshot(
            schedule = schedule?.toDomainModel() ?: SleepSchedule.Default,
            overrides = overrides.associate { it.toDomainModel().date to it.toDomainModel() },
            records = records.map { it.toDomainModel() },
        )
    }

    override fun observeChangeHistory() = sleepDao.observeChangeHistory()

    override suspend fun getSchedule(): SleepSchedule = sleepDao.getSchedule()?.toDomainModel() ?: SleepSchedule.Default

    override suspend fun getOverrides(): Map<LocalDate, SleepDateOverride> =
        sleepDao.getOverrides().associate { it.toDomainModel().date to it.toDomainModel() }

    override suspend fun saveSchedule(schedule: SleepSchedule, reason: String) {
        schedule.validationError()?.let { throw IllegalArgumentException(it) }
        val before = sleepDao.getSchedule()?.toDomainModel() ?: SleepSchedule.Default
        val now = clock.millis()
        database.withTransaction {
            sleepDao.upsertSchedule(schedule.copy(updatedAtEpochMillis = now).toEntity())
            sleepDao.insertChangeHistory(
                SleepChangeHistoryEntity(
                    changedAtEpochMillis = now,
                    beforeJson = SleepScheduleJson.encode(before),
                    afterJson = SleepScheduleJson.encode(schedule),
                    reason = reason.trim().ifEmpty { "用户修改作息" },
                ),
            )
            historyDao.insert(
                TaskEventEntity(
                    objectType = "SLEEP",
                    objectId = 1,
                    eventType = "SLEEP_SCHEDULE_CHANGED",
                    occurredAtEpochMillis = now,
                    beforeJson = SleepScheduleJson.encode(before),
                    afterJson = SleepScheduleJson.encode(schedule),
                    source = "USER",
                    reason = reason.trim().ifEmpty { "用户修改作息" },
                    sessionId = null,
                    revision = 0,
                ),
            )
        }
    }

    override suspend fun saveOverride(override: SleepDateOverride) {
        override.validationError()?.let { throw IllegalArgumentException(it) }
        sleepDao.upsertOverride(override.toEntity())
    }

    override suspend fun deleteOverride(date: LocalDate) {
        sleepDao.deleteOverride(date.toEpochDay())
    }

    override suspend fun saveRecord(record: SleepRecord) {
        record.validationError()?.let { throw IllegalArgumentException(it) }
        val now = clock.millis()
        val existing = sleepDao.getRecord(record.date.toEpochDay())
        sleepDao.upsertRecord(
            SleepRecordEntity(
                dateEpochDay = record.date.toEpochDay(),
                bedTimeEpochMillis = record.bedAt?.toEpochMilli(),
                wakeTimeEpochMillis = record.wakeAt?.toEpochMilli(),
                quality = record.quality,
                nextDayEnergy = record.nextDayEnergy,
                note = record.note.trim(),
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    override suspend fun deleteRecord(date: LocalDate) {
        sleepDao.deleteRecord(date.toEpochDay())
    }

    override suspend fun intervalsOn(date: LocalDate): List<SleepInterval> =
        SleepProtectionEngine.intervalsOn(date, getSchedule(), getOverrides())

    override suspend fun statusAt(date: LocalDate, minuteOfDay: Int): SleepStatus =
        SleepProtectionEngine.statusAt(date, minuteOfDay, getSchedule(), getOverrides())

    override suspend fun overview(date: LocalDate, minuteOfDay: Int): SleepOverview {
        val schedule = getSchedule()
        val overrides = getOverrides()
        val status = SleepProtectionEngine.statusAt(date, minuteOfDay, schedule, overrides)
        val intervals = SleepProtectionEngine.intervalsOn(date, schedule, overrides)
        val conflicts = sleepConflicts(date)
        val recommendations = buildList {
            if (conflicts.isNotEmpty()) {
                add("睡眠保护与 ${conflicts.size} 项固定安排冲突；应用不会静默覆盖课程，请手动调整。")
            }
            if (schedule.isLaterThanLatestBed(
                    SleepProtectionEngine.effectiveForNight(date, schedule, overrides).bedMinute,
                )
            ) {
                add("今晚入睡时间晚于你设置的最晚睡觉时间；确实需要调整时，请修改作息并保留变更记录。")
            }
            if (schedule.isEarlierThanEarliestWake(
                    SleepProtectionEngine.effectiveForNight(date, schedule, overrides).wakeMinute,
                )
            ) {
                add("起床时间早于你设置的最早起床时间；排程仍会保护这段睡眠，但你可能需要注意休息。")
            }
            if (schedule.napEnabled) add("午休已被加入排程硬占用，不会被学习任务占用。")
        }
        return SleepOverview(
            status = status,
            intervals = intervals,
            description = SleepProtectionEngine.describeDay(date, schedule, overrides),
            conflicts = conflicts,
            recommendations = recommendations,
        )
    }

    override suspend fun sleepConflicts(date: LocalDate): List<SleepConflict> {
        val snapshot = buildScheduleSnapshot()
        val schedule = getSchedule()
        val overrides = getOverrides()
        val day = ScheduleEngine.daySchedule(snapshot, date, clock.zone)
        val busy = day.items.filter { item ->
            when (item.instance.type) {
                ScheduleItemType.COURSE, ScheduleItemType.ONE_OFF_EVENT -> true
                ScheduleItemType.ROUTINE ->
                    item.instance.routineCategory != RoutineCategory.SLEEP
                ScheduleItemType.TASK -> false
            }
        }.map { item ->
            SleepBusyItem(
                title = item.instance.title,
                startMinute = item.segmentStartMinute,
                endMinuteExclusive = item.segmentEndMinuteExclusive,
            )
        }
        return SleepProtectionEngine.findConflicts(date, schedule, busy, overrides)
    }

    private suspend fun buildScheduleSnapshot(): ScheduleSnapshot = ScheduleSnapshot(
        semester = scheduleDao.getSemester()?.toDomainModel(),
        courses = scheduleDao.getCourses().map { it.toDomainModel() },
        exceptions = scheduleDao.getExceptions().map { it.toDomainModel() },
        events = scheduleDao.getEvents().map { it.toDomainModel() },
        routines = scheduleDao.getRoutines().map { it.toDomainModel() },
    )
}

private fun SleepSchedule.toEntity() = SleepScheduleEntity(
    id = 1,
    weekdayBedMinute = weekdayBedMinute,
    weekdayWakeMinute = weekdayWakeMinute,
    weekendBedMinute = weekendBedMinute,
    weekendWakeMinute = weekendWakeMinute,
    earliestWakeMinute = earliestWakeMinute,
    latestBedMinute = latestBedMinute,
    preSleepBufferMinutes = preSleepBufferMinutes,
    napEnabled = napEnabled,
    napStartMinute = napStartMinute,
    napEndMinute = napEndMinute,
    examPeriodStartEpochDay = examPeriodStart?.toEpochDay(),
    examPeriodEndEpochDay = examPeriodEnd?.toEpochDay(),
    examBedMinute = examBedMinute,
    examWakeMinute = examWakeMinute,
    bedtimeModeEnabled = bedtimeModeEnabled,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SleepScheduleEntity.toDomainModel() = SleepSchedule(
    weekdayBedMinute = weekdayBedMinute,
    weekdayWakeMinute = weekdayWakeMinute,
    weekendBedMinute = weekendBedMinute,
    weekendWakeMinute = weekendWakeMinute,
    earliestWakeMinute = earliestWakeMinute,
    latestBedMinute = latestBedMinute,
    preSleepBufferMinutes = preSleepBufferMinutes,
    napEnabled = napEnabled,
    napStartMinute = napStartMinute,
    napEndMinute = napEndMinute,
    examPeriodStart = examPeriodStartEpochDay?.let(LocalDate::ofEpochDay),
    examPeriodEnd = examPeriodEndEpochDay?.let(LocalDate::ofEpochDay),
    examBedMinute = examBedMinute,
    examWakeMinute = examWakeMinute,
    bedtimeModeEnabled = bedtimeModeEnabled,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SleepDateOverride.toEntity() = SleepDateOverrideEntity(
    dateEpochDay = date.toEpochDay(),
    bedMinute = bedMinute,
    wakeMinute = wakeMinute,
    note = note.trim(),
    createdAtEpochMillis = System.currentTimeMillis(),
)

private fun SleepDateOverrideEntity.toDomainModel() = SleepDateOverride(
    date = LocalDate.ofEpochDay(dateEpochDay),
    bedMinute = bedMinute,
    wakeMinute = wakeMinute,
    note = note,
)

private fun SleepRecordEntity.toDomainModel() = SleepRecord(
    date = LocalDate.ofEpochDay(dateEpochDay),
    bedAt = bedTimeEpochMillis?.let(Instant::ofEpochMilli),
    wakeAt = wakeTimeEpochMillis?.let(Instant::ofEpochMilli),
    quality = quality,
    nextDayEnergy = nextDayEnergy,
    note = note,
)

object SleepScheduleJson {
    fun encode(schedule: SleepSchedule): String = JSONObject()
        .put("weekdayBedMinute", schedule.weekdayBedMinute)
        .put("weekdayWakeMinute", schedule.weekdayWakeMinute)
        .put("weekendBedMinute", schedule.weekendBedMinute)
        .put("weekendWakeMinute", schedule.weekendWakeMinute)
        .put("earliestWakeMinute", schedule.earliestWakeMinute)
        .put("latestBedMinute", schedule.latestBedMinute)
        .put("preSleepBufferMinutes", schedule.preSleepBufferMinutes)
        .put("napEnabled", schedule.napEnabled)
        .put("napStartMinute", schedule.napStartMinute)
        .put("napEndMinute", schedule.napEndMinute)
        .put("examPeriodStart", schedule.examPeriodStart?.toString() ?: JSONObject.NULL)
        .put("examPeriodEnd", schedule.examPeriodEnd?.toString() ?: JSONObject.NULL)
        .put("examBedMinute", schedule.examBedMinute ?: JSONObject.NULL)
        .put("examWakeMinute", schedule.examWakeMinute ?: JSONObject.NULL)
        .put("bedtimeModeEnabled", schedule.bedtimeModeEnabled)
        .toString()
}
