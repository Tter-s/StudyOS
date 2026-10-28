package com.zhixu.studyos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zhixu.studyos.navigation.StudyOSApp
import com.zhixu.studyos.widget.WidgetUpdater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StudyOSApplication).container
        setContent {
            StudyOSApp(
                container = container,
                initialDestination = intent.getStringExtra(EXTRA_DESTINATION),
                initialSection = intent.getStringExtra(EXTRA_SECTION),
            )
        }
    }

    /**
     * 桌面小组件点击会带来新的目标页面。当前 Activity 已存在时重建一次，
     * 让 NavHost 使用新的起始目的地；所有状态仍来自本地数据库。
     */
    override fun onStart() {
        super.onStart()
        WidgetUpdater.requestUpdate(this)
    }

    override fun onStop() {
        super.onStop()
        WidgetUpdater.requestUpdate(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_DESTINATION) || intent.hasExtra(EXTRA_SECTION)) {
            recreate()
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "com.zhixu.studyos.extra.DESTINATION"
        const val EXTRA_SECTION = "com.zhixu.studyos.extra.SECTION"
    }
}
