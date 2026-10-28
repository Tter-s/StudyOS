# StudyOS V3.1.0 架构说明

> 适用版本：`3.1.0`（versionCode 31） · 数据库 v10 · 包名 `com.zhixu.studyos`
> 本文以本目录中的实际代码为唯一依据，描述 V3.1.0 的完整架构。V3.0.0 及更早版本的架构文档保留在上级目录，不作为本版本的设计依据。

---

## 0. 文档定位

本文回答四个问题：

1. V3.1.0 由哪些层、哪些模块组成，边界在哪里；
2. 一次用户操作如何从 Compose 界面走到 SQLite，再回到界面；
3. 已被产品移除的旧模块以什么形式存在，为什么不能直接删；
4. 构建、签名、测试与发布如何保证这个版本可以独立演进。

配套文档：

- 模块职责与归档清单 → [`MODULES.md`](MODULES.md)
- 表结构、迁移与备份 → [`DATABASE.md`](DATABASE.md)
- 构建、签名、产物 → [`BUILD.md`](BUILD.md)
- 版本分割规则 → [`VERSION_BOUNDARY.md`](VERSION_BOUNDARY.md)

---

## 1. 架构目标与约束

V3.1.0 的架构不是“功能越多越好”，而是围绕五条硬约束做减法：

| 约束 | 架构含义 |
| --- | --- |
| **只记录，不评判** | 领域模型不包含评分、优先级、连续打卡、效率分析；待办只有日期、标题、完成状态 |
| **完全离线** | 不申请网络权限；OCR 模型随包；备份走 SAF，不上传 |
| **打开即记录** | 主路径最多两层：底栏 → 功能页 → 表单；低频能力收进二级页或菜单 |
| **旧数据不丢** | 数据库只增表、不改旧表；旧模块代码保留为归档层；备份表清单覆盖全部业务表 |
| **单模块可构建** | 一个 `:app` 模块、一套版本目录、一条 Gradle 命令即可产出 APK |

由此形成三条设计取舍：

- **不引入 Hilt / 多模块**：对象图仍可由一个 `AppContainer` 手工装配，模块拆分带来的收益小于构建复杂度。
- **不删除归档代码**：旧实体、DAO、仓库、界面保留编译，保证备份/恢复/迁移的类引用与历史数据可读；只是不挂到导航。
- **不追求通用化**：领域层只服务当前六个记录能力，不为“未来可能的需求”预留抽象。

---

## 2. 架构总览

### 2.1 分层

```text
┌──────────────────────────────────────────────────────────────┐
│ 表现层  Jetpack Compose                                      │
│  TodayScreen / RecordsScreen / FinanceHomeScreen /           │
│  ScheduleScreen / MineScreen + 五个二级页                     │
│  只做渲染与事件上抛，不直接访问 Room                          │
├──────────────────────────────────────────────────────────────┤
│ 状态层  ViewModel（AndroidX ViewModel + StateFlow）           │
│  TodayViewModel / RecordsHubViewModel / DailySummaryViewModel │
│  SleepLogViewModel / EntertainmentViewModel / HistoryViewModel│
│  FinanceViewModel / ScheduleViewModel / MineViewModel         │
├──────────────────────────────────────────────────────────────┤
│ 导航层  Navigation Compose                                   │
│  StudyOSApp（Scaffold + 底栏）→ StudyOSNavHost（路由与 VM 工厂）│
├──────────────────────────────────────────────────────────────┤
│ 领域层  纯 Kotlin，无 Android UI 依赖                        │
│  ScheduleEngine / ScheduleConflictDetector / Money /          │
│  FinanceCalculator / ScheduleOcrParser / BackupCodec …        │
├──────────────────────────────────────────────────────────────┤
│ 数据层  Repository 接口 + Offline 实现                        │
│  DailyLogRepository / FinanceRepository / ScheduleRepository  │
│  SleepRepository / BackupService / DataExportService          │
├──────────────────────────────────────────────────────────────┤
│ 持久化  Room DAO → SQLite（studyos.db）                       │
│  Preferences DataStore（主题等） / SAF（备份文件）             │
│  filesDir（题库文档、恢复日志）                                │
├──────────────────────────────────────────────────────────────┤
│ 平台层  Application / MainActivity / AppContainer / Glance     │
└──────────────────────────────────────────────────────────────┘
```

依赖方向严格向下：界面不认识 DAO，ViewModel 只认识 Repository 接口，领域算法不依赖 Compose 或 Android API。唯一的反向引用是 `AppContainer` 在装配时把具体实现注入接口。

### 2.2 技术栈

