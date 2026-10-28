# StudyOS V3.1 发布说明

日期：2026-10-26
版本：`3.1.0`（versionCode 31）
数据库：Room v10
包名：`com.zhixu.studyos`

## 目标

> 只记录生活，不管理人生。
> 打开就能记录，记录完就能离开。

V3.1 把产品收敛为“每日待办 + 每日总结 + 财务 + 睡眠 + 课表”五项，加一个手动填写的娱乐时间。不做 AI 规划、不做学习计时、不做评分和排行榜。

## 用户可见变化

### 今日

- 显示今天日期、完成进度（完成数 / 总数、百分比、进度条）。
- 待办列表支持勾选完成；点击文字或铅笔编辑；编辑弹窗内可删除。
- 右下角 `+` 添加待办；空状态提供“添加第一件”。
- “明日待办”卡片显示已安排数量与前两项预览，点击进入明日安排页。
- 跨过午夜或从后台回到前台会刷新为当天日期。
- 未完成待办不会自动带到第二天。

### 记录

- 记录中心仅四个入口：每日总结、睡眠记录、娱乐时间、历史记录。
- 每日总结：按日期查看/补写，自动显示当天完成数 / 总数，并只读列出当天待办；最多 2000 字；保存后原地提示“已保存”。
- 睡眠记录：日期按起床当天，填写入睡/起床时间（24 小时制），自动计算时长；显示最近 7 天与平均时长，以及全部记录；点击记录可编辑或删除。
- 娱乐时间：以“小时 + 分钟”填写，可跨日期查看、修改、删除；最近记录可点选回填。
- 历史记录：所有有待办、总结、睡眠或娱乐记录的日期，按天汇总；点击某天回到该日总结。

### 财务

- 当月收入、支出（净支出）、结余；左右切换月份。
- “记一笔”复用原有新增/编辑对话框：支出/收入/退款、金额、分类、日期、备注。
- 最近账目列表，点击可编辑或删除。
- 不再展示预算、预测、异常分析、分类管理入口；历史预算与分类数据仍保留在数据库中。

### 课表

- 保留周课表、学期设置、教学周切换、单双周、跨日课程、停课/调课、一次性日程与作息。
- 顶部仅保留“添加课程”和“更多”菜单；图片导入、学期设置、一次性日程、作息、调整记录收进菜单，减少横向按钮堆叠。
- 课程点击后可查看、编辑、删除、停课或调课。

### 我的

- 主题：跟随系统 / 浅色 / 深色。
- 数据备份与导出：完整 ZIP 备份、恢复、CSV 导出。
- 版本信息与本地优先说明。
- 移除 AI 设置、首页卡片排序、睡眠作息保护、数据与同步、彻底删除等入口。

### 桌面小组件

- 保留“今日待办”（读取新的 `daily_tasks`，显示完成数与下一项）和“下一节课”。
- 移除“学习计时”“今日概览”两个组件及对应 Receiver/XML。
- 启动时取消旧版本遗留的预算、计划、考试、睡前提醒 WorkManager 任务。

## 移除的功能与数据依赖审计

以下模块不再有界面入口；历史数据全部保留在原表中，并继续进入完整备份。

| 功能 | 主要表 / 存储 | V3.1 处理 |
| --- | --- | --- |
| AI 学习规划、AI API 配置 | `ai_usage_daily`、`ai_analysis_cache`、`ai_plan_records`、DataStore、Keystore | 删除设置界面与导航；表与存储保留；启动不再调用 AI |
| 学习计时 / 效率分析 | `study_timing_sessions`、`subjects`、`learning_goals` | 仅保留在归档表；新界面不读写 |
| 任务科目 / 优先级 / 排程 | `study_tasks`、`task_sessions`、`scheduling_*` | 旧任务复制为 `daily_tasks`；旧表保留；排程界面移除 |
| 题库 / 刷题 / 习题 | `problem_*`、`exercise_*`、原始 PDF/图片文件 | 界面移除；文件与表保留，备份仍包含 |
| 词汇 / 知识图谱 / 数学助手 | `vocabulary_*`、`knowledge_*`、`math` 相关表 | 界面移除；表保留 |
| 考试 / 长期目标 / 成就 | `exams`、`long_term_goals`、`goal_milestones` | 界面与提醒移除；表保留 |
| 周报 / 成长分析 | `weekly_reports`、`ai_analysis_records` | 界面移除；表保留 |
| 睡眠保护 / 作息引擎 | `sleep_schedule`、`sleep_date_overrides`、`sleep_change_history` | 保留作息数据；新“睡眠记录”只使用 `sleep_records` |
| 预算 / 预测 | `finance_monthly_budgets`、`finance_category_budgets` | 保留数据；界面只展示收支 |
| 首页卡片配置 / 本地同步 | `home_card_preferences`、`sync_*` | 入口移除；表保留 |

