package com.zhixu.studyos.feature.study

import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.scheduling.AutoScheduler
import com.zhixu.studyos.core.sleep.SleepInterval
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排程页 → AutoScheduler 的接线测试。
 *
 * 背景：SchedulingUiState 一直保存着 sleepIntervals，但 generate() 曾在构造
 * SchedulingInput 时漏传，导致睡眠硬约束在生产路径失效，而只测试 AutoScheduler
 * 的单元测试仍然全绿。这里从 UI 状态直接构造输入，防止再次漏接。
 */
class SchedulingInputWiringTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun toSchedulingInput_keepsSleepIntervalsFromUiState() {
        val sleep = mapOf(today to listOf(SleepInterval(8 * 60, 10 * 60)))
        val state = SchedulingUiState(
            today = today,
            zoneId = zone,
            sleepIntervals = sleep,
            settings = SchedulingSettings(
                studyStartMinute = 8 * 60,
                studyEndMinuteExclusive = 22 * 60,
                dailyLimitMinutes = 240,
                bufferMinutes = 0,
            ),
        )

        val input = state.toSchedulingInput(today, nowMinuteOfDay = 8 * 60)

        assertEquals(sleep, input.sleepIntervals)
    }

    @Test
    fun generatedPlan_fromUiState_neverOverlapsSleepIntervals() {
        val state = SchedulingUiState(
            today = today,
            zoneId = zone,
            tasks = listOf(
                StudyTask(
                    id = 1,
                    title = "睡眠时段内不得安排",
                    estimatedMinutes = 120,
                ),
            ),
            sleepIntervals = mapOf(today to listOf(SleepInterval(8 * 60, 10 * 60))),
            settings = SchedulingSettings(
                studyStartMinute = 8 * 60,
                studyEndMinuteExclusive = 22 * 60,
                dailyLimitMinutes = 240,
                bufferMinutes = 0,
            ),
        )

        val result = AutoScheduler.schedule(
            state.toSchedulingInput(today, nowMinuteOfDay = 8 * 60),
        )

        assertEquals(120, result.blocks.sumOf { it.durationMinutes })
        val sleep = state.sleepIntervals.getValue(today).single()
        result.blocks.forEach { block ->
            assertTrue(
                "自动时段 ${block.startMinute}-${block.endMinuteExclusive} 与睡眠 " +
                    "${sleep.startMinute}-${sleep.endMinuteExclusive} 重叠",
                block.startMinute >= sleep.endMinuteExclusive ||
                    block.endMinuteExclusive <= sleep.startMinute,
            )
        }
    }
}