| 关注点 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.0.21，JVM Target 17 |
| UI | Compose + Material 3，单 Activity、Edge-to-Edge |
| 状态 | `ViewModel` + `StateFlow` + `collectAsStateWithLifecycle` |
| 导航 | `androidx.navigation:navigation-compose` 2.8.5 |
| 数据库 | Room 2.6.1（KSP 生成，`exportSchema = true`） |
| 偏好 | Preferences DataStore 1.1.1 |
| 后台 | WorkManager 2.10.0（仅组件兜底刷新与旧任务清理） |
| 组件 | Glance AppWidget 1.1.1 |
| 图片 / OCR | ML Kit 中文文字识别 16.0.1、ExifInterface、PDFBox-Android |
| 测试 | JUnit4、Robolectric 4.14.1、Compose UI Test、coroutines-test |

### 2.3 规模

| 指标 | 数值 |
| --- | --- |
| 主源码 Kotlin 文件 | 239 |
| 主源码行数 | 约 55,200 |
| Room 实体 | 58 |
| 备份业务表 | 55 |
| 顶级导航目的地 | 5 |
| 二级路由 | 5 |
| JVM 测试 | 496 项 / 81 个测试文件 |
| 桌面组件 | 2 |

---

## 3. 启动与依赖装配

### 3.1 进程入口

```text
Application.onCreate()
 ├─ 若 filesDir 存在 .restore_journal
 │   └─ BackupRecovery.recover(...)：按数据库哨兵判断“继续替换文件”或“回滚”
 ├─ WidgetUpdater.schedulePeriodicRefresh()：30 分钟唯一周期任务
 └─ IO 协程中清理旧版本遗留后台任务
     ├─ budgetReminderScheduler.sync(false)
     ├─ planReminderScheduler.sync(false, 7, 30)
     ├─ sleepModeScheduler.cancel()
     └─ WorkManager.cancelUniqueWork("studyos_exam_reminders")

MainActivity.onCreate()
 ├─ enableEdgeToEdge()
 ├─ 从 Intent 读取 EXTRA_DESTINATION / EXTRA_SECTION（桌面组件入口）
 └─ setContent { StudyOSApp(container, initialDestination, initialSection) }
```

`StudyOSApplication` 只做三件事：**持有容器、完成中断恢复、清理旧任务**。它不启动 AI、不加载题库、不注册通知渠道。

`MainActivity` 是单 Activity；`onStart/onStop` 触发组件刷新，`onNewIntent` 在带目标页参数时 `recreate()`，让 NavHost 使用新的起始目的地。

### 3.2 手动依赖注入

`di/AppContainer.kt` 是唯一的对象图：

```kotlin
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: StudyOSDatabase by lazy { StudyOSDatabase.build(appContext) }

    val dailyLogRepository: DailyLogRepository by lazy {
        OfflineDailyLogRepository(database.dailyLogDao(), clock)
    }
    val scheduleRepository: ScheduleRepository by lazy { … }
    val financeRepository: FinanceRepository by lazy { … }
    val sleepRepository: SleepRepository by lazy { … }
    val backupService: BackupService by lazy { AppBackupService(...) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(appContext) }

    val clock: Clock = Clock.systemDefaultZone()
    val appVersion: String = BuildConfig.VERSION_NAME
}
```

装配规则：

1. **全部 `by lazy`**：启动只创建容器本身，数据库与 OCR/PDFBox 等重对象在首次使用时初始化。
2. **接口优先**：ViewModel 构造参数声明接口（`DailyLogRepository`、`BackupService`），测试可直接替换 Fake。
3. **Clock 注入**：所有日期/时间来自 `container.clock`，测试可固定时间，避免 `LocalDate.now()` 散落。
4. **归档服务也在容器中**：`aiPlanningService`、`problemBankRepository` 等仍可装配，但没有导航入口，正常使用不会触发。

### 3.3 主题

`MineViewModel` 从 `UserPreferencesRepository` 读取 `ThemePreference`（SYSTEM / LIGHT / DARK），`StudyOSApp` 据此决定 `darkTheme`，再套 `StudyOSTheme`。主题状态是应用级唯一全局 UI 状态，其余状态都归属各自 ViewModel。

---

## 4. 导航架构

### 4.1 顶级目的地

`StudyOSDestination` 是底栏的唯一事实来源：

| 枚举 | route | 标签 | 图标 | 页面 |
| --- | --- | --- | --- | --- |
| `HOME` | `home` | 今日 | Home | `TodayScreen` |
| `SCHEDULE` | `schedule` | 课表 | CalendarMonth | `ScheduleScreen` |
| `RECORDS` | `records` | 记录 | EditNote | `RecordsScreen` |
| `FINANCE` | `finance` | 财务 | AccountBalanceWallet | `FinanceHomeScreen` |
| `PROFILE` | `profile` | 我的 | Person | `MineScreen` |

底栏点击使用：

```kotlin
popUpTo(graph.startDestinationId) { saveState = true }
launchSingleTop = true
restoreState = true
```

因此五个顶级页之间切换不会堆栈膨胀，返回时恢复各自的滚动与输入状态。

### 4.2 二级路由

