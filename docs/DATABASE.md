# V3.1.0 数据库说明

> 数据库：`studyos.db` · Room **v10** · 58 个实体 · 包名 `com.zhixu.studyos`
> 导出 schema：`app/schemas/com.zhixu.studyos.core.data.local.StudyOSDatabase/1.json … 10.json`

---

## 1. 总览

| 指标 | 数值 |
| --- | --- |
| Room 版本 | 10 |
| 实体 / 表 | 58 |
| V3.1 界面读写的表 | 11 |
| 归档表 | 47 |
| 完整备份包含的表 | 55 |
| 备份排除的表 | 3（AI 用量/缓存/计划） |
| 迁移文件 | `MIGRATION_1_2` … `MIGRATION_9_10` |

设计原则：

1. **只增表、不改旧表**：V3.1 迁移只新建三张表并回填，不删除、不改写任何旧数据。
2. **归档表继续可读**：旧表仍注册在 `@Database` 中，旧备份仍可恢复。
3. **备份显式枚举**：`BackupTables.ordered` 是唯一表清单，新增表必须手动登记。

---

## 2. V3.1 活跃表

### 2.1 `daily_tasks` — 每日待办

```sql
CREATE TABLE daily_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  dateEpochDay INTEGER NOT NULL,
  title TEXT NOT NULL,
  isCompleted INTEGER NOT NULL,
  completedAtEpochMillis INTEGER,
  sortOrder INTEGER NOT NULL,
  createdAtEpochMillis INTEGER NOT NULL,
  updatedAtEpochMillis INTEGER NOT NULL
);
CREATE INDEX index_daily_tasks_dateEpochDay ON daily_tasks(dateEpochDay);
```

- 一条待办只属于一个自然日；未完成不会滚到第二天。
- `title` 1–200 字，去首尾空格后校验。
- `sortOrder` 为当日排序，新待办 `max + 1`。
- `completedAtEpochMillis` 仅在完成时写入，取消完成置空。

### 2.2 `daily_summaries` — 每日总结

```sql
CREATE TABLE daily_summaries (
  dateEpochDay INTEGER PRIMARY KEY NOT NULL,
  content TEXT NOT NULL,
  createdAtEpochMillis INTEGER NOT NULL,
  updatedAtEpochMillis INTEGER NOT NULL
);
```

- 每天最多一条；`content` ≤ 2000 字。
- 空内容不新建记录；已有记录保存空串等价于清空文本，但记录仍存在。

### 2.3 `entertainment_records` — 娱乐时间

```sql
CREATE TABLE entertainment_records (
  dateEpochDay INTEGER PRIMARY KEY NOT NULL,
  minutes INTEGER NOT NULL,
  createdAtEpochMillis INTEGER NOT NULL,
  updatedAtEpochMillis INTEGER NOT NULL
);
```

- 每天一条，`minutes` 范围 0–1440。
- 手动填写，不做自动监控、不做惩罚。

### 2.4 复用的旧表

| 表 | 用途 | V3.1 行为 |
| --- | --- | --- |
| `sleep_records` | 睡眠记录 | 记录中心读写；日期按起床当天 |
| `finance_transactions` | 收支流水 | 财务首页读写 |
| `finance_categories` | 收支分类 | 财务首页读取；分类管理入口隐藏 |
| `semester_settings` | 学期与教学周 | 课表读写 |
| `courses` | 课程规则 | 课表读写 |
| `course_exceptions` | 停课/调课 | 课表读写 |
| `one_off_events` | 一次性日程 | 课表读写 |
| `routine_blocks` | 作息时段 | 课表读写 |

> `finance_monthly_budgets`、`finance_category_budgets` 仍被 `FinanceRepository.observeSnapshot()` 读取以构建快照，但 V3.1 首页不展示预算；数据保留并进入备份。

---

## 3. 归档表（47）

以下表不再有界面入口，但保持原样、继续参与备份。按领域分组：

