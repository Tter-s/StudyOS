package com.zhixu.studyos.feature.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.ai.AiCostCalculator
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.model.AiIssueSeverity
import com.zhixu.studyos.core.model.AiPlanReview

/**
 * 「学习 → AI 规划」标签页。
 *
 * 隐私优先：调用前展示将发送的数据摘要与完整 JSON 预览；
 * 成本可控：明确显示今日调用与费用、缓存状态，默认不自动调用；
 * 结果可信：JSON Schema 校验 + 本地约束检查 + 用户勾选确认后才应用。
 */
@Composable
fun AiPlannerScreen(
    state: AiPlannerUiState,
    onRefresh: () -> Unit,
    onPrivacyChange: (taskTitles: Boolean, subjectNames: Boolean, problemDetails: Boolean) -> Unit,
    onRequestAnalysis: () -> Unit,
    onUseCached: () -> Unit,
    onToggleEstimate: (Long) -> Unit,
    onConfirmApply: () -> Unit,
    onDismissError: () -> Unit,
) {
    if (state.loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_ai"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag("screen_ai"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI 学习规划",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("刷新数据") }
        }
        Text(
            text = "AI 只给出结构化建议；排程与数据保存仍由本地系统控制。" +
                "默认不会自动调用，只有你点击生成时才会发送数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConfigurationCard(state)

        PrivacyCard(state, onPrivacyChange)

        UsageCard(state)

        if (state.errorMessage != null) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.errorKind?.let { kind ->
                            Text(
                                text = if (kind.isRetryable) "该错误可以重试。" else "该错误需要先调整配置或额度。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onDismissError) { Text("知道了") }
                }
            }
        }

        ActionCard(
            state = state,
            onRequestAnalysis = onRequestAnalysis,
            onUseCached = onUseCached,
        )

        state.review?.let { review ->
            PlanReviewCard(
                review = review,
                state = state,
                onToggleEstimate = onToggleEstimate,
                onConfirmApply = onConfirmApply,
            )
        }

        if (state.records.isNotEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(text = "已保存的建议记录", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                state.records.take(3).forEach { record ->
                    Text(
                        text = "${record.createdAt} · ${record.model} · 应用 ${record.appliedTaskCount} 项" +
                            if (record.usedCache) "（缓存）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ConfigurationCard(state: AiPlannerUiState) {
    val config = state.config
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "服务状态", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        when {
            !config.enabled -> StatusLine(
                text = "AI 功能未启用。请到「我的 → AI 设置」开启并填写服务地址、模型与 API Key。",
                isWarning = true,
            )
            !config.isConfigured -> StatusLine(
                text = "尚未填写服务地址或模型，请到「我的 → AI 设置」完成配置。",
                isWarning = true,
            )
            !state.hasApiKey -> StatusLine(
                text = "尚未保存 API Key，请到「我的 → AI 设置」保存密钥。",
                isWarning = true,
            )
            else -> StatusLine(
                text = "已配置：${config.model}（${config.baseUrl}）。密钥保存在系统 Keystore 加密存储中。",
                isWarning = false,
            )
        }
    }
}

@Composable
private fun StatusLine(text: String, isWarning: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PrivacyCard(
    state: AiPlannerUiState,
    onPrivacyChange: (Boolean, Boolean, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val config = state.config
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "将发送的数据（隐私）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.privacySummary.ifBlank { "学习统计摘要" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ToggleRow(
            label = "发送科目名称",
            checked = config.includeSubjectNames,
            testTag = "ai_privacy_subject_names",
            onCheckedChange = { checked ->
                onPrivacyChange(config.includeTaskTitles, checked, config.includeProblemStatements)
            },
        )
        ToggleRow(
            label = "发送任务标题",
            checked = config.includeTaskTitles,
            testTag = "ai_privacy_task_titles",
            onCheckedChange = { checked ->
                onPrivacyChange(checked, config.includeSubjectNames, config.includeProblemStatements)
            },
        )
        ToggleRow(
            label = "发送困难题目的题号与知识点",
            checked = config.includeProblemStatements,
            testTag = "ai_privacy_problem_details",
            onCheckedChange = { checked ->
                onPrivacyChange(config.includeTaskTitles, config.includeSubjectNames, checked)
            },
        )
        Text(
            text = "关闭后科目名称使用掩码、任务标题使用「任务 #id」、困难题目只发送按科目的数量；" +
                "掩码模式下 AI 的科目分配建议无法映射回本地科目，会被本地约束拒绝。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起发送内容预览" else "查看发送内容预览（${state.payloadBytes} B）")
        }
        if (expanded) {
            Text(
                text = state.payloadPreview,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 40,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun UsageCard(state: AiPlannerUiState) {
    val config = state.config
    val usage = state.todayUsage
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "今日用量与成本控制", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(
                onClick = {},
                label = {
                    Text("调用 ${usage?.requestCount ?: 0}/${config.dailyCallLimit}")
                },
            )
            AssistChip(
                onClick = {},
                label = {
                    Text("Token ${AiCostCalculator.formatTokens(usage?.totalTokens ?: 0)}")
                },
            )
            AssistChip(
                onClick = {},
                label = {
                    Text("估算费用 ${AiCostCalculator.formatYuan(usage?.estimatedCostMicros ?: 0)}")
                },
            )
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        if (config.dailyCostLimitMicros > 0) {
                            "限额 ${AiCostCalculator.formatYuan(config.dailyCostLimitMicros)}"
                        } else {
                            "费用不限"
                        },
                    )
                },
            )
        }
        Text(
            text = "费用为按自填单价估算，实际以服务商账单为准；缓存命中不产生调用与费用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionCard(
    state: AiPlannerUiState,
    onRequestAnalysis: () -> Unit,
    onUseCached: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "生成建议", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                !state.isReadyToCall && !state.busy ->
                    "完成配置并保存密钥后即可生成。此操作会调用你配置的付费接口。"
                state.busy -> "正在请求 AI，请稍候…"
                state.cachedAt != null ->
                    "检测到相同输入已有缓存结果（生成于 ${state.cachedAt}）。使用缓存不会产生费用。"
                else -> "输入数据尚未分析过。生成会调用一次 AI 接口并计入今日用量。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            }
            if (state.cachedAt != null && state.review == null) {
                PrimaryButton(
                    text = "使用缓存结果",
                    onClick = onUseCached,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_use_cache_button"),
                )
                SecondaryButton(
                    text = "重新生成",
                    onClick = onRequestAnalysis,
                    enabled = state.isReadyToCall,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_generate_button"),
                )
            } else {
                PrimaryButton(
                    text = if (state.review == null) "生成 AI 建议" else "重新生成（会产生费用）",
                    onClick = onRequestAnalysis,
                    enabled = state.isReadyToCall,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_generate_button"),
                )
            }
        }
        state.outcomeMeta?.let { meta ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("模型 ${meta.model}")
                    if (meta.fromCache) append(" · 来自缓存${meta.cachedAt?.let { "（$it）" } ?: ""}")
                    append(" · 估算费用 ${AiCostCalculator.formatYuan(meta.costMicros)}")
                    if (!meta.fromCache) {
                        append(" · 输入 ${meta.promptTokens} / 输出 ${meta.completionTokens} token")
                        if (meta.tokensEstimated) append("（输出为估算）")
                        meta.latencyMillis?.let { append(" · 耗时 ${it} ms") }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanReviewCard(
    review: AiPlanReview,
    state: AiPlannerUiState,
    onToggleEstimate: (Long) -> Unit,
    onConfirmApply: () -> Unit,
) {
    val tasksById = state.snapshot?.upcomingTasks?.associateBy { it.taskId }.orEmpty()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = "建议预览（尚未应用）", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = review.plan.summary, style = MaterialTheme.typography.bodyMedium)

        if (review.acceptedAllocations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "科目时间分配建议", style = MaterialTheme.typography.labelLarge)
            review.acceptedAllocations.forEach { allocation ->
                Text(
                    text = "· ${allocation.subject}：每日 ${allocation.minutesPerDay} 分钟" +
                        allocation.reason.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (review.plan.taskEstimates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "任务用时估计（勾选后应用）", style = MaterialTheme.typography.labelLarge)
            review.acceptedTaskEstimates.forEach { estimate ->
                val task = tasksById[estimate.taskId]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = estimate.taskId in state.selectedTaskIds,
                        onCheckedChange = { onToggleEstimate(estimate.taskId) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${task?.title ?: "任务 #${estimate.taskId}"} → ${estimate.estimatedMinutes} 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (estimate.reason.isNotBlank()) {
                            Text(
                                text = estimate.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            review.rejectedTaskEstimates.forEach { estimate ->
                Text(
                    text = "未采用：任务 #${estimate.taskId}（${estimate.estimatedMinutes} 分钟）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (review.plan.restReminders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "休息提醒", style = MaterialTheme.typography.labelLarge)
            review.plan.restReminders.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (review.plan.weeklySuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "下一周建议", style = MaterialTheme.typography.labelLarge)
            review.plan.weeklySuggestions.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (review.plan.riskFlags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "风险提示", style = MaterialTheme.typography.labelLarge)
            review.plan.riskFlags.forEach {
                Text("· ${it.kind}：${it.detail}", style = MaterialTheme.typography.bodySmall)
            }
        }

        val errors = review.issues.filter { it.severity == AiIssueSeverity.ERROR }
        if (errors.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "本地约束检查：${errors.size} 项未采用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            errors.take(6).forEach {
                Text(
                    text = "· ${it.itemLabel}：${it.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        PrimaryButton(
            text = if (state.selectedEstimateCount > 0) {
                "确认并应用 ${state.selectedEstimateCount} 项用时"
            } else {
                "确认保存建议"
            },
            onClick = onConfirmApply,
            enabled = state.canApply,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_confirm_button"),
        )
        Text(
            text = "确认只会把勾选的预计用时写入本地任务，并保存这份建议；不会自动排程。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
