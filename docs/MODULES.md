# V3.1.0 模块清单

> 本文回答“哪些代码是活跃的、哪些只是归档、它们各自负责什么”。
> 判定标准不是文件是否存在，而是**是否被 `navigation/StudyOSNavHost.kt` 或活跃 ViewModel 引用**。

---

## 1. 模块总览

```text
app
└─ com.zhixu.studyos
   ├─ di                 依赖容器（活跃）
   ├─ navigation         五项底栏与路由（活跃）
   ├─ widget             两个 Glance 组件（活跃）
   ├─ core
   │  ├─ data            持久化与仓库（活跃 + 归档实体/DAO）
   │  ├─ model           领域模型（活跃 + 归档模型）
   │  ├─ designsystem    Compose 设计系统（活跃）
   │  ├─ schedule        课表算法（活跃）
   │  ├─ finance         财务算法（活跃）
   │  ├─ sleep           睡眠模型与格式化（部分活跃）
   │  ├─ importer        课表图片导入（活跃）
   │  ├─ backup          备份 / 恢复 / CSV（活跃）
   │  └─ ai / bank / learning / scheduling / exam / vocabulary /
   │     knowledge / math / exercise / report / growth / sync /
   │     ownership / reminder / history / task   归档兼容层
   └─ feature
      ├─ today           今日 / 指定日待办（活跃）
      ├─ records         记录中心与四个二级页（活跃）
      ├─ finance         简化财务首页（活跃）
      ├─ schedule        课表与图片导入（活跃）
      ├─ profile         主题 / 备份 / 版本（活跃）
      ├─ backup          备份对话框（活跃）
      └─ ai / bank / study / exam / vocabulary / math / report /
         sleep / data / home   归档界面（无导航）
```

---

## 2. 活跃模块

### 2.1 平台与装配

| 文件 | 职责 | 关键约束 |
| --- | --- | --- |
| `MainActivity.kt` | 单 Activity、Edge-to-Edge、解析组件入口参数 | 不含业务逻辑 |
| `StudyOSApplication.kt` | 持有容器、恢复中断的备份、取消旧 Worker | 启动不触发 AI / OCR |
| `di/AppContainer.kt` | 手写懒加载 DI | 全部 `by lazy`，接口注入 |
| `navigation/StudyOSApp.kt` | 主题、Scaffold、底栏、路由容器 | 底栏只在五个顶级页显示 |
| `navigation/StudyOSDestination.kt` | 五个目的地枚举 | 底栏唯一事实来源 |
| `navigation/StudyOSNavHost.kt` | 路由表与 ViewModel 工厂 | 只 import 活跃 feature |
| `widget/StudyOSWidgets.kt` | 今日待办、下一节课组件 | 直接读 Room，不发网络请求 |

### 2.2 表现层

| 模块 | 文件数 | 关键文件 | 职责 |
| --- | --- | --- | --- |
| `feature/today` | 3 | `TodayScreen.kt`、`TodayViewModel.kt`、`TaskEditorDialog.kt` | 今日进度、待办勾选/编辑/删除、明日入口 |
| `feature/records` | 6 | `RecordsScreen.kt`、`RecordsViewModels.kt`、`DailySummaryScreen.kt`、`SleepLogScreen.kt`、`EntertainmentScreen.kt`、`HistoryScreen.kt` | 记录中心四个入口与对应表单/历史 |
| `feature/finance` | 4（活跃） | `FinanceHomeScreen.kt`、`FinanceViewModel.kt`、`FinanceDialogs.kt`、`FinanceFormat.kt` | 月度收支、最近账目、记一笔 |
| `feature/schedule` | 10 | `ScheduleScreen.kt`、`ScheduleViewModel.kt`、`ScheduleGrid.kt`、`ScheduleDialogs.kt`、`ScheduleImport*.kt` | 周课表、学期、调停课、一次性日程、作息、图片导入 |
| `feature/profile` | 2 | `MineScreen.kt`、`MineViewModel.kt` | 主题、备份入口、版本信息 |
| `feature/backup` | 2 | `DataBackupDialog.kt`、`DataBackupViewModel.kt` | ZIP 备份/恢复/CSV 导出的 UI 状态 |

> `feature/finance` 目录下另有 `FinanceAnalyticsTab.kt`、`FinanceCategoriesTab.kt`、`FinanceForecastCards.kt`、`FinanceRecordsTab.kt`、`FinanceScreen.kt`，属于旧财务界面，V3.1 不挂导航；`FinanceHomeScreen` 只复用 `FinanceDialogs` 与 `FinanceFormat`。

