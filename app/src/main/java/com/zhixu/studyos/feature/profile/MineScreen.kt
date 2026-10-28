package com.zhixu.studyos.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.BuildConfig
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.PageTitle
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.model.ThemePreference
import com.zhixu.studyos.feature.backup.DataBackupDialog
import com.zhixu.studyos.feature.backup.DataBackupViewModel

@Composable
fun MineScreen(
    viewModel: MineViewModel,
    backupViewModel: DataBackupViewModel,
) {
    val themePreference by viewModel.themePreference.collectAsStateWithLifecycle()
    var showBackup by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)
            .testTag("screen_profile"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageTitle(
            title = "我的",
            subtitle = "知序 StudyOS · V${BuildConfig.VERSION_NAME}",
        )

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = "外观", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "跟随系统，或固定浅色/深色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeChip(
                    label = "跟随系统",
                    selected = themePreference == ThemePreference.SYSTEM,
                    onClick = { viewModel.setThemePreference(ThemePreference.SYSTEM) },
                    testTag = "theme_system",
                )
                ThemeChip(
                    label = "浅色",
                    selected = themePreference == ThemePreference.LIGHT,
                    onClick = { viewModel.setThemePreference(ThemePreference.LIGHT) },
                    testTag = "theme_light",
                )
                ThemeChip(
                    label = "深色",
                    selected = themePreference == ThemePreference.DARK,
                    onClick = { viewModel.setThemePreference(ThemePreference.DARK) },
                    testTag = "theme_dark",
                )
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = "数据备份与导出", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "完整备份包含现有记录与旧版本归档数据；也可以从备份恢复，或导出 CSV。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            SecondaryButton(
                text = "打开备份与导出",
                onClick = { showBackup = true },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("open_backup"),
            )
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = "关于", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "这是一个本地优先的个人生活记录工具：不需要账号，数据保存在设备上，" +
                    "不评价你的一天，也不替你安排生活。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "打开就能记录，记录完就能离开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showBackup) {
        DataBackupDialog(
            viewModel = backupViewModel,
            onDismiss = { showBackup = false },
        )
    }
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(testTag),
    )
}
