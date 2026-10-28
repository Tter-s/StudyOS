package com.zhixu.studyos.core.sync

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 同步对象哈希。
 *
 * 关键点：
 * - 先按 JSON 序列化再解析，保证发送端与接收端看到的是同一种数字表示；
 *   例如 REAL 列里的 `3.0` 会被 org.json 序列化成 `3`，若直接对对象求 hash，
 *   接收端重算时会从 `"3.0"` 变成 `"3"`，整包会被误判为损坏。
 * - 子行（学习时段、目标里程碑）的本地自增 id 不参与 hash。导入时子行会
 *   重新分配 id，如果 id 参与 hash，同一对象会永远被判定为“有更新”并反复重放。
 */
internal object SyncPayloadHasher {
    private val CHILD_ARRAYS = listOf("sessions", "milestones")
    private val CHILD_ID_KEY = "id"

    fun hash(payload: JSONObject): String {
        val normalized = normalize(payload)
        val canonical = canonicalize(normalized)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun hash(payloadJson: String): String {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull()
        return hash(payload ?: JSONObject())
    }

    /**
     * 旧版同步包的哈希：子行 id 参与、数字使用 Number.toString()。
     * 仅用于兼容本次修复前导出的同步包，不应再用于新导出。
     */
    fun hashLegacy(payloadJson: String): String {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: JSONObject()
        val canonical = canonicalizeLegacy(payload)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalize(payload: JSONObject): JSONObject {
        // 通过序列化/反序列化复制，数字表示与接收端解析结果保持一致。
        val copy = runCatching { JSONObject(payload.toString()) }.getOrElse { payload }
        CHILD_ARRAYS.forEach { key ->
            copy.optJSONArray(key)?.let { children ->
                for (index in 0 until children.length()) {
                    children.optJSONObject(index)?.remove(CHILD_ID_KEY)
                }
            }
        }
        return copy
    }

    private fun canonicalize(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalize(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalize(value.opt(index))
        }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> numberKey(value)
        else -> JSONObject.quote(value.toString())
    }

    private fun numberKey(value: Number): String = when (value) {
        // 整数类型直接保留原值，避免 Long 超过 2^53 时经 Double 转换丢精度。
        is Long, is Int, is Short, is Byte -> value.toString()
        is Double, is Float -> {
            val asDouble = value.toDouble()
            if (asDouble.isFinite() && asDouble == asDouble.toLong().toDouble()) {
                asDouble.toLong().toString()
            } else {
                value.toString()
            }
        }
        else -> value.toString()
    }

    private fun canonicalizeLegacy(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalizeLegacy(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalizeLegacy(value.opt(index))
        }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
