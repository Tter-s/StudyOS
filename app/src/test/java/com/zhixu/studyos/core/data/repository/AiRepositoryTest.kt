package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.model.AiCachedAnalysis
import com.zhixu.studyos.core.model.AiPlanRecord
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AiRepositoryTest {

    private lateinit var database: StudyOSDatabase
    private var nowMillis: Long = Instant.parse("2026-10-25T08:00:00Z").toEpochMilli()
    private lateinit var repository: OfflineAiRepository

    private val clock: Clock
        get() = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
            override fun millis(): Long = nowMillis
        }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = OfflineAiRepository(database, database.aiDao(), clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun recordUsage_accumulatesCountsTokensAndCost() = runTest {
        repository.recordUsage(today, promptTokens = 100, completionTokens = 20, costMicros = 300)
        val usage = repository.recordUsage(today, promptTokens = 50, completionTokens = 10, costMicros = 150)

        assertEquals(2, usage.requestCount)
        assertEquals(150, usage.promptTokens)
        assertEquals(30, usage.completionTokens)
        assertEquals(450L, usage.estimatedCostMicros)
        val reloaded = repository.getUsage(today)
        assertEquals(2, reloaded.requestCount)
    }

    @Test
    fun getUsage_missingDayReturnsZero() = runTest {
        val usage = repository.getUsage(today.minusDays(1))

        assertEquals(0, usage.requestCount)
        assertEquals(0L, usage.estimatedCostMicros)
    }

    @Test
    fun cache_isReadBackWithinMaxAge() = runTest {
        val cached = AiCachedAnalysis(
            cacheKey = "key-1",
            model = "m",
            createdAt = Instant.ofEpochMilli(nowMillis),
            planJson = "{}",
            promptTokens = 10,
            completionTokens = 5,
            costMicros = 100,
        )
        repository.saveCache(cached)

        assertNotNull(repository.findCache("key-1", maxAgeHours = 24))
        assertNull(repository.findCache("other", maxAgeHours = 24))
        // 一小时后，0 小时有效期的缓存过期
        nowMillis += 60L * 60 * 1000
        assertNull(repository.findCache("key-1", maxAgeHours = 0))
        assertNotNull(repository.findCache("key-1", maxAgeHours = 24))
    }

    @Test
    fun cache_isExpiredByAge() = runTest {
        repository.saveCache(
            AiCachedAnalysis(
                cacheKey = "old",
                model = "m",
                createdAt = Instant.ofEpochMilli(nowMillis),
                planJson = "{}",
                promptTokens = 0,
                completionTokens = 0,
                costMicros = 0,
            ),
        )
        nowMillis += 25L * 60 * 60 * 1000

        assertNull(repository.findCache("old", maxAgeHours = 24))
    }

    @Test
    fun pruneCache_removesOldEntries() = runTest {
        repository.saveCache(
            AiCachedAnalysis(
                cacheKey = "old",
                model = "m",
                createdAt = Instant.ofEpochMilli(nowMillis),
                planJson = "{}",
                promptTokens = 0,
                completionTokens = 0,
                costMicros = 0,
            ),
        )
        nowMillis += 8L * 24 * 60 * 60 * 1000

        repository.pruneCache(olderThanDays = 7)

        assertNull(repository.findCache("old", maxAgeHours = 100_000))
    }

    @Test
    fun planRecords_areObservedNewestFirst() = runTest {
        repository.savePlanRecord(
            AiPlanRecord(
                createdAt = Instant.ofEpochMilli(nowMillis),
                model = "m1",
                usedCache = false,
                planJson = "{}",
                appliedTaskCount = 2,
                costMicros = 100,
            ),
        )
        nowMillis += 1000
        repository.savePlanRecord(
            AiPlanRecord(
                createdAt = Instant.ofEpochMilli(nowMillis),
                model = "m2",
                usedCache = true,
                planJson = "{}",
                appliedTaskCount = 0,
                costMicros = 0,
            ),
        )

        val records = repository.observePlanRecords(10).first()

        assertEquals(2, records.size)
        assertEquals("m2", records.first().model)
        assertEquals(2, records.last().appliedTaskCount)
    }
}
