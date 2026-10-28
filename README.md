# 知序 StudyOS V3.1.0

> 只记录生活，不管理人生。
> 打开就能记录，记录完就能离开。

V3.1.0 是 StudyOS 的**独立版本基线**。从本版本起，代码、构建脚本、文档与产物全部收敛在本目录（`D:\studyOS\V3_1_0`），不再与 V3.0.0 及更早版本的文档归档、APK 产物、历史需求记录混放。旧版本内容原样保留在上级目录 `D:\studyOS`，本目录不引用、不修改它们。

---

## 1. 版本事实

| 项目 | 值 |
| --- | --- |
| 版本名 | `3.1.0` |
| versionCode | `31` |
| 包名 / applicationId | `com.zhixu.studyos` |
| 数据库 | Room v10（`studyos.db`） |
| 最低系统 | Android 8.0（API 26） |
| 编译 / 目标 SDK | 35 / 35 |
| 构建 | Gradle 8.9 Wrapper、AGP 8.7.3、Kotlin 2.0.21、JDK 17 |
| 界面 | Jetpack Compose（BOM 2024.11.00）+ Material 3 |
| 数据 | Room 2.6.1 + Preferences DataStore + SAF |
| 联网 | 无。APK 不包含 `INTERNET` 权限 |
| 桌面组件 | 今日待办、下一节课（Glance） |
| Release APK | 28.0 MiB（29,427,174 字节，R8 + 压缩 `.so`） |
| 测试 | 496 项 JVM 测试，0 失败 |

---

## 2. 保留的六项记录能力

1. **每日待办**：前一天晚上安排明天；今天逐条勾选；可增删改；未完成不自动滚到第二天。
2. **完成进度**：记录页顶部直接显示「已完成 X / Y」方框与进度条，不再单独开页面，也不写文字总结。
3. **财务记账**：收入、支出、退款；分类、金额、日期、备注；按月查看收入、支出、结余；记录按天折叠，点开看当天明细；右下角圆形入口打开分类饼图。
4. **睡眠记录**：只记两个时间——放下手机、早上起床，自动算时长；最近 7 天与全部记录。
5. **时间分布**：只呈现今天，按 6:00–12:00、12:00–18:00、18:00–24:00 三段显示每段安排；课程的增删改、学期设置、单双周、调课 / 停课、一次性日程、作息与图片导入都收进右上角「课程管理」二级页。
6. **娱乐时间**：一个「今天玩了多久（分钟）」输入框，自动换算成小时与分钟；可回看、修改、删除；无惩罚与限制。

底栏固定五项：

```text
今日｜时间分布｜记录｜财务｜我的
```

二级页面（指定日待办、睡眠、娱乐、历史、课程管理）使用系统返回键或左上角返回，底栏自动隐藏；返回后由 ViewModel 与 Room 保留状态。

---

## 3. 快速开始

环境：JDK 17、Android SDK Platform 35、Android Studio Ladybug 或更新版本。

