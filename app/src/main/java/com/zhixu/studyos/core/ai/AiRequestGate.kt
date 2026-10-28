package com.zhixu.studyos.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程内 AI 调用闸门。
 *
 * 每日额度目前是“读取用量 → 判断上限 → 调用 → 记用量”的流程；两个入口同时
 * 发起请求时可能都读到旧用量而突破上限。规划服务与统一任务服务共用这一把锁，
 * 把检查、调用和记账串行化，代价是同一时刻只允许一个 AI 请求（产品本身也要求
 * 用户主动触发，不做后台并发）。
 */
object AiRequestGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
