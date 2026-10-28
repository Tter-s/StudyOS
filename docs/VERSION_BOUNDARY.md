# V3.1.0 版本边界与分割说明

> 目的：让 `D:\studyOS\V3_1_0` 成为 **V3.1.0 的唯一事实来源**，同时保证 `D:\studyOS` 中 V3.0.0 及更早版本的文档、产物、需求记录**原样不变**。

---

## 1. 分割原则

1. **只复制，不移动**：本次不删除、不改写上级目录中的任何文件；V3.1.0 的全部内容以副本形式写入 `V3_1_0`。
2. **自包含**：`V3_1_0` 拥有完整的 Gradle 工程、源码、schema、构建脚本、签名配置、文档与产物，可脱离上级目录独立构建。
3. **单向依赖**：`V3_1_0` 不引用上级目录的相对路径；构建、测试、发布只依赖本目录与 JDK/SDK。
4. **旧版本冻结**：V3.0.0 及更早的内容只作为历史归档，不再参与 V3.1.0 的构建与发布。

---

## 2. 目录对照

```text
D:\studyOS\                         D:\studyOS\V3_1_0\
├─ app/            ──复制──►        ├─ app/               完整源码 + schemas
├─ gradle/         ──复制──►        ├─ gradle/            Wrapper + 版本目录
├─ gradlew(.bat)   ──复制──►        ├─ gradlew(.bat)
├─ build.gradle.kts ─复制──►        ├─ build.gradle.kts
├─ settings.gradle.kts ─复制──►     ├─ settings.gradle.kts
├─ gradle.properties ─复制──►       ├─ gradle.properties
├─ local.properties ─复制──►        ├─ local.properties   （忽略）
├─ keystore.properties ─复制──►     ├─ keystore.properties（忽略）
├─ .signing/       ──复制──►        ├─ .signing/          （忽略）
├─ docs/V3_1_RELEASE_NOTES.md ─复制► ├─ docs/V3_1_RELEASE_NOTES.md
├─ docs/samples/   ──复制──►        ├─ docs/samples/
├─ 版本更新工作/V3_1.md ─复制──►     ├─ 版本更新工作/V3_1.md
├─ outputs/StudyOS-V3.1.0-* ─复制─► ├─ outputs/
│                                   ├─ README.md          【新写】
│                                   ├─ docs/ARCHITECTURE.md【新写】
│                                   ├─ docs/MODULES.md   【新写】
│                                   ├─ docs/DATABASE.md  【新写】
│                                   ├─ docs/BUILD.md     【新写】
│                                   ├─ docs/CHANGELOG.md 【新写】
│                                   └─ docs/VERSION_BOUNDARY.md【新写】
├─ docs/（V2/V3.0 文档）             └─ 不复制，保留在原位
├─ 版本更新工作/（V0.x–V2.0）          └─ 不复制，保留在原位
└─ outputs/（V0.x–V3.0 产物）          └─ 不复制，保留在原位
```

---

## 3. 文件归属清单

### 3.1 属于 V3.1.0，已写入 `V3_1_0`

| 类别 | 内容 | 数量 / 大小 |
| --- | --- | --- |
| 应用源码 | `app/src/main`（Kotlin、res、assets、Manifest） | 239 个 Kotlin 文件 |
| 测试源码 | `app/src/test` | 81 个 Kotlin 文件、496 项测试 |
| Room schema | `app/schemas/…/1.json … 10.json` | 10 个文件，616 KB |
| 模块构建 | `app/build.gradle.kts`、`app/proguard-rules.pro` | 2 个文件 |
| 根构建 | `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties` | 3 个文件 |
| Wrapper | `gradlew`、`gradlew.bat`、`gradle/wrapper/*` | 3 个文件 |
| 版本目录 | `gradle/libs.versions.toml` | 1 个文件 |
| 签名 | `.signing/studyos-release.jks`、`keystore.properties` | 本地保留，不入库 |
| 本地配置 | `local.properties` | 本地保留，不入库 |
| 需求原文 | `版本更新工作/V3_1.md` | 1 个文件 |
| 架构文档 | `README.md`、`docs/ARCHITECTURE.md`、`MODULES.md`、`DATABASE.md`、`BUILD.md`、`VERSION_BOUNDARY.md`、`CHANGELOG.md` | 7 个文件 |
| 发布说明 | `docs/V3_1_RELEASE_NOTES.md` | 1 个文件 |
| 文档样例 | `docs/samples/*` | 2 个文件 |
| 归档产物 | `outputs/StudyOS-V3.1.0-debug.apk`、`-source.zip` | 约 76 MB |

合计（不含 `.git`、`app/build`、`.gradle`）：**417 个文件**，其中源码与资源 380 个。

### 3.2 属于 V3.0.0 及以前，保留在 `D:\studyOS`，未改动

