package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.ScheduleInstance
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object ScheduleDateTimes {
    /** Uses civil dates for recurrence and zone rules only at the final instant boundary. */
    fun atMinute(
        anchorDate: LocalDate,
        minute: Int,
        zoneId: ZoneId,
        endBoundary: Boolean = false,
    ): ZonedDateTime {
        require(minute >= 0) { "分钟不能为负数" }
        val dayOffset = Math.floorDiv(minute, MINUTES_PER_DAY)
        val minuteOfDay = Math.floorMod(minute, MINUTES_PER_DAY)
        val local = LocalDateTime.of(
            anchorDate.plusDays(dayOffset.toLong()),
            LocalTime.of(minuteOfDay / 60, minuteOfDay % 60),
        )
        val offsets = zoneId.rules.getValidOffsets(local)
        return when {
            // java.time shifts a local time in a DST gap forward by the gap length.
            offsets.isEmpty() -> ZonedDateTime.ofLocal(local, zoneId, null)
            offsets.size == 1 -> ZonedDateTime.ofStrict(local, offsets.single(), zoneId)
            // At an overlap, starts use the earlier offset and ends use the later one.
            else -> ZonedDateTime.ofStrict(local, if (endBoundary) offsets.last() else offsets.first(), zoneId)
        }
    }

    fun dayStart(date: LocalDate, zoneId: ZoneId): Instant =
        atMinute(date, 0, zoneId).toInstant()

    fun dayEnd(date: LocalDate, zoneId: ZoneId): Instant =
        atMinute(date.plusDays(1L), 0, zoneId, endBoundary = true).toInstant()

    fun instantRange(instance: ScheduleInstance, zoneId: ZoneId): Pair<Instant, Instant>? {
        val start = atMinute(instance.anchorDate, instance.startMinute, zoneId).toInstant()
        val end = atMinute(
            instance.anchorDate,
            instance.endMinuteExclusive,
            zoneId,
            endBoundary = true,
        ).toInstant()
        return if (end.isAfter(start)) start to end else null
    }
}
