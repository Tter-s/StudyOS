package com.zhixu.studyos.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.preferences.UserPreferencesRepository
import com.zhixu.studyos.core.model.ThemePreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** V3.1 「我的」只保留外观、备份与版本信息，不再包含 AI 或学习设置。 */
class MineViewModel(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = preferences.themePreference.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemePreference.SYSTEM,
    )

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch {
            preferences.setThemePreference(preference)
        }
    }
}
