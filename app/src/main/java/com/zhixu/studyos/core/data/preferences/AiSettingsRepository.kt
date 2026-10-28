package com.zhixu.studyos.core.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhixu.studyos.core.model.AiProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.aiSettingsDataStore by preferencesDataStore(name = "ai_settings")

/** AI 设置读写抽象，便于在测试中替换。 */
interface AiConfigStore {
    val config: Flow<AiProviderConfig>
    suspend fun getConfig(): AiProviderConfig
    suspend fun saveConfig(config: AiProviderConfig)
    suspend fun updatePrivacy(
        includeTaskTitles: Boolean,
        includeSubjectNames: Boolean,
        includeProblemStatements: Boolean,
    )
}

/**
 * AI 设置（不含密钥）持久化。API Key 单独存放在 Android Keystore 加密的文件中，
 * 这里只保存服务地址、模型、超时、价格、额度与隐私开关。
 */
class AiSettingsRepository(private val context: Context) : AiConfigStore {
    private val enabledKey = booleanPreferencesKey("ai_enabled")
    private val baseUrlKey = stringPreferencesKey("ai_base_url")
    private val modelKey = stringPreferencesKey("ai_model")
    private val timeoutKey = intPreferencesKey("ai_timeout_seconds")
    private val inputPriceKey = longPreferencesKey("ai_input_price_micros_per_million")
    private val outputPriceKey = longPreferencesKey("ai_output_price_micros_per_million")
    private val dailyCallLimitKey = intPreferencesKey("ai_daily_call_limit")
    private val dailyCostLimitKey = longPreferencesKey("ai_daily_cost_limit_micros")
    private val includeTaskTitlesKey = booleanPreferencesKey("ai_include_task_titles")
    private val includeSubjectNamesKey = booleanPreferencesKey("ai_include_subject_names")
    private val includeProblemStatementsKey = booleanPreferencesKey("ai_include_problem_statements")

    override val config: Flow<AiProviderConfig> = context.aiSettingsDataStore.data.map { it.toConfig() }

    override suspend fun getConfig(): AiProviderConfig = context.aiSettingsDataStore.data.first().toConfig()

    override suspend fun saveConfig(config: AiProviderConfig) {
        config.requireValid()
        context.aiSettingsDataStore.edit { preferences ->
            preferences[enabledKey] = config.enabled
            preferences[baseUrlKey] = config.baseUrl.trim()
            preferences[modelKey] = config.model.trim()
            preferences[timeoutKey] = config.timeoutSeconds
            preferences[inputPriceKey] = config.inputPriceMicrosPerMillionTokens
            preferences[outputPriceKey] = config.outputPriceMicrosPerMillionTokens
            preferences[dailyCallLimitKey] = config.dailyCallLimit
            preferences[dailyCostLimitKey] = config.dailyCostLimitMicros
            preferences[includeTaskTitlesKey] = config.includeTaskTitles
            preferences[includeSubjectNamesKey] = config.includeSubjectNames
            preferences[includeProblemStatementsKey] = config.includeProblemStatements
        }
    }

    /** 只更新隐私开关，便于在 AI 页面直接切换。 */
    override suspend fun updatePrivacy(
        includeTaskTitles: Boolean,
        includeSubjectNames: Boolean,
        includeProblemStatements: Boolean,
    ) {
        val current = getConfig()
        saveConfig(
            current.copy(
                includeTaskTitles = includeTaskTitles,
                includeSubjectNames = includeSubjectNames,
                includeProblemStatements = includeProblemStatements,
            ),
        )
    }

    private fun Preferences.toConfig(): AiProviderConfig {
        val defaults = AiProviderConfig.DeepSeekDefault
        return AiProviderConfig(
            enabled = this[enabledKey] ?: defaults.enabled,
            // 旧版本可能保存过空字符串；空值也回退到 DeepSeek 默认，
            // 保证“默认模型接入 DS”对已安装用户同样生效。
            baseUrl = this[baseUrlKey]?.takeIf { it.isNotBlank() } ?: defaults.baseUrl,
            model = this[modelKey]?.takeIf { it.isNotBlank() } ?: defaults.model,
            timeoutSeconds = this[timeoutKey] ?: defaults.timeoutSeconds,
            inputPriceMicrosPerMillionTokens = this[inputPriceKey]
                ?: defaults.inputPriceMicrosPerMillionTokens,
            outputPriceMicrosPerMillionTokens = this[outputPriceKey]
                ?: defaults.outputPriceMicrosPerMillionTokens,
            dailyCallLimit = this[dailyCallLimitKey] ?: defaults.dailyCallLimit,
            dailyCostLimitMicros = this[dailyCostLimitKey] ?: defaults.dailyCostLimitMicros,
            includeTaskTitles = this[includeTaskTitlesKey] ?: defaults.includeTaskTitles,
            includeSubjectNames = this[includeSubjectNamesKey] ?: defaults.includeSubjectNames,
            includeProblemStatements = this[includeProblemStatementsKey] ?: defaults.includeProblemStatements,
        )
    }
}
