package com.zhixu.studyos.feature.math

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.MathHintRepository
import com.zhixu.studyos.core.data.repository.ExerciseRepository
import com.zhixu.studyos.core.data.repository.HintRecordView
import com.zhixu.studyos.core.data.repository.ProblemBankRepository
import com.zhixu.studyos.core.data.repository.SavedProofView
import com.zhixu.studyos.core.data.repository.ProofCheckOutcome
import com.zhixu.studyos.core.data.repository.SubmitAttemptRequest
import com.zhixu.studyos.core.exercise.ExerciseAttempt
import com.zhixu.studyos.core.exercise.ExerciseProfile
import com.zhixu.studyos.core.exercise.ExerciseProblemType
import com.zhixu.studyos.core.exercise.ExerciseRecommendation
import com.zhixu.studyos.core.math.HintLevel
import com.zhixu.studyos.core.math.HintProgression
import com.zhixu.studyos.core.math.MathHint
import com.zhixu.studyos.core.math.MathHintOutcome
import com.zhixu.studyos.core.math.ProofCheck
import com.zhixu.studyos.core.model.ProblemBankEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MathAssistantUiState(
    val isLoading: Boolean = true,
    val problems: List<ProblemBankEntry> = emptyList(),
    val search: String = "",
    val recommendations: List<ExerciseRecommendation> = emptyList(),
    val selected: ProblemBankEntry? = null,
    val attempts: List<ExerciseAttempt> = emptyList(),
    val profile: ExerciseProfile? = null,
    val hints: List<HintRecordView> = emptyList(),
    val highestLevel: HintLevel? = null,
    /** 仅用于本次尝试的提示等级；提交后清零，不能沿用历史最高等级。 */
    val attemptHighestLevel: HintLevel? = null,
    val thoughts: String = "",
    val hint: MathHint? = null,
    val hintMeta: String? = null,
    val isHintLoading: Boolean = false,
    val pendingLevel: HintLevel? = null,
    val proofText: String = "",
    val proofCheck: ProofCheck? = null,
    val proofMeta: String? = null,
    val isProofLoading: Boolean = false,
    val savedProofs: List<SavedProofView> = emptyList(),
    val errorMessage: String? = null,
    val notice: String? = null,
    val attemptResultMessage: String? = null,
) {
    val visibleProblems: List<ProblemBankEntry>
        get() = if (search.isBlank()) {
            problems
        } else {
            problems.filter {
                it.statement.contains(search, ignoreCase = true) ||
                    it.chapter.contains(search, ignoreCase = true) ||
                    it.problemNumber.contains(search, ignoreCase = true)
            }
        }
    val nextLevel: HintLevel get() = HintProgression.nextLevel(highestLevel)
}

