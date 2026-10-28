package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.model.AiProviderConfig
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCostCalculatorTest {

    private val config = AiProviderConfig(
        enabled = true,
        baseUrl = "https://example.com/v1",
        model = "test-model",
        inputPriceMicrosPerMillionTokens = 3_000_000, // 3 元 / 百万
        outputPriceMicrosPerMillionTokens = 6_000_000, // 6 元 / 百万
    )

    @Test
    fun cost_isComputedFromPerMillionPrices() {
        // 1000 输入 token = 3000 微元；500 输出 token = 3000 微元
        val cost = AiCostCalculator.estimateMicros(1000, 500, config)

        assertEquals(6000L, cost)
    }

    @Test
    fun zeroPrices_produceZeroCost() {
        val cost = AiCostCalculator.estimateMicros(100_000, 50_000, AiProviderConfig())

        assertEquals(0L, cost)
    }

    @Test
    fun negativeTokens_areClampedToZero() {
        assertEquals(0L, AiCostCalculator.estimateMicros(-10, -5, config))
    }

    @Test
    fun rounding_isToNearestMicro() {
        // 1 token * 500000 / 1000000 = 0.5 → 四舍五入为 1
        val config = AiProviderConfig(inputPriceMicrosPerMillionTokens = 500_000)
        assertEquals(1L, AiCostCalculator.estimateMicros(1, 0, config))
    }

    @Test
    fun formatYuan_usesFourDecimals() {
        assertEquals("0.0060 元", AiCostCalculator.formatYuan(6000))
        assertEquals("1.5000 元", AiCostCalculator.formatYuan(1_500_000))
    }

    @Test
    fun formatTokens_isReadable() {
        assertEquals("999", AiCostCalculator.formatTokens(999))
        assertEquals("1.5k", AiCostCalculator.formatTokens(1500))
        assertEquals("2.00M", AiCostCalculator.formatTokens(2_000_000))
    }

    @Test
    fun estimateTokens_usesCharacterHeuristic() {
        assertTrue(AiCostCalculator.estimateTokens("abcdefgh") >= 1)
        assertEquals(0, AiCostCalculator.estimateTokens(""))
    }
}

class AiRequestHasherTest {

    @Test
    fun sameInput_producesSameKey() {
        val a = AiRequestHasher.cacheKey(1, "model-a", "{\"x\":1}")
        val b = AiRequestHasher.cacheKey(1, "model-a", "{\"x\":1}")

        assertEquals(a, b)
    }

    @Test
    fun modelChange_changesKey() {
        val a = AiRequestHasher.cacheKey(1, "model-a", "{}")
        val b = AiRequestHasher.cacheKey(1, "model-b", "{}")

        assertFalse(a == b)
    }

    @Test
    fun dataChange_changesKey() {
        val a = AiRequestHasher.cacheKey(1, "m", "{\"studyMinutes\":10}")
        val b = AiRequestHasher.cacheKey(1, "m", "{\"studyMinutes\":11}")

        assertFalse(a == b)
    }

    @Test
    fun promptVersionChange_changesKey() {
        assertFalse(AiRequestHasher.cacheKey(1, "m", "{}") == AiRequestHasher.cacheKey(2, "m", "{}"))
    }
}

class AiProviderConfigTest {

    @Test
    fun default_isNotConfiguredAndDisabled() {
        val config = AiProviderConfig()

        assertFalse(config.isConfigured)
        assertFalse(config.enabled)
        assertEquals(null, config.validationError())
    }

    @Test
    fun deepSeekDefault_isConfiguredButStillDisabledUntilUserEnablesIt() {
        val config = AiProviderConfig.DeepSeekDefault

        assertTrue(config.isConfigured)
        assertFalse(config.enabled)
        assertEquals("https://api.deepseek.com/v1", config.baseUrl)
        assertEquals("deepseek-chat", config.model)
        assertEquals(null, config.validationError())
    }

    @Test
    fun invalidScheme_isRejected() {
        val config = AiProviderConfig(baseUrl = "ftp://example.com", model = "m")

        assertTrue(config.validationError()!!.contains("http"))
    }

    @Test
    fun enabledWithoutUrl_isRejected() {
        val config = AiProviderConfig(enabled = true)

        assertTrue(config.validationError()!!.contains("服务地址"))
    }