```bat
set JAVA_HOME=D:\jdk17
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

安装：

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Release 签名读取根目录 `keystore.properties` 与 `.signing/studyos-release.jks`；两者不入库，缺失时仍可构建 debug 与未签名 release。

更多构建、签名与校验细节见 [`docs/BUILD.md`](docs/BUILD.md)。

---

## 4. 工程结构

```text
V3_1_0/
├─ app/                         # 唯一应用模块
│  ├─ build.gradle.kts          # versionCode 31 / 3.1.0 / SDK 35
│  ├─ schemas/                  # Room 导出 schema（v1–v10）
│  └─ src/
│     ├─ main/java/com/zhixu/studyos/
│     ├─ main/res/              # 主题、字符串、两个组件 XML
│     └─ test/                  # 496 项 JVM / Robolectric 测试
├─ gradle/                      # Wrapper 8.9 + 版本目录
├─ docs/                        # V3.1.0 架构与发布文档
├─ outputs/                     # V3.1.0 的 APK 与源码归档
├─ 版本更新工作/                 # V3.1 原始需求
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ local.properties             # 本机 SDK 路径（忽略）
├─ keystore.properties          # 发布签名配置（忽略）
└─ .signing/                    # 发布密钥（忽略）
```

源码主路径：

```text
com/zhixu/studyos/
├─ MainActivity.kt              # 单 Activity，仅装配 Compose
├─ StudyOSApplication.kt        # 持有 AppContainer，处理恢复哨兵与旧任务清理
├─ di/AppContainer.kt           # 手写懒加载依赖注入
├─ navigation/                  # 五项底栏 + NavHost + 路由
├─ core/
│  ├─ data/                     # Room 实体 / DAO / 迁移 / 仓库 / DataStore
│  ├─ model/                    # 领域模型与输入上限
│  ├─ designsystem/             # 主题、间距、通用组件
│  ├─ schedule/                 # 教学周、重复展开、冲突、空闲计算
│  ├─ finance/                  # 整数分金额、汇总、CSV
│  ├─ sleep/                    # 睡眠模型与时长格式化
│  ├─ importer/                 # 课表图片 OCR 解析与校验
│  ├─ backup/                   # ZIP 备份、恢复、CSV 导出
│  └─ …                         # 归档模块（见 docs/MODULES.md）
├─ feature/
│  ├─ today/                    # 今日 / 指定日待办
│  ├─ records/                  # 记录中心与四个二级页
│  ├─ finance/                  # 简化财务首页（复用记账对话框）
│  ├─ schedule/                 # 课表与图片导入
│  ├─ profile/                  # 主题、备份、版本信息
│  └─ …                         # 归档界面（不挂导航）
└─ widget/StudyOSWidgets.kt     # 两个 Glance 组件 + 30 分钟兜底刷新
```

完整分层、数据流、迁移与模块边界见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

---

## 5. 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | **完整架构**：分层、启动装配、导航、五大功能、数据层、归档层、横切规则、构建与测试 |
| [`docs/MODULES.md`](docs/MODULES.md) | 活跃模块与归档模块清单、目录职责、依赖方向 |
| [`docs/DATABASE.md`](docs/DATABASE.md) | 数据库 v10：活跃表、归档表、迁移 9→10、回填与备份表清单 |
| [`docs/BUILD.md`](docs/BUILD.md) | 环境、构建命令、签名、产物、验证清单 |
| [`docs/VERSION_BOUNDARY.md`](docs/VERSION_BOUNDARY.md) | V3.1.0 与 V3.0.0 及更早版本的分割清单与维护规则 |
| [`docs/V3_1_RELEASE_NOTES.md`](docs/V3_1_RELEASE_NOTES.md) | 发布说明：用户可见变化、数据依赖审计、验证结果 |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | 变更记录（V3.1.0 详细，早期版本摘要） |

---

## 6. 数据与隐私

- **完全离线**：不申请 `INTERNET`；库清单合并进来的 `INTERNET` 已用 `tools:node="remove"` 显式移除；课表 OCR 使用随 APK 打包的 ML Kit 中文模型。
- **非破坏性升级**：v9 → v10 只新建 `daily_tasks`、`daily_summaries`、`entertainment_records` 三张表，并把旧任务回填为待办；旧表不删除、不改写。
- **历史数据不丢**：已从界面移除的模块，其表与文件仍在数据库中，并继续进入完整备份。
- **备份自持**：`我的 → 数据备份与导出` 生成包含全部业务表与题库原始文件的 ZIP；恢复前先只读校验并二次确认，失败回滚。
- **不备份密钥**：AI 设置与 API Key 不进入普通备份（V3.1 界面也不再提供）。

---

## 7. 已知限制（V3.1.0）

- 未连接真机 / 模拟器：桌面组件、通知取消、图片导入、深层视觉与真实升级安装未做物理设备验收。
- 睡眠为手动填写，未接入可穿戴设备；日期按“起床当天”保存。
- 旧任务回填只取截止日期（无截止日期时取最早学习时段日期），多条时段会合并为一条待办。
- 旧备份恢复会在恢复事务内补齐待办表；V3.1 备份直接包含三张新表。
- 备份对话框中仍保留旧模块的 CSV 导出入口（学习计时、习题），用于导出归档数据，主界面不展示对应功能。
