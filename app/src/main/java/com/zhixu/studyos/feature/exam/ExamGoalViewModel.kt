package com.zhixu.studyos.feature.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.ExamGoalRepository
import com.zhixu.studyos.core.data.repository.ExamGoalSnapshot
import com.zhixu.studyos.core.data.repository.ExamTaskGenerationResult
import com.zhixu.studyos.core.exam.Exam
import com.zhixu.studyos.core.exam.ExamCountdown
import com.zhixu.studyos.core.exam.ExamCountdownCalculator
import com.zhixu.studyos.core.exam.GoalMilestone
import com.zhixu.studyos.core.exam.GoalProgress
import com.zhixu.studyos.core.exam.GoalProgressCalculator
import com.zhixu.studyos.core.exam.LongTermGoal
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ExamGoalUiState(
    val isLoading: Boolean = true,
    val snapshot: ExamGoalSnapshot = ExamGoalSnapshot(),
    val countdowns: List<ExamCountdown> = emptyList(),
    val goalProgress: Map<Long, GoalProgress> = emptyMap(),
    val lastGeneratedPlan: ExamTaskGenerationResult? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
) {
    val nextExam: ExamCountdown? get() = countdowns.firstOrNull { it.phase != com.zhixu.studyos.core.exam.ExamPhase.PASSED }
}

class ExamGoalViewModel(
    private val repository: ExamGoalRepository,
    private val clock: Clock,
) : ViewModel() {
    private val mutable = MutableStateFlow(ExamGoalUiState())
    val uiState: StateFlow<ExamGoalUiState> = mutable

    init {
        viewModelScope.launch {
            repository.observeSnapshot()
                .catch {
                    mutable.value = mutable.value.copy(
                        isLoading = false,
                        errorMessage = it.message ?: "无法读取考试与目标",
                    )
                }
                .collect { snapshot ->
                    buildState(snapshot)
                }
        }
    }

    private fun buildState(snapshot: ExamGoalSnapshot) {
        val today = LocalDate.now(clock)
        mutable.value = mutable.value.copy(
            isLoading = false,
            snapshot = snapshot,
            countdowns = ExamCountdownCalculator.build(snapshot.exams, today),
            goalProgress = snapshot.goals.associate { goal ->
                goal.id to GoalProgressCalculator.calculate(
                    goal,
                    snapshot.milestones[goal.id].orEmpty(),
                    today,
                )
            },
        )
    }

    fun saveExam(exam: Exam, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveExam(exam) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "考试已保存")
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存考试失败") }
        }
    }

    fun deleteExam(examId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteExam(examId) }
                .onSuccess { mutable.value = mutable.value.copy(notice = "考试已删除") }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除考试失败") }
        }
    }

    fun generateReviewTasks(examId: Long) {
        viewModelScope.launch {
            runCatching { repository.generateReviewTasks(examId, LocalDate.now(clock)) }
                .onSuccess { result ->
                    mutable.value = mutable.value.copy(
                        lastGeneratedPlan = result,
                        notice = "已生成 ${result.createdTaskIds.size} 个待安排复习任务" +
                            if (result.skippedExisting > 0) "；跳过 ${result.skippedExisting} 个已存在任务" else "",
                    )
                }
                .onFailure {
                    mutable.value = mutable.value.copy(notice = "生成复习任务失败：${it.message ?: "未知错误"}")
                }
        }
    }

    fun dismissPlan() {
        mutable.value = mutable.value.copy(lastGeneratedPlan = null)
    }

    fun saveGoal(goal: LongTermGoal, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveGoal(goal) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "目标已保存")
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存目标失败") }
        }
    }

    fun deleteGoal(goalId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteGoal(goalId) }
                .onSuccess { mutable.value = mutable.value.copy(notice = "目标及其里程碑已删除") }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除目标失败") }
        }
    }

    fun saveMilestone(milestone: GoalMilestone, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveMilestone(milestone) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "里程碑已保存")
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "保存里程碑失败") }
        }
    }

    fun toggleMilestone(milestoneId: Long, completed: Boolean) {
        viewModelScope.launch {
            runCatching { repository.toggleMilestone(milestoneId, completed) }
                .onSuccess { mutable.value = mutable.value.copy(notice = if (completed) "里程碑已完成" else "里程碑已重新开启") }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "更新里程碑失败") }
        }
    }

    fun deleteMilestone(milestoneId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteMilestone(milestoneId) }
                .onSuccess { mutable.value = mutable.value.copy(notice = "里程碑已删除") }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除里程碑失败") }
        }
    }

    fun consumeNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }
}