| 领域 | 表 |
| --- | --- |
| AI（4） | `ai_usage_daily`、`ai_analysis_cache`、`ai_plan_records`、`ai_analysis_records` |
| 任务 / 科目 / 历史（5） | `study_tasks`、`task_sessions`、`task_events`、`subjects`、`study_timing_sessions` |
| 学习目标 / 自控（2） | `learning_goals`、`self_control_entries` |
| 排程（2） | `scheduling_settings`、`scheduling_subject_preferences` |
| 题库（8） | `problems`、`problem_documents`、`problem_document_pages`、`problem_import_batches`、`problem_import_items`、`problem_bank_entries`、`problem_bank_formulas`、`problem_bank_knowledge_points` |
| 考试 / 长期目标（3） | `exams`、`long_term_goals`、`goal_milestones` |
| 词汇（3） | `vocabulary_words`、`vocabulary_reviews`、`vocabulary_settings` |
| 知识图谱（3） | `knowledge_nodes`、`knowledge_dependencies`、`knowledge_problem_links` |
| 习题能力（6） | `exercise_attempts`、`exercise_hint_records`、`exercise_profiles`、`exercise_saved_proofs`、`exercise_error_reports`、`ability_estimates` |
| 周报（1） | `weekly_reports` |
| 睡眠保护（3） | `sleep_schedule`、`sleep_date_overrides`、`sleep_change_history` |
| 财务预算（2） | `finance_monthly_budgets`、`finance_category_budgets` |
| 同步（4） | `sync_operations`、`sync_baselines`、`sync_tombstones`、`sync_state` |
| 首页配置（1） | `home_card_preferences` |

> `ai_usage_daily`、`ai_analysis_cache`、`ai_plan_records` 属于可选 AI 数据，可能含任务标题且缓存可再生成，**不进入普通备份**；其余归档表全部进入备份。

---

## 4. 迁移

### 4.1 迁移链

```text
v1 → v2 → v3 → v4 → v5 → v6 → v7 → v8 → v9 → v10
     MIGRATION_1_2 … MIGRATION_9_10
```

`StudyOSDatabase.build()` 注册全部迁移；不使用 `fallbackToDestructiveMigration()`。

迁移测试覆盖 `1 → 10`、`2 → 10`、`6 → 10`，并断言：

- 旧数据行数不变；
- 新三张表存在且可写；
- 旧任务回填后的待办数量与内容符合规则。

### 4.2 `MIGRATION_9_10`

```kotlin
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 建 daily_tasks + 索引
        // 2. 建 daily_summaries
        // 3. 建 entertainment_records
        // 4. DailyLogBackfill.run(db)
    }
}
```

**不做**：`DROP TABLE`、`ALTER TABLE … RENAME`、批量 `UPDATE` 旧表。

### 4.3 回填规则

`DailyLogBackfill` 从 `study_tasks` 复制仍能确定日期的任务：

```sql
INSERT INTO daily_tasks (
  dateEpochDay, title, isCompleted, completedAtEpochMillis,
  sortOrder, createdAtEpochMillis, updatedAtEpochMillis
)
SELECT
  COALESCE(
    dueDateEpochDay,
    (SELECT MIN(dateEpochDay) FROM task_sessions WHERE taskId = study_tasks.id)
  ),
  title,
  CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END,
  completedAtEpochMillis,
  sortOrder,
  createdAtEpochMillis,
  updatedAtEpochMillis
FROM study_tasks
WHERE COALESCE(
  dueDateEpochDay,
  (SELECT MIN(dateEpochDay) FROM task_sessions WHERE taskId = study_tasks.id)
) IS NOT NULL;
```

语义：

- 优先 `dueDateEpochDay`；
- 否则取最早 `task_sessions.dateEpochDay`；
- 两者都没有的任务不进入新表，但仍留在 `study_tasks`；
- 同一天多个旧任务保留为多条待办；
- 保留完成状态、完成时间、排序、创建/更新时间。

### 4.4 旧备份恢复时的回填

恢复 `databaseVersion < 10` 的备份时，`BackupRestorer` 在**同一个恢复事务**内调用同一段回填逻辑，确保“旧任务恢复了，新界面也能看到”。恢复 V3.1 备份时三张新表已存在，直接使用备份数据。

---

## 5. 备份与恢复

### 5.1 格式

```kotlin
const val BACKUP_FORMAT_VERSION = 1
```

`BackupTables.ordered` 共 55 张，按父表 → 子表排列：

- 恢复时正序插入，删除时逆序；
- 旧备份缺表按空表处理，不拒绝恢复；
- V3.1 三张表追加在清单末尾。

