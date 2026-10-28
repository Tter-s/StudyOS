package com.zhixu.studyos.feature.vocabulary

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.repository.VocabularyRepository
import com.zhixu.studyos.core.data.repository.VocabularyImportResultSummary
import com.zhixu.studyos.core.data.repository.VocabularyTaskResult
import com.zhixu.studyos.core.vocabulary.VocabularyRating
import com.zhixu.studyos.core.vocabulary.VocabularySettings
import com.zhixu.studyos.core.vocabulary.VocabularyStats
import com.zhixu.studyos.core.vocabulary.VocabularyStatus
import com.zhixu.studyos.core.vocabulary.VocabularyWord
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VocabularyUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val totalWords: Int = 0,
    val dueWords: Int = 0,
    val settings: VocabularySettings = VocabularySettings(),
    val stats: VocabularyStats? = null,
    val words: List<VocabularyWord> = emptyList(),
    val totalCount: Int = 0,
    val listNames: List<String> = emptyList(),
    val selectedList: String = "",
    val search: String = "",
    val isLoadingMore: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
    val reviewQueue: List<VocabularyWord> = emptyList(),
    val reviewIndex: Int = 0,
    val revealed: Boolean = false,
    val reviewedCount: Int = 0,
    val reviewStartedAtMillis: Long? = null,
    val importResult: VocabularyImportResultSummary? = null,
) {
    val isReviewing: Boolean get() = reviewQueue.isNotEmpty() && reviewIndex < reviewQueue.size
    val currentReviewWord: VocabularyWord?
        get() = reviewQueue.getOrNull(reviewIndex)
}