旧业务代码（`feature/ai`、`feature/bank`、`feature/study` 等）仍留在仓库，作为数据兼容与备份的归档层，但不再被导航引用。这样既满足“从界面移除”，也满足“不直接删除历史数据”。

## 数据库与迁移

Room v10 新增：

```sql
daily_tasks(id, dateEpochDay, title, isCompleted, completedAtEpochMillis, sortOrder, createdAtEpochMillis, updatedAtEpochMillis)
daily_summaries(dateEpochDay PK, content, createdAtEpochMillis, updatedAtEpochMillis)
entertainment_records(dateEpochDay PK, minutes, createdAtEpochMillis, updatedAtEpochMillis)
```

`MIGRATION_9_10`：

1. 创建上述三张表与 `daily_tasks.dateEpochDay` 索引。
2. 从 `study_tasks` 复制仍能确定日期的任务到 `daily_tasks`：
   - 优先使用 `dueDateEpochDay`；
   - 否则使用最早 `task_sessions.dateEpochDay`；
   - 完成状态、完成时间、排序、创建/更新时间一并保留。
3. 不删除、不改写任何旧表。

恢复旧备份时（备份 `databaseVersion < 10`），`BackupRestorer` 在同一个恢复事务里调用同一段回填 SQL，避免“旧任务恢复了但新界面看不到”。

完整备份新增三张表；旧版备份缺少它们时按空表处理，不拒绝恢复。彻底删除（数据所有权服务）使用的表清单也自动包含新表。

## 验证

- `:app:testDebugUnitTest`：**496 项测试，0 失败、0 跳过**（删除 4 项针对已移除 AI 设置换算的测试，新增每日记录/迁移/导航测试）。
- `:app:assembleDebug`、`:app:assembleRelease`：成功。Debug APK 74.6 MiB（78,274,101 字节），Release APK 65.8 MiB（68,949,000 字节）。
- 签名：Debug 使用默认调试签名（v2 验证通过）；Release 使用项目密钥，`apksigner verify --verbose` 显示 v2=true、v3=true。
- 权限：APK 不含 `INTERNET`、`POST_NOTIFICATIONS`；仅保留 WorkManager 合并的 `WAKE_LOCK`、`ACCESS_NETWORK_STATE`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`。
- `:app:lintDebug`：0 errors、38 warnings（多为归档模块未使用资源/依赖版本提示）。
- 工程备份：`outputs/StudyOS-V3.1.0-source.zip`（源码、schemas、docs、构建脚本）与 `outputs/StudyOS-V3.0.0-source-20261026.zip`（重构前基线）。
- 数据库迁移测试覆盖 v1/v2/v6 → v10，并断言旧任务回填、新总结表可写。
- 新增 `DailyLogRepositoryTest`：待办增删改查、跨日隔离、总结/娱乐往返、历史聚合、非法输入。
- 导航测试覆盖五个底栏、深色主题、课表图片导入、添加待办、明日待办、记录中心四个入口、保存总结、财务新增对话框。
- 未连接真机/模拟器：小组件、通知取消、图片导入、真实升级安装与视觉未做物理设备验收。
- 重构前已备份 V3.0.0 源码；设备内数据库未单独导出（本机未连接设备），但升级迁移只新增表并复制旧任务，不删除/不改写旧表，应用内“完整备份”也可随时导出当前数据。

## 已知限制

- 旧任务的多个学习时段只取最早一天生成一条 V3.1 待办；这是有意的简化，旧数据仍可在归档表中完整保留。
- 睡眠日期按“起床当天”解释；若填写时间导致时长不在 1 分钟–16 小时，会拒绝保存。
- 历史记录按天汇总，不再限制最近 30 天；数据量很大时列表仍由 LazyColumn 按需渲染。
- CSV 导出仍保留旧模块入口（学习计时、习题），用于导出归档数据；V3.1 主界面不展示对应功能。
- 备份恢复对话框中的“恢复”仍会替换全部业务表；恢复前应先创建备份（界面有提示与二次确认）。