    @Test
    fun timeoutOutOfRange_isRejected() {
        assertEquals(
            "请求超时应在 10–180 秒之间",
            AiProviderConfig(timeoutSeconds = 5).validationError(),
        )
        assertEquals(
            "请求超时应在 10–180 秒之间",
            AiProviderConfig(timeoutSeconds = 181).validationError(),
        )
    }

    @Test
    fun limitsAndPrices_areValidated() {
        assertTrue(AiProviderConfig(dailyCallLimit = 0).validationError()!!.contains("每日调用"))
        assertTrue(
            AiProviderConfig(dailyCostLimitMicros = -1).validationError()!!.contains("费用上限"),
        )
        assertTrue(
            AiProviderConfig(inputPriceMicrosPerMillionTokens = -1).validationError()!!.contains("价格"),
        )
    }

    @Test
    fun validConfig_hasNoError() {
        val config = AiProviderConfig(
            enabled = true,
            baseUrl = "https://api.example.com/v1",
            model = "gpt-x",
        )

        assertEquals(null, config.validationError())
        assertTrue(config.isConfigured)
    }
}

class AiSnapshotBuilderTest {

    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), zone)
    private val builder = AiSnapshotBuilder(clock, zone)
    private val today = LocalDate.of(2026, 10, 25)

    private val subjects = listOf(
        com.zhixu.studyos.core.model.Subject(id = 1, name = "数学分析", isPreset = true),
        com.zhixu.studyos.core.model.Subject(id = 2, name = "高等代数", isPreset = true),
    )

    private fun timing(
        subjectId: Long,
        date: LocalDate,
        minutes: Int,
        status: com.zhixu.studyos.core.model.StudyTimerStatus =
            com.zhixu.studyos.core.model.StudyTimerStatus.COMPLETED,
    ) = com.zhixu.studyos.core.model.TimingSession(
        id = 0,
        subjectId = subjectId,
        studyDate = date,
        mode = com.zhixu.studyos.core.model.StudyTimerMode.STOPWATCH,
        status = status,
        accumulatedMillis = minutes * 60_000L,
        startedAt = Instant.parse("2026-10-20T00:00:00Z"),
    )

    private fun task(
        id: Long,
        title: String,
        subject: String,
        status: com.zhixu.studyos.core.model.TaskStatus =
            com.zhixu.studyos.core.model.TaskStatus.BACKLOG,
        completedAt: Instant? = null,
        dueDate: LocalDate? = null,
        sessions: List<com.zhixu.studyos.core.model.TaskSession> = emptyList(),
        postponeCount: Int = 0,
    ) = com.zhixu.studyos.core.model.StudyTask(
        id = id,
        title = title,
        subject = subject,
        status = status,
        completedAt = completedAt,
        dueDate = dueDate,
        estimatedMinutes = 60,
        postponeCount = postponeCount,
        sessions = sessions,
    )

    @Test
    fun aggregatesSevenDayWindow() {
        val snapshot = builder.build(
            today = today,
            tasks = listOf(
                task(
                    id = 1,
                    title = "完成作业",
                    subject = "数学分析",
                    status = com.zhixu.studyos.core.model.TaskStatus.COMPLETED,
                    completedAt = Instant.parse("2026-10-24T08:00:00Z"),
                ),
                task(
                    id = 2,
                    title = "复习",
                    subject = "数学分析",
                    status = com.zhixu.studyos.core.model.TaskStatus.SCHEDULED,
                    sessions = listOf(
                        com.zhixu.studyos.core.model.TaskSession(
                            id = 1,
                            date = LocalDate.of(2026, 10, 21),
                            startMinute = 480,
                            endMinuteExclusive = 540,
                        ),
                    ),
                ),
            ),
            timings = listOf(
                timing(1, LocalDate.of(2026, 10, 20), 60),
                timing(1, LocalDate.of(2026, 10, 24), 30),
                timing(2, LocalDate.of(2026, 10, 25), 45),
                timing(1, LocalDate.of(2026, 10, 1), 120), // 窗口外
                timing(1, today, 90, com.zhixu.studyos.core.model.StudyTimerStatus.RUNNING),
            ),
            manualProblems = emptyList(),
            bankProblems = emptyList(),
            subjects = subjects,
            goals = com.zhixu.studyos.core.model.LearningGoals(dailyStudyMinutes = 120),
            settings = com.zhixu.studyos.core.model.SchedulingSettings(dailyLimitMinutes = 240),
        )

        assertEquals(LocalDate.of(2026, 10, 19), snapshot.windowStart)
        assertEquals(today, snapshot.windowEnd)
        assertEquals(135, snapshot.totalStudyMinutes) // 60 + 30 + 45，排除窗口外与进行中
        assertEquals(7, snapshot.recentDays.size)
        assertEquals(60, snapshot.recentDays.first { it.date == LocalDate.of(2026, 10, 20) }.studyMinutes)
        val math = snapshot.subjects.first { it.subjectName == "数学分析" }
        assertEquals(90, math.studyMinutes)
        assertEquals(1, math.completedTasks)
        assertEquals(1, math.scheduledTasks)
        assertEquals(100, math.completionRatePercent)
        val algebra = snapshot.subjects.first { it.subjectName == "高等代数" }
        assertEquals(45, algebra.studyMinutes)
        assertEquals(null, algebra.completionRatePercent)
    }

    @Test
    fun upcomingTasks_includePostponeCountAndExcludeCompleted() {
        val snapshot = builder.build(
            today = today,
            tasks = listOf(
                task(
                    id = 10,
                    title = "延期三次的任务",
                    subject = "数学分析",
                    dueDate = LocalDate.of(2026, 10, 28),
                    postponeCount = 3,
                ),
                task(
                    id = 11,
                    title = "已完成任务",
                    subject = "数学分析",
                    status = com.zhixu.studyos.core.model.TaskStatus.COMPLETED,
                    completedAt = Instant.parse("2026-10-24T08:00:00Z"),
                ),
                task(id = 12, title = "待安排任务", subject = "高等代数"),
            ),
            timings = emptyList(),
            manualProblems = emptyList(),
            bankProblems = emptyList(),
            subjects = subjects,
            goals = com.zhixu.studyos.core.model.LearningGoals(),
            settings = com.zhixu.studyos.core.model.SchedulingSettings(),
        )

        assertEquals(listOf(10L, 12L), snapshot.upcomingTasks.map { it.taskId })
        assertEquals(3, snapshot.upcomingTasks.first().postponeCount)
    }

    @Test
    fun difficultProblems_includeMistakesRetriesAndSlowBankItems() {
        val snapshot = builder.build(
            today = today,
            tasks = emptyList(),
            timings = emptyList(),
            manualProblems = listOf(
                com.zhixu.studyos.core.model.Problem(
                    id = 1,
                    subjectId = 1,
                    textbook = "数分",
                    chapter = "第一章",
                    problemNumber = "3",
                    knowledgePoint = "极限",
                    isMistake = true,
                    retryCount = 2,
                    createdAt = Instant.parse("2026-10-20T00:00:00Z"),
                    updatedAt = Instant.parse("2026-10-20T00:00:00Z"),
                ),
                com.zhixu.studyos.core.model.Problem(
                    id = 2,
                    subjectId = 1,
                    textbook = "数分",
                    chapter = "第一章",
                    problemNumber = "4",
                    createdAt = Instant.parse("2026-10-20T00:00:00Z"),
                    updatedAt = Instant.parse("2026-10-20T00:00:00Z"),
                ),
            ),
            bankProblems = listOf(
                com.zhixu.studyos.core.model.ProblemBankEntry(
                    id = 5,
                    documentId = 1,
                    pageIndex = 0,
                    courseId = null,
                    courseName = "高等代数",
                    textbook = "高代习题册",
                    chapter = "第二章",
                    problemNumber = "7",
                    statement = "题干",
                    estimatedMinutes = 30,
                    actualMinutes = 90,
                    completionStatus = com.zhixu.studyos.core.model.ProblemCompletionStatus.COMPLETED,
                ),
            ),
            subjects = subjects,
            goals = com.zhixu.studyos.core.model.LearningGoals(),
            settings = com.zhixu.studyos.core.model.SchedulingSettings(),
        )

        // 手工题 1（错误+重练）与题库 5（用时 > 估计 2 倍）；题 2 没有困难信号
        assertEquals(2, snapshot.difficultProblems.size)
        assertTrue(snapshot.difficultProblems.any { it.problemNumber == "3" })
        assertTrue(snapshot.difficultProblems.any { it.problemNumber == "7" })
        assertFalse(snapshot.difficultProblems.any { it.problemNumber == "4" })
    }
}
