package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.ai.network.AiHttpResponse
import com.zhixu.studyos.core.ai.network.AiHttpTransport
import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.data.repository.AiRepository
import com.zhixu.studyos.core.model.AiCachedAnalysis
import com.zhixu.studyos.core.model.AiDayStat
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiGoalSummary
import com.zhixu.studyos.core.model.AiPlanRecord
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiProviderException
import com.zhixu.studyos.core.model.AiSchedulingSummary
import com.zhixu.studyos.core.model.AiStudySnapshot
import com.zhixu.studyos.core.model.AiSubjectStat
import com.zhixu.studyos.core.model.AiTaskStat
import com.zhixu.studyos.core.model.AiUsage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlanningServiceTest {

    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), zone)
    private val today = LocalDate.of(2026, 10, 25)

    private val snapshot = AiStudySnapshot(
        generatedAt = Instant.parse("2026-10-25T08:00:00Z"),
        windowStart = LocalDate.of(2026, 10, 19),
        windowEnd = today,
        subjects = listOf(AiSubjectStat("数学分析", 120, 2, 3)),
        recentDays = (0 until 7).map { AiDayStat(LocalDate.of(2026, 10, 19).plusDays(it.toLong()), 20, 0) },
        upcomingTasks = listOf(
            AiTaskStat(
                taskId = 42,
                subject = "数学分析",
                title = "第三章习题",
                estimatedMinutes = 90,
                dueDate = LocalDate.of(2026, 10, 28),
                priority = "HIGH",
                scheduledMinutes = 0,
                postponeCount = 1,
            ),
        ),
        difficultProblems = emptyList(),
        goals = AiGoalSummary(null, null, null),
        scheduling = AiSchedulingSummary(240, 480, 1320, 10),
    )

    private val config = AiProviderConfig(
        enabled = true,
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        dailyCallLimit = 5,
        dailyCostLimitMicros = 0,
        inputPriceMicrosPerMillionTokens = 1_000_000,
        outputPriceMicrosPerMillionTokens = 2_000_000,
    )

    private fun chatBody(
        content: String = """
            {"schemaVersion":1,"summary":"本周整体稳定。","subjectAllocations":[],
             "taskEstimates":[{"taskId":42,"estimatedMinutes":75,"reason":"题目多"}],
             "restReminders":["每小时休息"],"weeklySuggestions":["先做数分"],"riskFlags":[]}
        """.trimIndent(),
        promptTokens: Int = 500,
        completionTokens: Int = 200,
    ): String = JSONObject()
        .put("model", "test-model")
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", content)),
            ),
        )
        .put("usage", JSONObject().put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens))
        .toString()

    private class FakeAiRepository : AiRepository {
        val usage = mutableMapOf<LocalDate, AiUsage>()
        val cache = mutableMapOf<String, AiCachedAnalysis>()
        val records = mutableListOf<AiPlanRecord>()
        private val usageFlow = MutableStateFlow<List<AiUsage>>(emptyList())
        private val recordFlow = MutableStateFlow<List<AiPlanRecord>>(emptyList())

        override suspend fun getUsage(date: LocalDate): AiUsage = usage[date] ?: AiUsage(date)
        override fun observeUsage(limit: Int): Flow<List<AiUsage>> = usageFlow

        override suspend fun recordUsage(
            date: LocalDate,
            promptTokens: Int,
            completionTokens: Int,
            costMicros: Long,
        ): AiUsage {
            val current = usage[date] ?: AiUsage(date)
            val updated = current.copy(
                requestCount = current.requestCount + 1,
                promptTokens = current.promptTokens + promptTokens,
                completionTokens = current.completionTokens + completionTokens,
                estimatedCostMicros = current.estimatedCostMicros + costMicros,
            )
            usage[date] = updated
            usageFlow.value = usage.values.sortedByDescending { it.date }
            return updated
        }

        override suspend fun findCache(cacheKey: String, maxAgeHours: Long): AiCachedAnalysis? =
            cache[cacheKey]

        override suspend fun saveCache(cached: AiCachedAnalysis) {
            cache[cached.cacheKey] = cached
        }

        override suspend fun pruneCache(olderThanDays: Long) = Unit
        override fun observePlanRecords(limit: Int): Flow<List<AiPlanRecord>> = recordFlow

        override suspend fun savePlanRecord(record: AiPlanRecord): Long {
            records += record
            recordFlow.value = records.toList()
            return records.size.toLong()
        }
    }

    private class FakeKeyStore(var key: String? = "sk-test") : ApiKeyStore {
        override suspend fun save(key: String) {
            this.key = key
        }

        override suspend fun load(): String? = key
        override suspend fun clear() {
            key = null
        }

        override suspend fun hasKey(): Boolean = key != null
    }

    private class FakeConnectivity(var online: Boolean = true) : AiConnectivity {
        override fun isOnline(): Boolean = online
    }

    private class FakeTransport(
        var response: AiHttpResponse,
        val calls: MutableList<String> = mutableListOf(),
    ) : AiHttpTransport {
        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutSeconds: Int,
        ): AiHttpResponse {
            calls += body
            return response
        }
    }

    private fun service(
        repository: FakeAiRepository = FakeAiRepository(),
        keyStore: FakeKeyStore = FakeKeyStore(),
        connectivity: FakeConnectivity = FakeConnectivity(),
        transport: FakeTransport = FakeTransport(AiHttpResponse(200, chatBody())),
    ) = AiPlanningService(
        repository = repository,
        keyStore = keyStore,
        connectivity = connectivity,
        transport = transport,
        snapshotBuilder = AiSnapshotBuilder(clock, zone),
        clock = clock,
        zoneId = zone,
    )

    @Test
    fun successfulRequest_recordsUsageCachesPlanAndAcceptsEstimate() = runTest {
        val repository = FakeAiRepository()
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))

        val outcome = service(repository = repository, transport = transport)
            .prepare(config, snapshot, forceRefresh = false)

        assertTrue(outcome is AiAnalysisOutcome.Fresh)
        assertEquals(1, transport.calls.size)
        assertEquals(1, repository.usage.getValue(today).requestCount)
        assertEquals(1, repository.cache.size)
        assertEquals(1, outcome.review.acceptedTaskEstimates.size)
        // 500 输入 * 1 元/百万 + 200 输出 * 2 元/百万 = 500 + 400 = 900 微元
        assertEquals(900L, outcome.costMicros)
    }

    @Test
    fun unchangedInput_returnsCacheWithoutCallingProvider() = runTest {
        val repository = FakeAiRepository()
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))
        val planning = service(repository = repository, transport = transport)

        planning.prepare(config, snapshot, forceRefresh = false)
        val second = planning.prepare(config, snapshot, forceRefresh = false)

        assertTrue(second is AiAnalysisOutcome.Cached)
        assertEquals(1, transport.calls.size)
        assertEquals(1, repository.usage.getValue(today).requestCount)
        assertEquals(0L, second.costMicros)
    }

    @Test
    fun forceRefresh_bypassesCache() = runTest {
        val repository = FakeAiRepository()
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))
        val planning = service(repository = repository, transport = transport)

        planning.prepare(config, snapshot, forceRefresh = false)
        val second = planning.prepare(config, snapshot, forceRefresh = true)

        assertTrue(second is AiAnalysisOutcome.Fresh)
        assertEquals(2, transport.calls.size)
        assertEquals(2, repository.usage.getValue(today).requestCount)
    }

    @Test
    fun disabledConfig_isRejectedWithoutCall() = runTest {
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))

        val error = runCatching {
            service(transport = transport).prepare(config.copy(enabled = false), snapshot, false)
        }.exceptionOrNull()

        assertEquals(AiFailureKind.DISABLED, (error as AiProviderException).kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun missingApiKey_isRejectedWithoutCall() = runTest {
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))

        val error = runCatching {
            service(keyStore = FakeKeyStore(key = null), transport = transport)
                .prepare(config, snapshot, false)
        }.exceptionOrNull()

        assertEquals(AiFailureKind.NOT_CONFIGURED, (error as AiProviderException).kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun invalidConfig_isRejected() = runTest {
        val error = runCatching {
            service().prepare(config.copy(baseUrl = "ftp://x"), snapshot, false)
        }.exceptionOrNull()

        assertEquals(AiFailureKind.NOT_CONFIGURED, (error as AiProviderException).kind)
    }

    @Test
    fun offline_isRejectedWithoutCall() = runTest {
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))

        val error = runCatching {
            service(connectivity = FakeConnectivity(online = false), transport = transport)
                .prepare(config, snapshot, false)
        }.exceptionOrNull()

        assertEquals(AiFailureKind.OFFLINE, (error as AiProviderException).kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun dailyCallLimit_blocksRequest() = runTest {
        val repository = FakeAiRepository()
        repository.usage[today] = AiUsage(today, requestCount = 5)
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))

        val error = runCatching {
            service(repository = repository, transport = transport).prepare(config, snapshot, false)
        }.exceptionOrNull()

        assertEquals(AiFailureKind.DAILY_CALL_LIMIT, (error as AiProviderException).kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun dailyCostLimit_blocksRequest() = runTest {
        val repository = FakeAiRepository()
        repository.usage[today] = AiUsage(today, requestCount = 1, estimatedCostMicros = 1000)
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))

        val error = runCatching {
            service(repository = repository, transport = transport)
                .prepare(config.copy(dailyCostLimitMicros = 1000), snapshot, false)
        }.exceptionOrNull()

        assertEquals(AiFailureKind.DAILY_COST_LIMIT, (error as AiProviderException).kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun schemaInvalid_stillRecordsUsage() = runTest {
        val repository = FakeAiRepository()
        val transport = FakeTransport(AiHttpResponse(200, chatBody(content = "这不是 JSON")))

        val error = runCatching {
            service(repository = repository, transport = transport).prepare(config, snapshot, false)
        }.exceptionOrNull()

        assertEquals(AiFailureKind.SCHEMA_INVALID, (error as AiProviderException).kind)
        assertEquals(1, repository.usage.getValue(today).requestCount)
        assertTrue(repository.cache.isEmpty())
    }

    @Test
    fun unknownTaskId_isRejectedByLocalConstraints() = runTest {
        val content = """
            {"schemaVersion":1,"summary":"s",
             "taskEstimates":[{"taskId":9999,"estimatedMinutes":30,"reason":"r"}]}
        """.trimIndent()
        val transport = FakeTransport(AiHttpResponse(200, chatBody(content = content)))

        val outcome = service(transport = transport).prepare(config, snapshot, false)

        assertTrue(outcome.review.acceptedTaskEstimates.isEmpty())
        assertEquals(1, outcome.review.rejectedTaskEstimates.size)
        assertFalse(outcome.review.issues.isEmpty())
    }

    @Test
    fun useCache_returnsReviewWithoutRecordingUsage() = runTest {
        val repository = FakeAiRepository()
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))
        val planning = service(repository = repository, transport = transport)
        val first = planning.prepare(config, snapshot, false)
        val cacheKey = planning.cacheKeyFor(config, snapshot)

        val cached = planning.useCache(cacheKey, snapshot, config)

        assertEquals(first.planJson, cached.planJson)
        assertEquals(1, repository.usage.getValue(today).requestCount)
        assertTrue(cached.usedCache)
    }

    @Test
    fun useCache_missingCache_throws() = runTest {
        val error = runCatching {
            service().useCache("missing", snapshot, config)
        }.exceptionOrNull()

        assertTrue(error is AiProviderException)
    }

    @Test
    fun recordConfirmedPlan_persistsRecordWithCost() = runTest {
        val repository = FakeAiRepository()
        val transport = FakeTransport(AiHttpResponse(200, chatBody()))
        val planning = service(repository = repository, transport = transport)
        val outcome = planning.prepare(config, snapshot, false)

        planning.recordConfirmedPlan(outcome, appliedTaskCount = 1, usedCache = outcome.usedCache)

        assertEquals(1, repository.records.size)
        assertEquals(1, repository.records.single().appliedTaskCount)
        assertEquals(900L, repository.records.single().costMicros)
    }

    @Test
    fun testConnection_recordsUsage() = runTest {
        val repository = FakeAiRepository()
        val transport = FakeTransport(
            AiHttpResponse(
                200,
                JSONObject()
                    .put("model", "test-model")
                    .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "OK"))))
                    .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 1))
                    .toString(),
            ),
        )

        val message = service(repository = repository, transport = transport)
            .testConnection(config, "sk-test")

        assertTrue(message.contains("连接成功"))
        assertEquals(1, repository.usage.getValue(today).requestCount)
    }

    @Test
    fun cacheKey_changesWhenPrivacyChanges() = runTest {
        val planning = service()

        val a = planning.cacheKeyFor(config, snapshot)
        val b = planning.cacheKeyFor(config.copy(includeTaskTitles = false), snapshot)

        assertFalse(a == b)
    }
}
