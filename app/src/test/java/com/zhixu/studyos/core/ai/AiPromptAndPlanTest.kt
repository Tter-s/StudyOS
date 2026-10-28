package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.model.AiDayStat
import com.zhixu.studyos.core.model.AiGoalSummary
import com.zhixu.studyos.core.model.AiPlan
import com.zhixu.studyos.core.model.AiProblemSignal
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiSchedulingSummary
import com.zhixu.studyos.core.model.AiStudySnapshot
import com.zhixu.studyos.core.model.AiSubjectAllocation
import com.zhixu.studyos.core.model.AiSubjectStat
import com.zhixu.studyos.core.model.AiTaskEstimate
import com.zhixu.studyos.core.model.AiTaskStat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptBuilderTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val builder = AiSnapshotBuilder(
        java.time.Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), zone),
        zone,
    )

    private fun snapshot() = AiStudySnapshot(
        generatedAt = Instant.parse("2026-10-25T10:00:00Z"),
        windowStart = LocalDate.of(2026, 10, 19),
        windowEnd = LocalDate.of(2026, 10, 25),
        subjects = listOf(
            AiSubjectStat("数学分析", 120, 2, 3),
            AiSubjectStat("高等代数", 60, 1, 1),
        ),
        recentDays = (0 until 7).map { offset ->
            AiDayStat(LocalDate.of(2026, 10, 19).plusDays(offset.toLong()), 20, 0)
        },
        upcomingTasks = listOf(
            AiTaskStat(
                taskId = 42,
                subject = "数学分析",
                title = "完成第三章习题",
                estimatedMinutes = 90,
                dueDate = LocalDate.of(2026, 10, 28),
                priority = "HIGH",
                scheduledMinutes = 90,
                postponeCount = 2,
            ),
        ),
        difficultProblems = listOf(
            AiProblemSignal(
                source = "个人题库",
                label = "数分习题册 第一章 5",
                subject = "数学分析",
                chapter = "第一章",
                problemNumber = "5",
                retryCount = 2,
                isMistake = true,
                note = "数列极限",
            ),
        ),
        goals = AiGoalSummary(dailyStudyMinutes = 120, weeklyStudyMinutes = null, dailyTaskTarget = 3),
        scheduling = AiSchedulingSummary(
            dailyLimitMinutes = 240,
            studyStartMinute = 480,
            studyEndMinuteExclusive = 1320,
            bufferMinutes = 10,
        ),
    )

    private fun payload(config: AiProviderConfig) = builder.toJson(snapshot(), config, zone)

    @Test
    fun payload_withDetails_disclosesProblemMetadata() {
        val json = payload(AiProviderConfig(includeProblemStatements = true, includeTaskTitles = true))

        assertTrue(json.contains("\"detailsIncluded\":true"))
        assertTrue(json.contains("数分习题册 第一章 5"))
        assertTrue(json.contains("数列极限"))
    }

    @Test
    fun payload_withoutDetails_onlySendsCounts() {
        val json = payload(AiProviderConfig(includeProblemStatements = false))

        assertTrue(json.contains("\"detailsIncluded\":false"))
        assertFalse(json.contains("数分习题册 第一章 5"))
        assertFalse(json.contains("数列极限"))
        assertTrue(json.contains("\"count\":1"))
    }

    @Test
    fun payload_masksSubjectNamesWhenDisabled() {
        val json = payload(AiProviderConfig(includeSubjectNames = false))

        assertFalse(json.contains("数学分析"))
        assertFalse(json.contains("高等代数"))
        assertTrue(json.contains("科目1"))
        assertTrue(json.contains("\"subjectNamesMasked\":true"))
    }

    @Test
    fun payload_masksTaskTitlesWhenDisabled() {
        val json = payload(AiProviderConfig(includeTaskTitles = false))

        assertFalse(json.contains("完成第三章习题"))
        assertTrue(json.contains("任务 #42"))
        assertTrue(json.contains("\"taskTitlesMasked\":true"))
    }

    @Test
    fun payload_isDeterministicForSameInput() {
        val config = AiProviderConfig()

        assertEquals(payload(config), payload(config))
    }

    @Test
    fun payload_neverContainsFinanceFields() {
        val json = payload(AiProviderConfig(includeProblemStatements = true))

        assertFalse(json.contains("finance", ignoreCase = true))
        assertFalse(json.contains("budget", ignoreCase = true))
        assertFalse(json.contains("amount", ignoreCase = true))
        assertFalse(json.contains("transaction", ignoreCase = true))
    }

    @Test
    fun prompt_declaresSchemaAndRanges() {
        val prompts = AiPromptBuilder.build(payload(AiProviderConfig()), snapshot())

        assertTrue(prompts.systemPrompt.contains("taskEstimates"))
        assertTrue(prompts.systemPrompt.contains("subjectAllocations"))
        assertTrue(prompts.systemPrompt.contains("5–300"))
        assertTrue(prompts.userPrompt.contains("2026-10-19"))
        assertTrue(prompts.userPrompt.contains("240 分钟"))
    }

    @Test
    fun privacySummary_mentionsWhatIsSentAndWhatIsNot() {
        val summary = AiPromptBuilder.privacySummary(AiProviderConfig())

        assertTrue(summary.contains("不会发送财务流水"))
        assertTrue(summary.contains("科目名称"))
    }
}

