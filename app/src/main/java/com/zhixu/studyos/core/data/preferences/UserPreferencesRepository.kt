package com.zhixu.studyos.core.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhixu.studyos.core.model.BudgetReminderLevel
import com.zhixu.studyos.core.model.BudgetReminderState
import com.zhixu.studyos.core.model.ThemePreference
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPreferencesDataStore by preferencesDataStore(
    name = "user_preferences",
)

class UserPreferencesRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_preference")
    private val budgetReminderEnabledKey = booleanPreferencesKey("budget_reminder_enabled")
    private val budgetReminderMonthKey = stringPreferencesKey("budget_reminder_month")
    private val budgetReminderLevelKey = stringPreferencesKey("budget_reminder_level")

    val themePreference: Flow<ThemePreference> = context.userPreferencesDataStore.data.map { preferences ->
        preferences[themeKey]
            ?.let(::runCatching)
            ?.getOrNull()
            ?: ThemePreference.SYSTEM
    }

    val budgetReminderEnabled: Flow<Boolean> = context.userPreferencesDataStore.data.map { preferences ->
        preferences[budgetReminderEnabledKey] ?: false
    }

    val budgetReminderState: Flow<BudgetReminderState> = context.userPreferencesDataStore.data.map { preferences ->
        preferences.toBudgetReminderState()
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        context.userPreferencesDataStore.edit { preferences ->
            preferences[themeKey] = preference.name
        }
    }

    suspend fun setBudgetReminderEnabled(enabled: Boolean) {
        context.userPreferencesDataStore.edit { preferences ->
            preferences[budgetReminderEnabledKey] = enabled
        }
    }

    suspend fun getBudgetReminderState(): BudgetReminderState =
        context.userPreferencesDataStore.data.first().toBudgetReminderState()

    suspend fun recordBudgetReminder(month: YearMonth, level: BudgetReminderLevel) {
        context.userPreferencesDataStore.edit { preferences ->
            preferences[budgetReminderMonthKey] = month.toString()
            preferences[budgetReminderLevelKey] = level.name
        }
    }

    private fun Preferences.toBudgetReminderState(): BudgetReminderState {
        val month = this[budgetReminderMonthKey]
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val level = this[budgetReminderLevelKey]
            ?.let { runCatching { BudgetReminderLevel.valueOf(it) }.getOrNull() }
        return BudgetReminderState(lastMonth = month, lastLevel = level)
    }
}

private fun runCatching(value: String): Result<ThemePreference> = kotlin.runCatching {
    ThemePreference.valueOf(value)
}
