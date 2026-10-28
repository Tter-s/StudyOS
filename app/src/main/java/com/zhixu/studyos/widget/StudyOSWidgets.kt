package com.zhixu.studyos.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment as GlanceAlignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zhixu.studyos.MainActivity
import com.zhixu.studyos.StudyOSApplication
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.schedule.ScheduleEngine
import com.zhixu.studyos.core.sleep.formatSleepMinute
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V3.1 只保留两个桌面小组件：
 * - 今日待办：完成进度与下一项；
 * - 下一节课：课程、教室和开始时间。
 *
 * 组件直接读取本地 Room 数据，不发网络请求，也不使用已归档的学习计时模块。
 */

private object WidgetSize {
    val Modes = SizeMode.Responsive(
        setOf(DpSize(160.dp, 100.dp), DpSize(240.dp, 100.dp), DpSize(240.dp, 180.dp)),
    )
}

private fun app(context: Context): StudyOSApplication =
    context.applicationContext as StudyOSApplication

private fun openIntent(context: Context, destination: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_DESTINATION, destination)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

data class TodayTasksWidgetData(
    val total: Int,
    val completed: Int,
    val nextTitle: String?,
)

data class NextClassWidgetData(
    val title: String,
    val detail: String,
    val time: String,
)

private suspend fun loadTodayTasks(context: Context): TodayTasksWidgetData = withContext(Dispatchers.IO) {
    val container = app(context).container
    val today = LocalDate.now()
    val tasks = container.dailyLogRepository.observeTasks(today).first()
    TodayTasksWidgetData(
        total = tasks.size,
        completed = tasks.count { it.isCompleted },
        nextTitle = tasks.firstOrNull { !it.isCompleted }?.title,
    )
}

private suspend fun loadNextClass(context: Context): NextClassWidgetData? = withContext(Dispatchers.IO) {
    val container = app(context).container
    val snapshot = container.scheduleRepository.observeSnapshot().first()
    val today = LocalDate.now()
    val zone = snapshot.semester?.zoneId ?: ZoneId.systemDefault()
    val day = ScheduleEngine.daySchedule(snapshot, today, zone)
    val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
    val next = day.items.firstOrNull {
        it.instance.type == ScheduleItemType.COURSE && it.segmentEndMinuteExclusive > nowMinute
    }
    next?.let {
        NextClassWidgetData(
            title = it.instance.title,
            detail = it.instance.subtitle,
            time = "${formatSleepMinute(it.segmentStartMinute)}–" +
                formatSleepMinute(it.segmentEndMinuteExclusive),
        )
    }
}

@Composable
private fun color(): ColorProvider = GlanceTheme.colors.onSurface

class TodayTasksWidget : GlanceAppWidget() {
    override val sizeMode = WidgetSize.Modes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadTodayTasks(context)
        provideContent {
            GlanceTheme {
                val compact = LocalSize.current.width < 200.dp
                WidgetScaffold(
                    title = "今日待办",
                    clickIntent = openIntent(context, "home"),
                ) {
                    Text(
                        text = if (data.total == 0) "今天还没有待办" else "${data.completed} / ${data.total}",
                        style = TextStyle(
                            fontSize = if (compact) 22.sp else 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.primary,
                        ),
                    )
                    if (data.total > 0) {
                        Text(
                            text = "完成 ${data.completed * 100 / data.total}%",
                            style = TextStyle(fontSize = 11.sp, color = color()),
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = data.nextTitle?.let { "下一项：$it" } ?: "点击添加待办",
                        style = TextStyle(fontSize = 11.sp, color = color()),
                        maxLines = if (compact) 2 else 1,
                    )
                }
            }
        }
    }
}

class NextClassWidget : GlanceAppWidget() {
    override val sizeMode = WidgetSize.Modes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadNextClass(context)
        provideContent {
            GlanceTheme {
                WidgetScaffold(
                    title = "下一节课",
                    clickIntent = openIntent(context, "schedule"),
                ) {
                    if (data == null) {
                        Text(
                            "今天没有课程",
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = color()),
                        )
                        Text(
                            "点击查看课表",
                            style = TextStyle(fontSize = 11.sp, color = color()),
                        )
                    } else {
                        Text(
                            data.title,
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color()),
                            maxLines = 2,
                        )
                        Text(
                            listOf(data.detail, data.time).filter(String::isNotBlank).joinToString(" · "),
                            style = TextStyle(fontSize = 11.sp, color = color()),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetScaffold(
    title: String,
    clickIntent: Intent,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
            .clickable(actionStartActivity(clickIntent)),
        verticalAlignment = GlanceAlignment.Top,
        horizontalAlignment = GlanceAlignment.Start,
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
        )
        Spacer(GlanceModifier.height(4.dp))
        content()
    }
}

class TodayTasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayTasksWidget()
}

class NextClassWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()
}

object WidgetUpdater {
    val widgets: List<GlanceAppWidget> = listOf(
        TodayTasksWidget(),
        NextClassWidget(),
    )

    suspend fun updateAll(context: Context) {
        widgets.forEach { widget ->
            runCatching { widget.updateAll(context) }
        }
    }

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            updateAll(appContext)
        }
    }

    fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "studyos_widget_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}