### 2.3 领域层

| 包 | 文件数 | 关键对象 | 是否纯 Kotlin |
| --- | --- | --- | --- |
| `core/schedule` | 5 | `ScheduleEngine`、`SemesterCalendar`、`ScheduleConflictDetector`、`ScheduleDateTimes`、`FreeTimeCalculator`、`ScheduleValidation` | 是 |
| `core/finance` | 7 | `Money`、`FinanceCalculator`、`FinanceCsv`、`FinanceSearch`、`FinanceBackup`、`FinanceForecast`、`BudgetReminderPolicy` | 是（JSON 用 `org.json`） |
| `core/sleep` | 2 | `SleepRecord`、`formatSleepMinute`、`SleepProtectionEngine` | 是 |
| `core/importer` | 7 | `ScheduleOcrParser`、`ScheduleImportValidation`、`ScheduleImportAnalyzer`、`ScheduleImportFormat`、`ScheduleImageLoader`、`MlKitScheduleImportService` | 解析纯 Kotlin；图片/OCR 在边界依赖 Android |
| `core/backup` | 6 | `BackupService`、`BackupCodec`、`BackupArchive`、`CsvExport`、`DataExportService` | 编解码纯 Kotlin；文件走 SAF |
| `core/model` | 10 | `DailyTask`、`DailySummary`、`EntertainmentRecord`、`DayRecord`、`ThemePreference`、`ScheduleModels`、`FinanceModels` … | 是 |

### 2.4 数据层

| 文件 | 职责 |
| --- | --- |
| `core/data/local/StudyOSDatabase.kt` | 58 实体、v10、迁移注册、种子数据 |
| `core/data/local/DailyLogEntities.kt` | `daily_tasks` / `daily_summaries` / `entertainment_records` |
| `core/data/local/DailyLogDao.kt` | 三张新表的查询与写入 |
| `core/data/local/MigrationsV9To10.kt` | 非破坏性迁移 |
| `core/data/local/DailyLogBackfill.kt` | 旧任务 → 待办的回填 SQL |
| `core/data/repository/DailyLogRepository.kt` | 待办/总结/娱乐的接口与离线实现、输入校验、历史聚合 |
| `core/data/repository/FinanceRepository.kt` | 财务快照、记账、分类、预算 |
| `core/data/repository/ScheduleRepository.kt` | 课表快照、课程 CRUD、原子导入 |
| `core/data/repository/SleepRepository.kt` | 睡眠记录、作息设置 |
| `core/data/preferences/UserPreferencesRepository.kt` | 主题等偏好 |
| `core/data/export/AppFileStore.kt` | SAF 读写 |

### 2.5 设计系统

| 文件 | 内容 |
| --- | --- |
| `core/designsystem/theme/Color.kt` | 清爽蓝色主色与浅/深色配色 |
| `core/designsystem/theme/Theme.kt` | `StudyOSTheme` |
| `core/designsystem/theme/Type.kt` | 字体层级 |
| `core/designsystem/theme/Spacing.kt` | 统一间距 |
| `core/designsystem/component/AppCard.kt` | 卡片容器 |
| `core/designsystem/component/Buttons.kt` | `PrimaryButton` / `SecondaryButton` |
| `core/designsystem/component/PageChrome.kt` | `PageTitle` / `DetailTopBar` / `DateNavigator` |
| `core/designsystem/component/SectionHeader.kt` | 分区标题 |
| `core/designsystem/component/EmptyState.kt` | 空状态 |
| `core/designsystem/component/MetricCard.kt` | 指标卡 |
| `core/designsystem/component/ModuleEmptyScreen.kt` | 归档模块占位（保留） |

---

## 3. 归档模块

归档模块的共同特征：

- 仍参与编译、仍注册 Room 实体、仍进入备份；
- **不被 `StudyOSNavHost` 引用**，用户无法从界面进入；
- 不得被活跃 ViewModel 依赖（除 `SleepRepository` 这类被记录中心复用的数据层）。