`StudyOSRoutes` 定义非底栏页面：

| 常量 | 实际路由 | 参数 | 页面 |
| --- | --- | --- | --- |
| `DAY` | `day/{date}` | ISO 日期 | `DayTasksScreen`（明日/指定日待办） |
| `SUMMARY` | `records/summary?date={date}` | 可选 ISO 日期 | `DailySummaryScreen` |
| `SLEEP` | `records/sleep` | 无 | `SleepLogScreen` |
| `ENTERTAINMENT` | `records/entertainment` | 无 | `EntertainmentScreen` |
| `HISTORY` | `records/history` | 无 | `HistoryScreen` |

底栏显示规则：

```kotlin
val showBottomBar = currentRoute == null || currentRoute in topLevelRoutes
```

即只有五个顶级页显示底栏；二级页自动隐藏，使用 `DetailTopBar` 或系统返回。`RECORDS` 的选中态对 `records/*` 全部为真，进入记录子页时底栏虽隐藏，返回后仍保持“记录”高亮。

### 4.3 ViewModel 的创建方式

每个路由在 `StudyOSNavHost` 内用 `viewModelFactory { initializer { … } }` 构造 ViewModel，并注入容器依赖：

- `home`：`TodayViewModel(dailyLogRepository, clock)`
- `day/{date}`：`TodayViewModel(..., initialDate = date, followToday = false)`，`key = "day-$date"`
- `records`：`RecordsHubViewModel(dailyLogRepository, sleepRepository, clock)`
- `records/summary`：`DailySummaryViewModel(..., initialDate = date)`，`key = "summary-$date"`
- `records/sleep`：`SleepLogViewModel(sleepRepository, clock)`
- `records/entertainment`：`EntertainmentViewModel(dailyLogRepository, clock)`
- `records/history`：`HistoryViewModel(dailyLogRepository, sleepRepository)`
- `finance`：`FinanceViewModel(financeRepository, clock, documentStore, preferences, reminderScheduler, appVersion)`
- `schedule`：`ScheduleViewModel` + `ScheduleImportViewModel`
- `profile`：`MineViewModel(preferences)` + `DataBackupViewModel(backupService, exportService)`

日期参数解析失败时回退到“今天”或“明天”，不抛异常、不白屏。

### 4.4 组件入口

桌面组件点击构造 `Intent(MainActivity)` 并携带 `EXTRA_DESTINATION`：

- 今日待办 → `home`
- 下一节课 → `schedule`

`MainActivity` 把该值交给 `StudyOSApp`，NavHost 校验它是否属于五个顶级 route，合法才作为 `startDestination`。

---

## 5. 功能架构

### 5.1 今日（`feature/today`）

**职责**：当天待办的增删改查、完成进度、明日待办入口。

```text
TodayScreen
  ├─ 日期 / 完成数 / 总数 / 百分比 / 进度条
  ├─ 待办列表（勾选、点文字或铅笔编辑、弹窗删除）
  ├─ 明日待办卡片（数量 + 前两项预览）
  └─ FAB “+”（TaskEditorDialog）
        │ 事件
        ▼
TodayViewModel
  date: MutableStateFlow<LocalDate>
  uiState = date.flatMapLatest {
      combine(observeTasks(day), observeTasks(day+1),
              observeSummary(day), observeEntertainment(day), message)
  }.stateIn(WhileSubscribed(5_000))
        │
        ▼
DailyLogRepository（校验标题 1–200 字）→ DailyLogDao → daily_tasks
```

关键规则：

- `followToday = true` 时，`ON_RESUME` 调用 `refreshDate()`；跨过午夜或从后台回来会切到新的一天。明日页 `followToday = false`，日期固定。
- 未完成待办不迁移：第二天查询的是新的 `dateEpochDay`，旧记录留在原日期。
- 所有写操作经 `runAction` 包裹，失败把真实原因写入 `message`，由 Snackbar 展示；不伪造成功。
- `sortOrder = maxSortOrder(date) + 1`，新待办排在末尾。

### 5.2 记录（`feature/records`）

记录中心只做入口聚合，不承载表单：

```text
RecordsScreen（RecordsHubViewModel）
  ├─ 每日总结 → records/summary?date=today
  ├─ 睡眠记录 → records/sleep
  ├─ 娱乐时间 → records/entertainment
  └─ 历史记录 → records/history
```

| 页面 | ViewModel | 数据源 | 规则 |
| --- | --- | --- | --- |
| 每日总结 | `DailySummaryViewModel` | `daily_summaries` + 当天 `daily_tasks` | 按日期查看/补写；只读列出当天待办；自动显示完成数/总数；≤2000 字；保存后原地提示 |
| 睡眠记录 | `SleepLogViewModel` | `sleep_records` | 日期按起床当天；24 小时制入睡/起床；自动算时长；最近 7 天 + 平均 + 全部；可编辑/删除 |
| 娱乐时间 | `EntertainmentViewModel` | `entertainment_records` | 小时 + 分钟输入，折算 0–1440 分钟；可跨日期查看/修改/删除；最近记录可回填 |
| 历史记录 | `HistoryViewModel` | 待办 + 总结 + 娱乐 + 睡眠 | 合并所有有记录的日期，倒序；只显示今天及以前；点击某天回到该日总结 |

