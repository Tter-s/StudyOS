package com.zhixu.studyos.core.data.repository

import com.zhixu.studyos.core.model.StudyTask
import kotlinx.coroutines.flow.Flow

interface HomeRepository {
    fun observeTasks(epochDay: Long): Flow<List<StudyTask>>

    suspend fun setTaskCompleted(taskId: Long, completed: Boolean)
}