class MathAssistantViewModel(
    private val problemBankRepository: ProblemBankRepository,
    private val exerciseRepository: ExerciseRepository,
    private val hintRepository: MathHintRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(MathAssistantUiState())
    val uiState: StateFlow<MathAssistantUiState> = mutable

    private var started = false

    fun start() {
        if (started) return
        started = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isLoading = true, errorMessage = null)
            runCatching {
                val page = problemBankRepository.pageProblems(
                    query = com.zhixu.studyos.core.data.repository.ProblemQuery(),
                    limit = 100,
                    offset = 0,
                )
                val recommendations = exerciseRepository.recommendations(8)
                page.problems to recommendations
            }.onSuccess { (problems, recommendations) ->
                mutable.value = mutable.value.copy(
                    isLoading = false,
                    problems = problems,
                    recommendations = recommendations,
                )
                if (mutable.value.selected == null) {
                    problems.firstOrNull()?.let { openProblem(it.id) }
                }
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isLoading = false,
                    errorMessage = it.message ?: "无法读取题库",
                )
            }
        }
    }

    fun setSearch(value: String) {
        mutable.value = mutable.value.copy(search = value)
    }

    fun openProblem(problemId: Long) {
        viewModelScope.launch {
            runCatching {
                val problem = problemBankRepository.getProblem(problemId)
                    ?: throw IllegalArgumentException("题目不存在，可能已被删除")
                val attempts = exerciseRepository.attempts(ExerciseProblemType.BANK, problemId)
                val profile = exerciseRepository.profile(ExerciseProblemType.BANK, problemId)
                val hints = hintRepository.hintRecords(ExerciseProblemType.BANK, problemId)
                val proofs = hintRepository.savedProofs(ExerciseProblemType.BANK, problemId)
                Triple(problem, Triple(attempts, profile, hints), proofs)
            }.onSuccess { (problem, data, proofs) ->
                val (attempts, profile, hints) = data
                mutable.value = mutable.value.copy(
                    selected = problem,
                    attempts = attempts,
                    profile = profile,
                    hints = hints,
                    highestLevel = hints.maxByOrNull { it.level }?.level,
                    attemptHighestLevel = null,
                    savedProofs = proofs,
                    hint = null,
                    proofCheck = null,
                    thoughts = mutable.value.thoughts,
                    proofText = mutable.value.proofText,
                    attemptResultMessage = null,
                )
            }.onFailure {
                mutable.value = mutable.value.copy(notice = it.message ?: "无法读取题目")
            }
        }
    }

    fun setThoughts(value: String) {
        mutable.value = mutable.value.copy(thoughts = value)
    }

    fun requestLevel(level: HintLevel) {
        if (level == HintLevel.LEVEL_0) {
            mutable.value = mutable.value.copy(
                hint = null,
                notice = "Level 0：独立思考，不显示 AI 提示。先写下你的思路。",
            )
            return
        }
        val state = mutable.value
        if (HintProgression.isJump(state.highestLevel, level) || level == HintLevel.LEVEL_5) {
            mutable.value = state.copy(pendingLevel = level)
        } else {
            requestHint(level)
        }
    }

    fun confirmPendingLevel() {
        val level = mutable.value.pendingLevel ?: return
        mutable.value = mutable.value.copy(pendingLevel = null)
        requestHint(level)
    }

    fun dismissPendingLevel() {
        mutable.value = mutable.value.copy(pendingLevel = null)
    }

    private fun requestHint(level: HintLevel) {
        val problem = mutable.value.selected ?: return
        if (mutable.value.isHintLoading) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isHintLoading = true, hintMeta = null)
            val allowJump = HintProgression.isJump(mutable.value.highestLevel, level)
            val allowReference = level == HintLevel.LEVEL_5
            runCatching {
                hintRepository.requestHint(
                    problemType = ExerciseProblemType.BANK,
                    problemId = problem.id,
                    level = level,
                    userThoughts = mutable.value.thoughts,
                    allowJump = allowJump,
                    allowReferenceProof = allowReference,
                )
            }.onSuccess { outcome ->
                mutable.value = mutable.value.copy(
                    isHintLoading = false,
                    hint = outcome.hint,
                    highestLevel = hintRepository.highestHintLevel(
                        ExerciseProblemType.BANK,
                        problem.id,
                    ),
                    attemptHighestLevel = listOfNotNull(
                        mutable.value.attemptHighestLevel,
                        level,
                    ).maxByOrNull { it.ordinal },
                    hints = hintRepository.hintRecords(ExerciseProblemType.BANK, problem.id),
                    hintMeta = buildString {
                        append("Level ${level.ordinal} ${level.label}")
                        append(" · ${if (outcome.usedCache) "来自缓存，本次未产生费用" else "本次费用估算 ${outcome.costMicros} 微元"}")
                        append(" · ${outcome.model}")
                    },
                )
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isHintLoading = false,
                    notice = "AI 提示失败：${it.message ?: "未知错误"}",
                )
            }
        }
    }

    fun saveDraft() {
        val problem = mutable.value.selected ?: return
        if (mutable.value.thoughts.isBlank() && mutable.value.proofText.isBlank()) {
            mutable.value = mutable.value.copy(notice = "还没有可保存的思路或证明草稿")
            return
        }
        viewModelScope.launch {
            runCatching {
                hintRepository.saveProof(
                    problemType = ExerciseProblemType.BANK,
                    problemId = problem.id,
                    content = mutable.value.proofText.ifBlank { mutable.value.thoughts },
                    source = "DRAFT",
                    note = "思路/证明草稿",
                )
            }.onSuccess {
                mutable.value = mutable.value.copy(
                    notice = "草稿已保存到本机",
                    savedProofs = hintRepository.savedProofs(ExerciseProblemType.BANK, problem.id),
                )
            }.onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "保存草稿失败") }
        }
    }

    fun setProofText(value: String) {
        mutable.value = mutable.value.copy(proofText = value)
    }

    fun checkProof(otherMethods: Boolean) {
        val problem = mutable.value.selected ?: return
        if (mutable.value.proofText.isBlank()) {
            mutable.value = mutable.value.copy(notice = "请先写下你的证明")
            return
        }
        if (mutable.value.isProofLoading) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isProofLoading = true, proofMeta = null)
            runCatching {
                hintRepository.checkProof(
                    problemType = ExerciseProblemType.BANK,
                    problemId = problem.id,
                    proof = mutable.value.proofText,
                    otherMethods = otherMethods,
                )
            }.onSuccess { outcome ->
                mutable.value = mutable.value.copy(
                    isProofLoading = false,
                    proofCheck = outcome.check,
                    proofMeta = buildString {
                        append(outcome.model)
                        append(" · ")
                        append(if (outcome.usedCache) "缓存结果，未产生费用" else "费用估算 ${outcome.costMicros} 微元")
                    },
                )
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isProofLoading = false,
                    notice = "证明检查失败：${it.message ?: "未知错误"}",
                )
            }
        }
    }

    fun saveConfirmedProof() {
        val problem = mutable.value.selected ?: return
        if (mutable.value.proofText.isBlank()) return
        viewModelScope.launch {
            runCatching {
                hintRepository.saveProof(
                    problemType = ExerciseProblemType.BANK,
                    problemId = problem.id,
                    content = mutable.value.proofText,
                    source = "USER_CONFIRMED",
                    note = "用户确认保存的证明版本",
                )
            }.onSuccess {
                mutable.value = mutable.value.copy(
                    notice = "已保存你确认的证明版本",
                    savedProofs = hintRepository.savedProofs(ExerciseProblemType.BANK, problem.id),
                )
            }.onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "保存失败") }
        }
    }

    fun reportHintError(reason: String, note: String) {
        val problem = mutable.value.selected ?: return
        viewModelScope.launch {
            runCatching {
                hintRepository.reportError(
                    problemType = ExerciseProblemType.BANK,
                    problemId = problem.id,
                    hintLevel = mutable.value.hint?.level?.ordinal,
                    reason = reason,
                    note = note,
                )
            }.onSuccess {
                mutable.value = mutable.value.copy(notice = "已记录问题报告，仅保存在本机")
            }.onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "提交报告失败") }
        }
    }

    fun submitAttempt(
        durationMinutes: Int,
        correct: Boolean,
        independent: Boolean,
        note: String,
        onResult: (String?) -> Unit,
    ) {
        val problem = mutable.value.selected ?: return
        viewModelScope.launch {
            runCatching {
                exerciseRepository.submitAttempt(
                    SubmitAttemptRequest(
                        problemType = ExerciseProblemType.BANK,
                        problemId = problem.id,
                        durationMinutes = durationMinutes,
                        highestHintLevel = mutable.value.attemptHighestLevel?.ordinal ?: 0,
                        independent = independent,
                        correct = correct,
                        note = note,
                    ),
                )
            }.onSuccess { result ->
                mutable.value = mutable.value.copy(
                    attempts = exerciseRepository.attempts(ExerciseProblemType.BANK, problem.id),
                    profile = result.profile,
                    attemptHighestLevel = null,
                    attemptResultMessage = buildString {
                        append("已记录第 ${result.attempt.attemptIndex} 次尝试。")
                        append("个人难度 ${result.profile.personalDifficulty ?: "—"}，")
                        append("掌握估计 ${(result.profile.mastery * 100).toInt()}%。")
                        result.suggestedEstimatedMinutes?.let {
                            append("多次实际用时与预计差异较大，建议把预计用时调整为 $it 分钟（题库编辑中可修改）。")
                        }
                        result.reviewSuggestion?.let {
                            append("；" + it)
                        }
                    },
                )
                onResult(null)
                refresh()
            }.onFailure { onResult(it.message ?: "记录尝试失败") }
        }
    }

    fun consumeNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }
}