“只记录事实”的落地方式：

- 总结页不计算评分、不比较昨日、不给建议；
- 娱乐页不设上限惩罚，只校验 0–1440 分钟的物理范围；
- 历史页只汇总数量与原文，不做趋势评价。

### 5.3 财务（`feature/finance`）

V3.1 复用旧财务的数据层与记账对话框，但把首页收敛为一屏：

```text
FinanceHomeScreen（FinanceViewModel）
  ├─ 月份切换（左右箭头，YearMonth）
  ├─ 当月收入 / 支出（净支出）/ 结余
  ├─ 最近账目列表（点击编辑/删除）
  └─ “记一笔” → FinanceDialogs（支出/收入/退款、金额、分类、日期、备注）
        │
        ▼
FinanceRepository.observeSnapshot()
  = combine(categories, transactions, monthlyBudgets, categoryBudgets)
        │
        ▼
finance_categories / finance_transactions / finance_monthly_budgets / finance_category_budgets
```

- 金额全链路使用 `Long` 分，`core/finance/Money` 负责严格解析与格式化，数据库不存浮点。
- 月份、周、趋势只由用户选择的记账日期（`dateEpochDay`）推导，不依赖创建时刻。
- `FinanceViewModel` 仍保留预算、分类、提醒等方法（供归档界面与测试使用），**但 V3.1 首页不暴露预算、预测、异常分析与分类管理入口**。
- 历史预算与分类数据继续保留在库中并进入备份。

### 5.4 课表（`feature/schedule`）

课表是 V3.1 中唯一保留完整编辑能力的复杂模块：

```text
ScheduleScreen（ScheduleViewModel）
  ├─ 周课表网格（教学周、单双周、跨日课程）
  ├─ 顶部：添加课程 / 更多菜单
  │    └─ 学期设置、一次性日程、作息、调整记录、图片导入
  └─ 课程点击：查看 / 编辑 / 删除 / 停课 / 调课

ScheduleImportViewModel + ScheduleImportDialog
  └─ 选图 → 裁剪/旋转 → OCR → 预览表格 → 编辑确认 → 原子导入
```

领域计算全部在 `core/schedule`：

| 文件 | 职责 |
| --- | --- |
| `ScheduleEngine.kt` | `SemesterCalendar` 教学周换算；`ScheduleEngine.daySchedule()` 把学期、课程规则、日期例外、一次性日程、作息展开为某天的可见片段 |
| `ScheduleConflictDetector.kt` | 课程实例级冲突检测 |
| `ScheduleDateTimes.kt` | 日期/分钟/时区边界换算 |
| `FreeTimeCalculator.kt` | 区间合并与空闲补集 |
| `ScheduleValidation.kt` | 字段与时间范围校验 |

导入链路：

```text
ScheduleImageLoader（ContentResolver 读取、EXIF 旋转、采样、归一化裁剪）
  → MlKitScheduleImportService（本地中文 OCR）
  → ScheduleOcrParser（纯 Kotlin 解析表头/节次/课程）
  → ScheduleImportValidation（字段校验）
  → ScheduleImportAnalyzer（周次/单双周/跨日查重与冲突）
  → ScheduleRepository.importCourses（单事务原子写入，任一失败整批回滚）
```

### 5.5 我的（`feature/profile`）

```text
MineScreen
  ├─ 外观：跟随系统 / 浅色 / 深色（DataStore 持久化）
  ├─ 数据备份与导出：完整 ZIP 备份 / 恢复 / CSV 导出
  └─ 关于：版本号、本地优先说明
```

V3.1 已移除 AI 设置、首页卡片排序、睡眠作息保护、数据与同步、彻底删除等入口；对应服务与表仍在，只是不再暴露。

---

## 6. 数据架构

### 6.1 数据库

`StudyOSDatabase` 注册 **58 个实体**，版本 **10**，导出 schema 到 `app/schemas/`：

```kotlin
@Database(entities = [ … 58 … ], version = 10, exportSchema = true)
abstract class StudyOSDatabase : RoomDatabase() {
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun financeDao(): FinanceDao
    abstract fun sleepDao(): SleepDao
    // … 其余归档 DAO
}
```

构建时注册 `MIGRATION_1_2 … MIGRATION_9_10`，并在 `onCreate` 植入预设科目与财务分类、安装旧版同步触发器。

### 6.2 V3.1 活跃读写面

新界面只读写以下表：

