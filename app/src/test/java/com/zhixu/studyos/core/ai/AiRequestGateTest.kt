package com.zhixu.studyos.core.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AiRequestGateTest {

    @Test
    fun concurrentAiRequests_areSerializedByTheSharedGate() = runTest {
        var inFlight = 0
        var maxInFlight = 0

        val jobs = (1..5).map {
            launch {
                AiRequestGate.run {
                    inFlight += 1
                    maxInFlight = maxOf(maxInFlight, inFlight)
                    delay(10)
                    inFlight -= 1
                }
            }
        }
        jobs.joinAll()

        assertEquals(1, maxInFlight)
    }
}