class AiPlanParserTest {

    private val validJson = """
        {
          "schemaVersion": 1,
          "summary": "本周学习较稳定。",
          "subjectAllocations": [
            {"subject": "数学分析", "minutesPerDay": 90, "reason": "基础最重要"}
          ],
          "taskEstimates": [
            {"taskId": 42, "estimatedMinutes": 75, "reason": "题目较多"}
          ],
          "restReminders": ["每 50 分钟休息 10 分钟"],
          "weeklySuggestions": ["优先完成数学分析"],
          "riskFlags": [{"kind": "POSTPONED_TASKS", "detail": "有任务延期两次"}]
        }
    """.trimIndent()

    @Test
    fun parse_validPlan() {
        val plan = AiPlanParser.parse(validJson)

        assertEquals(1, plan.schemaVersion)
        assertEquals("本周学习较稳定。", plan.summary)
        assertEquals("数学分析", plan.subjectAllocations.single().subject)
        assertEquals(90, plan.subjectAllocations.single().minutesPerDay)
        assertEquals(42L, plan.taskEstimates.single().taskId)
        assertEquals(75, plan.taskEstimates.single().estimatedMinutes)
        assertEquals(listOf("每 50 分钟休息 10 分钟"), plan.restReminders)
        assertEquals(1, plan.riskFlags.size)
    }

    @Test
    fun parse_stripsMarkdownFenceAndSurroundingText() {
        val raw = "好的，以下是建议：\n```json\n$validJson\n```\n谢谢。"

        val plan = AiPlanParser.parse(raw)

        assertEquals("本周学习较稳定。", plan.summary)
    }

    @Test
    fun invalidJson_isSchemaError() {
        assertSchemaError("这不是 JSON")
    }

    @Test
    fun missingSummary_isSchemaError() {
        assertSchemaError(validJson.replace("\"summary\": \"本周学习较稳定。\",", ""))
    }

    @Test
    fun wrongTypeForMinutes_isSchemaError() {
        assertSchemaError(validJson.replace("\"minutesPerDay\": 90", "\"minutesPerDay\": \"90\""))
    }

    @Test
    fun outOfRangeEstimate_isSchemaError() {
        assertSchemaError(validJson.replace("\"estimatedMinutes\": 75", "\"estimatedMinutes\": 1000"))
    }

    @Test
    fun unsupportedSchemaVersion_isSchemaError() {
        assertSchemaError(validJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
    }

    @Test
    fun tooManyEstimates_isSchemaError() {
        val items = (1..16).joinToString(",") {
            """{"taskId": $it, "estimatedMinutes": 30, "reason": "r"}"""
        }
        val json = """
            {"schemaVersion":1,"summary":"s","taskEstimates":[$items]}
        """.trimIndent()

        assertSchemaError(json)
    }

    @Test
    fun unknownExtraFields_areIgnored() {
        val json = validJson.replace("\"summary\":", "\"extra\": {\"a\": 1}, \"summary\":")

        val plan = AiPlanParser.parse(json)

        assertEquals("本周学习较稳定。", plan.summary)
    }

    @Test
    fun emptyOptionalArrays_areAllowed() {
        val json = """{"schemaVersion":1,"summary":"ok"}"""

        val plan = AiPlanParser.parse(json)

        assertTrue(plan.taskEstimates.isEmpty())
        assertTrue(plan.subjectAllocations.isEmpty())
    }

    @Test
    fun blankArrayItem_isDropped() {
        val json = validJson.replace(
            "\"restReminders\": [\"每 50 分钟休息 10 分钟\"]",
            "\"restReminders\": [\"  \"]",
        )

        assertTrue(AiPlanParser.parse(json).restReminders.isEmpty())
    }

    private fun assertSchemaError(json: String) {
        try {
            AiPlanParser.parse(json)
            throw AssertionError("应当抛出 Schema 错误")
        } catch (error: com.zhixu.studyos.core.model.AiProviderException) {
            assertEquals(com.zhixu.studyos.core.model.AiFailureKind.SCHEMA_INVALID, error.kind)
        }
    }
}

class AiPlanConstraintCheckerTest {