| 表 | 主键 | 用途 |
| --- | --- | --- |
| `daily_tasks` | 自增 `id` | 每日待办；`dateEpochDay` 索引 |
| `daily_summaries` | `dateEpochDay` | 每天一条总结 |
| `entertainment_records` | `dateEpochDay` | 每天一条娱乐分钟数 |
| `sleep_records` | 日期 | 睡眠记录（沿用旧表） |
| `finance_transactions` / `finance_categories` | id | 记账与分类 |
| `courses` / `course_exceptions` / `one_off_events` / `routine_blocks` / `semester_settings` | id | 课表与作息 |

其余 47 张表为归档数据，不在 V3.1 导航中读写，但继续参与备份与恢复。

### 6.3 迁移 9 → 10

`MigrationsV9To10.kt` 只做三件事：

1. 建 `daily_tasks`（含 `dateEpochDay` 索引）、`daily_summaries`、`entertainment_records`；
2. 调用 `DailyLogBackfill.run(db)`，从 `study_tasks` 复制仍能确定日期的任务；
3. 不删除、不改写任何旧表。

回填规则：

```sql
date = COALESCE(dueDateEpochDay, MIN(task_sessions.dateEpochDay))
```

保留标题、完成状态/时间、排序与创建/更新时间。同一天多个旧任务保留为多条待办；没有日期的任务不进入新表，但仍留在旧表。

恢复旧备份（`databaseVersion < 10`）时，`BackupRestorer` 在同一个恢复事务内调用同一段回填 SQL，避免“旧任务恢复了但新界面看不到”。

### 6.4 备份

`core/backup` 的门面是 `BackupService`：

```kotlin
interface BackupService {
    suspend fun currentDataSummary(): CurrentDataSummary
    suspend fun createBackup(uri: Uri): BackupSummary
    suspend fun inspectBackup(uri: Uri): BackupPreview
    suspend fun restoreBackup(uri: Uri): RestoreResult
}
```

- **格式版本**：`BACKUP_FORMAT_VERSION = 1`。
- **表清单**：`BackupTables.ordered` 共 **55 张**，父表在前，恢复正序插入、逆序删除；V3.1 新增三张表追加在末尾。
- **排除**：`ai_usage_daily`、`ai_analysis_cache`、`ai_plan_records`；AI 配置在 DataStore、API Key 在 Keystore，均不入普通备份。
- **文件**：ZIP 同时包含数据库快照与 `filesDir/problem_bank_documents/` 下的题库原始文件。
- **恢复安全**：先只读 `inspect` 校验并给出预览，用户二次确认后才进入事务；`.restore_journal` + 数据库哨兵保证“提交后替换文件”或“回滚”二选一，启动时自动收尾。

### 6.5 其他持久化

| 存储 | 内容 | 访问方式 |
| --- | --- | --- |
| Preferences DataStore | 主题、旧版提醒开关等偏好 | `UserPreferencesRepository` |
| SAF / ContentResolver | 用户选择的备份 ZIP、CSV 导出 | `AppFileStore` / `FinanceDocumentStore` |
| `filesDir` | 题库文档、恢复日志 | 服务内部 |

不申请存储权限，所有用户文件通过系统文件选择器读写。

---

## 7. 领域层

领域层是“可单测的纯逻辑”，不依赖 Compose、Context 或 Room：

| 包 | 关键对象 | 说明 |
| --- | --- | --- |
| `core/schedule` | `ScheduleEngine`、`SemesterCalendar`、`ScheduleConflictDetector`、`ScheduleDateTimes`、`FreeTimeCalculator`、`ScheduleValidation` | 教学周、重复展开、跨日裁剪、冲突、空闲补集；时间区间“含开始不含结束”，结束分钟可 >1440 |
| `core/finance` | `Money`、`FinanceCalculator`、`FinanceCsv`、`FinanceSearch`、`FinanceBackup` | 整数分金额、月度汇总、CSV、搜索；金额解析失败即拒绝 |
| `core/sleep` | `SleepRecord`、`formatSleepMinute`、`SleepProtectionEngine` | 睡眠时长与格式化；作息保护引擎保留但 V3.1 不启用 |
| `core/importer` | `ScheduleOcrParser`、`ScheduleImportValidation`、`ScheduleImportAnalyzer`、`ScheduleImportFormat` | OCR 文本 → 课程草稿 → 校验/查重；不写库 |
| `core/backup` | `BackupCodec`、`BackupArchive`、`CsvExport` | 通用 SQL 快照、列兼容校验、ZIP 读写 |
| `core/model` | `DailyTask`、`DailySummary`、`EntertainmentRecord`、`DayRecord`、`FinanceTransaction`、`Course` … | 领域模型与输入上限常量 |

输入上限集中定义在 `core/model/DailyLogModels.kt`：