| 类别 | 内容 |
| --- | --- |
| 旧版本文档 | `docs/PRODUCT.md`、`DECISIONS.md`、`ROADMAP.md`、`QA_REPORT.md`、`INSTALL.md`、`RELEASE.md`、`SCHEDULE_IMPORT.md`、`AI_PLANNER.md`、`PROBLEM_BANK.md`、`DATABASE.md`、`ARCHITECTURE.md`、`CHANGELOG.md` |
| V2 交接材料 | `docs/V2_AUDIT_HANDOFF.md`、`V2_HANDOVER.md`、`V2_RELEASE_NOTES.md` |
| V3.0 发布说明 | `docs/V3_RELEASE_NOTES.md` |
| 历史需求 | `版本更新工作/V0_0.md … V0_9.md`、`V1_0.md`、`V2_0.md` |
| 历史产物 | `outputs/StudyOS-V0.1.0…V3.0.0-*.apk`、`StudyOS-V3.0.0-source-20261026.zip` |
| 旧版源码 | `outputs/StudyOS-V3.0.0-source-20261026.zip`（V3.0.0 基线快照） |

> 根目录的 `app/` 仍保留 V3.1.0 源码，因为本次采用“复制”而非“移动”，以满足“旧内容不变”。它现在仅作为分割时的蓝本，不再作为后续开发入口。

### 3.3 重复但有意保留的内容

| 文件 | 根目录 | `V3_1_0` | 说明 |
| --- | --- | --- | --- |
| `app/src/**` | 保留 | 副本 | 本次蓝本；`diff -rq` 校验完全一致 |
| `outputs/StudyOS-V3.1.0-*.apk` | 保留 | 副本 | 发布产物双份留存 |
| `docs/V3_1_RELEASE_NOTES.md` | 保留 | 副本 | 发布说明双份留存 |
| `版本更新工作/V3_1.md` | 保留 | 副本 | 需求原文双份留存 |

---

## 4. 分割校验

本次分割执行了以下校验：

```bash
# 源码逐文件比对（无输出 = 完全一致）
diff -rq app/src V3_1_0/app/src

# schema 逐文件比对
diff -rq app/schemas V3_1_0/app/schemas
```

结果：两项均无差异。

独立工程完整性：

```text
app/build.gradle.kts        ✓
app/src/main/AndroidManifest.xml ✓
app/src/main/java/…         ✓ 239 个 Kotlin 文件
app/src/test/java/…         ✓ 81 个测试文件
app/schemas/…/10.json       ✓
gradle/wrapper/gradle-wrapper.jar ✓
gradlew / gradlew.bat       ✓
settings.gradle.kts         ✓
```

---

## 5. 后续维护规则

1. **唯一入口**：V3.1.x 的所有改动只在 `D:\studyOS\V3_1_0` 进行，不再修改上级目录的 `app/`。
2. **根目录只读**：上级目录作为 V3.0.0 及更早版本的历史归档，仅用于查阅、对比、取旧 APK。
3. **禁止交叉引用**：`V3_1_0` 的构建脚本、文档不得引用 `../docs`、`../outputs` 等上级路径。
4. **版本升级**：在 `V3_1_0` 内更新 `versionCode` / `versionName` / 数据库版本，并按 `docs/BUILD.md` 归档新产物。
5. **旧版本产物**：如需保留 V3.1.0 的历史构建，放入 `V3_1_0/outputs/`，不要写回上级目录。

---

## 6. 可选的进一步隔离（本次未执行）

如果希望上级目录**只保留 V3.0.0 及以前**、不再包含 V3.1.0 源码，可在确认后执行“移动”方案：

```text
D:\studyOS\app\            → 移入 D:\studyOS\V3_1_0\app\（合并）
D:\studyOS\docs\V3_1_*     → 移入 V3_1_0/docs\
D:\studyOS\版本更新工作\V3_1.md → 移入 V3_1_0\版本更新工作\
D:\studyOS\outputs\StudyOS-V3.1.0-* → 移入 V3_1_0\outputs\
```

该方案会删除上级目录中的 V3.1.0 副本，属于不可逆操作，因此本次**未执行**；当前状态已满足“V3.1.0 独立、旧版本不变”。

---

## 7. 版本标识

| 位置 | 标识 |
| --- | --- |
| `app/build.gradle.kts` | `versionCode = 31`、`versionName = "3.1.0"` |
| `settings.gradle.kts` | `rootProject.name = "StudyOS"` |
| 数据库 | `STUDYOS_DATABASE_VERSION = 10` |
| Git 仓库 | `V3_1_0/.git`，基线提交见 `git log` |
| 文档 | 本目录 `README.md` 与 `docs/*` 均以 3.1.0 为唯一版本口径 |
