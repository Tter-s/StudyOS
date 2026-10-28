package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.data.repository.LearningRepository
import com.zhixu.studyos.core.data.repository.ProblemBankRepository
import com.zhixu.studyos.core.data.repository.SchedulingRepository
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.model.AiStudySnapshot
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * 提供「发送给 AI 的学习数据快照」。AI 页面与 ViewModel 只依赖该接口，
 * 便于测试与将来替换数据来源；不包含任何财务数据。
 */
interface AiStudyDataSource {
    suspend fun load(today: LocalDate): AiStudySnapshot
}

private const val AI_PROBLEM_CANDIDATES = 50

class LocalAiStudyDataSource(
    private val taskRepository: TaskRepository,
    private val learningRepository: LearningRepository,
    private val problemBankRepository: ProblemBankRepository,
    private val schedulingRepository: SchedulingRepository,
    private val snapshotBuilder: AiSnapshotBuilder,
) : AiStudyDataSource {
    override suspend fun load(today: LocalDate): AiStudySnapshot {
        val learning = learningRepository.observeSnapshot().first()
        val tasks = taskRepository.observeAllTasks().first()
        // 只取困难信号候选，避免 AI 页面把整个题库读入内存
        val bankProblems = problemBankRepository.difficultProblemCandidates(AI_PROBLEM_CANDIDATES)
        val settings = schedulingRepository.getSettings()
        return snapshotBuilder.build(
            today = today,
            tasks = tasks,
            timings = learning.timings,
            manualProblems = learning.problems,
            bankProblems = bankProblems,
            subjects = learning.subjects,
            goals = learning.goals,
            settings = settings,
        )
    }
}