class VocabularyViewModel(
    private val repository: VocabularyRepository,
    private val fileStore: AppFileStore,
    private val clock: Clock,
) : ViewModel() {
    private val pageSize = 50
    private val mutable = MutableStateFlow(VocabularyUiState(today = LocalDate.now(clock)))
    val uiState: StateFlow<VocabularyUiState> = mutable

    private val summary = repository.observeSummary(LocalDate.now(clock))
        .catch { emit(com.zhixu.studyos.core.data.repository.VocabularySummary()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = com.zhixu.studyos.core.data.repository.VocabularySummary(),
        )

    init {
        viewModelScope.launch {
            summary.collect { value ->
                mutable.value = mutable.value.copy(
                    totalWords = value.totalWords,
                    dueWords = value.dueWords,
                    settings = value.settings,
                )
            }
        }
    }

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
                val words = repository.pageWords(
                    listName = mutable.value.selectedList,
                    search = mutable.value.search,
                    limit = pageSize,
                    offset = 0,
                )
                val total = repository.countWords(mutable.value.selectedList, mutable.value.search)
                val names = repository.listNames()
                val stats = repository.stats(LocalDate.now(clock))
                Triple(words, total, names) to stats
            }.onSuccess { (data, stats) ->
                mutable.value = mutable.value.copy(
                    isLoading = false,
                    words = data.first,
                    totalCount = data.second,
                    listNames = data.third,
                    stats = stats,
                )
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isLoading = false,
                    errorMessage = it.message ?: "无法读取词汇库",
                )
            }
        }
    }

    fun loadMore() {
        val state = mutable.value
        if (state.isLoadingMore || state.words.size >= state.totalCount) return
        mutable.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatching {
                repository.pageWords(
                    listName = state.selectedList,
                    search = state.search,
                    limit = pageSize,
                    offset = state.words.size,
                )
            }.onSuccess { more ->
                mutable.value = mutable.value.copy(
                    isLoadingMore = false,
                    words = (mutable.value.words + more).distinctBy { it.id },
                )
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isLoadingMore = false,
                    notice = it.message ?: "加载更多失败",
                )
            }
        }
    }

    fun setList(listName: String) {
        mutable.value = mutable.value.copy(selectedList = listName)
        refresh()
    }

    fun setSearch(value: String) {
        mutable.value = mutable.value.copy(search = value)
    }

    fun searchNow() {
        refresh()
    }

    fun addWord(
        word: String,
        meaning: String,
        phonetic: String,
        partOfSpeech: String,
        example: String,
        listName: String,
        note: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.addWord(
                    VocabularyWord(
                        word = word,
                        meaning = meaning,
                        phonetic = phonetic,
                        partOfSpeech = partOfSpeech,
                        example = example,
                        listName = listName.ifBlank { "自定义" },
                        note = note,
                        dueDate = LocalDate.now(clock),
                        status = VocabularyStatus.NEW,
                    ),
                )
            }.onSuccess {
                onResult(null)
                mutable.value = mutable.value.copy(notice = "单词已添加")
                refresh()
            }.onFailure { onResult(it.message ?: "添加单词失败") }
        }
    }

    fun updateWord(word: VocabularyWord, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.updateWord(word) }
                .onSuccess {
                    onResult(null)
                    mutable.value = mutable.value.copy(notice = "单词已更新")
                    refresh()
                }
                .onFailure { onResult(it.message ?: "更新单词失败") }
        }
    }

    fun deleteWord(id: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteWord(id) }
                .onSuccess {
                    mutable.value = mutable.value.copy(notice = "单词已删除")
                    refresh()
                }
                .onFailure { mutable.value = mutable.value.copy(notice = it.message ?: "删除失败") }
        }
    }

    fun saveSettings(settings: VocabularySettings, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.saveSettings(settings) }
                .onSuccess {
                    onResult(null)
                    mutable.value = mutable.value.copy(notice = "词汇设置已保存")
                }
                .onFailure { onResult(it.message ?: "保存设置失败") }
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isBusy = true, notice = null)
            runCatching {
                val text = fileStore.readBinary(uri) { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
                repository.importCsv(text)
            }.onSuccess { result ->
                mutable.value = mutable.value.copy(
                    isBusy = false,
                    importResult = result,
                    notice = when (result) {
                        is VocabularyImportResultSummary.Saved ->
                            "导入完成：新增 ${result.inserted}，跳过重复 ${result.skippedDuplicates}，" +
                                "无效 ${result.invalid}"
                        is VocabularyImportResultSummary.Rejected -> result.message
                    },
                )
                if (result is VocabularyImportResultSummary.Saved) refresh()
            }.onFailure {
                mutable.value = mutable.value.copy(
                    isBusy = false,
                    notice = "导入失败：${it.message ?: "无法读取文件"}",
                )
            }
        }
    }

    fun dismissImportResult() {
        mutable.value = mutable.value.copy(importResult = null)
    }

    fun startReview() {
        viewModelScope.launch {
            runCatching { repository.dueWords(LocalDate.now(clock)) }
                .onSuccess { words ->
                    if (words.isEmpty()) {
                        mutable.value = mutable.value.copy(notice = "今天没有到期需要复习的单词")
                    } else {
                        mutable.value = mutable.value.copy(
                            reviewQueue = words,
                            reviewIndex = 0,
                            revealed = false,
                            reviewedCount = 0,
                            reviewStartedAtMillis = clock.millis(),
                        )
                    }
                }
                .onFailure {
                    mutable.value = mutable.value.copy(notice = it.message ?: "无法开始复习")
                }
        }
    }

    fun revealAnswer() {
        mutable.value = mutable.value.copy(revealed = true)
    }

    fun rate(rating: VocabularyRating) {
        val state = mutable.value
        val word = state.currentReviewWord ?: return
        val elapsed = state.reviewStartedAtMillis?.let { (clock.millis() - it).coerceAtLeast(0L) }
        viewModelScope.launch {
            runCatching {
                repository.review(word.id, rating, elapsed, clock.instant())
            }.onSuccess { review ->
                val nextIndex = state.reviewIndex + 1
                mutable.value = mutable.value.copy(
                    reviewIndex = nextIndex,
                    revealed = false,
                    reviewedCount = state.reviewedCount + 1,
                    reviewStartedAtMillis = clock.millis(),
                    notice = if (nextIndex >= state.reviewQueue.size) {
                        "本轮复习完成，共 ${nextIndex} 个单词"
                    } else {
                        null
                    },
                )
                if (nextIndex >= state.reviewQueue.size) {
                    refresh()
                }
            }.onFailure {
                mutable.value = mutable.value.copy(notice = it.message ?: "记录复习失败")
            }
        }
    }

    fun exitReview() {
        mutable.value = mutable.value.copy(
            reviewQueue = emptyList(),
            reviewIndex = 0,
            revealed = false,
        )
    }

    fun generateTask() {
        viewModelScope.launch {
            runCatching { repository.generateDailyTask(LocalDate.now(clock), clock.instant()) }
                .onSuccess { result ->
                    mutable.value = mutable.value.copy(
                        notice = when (result) {
                            is VocabularyTaskResult.Created ->
                                "已生成「${result.title}」（${result.minutes} 分钟），可在排程中安排"
                            is VocabularyTaskResult.AlreadyExists -> "今天已经生成过词汇任务"
                            VocabularyTaskResult.Disabled -> "请先在设置中开启“自动生成每日词汇任务”"
                            VocabularyTaskResult.NothingDue -> "今天没有到期单词，不需要生成任务"
                        },
                    )
                }
                .onFailure {
                    mutable.value = mutable.value.copy(notice = it.message ?: "生成任务失败")
                }
        }
    }

    fun consumeNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }
}