```kotlin
const val MAX_DAILY_TASK_TITLE_LENGTH = 200
const val MAX_DAILY_SUMMARY_LENGTH = 2_000
const val MAX_ENTERTAINMENT_MINUTES = 24 * 60
```

仓库层与界面共用同一组常量，避免“UI 限制 200、仓库允许 500”的双口径。

---

## 8. 归档兼容层

### 8.1 为什么保留

V3.1 从界面移除了 AI 规划、学习计时、题库、自动排程、考试/目标、词汇/知识图谱、周报等模块，但**历史数据仍在数据库中**。如果直接删除实体与仓库：

- Room 会因 schema 变化要求破坏性迁移；
- 旧备份恢复会因缺表/缺类失败；
- 用户升级后历史数据无法再被读取或导出。

因此采用“**编译保留、导航摘除、备份覆盖**”的归档策略。

### 8.2 归档清单

| 归档模块 | 代码位置 | 数据表 | V3.1 状态 |
| --- | --- | --- | --- |
| AI 规划 / 统一 AI 服务 | `core/ai`、`feature/ai` | `ai_*` | 无入口；服务仍可装配，启动不调用 |
| 学习计时 / 效率分析 | `core/learning`、`feature/study` | `study_timing_sessions`、`self_control_entries`、`learning_goals` | 无入口；仅归档 |
| 任务科目 / 排程 | `core/scheduling`、`core/task` | `study_tasks`、`task_sessions`、`scheduling_*` | 旧任务回填为待办；旧表保留 |
| 题库 / 刷题 | `core/bank`、`feature/bank` | `problem_*` | 无入口；文件与表保留 |
| 考试 / 长期目标 | `core/exam`、`feature/exam` | `exams`、`long_term_goals`、`goal_milestones` | 无入口；提醒取消 |
| 词汇 / 知识图谱 / 数学 | `core/vocabulary`、`core/knowledge`、`core/math` | `vocabulary_*`、`knowledge_*`、`exercise_*` | 无入口；表保留 |
| 周报 / 成长 | `core/report`、`core/growth` | `weekly_reports`、`ai_analysis_records` | 无入口；表保留 |
| 预算 / 预测 | `core/finance` 中的预测与预算部分 | `finance_monthly_budgets`、`finance_category_budgets` | 数据保留；界面只展示收支 |
| 睡眠保护 / 同步 / 卡片配置 | `core/sleep`、`core/sync` | `sleep_schedule`、`sync_*`、`home_card_preferences` | 无入口；表保留 |

### 8.3 归档层的三条纪律

1. **导航不得引用**：`StudyOSNavHost` 只 import 活跃 feature；归档界面没有 route。
2. **不得静默删除数据**：任何清理必须走用户确认的“彻底删除”流程（该入口在 V3.1 也已隐藏）。
3. **备份必须覆盖**：新增活跃表要同时加入 `BackupTables.ordered`；归档表不得从清单移除。

---

## 9. 横切规则

### 9.1 时间与时区

- 统一使用 `java.time.Clock`、`LocalDate`、`LocalTime`、明确 `ZoneId`。
- 数据库保存 `dateEpochDay`（当地自然日），不把 epoch day 当时间戳。
- 课表使用学期配置的 IANA 时区；未设置学期时回退设备时区。
- 课程/作息/任务区间“含开始、不含结束”，结束分钟可超过 1440 表示跨日。
- 睡眠日期按“起床当天”解释；时长不在 1 分钟–16 小时之间拒绝保存。
- 历史列表只展示今天及以前；未来待办属于计划，不算已发生记录。

### 9.2 金额

- 全链路 `Long` 分，禁止浮点入库。
- `Money` 负责解析、格式化、四舍五入规则；图表只为几何尺寸使用浮点。
- 退款、净支出、结余都由整数运算得出。

### 9.3 离线与权限