| 模块 | 代码位置 | 文件数 | 数据表 | 归档原因 |
| --- | --- | --- | --- | --- |
| AI 规划 / 统一 AI 服务 | `core/ai`、`feature/ai` | 14 + 2 | `ai_usage_daily`、`ai_analysis_cache`、`ai_plan_records` | 产品明确“不做 AI 规划” |
| 学习计时 / 效率分析 | `core/learning`、`feature/study` | 2 + 18 | `study_timing_sessions`、`self_control_entries`、`learning_goals` | 只记录事实，不做计时与效率评价 |
| 任务 / 科目 / 排程 | `core/task`、`core/scheduling`、`feature/home` | 6 + 5 + 3 | `study_tasks`、`task_sessions`、`task_events`、`subjects`、`scheduling_*` | 旧任务回填为待办，旧表保留 |
| 题库 / 刷题 | `core/bank`、`feature/bank` | 11 + 8 | `problem_*` | 不在六项记录能力内 |
| 考试 / 长期目标 | `core/exam`、`feature/exam` | 1 + 2 | `exams`、`long_term_goals`、`goal_milestones` | 移除倒计时与目标管理 |
| 词汇 / 知识图谱 / 数学 | `core/vocabulary`、`core/knowledge`、`core/math`、`core/exercise` | 1 + 2 + 1 + 1 | `vocabulary_*`、`knowledge_*`、`exercise_*`、`ability_estimates` | 移除学习中心 |
| 周报 / 成长 | `core/report`、`core/growth`、`feature/report` | 3 + 1 + 2 | `weekly_reports`、`ai_analysis_records` | 不做评分与周报 |
| 睡眠保护 / 作息引擎 | `core/sleep` 中的 `SleepProtectionEngine` | 1 | `sleep_schedule`、`sleep_date_overrides`、`sleep_change_history` | 保留数据，不启用提醒 |
| 预算 / 预测 | `core/finance` 中的预测与预算算法、`feature/finance` 旧标签页 | — | `finance_monthly_budgets`、`finance_category_budgets` | 只展示收支 |
| 本地同步 / 数据所有权 | `core/sync`、`core/ownership`、`feature/data` | 4 + 1 + 2 | `sync_*` | 无云端，入口隐藏 |
| 首页卡片配置 | `feature/home`、`core/data/repository/HomeCardRepository.kt` | 3 | `home_card_preferences` | 首页固定，不提供排序 |
| 提醒 | `core/reminder` | 9 | 无独立表（偏好） | V3.1 启动时取消旧任务 |

### 3.1 归档层纪律

1. **只读优先**：活跃代码读取归档表仅限兼容目的（如备份、回填），不做写入。
2. **不引导航**：新增页面时先检查是否把归档 feature 重新挂回底栏——V3.1 不允许。
3. **不删表**：即使模块永久下线，也要先提供导出，再在独立版本中设计迁移。
4. **备份覆盖**：`BackupTables.ordered` 是归档表能否“活下去”的唯一保障，新增/删除表必须同步。

---

## 4. 依赖规则

```text
feature/*  ──►  core/data/repository  ──►  core/data/local  ──►  Room
    │                    │
    │                    └──►  core/model
    ▼
core/designsystem        core/schedule / finance / sleep / importer / backup
```

允许：

- `feature` → `core`（任意层）
- `core/data/repository` → `core/data/local` + `core/model` + 领域算法
- `core` 内部领域包 → `core/model`
- `navigation` → `feature` + `di`

禁止：

- `core` → `feature`
- `feature` → `core/data/local`（不得直接持有 DAO）
- 领域算法 → `android.*` / `androidx.compose.*`
- 归档 `feature` → 活跃 `feature`（避免归档层反向污染）

检查方式：

```bash
# 归档界面是否被导航引用（应为空）
grep -n "feature.study\|feature.ai\|feature.bank\|feature.exam" \
  app/src/main/java/com/zhixu/studyos/navigation/StudyOSNavHost.kt

# 活跃 feature 是否直接使用 DAO（应为空）
grep -rn "Dao" app/src/main/java/com/zhixu/studyos/feature/{today,records,profile}
```

---

## 5. 新增模块的准入清单

新增一个功能前，逐项确认：

- [ ] 属于“每日待办 / 每日总结 / 财务 / 睡眠 / 课表 / 娱乐”之一，或是其必要辅助；
- [ ] 不需要联网、不需要账号、不做评分；
- [ ] 有独立的 Repository 接口与至少一项 JVM 测试；
- [ ] 新表已登记到 `StudyOSDatabase`、迁移、`BackupTables`、`schemas`；
- [ ] 页面放在二级路由，底栏仍保持五项；
- [ ] 更新 `README.md`、`docs/ARCHITECTURE.md`、`docs/CHANGELOG.md`。
