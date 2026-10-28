package com.zhixu.studyos.core.data.repository

import com.zhixu.studyos.core.data.local.HomeCardDao
import com.zhixu.studyos.core.data.local.HomeCardPreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class HomeCardId(val label: String, val description: String) {
    TASK_PROGRESS("今日任务进度", "完成率与任务数量"),
    FREE_TIME("今日空闲时间", "扣除课程、作息、任务与睡眠后的空闲"),
    NEXT_CLASS("下一节课", "课程、教室与开始时间"),
    CURRENT_TASK("当前学习任务", "进行中的任务与计划时段"),
    SLEEP("睡眠保护", "当前作息、睡前缓冲与冲突提示"),
    BUDGET("本月预算", "真实剩余预算与使用进度"),
    EXAM("考试倒计时", "最近的考试与剩余天数"),
    EXERCISE_REVIEW("今日习题复习", "待巩固的题目数量与入口"),
    VOCABULARY("英语待复习", "今日待复习单词数"),
    AI_SUGGESTION("学习建议", "离线规则建议；AI 建议只在用户主动请求后显示"),
    ;

    companion object {
        val DefaultOrder: List<HomeCardId> = entries.toList()
    }
}

data class HomeCardPreference(
    val id: HomeCardId,
    val visible: Boolean = true,
    val sortOrder: Int = id.ordinal,
)

interface HomeCardRepository {
    fun observeCards(): Flow<List<HomeCardPreference>>
    suspend fun saveCards(cards: List<HomeCardPreference>)
}

class OfflineHomeCardRepository(
    private val dao: HomeCardDao,
) : HomeCardRepository {
    override fun observeCards(): Flow<List<HomeCardPreference>> = dao.observeCards().map { rows ->
        val byId = rows.associateBy { it.cardId }
        HomeCardId.entries.map { id ->
            val row = byId[id.name]
            HomeCardPreference(
                id = id,
                visible = row?.visible ?: true,
                sortOrder = row?.sortOrder ?: id.ordinal,
            )
        }.sortedWith(compareBy({ it.sortOrder }, { it.id.ordinal }))
    }

    override suspend fun saveCards(cards: List<HomeCardPreference>) {
        val ordered = cards.sortedBy { it.sortOrder }
            .map { it.id } + HomeCardId.entries.filter { id -> cards.none { it.id == id } }
        val normalized = ordered.mapIndexed { index, id ->
            HomeCardPreferenceEntity(
                cardId = id.name,
                visible = cards.firstOrNull { it.id == id }?.visible ?: true,
                sortOrder = index,
            )
        }
        dao.upsertCards(normalized)
    }
}
