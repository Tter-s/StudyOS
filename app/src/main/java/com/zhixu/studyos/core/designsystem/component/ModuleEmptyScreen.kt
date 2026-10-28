package com.zhixu.studyos.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun ModuleEmptyScreen(
    title: String,
    emptyTitle: String,
    emptyMessage: String,
    icon: ImageVector,
    testTag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        AppCard(modifier = Modifier.fillMaxWidth()) {
            EmptyState(
                title = emptyTitle,
                message = emptyMessage,
                icon = icon,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = "V0.1 已完成页面、主题与导航基础。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