- Manifest 显式移除 `INTERNET`；不申请 `POST_NOTIFICATIONS`。
- 保留的合并权限仅 WorkManager 所需：`WAKE_LOCK`、`ACCESS_NETWORK_STATE`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`。
- OCR 模型随 APK 打包，识别全程本地。
- 文件读写走 SAF，不申请存储权限。

### 9.4 状态与错误

| 场景 | 规则 |
| --- | --- |
| 加载中 | 显示真实 `isLoading` / 进度，不用空数据冒充 |
| 空数据 | 显示“还没有待办/记录”，不填 0 元、0 小时等易误解值 |
| 写入失败 | 返回真实原因（`IllegalArgumentException` 消息），Snackbar 提示；不伪造成功 |
| 日期参数非法 | 回退到今天/明天，不崩溃 |
| 备份恢复 | 先校验、再二次确认、事务回滚；失败不产生半恢复状态 |
| 课表导入 | 草稿只在内存；校验/冲突行不可选；整批事务，任一失败不写入 |

### 9.5 状态保存

- 顶级页切换使用 `saveState/restoreState`，返回保留滚动与输入。
- 二级页使用独立 ViewModel key（`day-$date`、`summary-$date`），同一路由不同日期互不污染。
- 输入中的文本属于 Compose 本地状态或 ViewModel `UiState`；提交成功后以 Room Flow 为准。

---

## 10. 桌面组件

`widget/StudyOSWidgets.kt` 定义两个 Glance 组件，均直接读 Room，不走网络：

| 组件 | 数据 | 展示 | 点击 |
| --- | --- | --- | --- |
| 今日待办 `TodayTasksWidget` | `dailyLogRepository.observeTasks(today)` | 完成数/总数、百分比、下一项 | `home` |
| 下一节课 `NextClassWidget` | `scheduleRepository.observeSnapshot()` + `ScheduleEngine.daySchedule()` | 课程名、教室、时间段；无课显示空状态 | `schedule` |

- 响应式尺寸：160×100、240×100、240×180，紧凑模式降低字号。
- 刷新：Activity `onStart/onStop` 主动刷新 + `studyos_widget_refresh` 每 30 分钟兜底；无高频轮询。
- 组件读取使用 `Flow.first()`，在 `Dispatchers.IO` 执行。

---

## 11. 构建架构

### 11.1 工程形态

单模块 Gradle 工程：

```text
settings.gradle.kts   rootProject.name = "StudyOS"; include(":app")
build.gradle.kts      四个插件 alias，apply false
gradle/libs.versions.toml  唯一版本目录
app/build.gradle.kts  android 配置、KSP schema、依赖
```

- `namespace` / `applicationId`：`com.zhixu.studyos`
- `compileSdk` / `targetSdk`：35；`minSdk`：26
- `versionCode`：31；`versionName`：`3.1.0`
- `buildFeatures`：Compose + BuildConfig
- `testOptions.unitTests.isIncludeAndroidResources = true`（Robolectric 需要）
- `packaging.resources.excludes`：`/META-INF/{AL2.0,LGPL2.1}`
- Release `isMinifyEnabled = false`，保留可读堆栈，便于个人项目排查

### 11.2 签名

`app/build.gradle.kts` 在根目录存在 `keystore.properties` 时创建 release 签名：

```kotlin
storeFile = rootProject.file(properties.getProperty("storeFile"))  // .signing/studyos-release.jks
enableV3Signing = true
```

缺失时仍可构建 debug 与未签名 release，不阻塞日常开发。

### 11.3 产物

| 产物 | 路径 |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| 归档 Debug | `outputs/StudyOS-V3.1.0-debug.apk`（78,274,101 字节） |
| 归档 Release | `outputs/StudyOS-V3.1.0-release.apk`（68,949,000 字节） |
| 源码归档 | `outputs/StudyOS-V3.1.0-source.zip` |

---

## 12. 测试架构

### 12.1 规模与分布

- 81 个测试文件，**496 个 `@Test`**，全部位于 `app/src/test`（JVM / Robolectric）；无 `androidTest` 源码目录。
- 覆盖面最大的区域：`core/data/repository`（11 个文件）、`core/bank`（8）、`core/finance`（7）、`core/task`（6）、`core/scheduling`（4）、`core/backup`（4）、`core/ai`（4）。

### 12.2 测试分层

| 类型 | 做法 | 代表 |
| --- | --- | --- |
| 纯 JVM 单元测试 | 领域算法、校验、状态机，无 Android 依赖 | `ScheduleEngineTest`、`MoneyTest`、`DailyLogRepositoryTest` |
| Room / Robolectric | 内存数据库、DAO、迁移 `1/2/6 → 10`、事务回滚 | `StudyOSDatabaseTest`、`MigrationTest` |
| Compose / Navigation | `createComposeRule` 冒烟：五个底栏、记录中心四入口、保存总结、财务对话框 | `StudyOSNavigationTest` |
| 稳定性 | 大输入、异常、边界 | `StabilitySmokeTest` |

### 12.3 质量门禁

V3.1.0 发布时的验证结果：

```text
:app:testDebugUnitTest   496 tests, 0 failed, 0 skipped
:app:assembleDebug       success
:app:assembleRelease     success
:app:lintDebug           0 errors, 38 warnings
apksigner verify         v2=true, v3=true
```

`lint` 的 38 条 warning 主要来自归档模块的未使用资源与依赖版本提示，不影响主路径。

---

## 13. 架构决策记录（V3.1）

| 编号 | 决策 | 理由 | 代价 |
| --- | --- | --- | --- |
| ADR-31-01 | 新增三张独立表，不复用 `study_tasks` | 旧表带科目/优先级/排程语义，复用会迫使新界面继承旧约束 | 需要一次迁移与回填 |
| ADR-31-02 | 旧模块代码保留、导航摘除 | 保证 Room schema、旧备份恢复与历史数据可读 | 代码体积与 lint 噪音 |
| ADR-31-03 | 不引入 Hilt / 多模块 | 单模块 + 手写 DI 足够，构建更快、调试更直接 | 依赖多时需手动维护容器 |
| ADR-31-04 | 记录中心只做入口，表单放二级页 | 避免单页堆叠所有表单，符合“打开即记录” | 多一次导航 |
| ADR-31-05 | 财务复用旧数据层与对话框 | 记账逻辑成熟，重写风险高 | 首页与归档代码共存 |
| ADR-31-06 | 金额继续用整数分 | 避免浮点误差与迁移 | 展示层需格式化 |
| ADR-31-07 | 备份表清单显式枚举 | 可控、可审计，避免反射漏表 | 新增表必须手动登记 |
| ADR-31-08 | 组件直接读 Room，不做额外缓存 | 数据量小，读取简单，避免一致性问题 | 每次刷新有查询开销 |

---

## 14. 目录树（活跃主路径）

```text
app/src/main/java/com/zhixu/studyos/
├─ MainActivity.kt
├─ StudyOSApplication.kt
├─ di/
│  └─ AppContainer.kt
├─ navigation/
│  ├─ StudyOSApp.kt
│  ├─ StudyOSDestination.kt
│  └─ StudyOSNavHost.kt
├─ core/
│  ├─ data/
│  │  ├─ local/
│  │  │  ├─ StudyOSDatabase.kt
│  │  │  ├─ DailyLogEntities.kt
│  │  │  ├─ DailyLogDao.kt
│  │  │  ├─ MigrationsV9To10.kt
│  │  │  ├─ DailyLogBackfill.kt
│  │  │  └─ …（归档实体/DAO）
│  │  ├─ repository/
│  │  │  ├─ DailyLogRepository.kt
│  │  │  ├─ FinanceRepository.kt
│  │  │  ├─ ScheduleRepository.kt
│  │  │  ├─ SleepRepository.kt
│  │  │  └─ …（归档仓库）
│  │  ├─ preferences/
│  │  └─ export/
│  ├─ model/
│  │  ├─ DailyLogModels.kt
│  │  ├─ ScheduleModels.kt
│  │  ├─ FinanceModels.kt
│  │  └─ …
│  ├─ designsystem/
│  │  ├─ theme/（Color/Spacing/Theme/Type）
│  │  └─ component/（AppCard/Buttons/EmptyState/PageChrome…）
│  ├─ schedule/
│  ├─ finance/
│  ├─ sleep/
│  ├─ importer/
│  ├─ backup/
│  └─ …（归档领域包）
├─ feature/
│  ├─ today/（TodayScreen、TodayViewModel、TaskEditorDialog）
│  ├─ records/（Hub、总结、睡眠、娱乐、历史）
│  ├─ finance/（FinanceHomeScreen、FinanceViewModel、FinanceDialogs、FinanceFormat）
│  ├─ schedule/（课表、导入、网格、对话框）
│  ├─ profile/（MineScreen、MineViewModel）
│  ├─ backup/（DataBackupDialog、DataBackupViewModel）
│  └─ …（归档界面）
└─ widget/
   └─ StudyOSWidgets.kt
