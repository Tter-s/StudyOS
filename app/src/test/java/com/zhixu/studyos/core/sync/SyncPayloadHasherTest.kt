package com.zhixu.studyos.core.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPayloadHasherTest {

    @Test
    fun integralRealValues_hashTheSameAsSerializedIntegers() {
        val realPayload = JSONObject()
            .put("row", JSONObject().put("easeFactor", 3.0).put("word", "analysis"))
        val integerPayload = JSONObject()
            .put("row", JSONObject().put("easeFactor", 3).put("word", "analysis"))

        // org.json 会把 3.0 序列化成 3；同步包的发送端和接收端必须得到同一个 hash。
        assertEquals(
            SyncPayloadHasher.hash(realPayload),
            SyncPayloadHasher.hash(realPayload.toString()),
        )
        assertEquals(
            SyncPayloadHasher.hash(realPayload),
            SyncPayloadHasher.hash(integerPayload),
        )
    }

    @Test
    fun childRowIds_doNotAffectHash() {
        fun payload(sessionId: Long) = JSONObject()
            .put(
                "row",
                JSONObject()
                    .put("id", 7)
                    .put("title", "任务")
                    .put("updatedAtEpochMillis", 1_700_000_000_000L),
            )
            .put(
                "sessions",
                JSONArray().put(
                    JSONObject()
                        .put("id", sessionId)
                        .put("dateEpochDay", 20_000)
                        .put("startMinute", 8 * 60)
                        .put("endMinuteExclusive", 9 * 60),
                ),
            )

        assertEquals(SyncPayloadHasher.hash(payload(1)), SyncPayloadHasher.hash(payload(999)))
    }

    @Test
    fun keyOrder_doesNotAffectHash() {
        val first = JSONObject()
            .put("row", JSONObject().put("title", "任务").put("subject", "数学分析"))
        val second = JSONObject()
            .put("row", JSONObject().put("subject", "数学分析").put("title", "任务"))

        assertEquals(SyncPayloadHasher.hash(first), SyncPayloadHasher.hash(second))
    }
}
