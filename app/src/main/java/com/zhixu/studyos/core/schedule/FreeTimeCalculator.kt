package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.FreeTimeInterval
import com.zhixu.studyos.core.model.ScheduleInstance
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object FreeTimeCalculator {
    /** Clips and merges busy intervals before taking their complement in the local day. */
    fun calculate(
        date: LocalDate,
        busyInstances: List<ScheduleInstance>,
        zoneId: ZoneId,
    ): List<FreeTimeInterval> {
        val dayStart = ScheduleDateTimes.dayStart(date, zoneId)
        val dayEnd = ScheduleDateTimes.dayEnd(date, zoneId)
        val clipped = busyInstances.mapNotNull { instance ->
            val range = ScheduleDateTimes.instantRange(instance, zoneId) ?: return@mapNotNull null
            val start = if (range.first.isBefore(dayStart)) dayStart else range.first
            val end = if (range.second.isAfter(dayEnd)) dayEnd else range.second
            if (end.isAfter(start)) start to end else null
        }.sortedWith(compareBy(Pair<Instant, Instant>::first, Pair<Instant, Instant>::second))

        val merged = mutableListOf<Pair<Instant, Instant>>()
        clipped.forEach { interval ->
            val previous = merged.lastOrNull()
            if (previous == null || interval.first.isAfter(previous.second)) {
                merged += interval
            } else if (interval.second.isAfter(previous.second)) {
                merged[merged.lastIndex] = previous.first to interval.second
            }
        }

        val freeTimes = mutableListOf<FreeTimeInterval>()
        var cursor = dayStart
        merged.forEach { busy ->
            if (busy.first.isAfter(cursor)) {
                freeTimes += intervalBetween(cursor, busy.first, zoneId)
            }
            if (busy.second.isAfter(cursor)) cursor = busy.second
        }
        if (cursor.isBefore(dayEnd)) freeTimes += intervalBetween(cursor, dayEnd, zoneId)
        return freeTimes.filter { it.durationMinutes > 0L }
    }

    fun totalMinutes(freeTimes: List<FreeTimeInterval>): Long =
        freeTimes.sumOf { Duration.between(it.start, it.end).toMinutes() }

    private fun intervalBetween(start: Instant, end: Instant, zoneId: ZoneId) = FreeTimeInterval(
        start = start.atZone(zoneId),
        end = end.atZone(zoneId),
    )
}