```

---

## 15. 演进规则

后续在 V3.1.x 上迭代时，遵守以下规则即可保持架构不腐化：

1. **新功能先问“是否属于六项记录能力”**；不属于就不进主界面。
2. **新表必须同时更新**：`StudyOSDatabase.entities`、`@Database(version)`、迁移文件、`BackupTables.ordered`、`app/schemas`。
3. **迁移只增不改**：不删除旧表、不改写旧列；需要变更时新增表或新增可空列。
4. **归档模块只读不写**：不得让活跃界面依赖归档仓库；确需读取历史时，走单独的只读查询。
5. **新界面不得直接持有 DAO**：一律经 Repository 接口，便于测试与后续替换。
6. **时间与金额只走统一入口**：`Clock` 注入、`Money` 计算，禁止散落 `System.currentTimeMillis()` 与 `Double` 金额。
7. **每个版本更新 `README` 的版本事实表与 `docs/CHANGELOG.md`**，并在 `docs/VERSION_BOUNDARY.md` 记录是否仍与旧版本隔离。

---

## 16. 已知限制

- 未连接真机/模拟器：组件、通知取消、图片导入、真实升级安装与视觉未做物理设备验收。
- 睡眠为手动填写；日期按起床当天，跨时区旅行时需用户自行校正。
- 旧任务回填只取一个日期，多个学习时段合并为一条待办；旧表仍保留完整时段。
- 历史记录按天聚合，不限制最近 30 天；数据量很大时依赖 `LazyColumn` 按需渲染。
- 备份恢复会替换全部业务表；恢复前必须自行创建备份（界面有提示与二次确认）。
- 归档模块仍参与编译与 lint，未来若确认永不再用，应单独设计一次“归档数据导出后删表”的版本，而不是直接删代码。