分组清单：

| 分组 | 表数 | 代表表 |
| --- | --- | --- |
| 课表 | 6 | `semester_settings`、`subjects`、`courses`、`course_exceptions`、`one_off_events`、`routine_blocks` |
| 任务与学习 | 6 | `study_tasks`、`task_sessions`、`study_timing_sessions`、`problems`、`self_control_entries`、`learning_goals` |
| 财务 | 4 | `finance_categories`、`finance_transactions`、`finance_monthly_budgets`、`finance_category_budgets` |
| 排程 | 2 | `scheduling_settings`、`scheduling_subject_preferences` |
| 题库 | 7 | `problem_documents`、`problem_document_pages`、`problem_import_batches`、`problem_import_items`、`problem_bank_entries`、`problem_bank_formulas`、`problem_bank_knowledge_points` |
| V2 归档 | 27 | `task_events`、`sleep_*`、`vocabulary_*`、`knowledge_*`、`exams`、`exercise_*`、`ai_analysis_records`、`weekly_reports`、`sync_*`、`home_card_preferences` |
| V3.1 记录 | 3 | `daily_tasks`、`daily_summaries`、`entertainment_records` |

> 精确顺序以 `core/backup/BackupModels.kt` 的 `BackupTables.ordered` 为准。

### 5.2 备份内容

- 数据库快照（55 张表）；
- `filesDir/problem_bank_documents/` 下的题库原始文件（PDF/图片）；
- 元信息：应用版本、数据库版本、备份格式版本、创建时间、表行数摘要。

不包含：

- AI 配置（DataStore）；
- API Key（Android Keystore）；
- 三个 AI 用量/缓存/计划表。

### 5.3 恢复流程

```text
选择 ZIP
  → AppFileStore 读取
  → BackupRestorer.inspect()：只读校验格式、版本、表结构、列兼容性
  → 展示 BackupPreview（各表行数、文件数、警告）
  → 用户二次确认
  → 写入 .restore_journal
  → 数据库事务：删除旧业务行 → 按 ordered 插入 → 写恢复哨兵
  → 替换 filesDir 文件
  → 清理 journal
```

异常处理：

- 事务前失败：不改变任何数据；
- 事务中失败：回滚，旧数据不变；
- 事务提交后、文件替换前中断：启动时 `BackupRecovery` 读哨兵，判断继续替换还是回滚；
- 恢复后若备份版本 < 10，补跑回填。

### 5.4 CSV 导出

`CsvExport` 仍保留旧模块入口（学习计时、习题记录），用于导出归档数据；V3.1 主界面不展示对应功能。CSV 导出不影响数据库。

---

## 6. 数据完整性规则

| 规则 | 落地位置 |
| --- | --- |
| 待办标题非空、≤200 字 | `OfflineDailyLogRepository.normalizeTaskTitle` |
| 总结 ≤2000 字 | `OfflineDailyLogRepository.saveSummary` |
| 娱乐 0–1440 分钟 | `OfflineDailyLogRepository.saveEntertainment` |
| 金额为整数分 | `Money`、`FinanceTransaction.amountFen: Long` |
| 课表导入整批原子 | `OfflineScheduleRepository.importCourses` 单事务 |
| 课程冲突写入前检测 | `ScheduleConflictDetector` + Repository |
| 恢复前只读校验 | `BackupRestorer.inspect` |
| 彻底删除需显式确认 | `DataOwnershipService`（V3.1 入口隐藏） |

数据库不承担业务校验的兜底：`NOT NULL` 只保证结构，范围与语义由仓库层负责，UI 不是唯一防线。

---

## 7. 维护清单

新增/修改表时按顺序完成：

1. 修改/新增 `@Entity`，更新 `StudyOSDatabase.entities`；
2. `version + 1`，新增 `Migration(n, n+1)` 并注册；
3. 运行 `:app:assembleDebug` 生成新的 `app/schemas/…/N.json`；
4. 更新 `BackupTables.ordered`（父表在前）与 `mayBeAbsentInOlderBackups`；
5. 增加迁移测试（旧版本 → 新版本、数据保留、新表可写）；
6. 更新本文与 `docs/CHANGELOG.md`。
