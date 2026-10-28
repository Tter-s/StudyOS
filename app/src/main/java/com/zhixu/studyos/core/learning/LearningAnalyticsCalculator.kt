package com.zhixu.studyos.core.learning

import com.zhixu.studyos.core.model.LearningSubjectAnalytics
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.SubjectLearningStats
import com.zhixu.studyos.core.model.TimingSession

object LearningAnalyticsCalculator {
    fun subjectAnalytics(
        subjects: List<Subject>,
        tasks: List<StudyTask>,
        timings: List<TimingSession>,
        problems: List<Problem>,
    ): LearningSubjectAnalytics {
        val completedTimings = timings.filter { it.status == StudyTimerStatus.COMPLETED }
        val stats = subjects.map { subject ->
            val subjectTasks = tasks.filter { it.subject.trim().equals(subject.name, ignoreCase = true) }
            val subjectProblems = problems.filter { it.subjectId == subject.id }
            val completedProblems = subjectProblems.filter { it.status == ProblemStatus.COMPLETED }
            SubjectLearningStats(
                subject = subject,
                studyMillis = completedTimings
                    .filter { it.subjectId == subject.id }
                    .sumOf(TimingSession::accumulatedMillis),
                taskTotal = subjectTasks.size,
                taskCompleted = subjectTasks.count(StudyTask::isCompleted),
                problemCompleted = completedProblems.size,
                difficultProblemCount = subjectProblems.count { it.isDifficult },
                independentProblemCount = completedProblems.count { it.latestAttemptIndependent },
                completedProblemCount = completedProblems.size,
            )
        }
        return LearningSubjectAnalytics(stats)
    }
}