    private val tasks = listOf(
        AiTaskStat(
            taskId = 1,
            subject = "数学分析",
            title = "任务一",
            estimatedMinutes = 60,
            dueDate = null,
            priority = "MEDIUM",
            scheduledMinutes = 0,
            postponeCount = 0,
        ),
        AiTaskStat(
            taskId = 2,
            subject = "高等代数",
            title = "任务二",
            estimatedMinutes = 60,
            dueDate = null,
            priority = "MEDIUM",
            scheduledMinutes = 0,
            postponeCount = 0,
        ),
    )

    private fun check(
        plan: AiPlan,
        subjects: Set<String> = setOf("数学分析", "高等代数"),
        limit: Int = 240,
        namesAvailable: Boolean = true,
    ) = AiPlanConstraintChecker.check(
        plan = plan,
        upcomingTasks = tasks,
        knownSubjects = subjects,
        dailyLimitMinutes = limit,
        subjectNamesAvailable = namesAvailable,
    )

    @Test
    fun validEstimates_areAccepted() {
        val review = check(
            AiPlan(
                summary = "s",
                taskEstimates = listOf(AiTaskEstimate(1, 75, "r"), AiTaskEstimate(2, 60, "r")),
            ),
        )

        assertEquals(2, review.acceptedTaskEstimates.size)
        assertTrue(review.rejectedTaskEstimates.isEmpty())
        assertFalse(review.hasErrors)
    }

    @Test
    fun unknownTask_isRejected() {
        val review = check(AiPlan(summary = "s", taskEstimates = listOf(AiTaskEstimate(99, 30, "r"))))

        assertTrue(review.acceptedTaskEstimates.isEmpty())
        assertEquals(1, review.rejectedTaskEstimates.size)
        assertTrue(review.issues.single().message.contains("不在本次发送"))
    }

    @Test
    fun duplicateTask_keepsFirstOnly() {
        val review = check(
            AiPlan(
                summary = "s",
                taskEstimates = listOf(AiTaskEstimate(1, 30, "a"), AiTaskEstimate(1, 45, "b")),
            ),
        )

        assertEquals(1, review.acceptedTaskEstimates.size)
        assertEquals(30, review.acceptedTaskEstimates.single().estimatedMinutes)
        assertEquals(1, review.rejectedTaskEstimates.size)
    }

    @Test
    fun minutesOutOfBounds_areRejected() {
        val review = check(
            AiPlan(
                summary = "s",
                taskEstimates = listOf(AiTaskEstimate(1, 4, "a"), AiTaskEstimate(2, 301, "b")),
            ),
        )

        assertTrue(review.acceptedTaskEstimates.isEmpty())
        assertEquals(2, review.rejectedTaskEstimates.size)
    }

    @Test
    fun allocations_totalMustStayWithinDailyLimit() {
        val review = check(
            AiPlan(
                summary = "s",
                subjectAllocations = listOf(
                    AiSubjectAllocation("数学分析", 150, "r"),
                    AiSubjectAllocation("高等代数", 120, "r"),
                ),
            ),
            limit = 240,
        )

        assertEquals(1, review.acceptedAllocations.size)
        assertEquals(1, review.rejectedAllocations.size)
        assertTrue(review.issues.any { it.message.contains("超过每日学习上限") })
    }

    @Test
    fun unknownSubject_isRejected() {
        val review = check(
            AiPlan(
                summary = "s",
                subjectAllocations = listOf(AiSubjectAllocation("量子力学", 60, "r")),
            ),
        )

        assertTrue(review.acceptedAllocations.isEmpty())
        assertEquals(1, review.rejectedAllocations.size)
    }

    @Test
    fun maskedSubjectNames_rejectAllAllocations() {
        val review = check(
            AiPlan(
                summary = "s",
                subjectAllocations = listOf(AiSubjectAllocation("科目1", 60, "r")),
            ),
            namesAvailable = false,
        )

        assertTrue(review.acceptedAllocations.isEmpty())
        assertTrue(review.issues.single().message.contains("隐私设置"))
    }

    @Test
    fun emptyExecutablePlan_producesWarning() {
        val review = check(AiPlan(summary = "只有文字建议"))

        assertTrue(review.issues.any { it.severity == com.zhixu.studyos.core.model.AiIssueSeverity.WARNING })
    }
}
