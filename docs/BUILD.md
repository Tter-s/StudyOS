# V3.1.0 构建与发布

> 本文件只描述 `D:\studyOS\V3_1_0` 这个独立工程。命令均在工程根目录执行。

---

## 1. 环境要求

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Windows 10/11（本工程在 Windows 上维护） |
| JDK | **17**（`JAVA_HOME` 指向 JDK 17） |
| Android SDK | Platform 35、Build-Tools 35.x |
| Android Studio | Ladybug 或更新（可选，命令行即可构建） |
| Gradle | 8.9（由 Wrapper 自动下载） |
| 网络 | 首次构建需要下载依赖；日常可离线 |

`local.properties` 保存本机 SDK 路径：

```properties
sdk.dir=D:/Android/Sdk
```

该文件已被 `.gitignore` 忽略，换机后需自行创建。

---

## 2. 常用命令

```bat
:: 单元测试（496 项）
gradlew.bat :app:testDebugUnitTest

:: Debug APK
gradlew.bat :app:assembleDebug

:: Release APK（需签名配置）
gradlew.bat :app:assembleRelease

:: Lint
gradlew.bat :app:lintDebug

:: 清理
gradlew.bat clean
```

PowerShell / Git Bash 使用 `./gradlew`。

首次构建如果卡在下载 Gradle，可检查 `gradle/wrapper/gradle-wrapper.properties`：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
```

---

## 3. 签名

Release 签名读取工程根目录的 `keystore.properties`：

```properties
storeFile=.signing/studyos-release.jks
storePassword=******
keyAlias=studyos
keyPassword=******
```

`app/build.gradle.kts` 的行为：

```kotlin
val hasReleaseSigning = keystorePropertiesFile.exists()
signingConfigs {
    if (hasReleaseSigning) {
        create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            …
            enableV3Signing = true
        }
    }
}
```

- 文件存在：release 自动签名，启用 APK Signature Scheme v3。
- 文件缺失：仍可构建 debug 与未签名 release，不阻塞开发。
- `.signing/`、`keystore.properties`、`local.properties` 均不入库。

验证签名：

```bat
"%ANDROID_HOME%\build-tools\35.0.0\apksigner.bat" verify --verbose ^
  app\build\outputs\apk\release\app-release.apk
```

期望包含：

```text
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

---

## 3.1 体积优化（release）

release 构建启用了三项与功能无关的体积优化：

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true       // R8 代码压缩
        isShrinkResources = true     // 资源收缩
        …
    }
}
packaging {
    jniLibs.useLegacyPackaging = true // 压缩 .so，安装时解压
}
```

- **ABI 不变**：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四个全部保留。
- **功能不变**：未删除任何模块；仅对桌面组件/Worker、PDFBox/FontBox、Room 生成实现等反射入口做必要保留。
- **体积结果**：release APK 从 68,949,000 字节降到 **29,427,174 字节（28.0 MiB）**。
- **映射文件**：`app/build/outputs/mapping/release/mapping.txt`，用于回溯混淆后的堆栈；不要随 APK 发布。
- **代价**：首次安装时系统需要解压原生库，安装体积略大于压缩前；运行期行为不变。

---

## 4. 产物与归档

| 产物 | 构建路径 | 归档路径 | 当前体积 |
| --- | --- | --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | `outputs/StudyOS-V3.1.0-debug.apk` | 75.0 MiB |
| Release APK | `app/build/outputs/apk/release/app-release.apk` | `outputs/StudyOS-V3.1.0-release.apk` | **28.0 MiB** |
| 源码 ZIP | — | `outputs/StudyOS-V3.1.0-source.zip` | 1.7 MiB |

发布时复制并重命名：

```bat
copy app\build\outputs\apk\debug\app-debug.apk ^
     outputs\StudyOS-V3.1.0-debug.apk
copy app\build\outputs\apk\release\app-release.apk ^
     outputs\StudyOS-V3.1.0-release.apk
```

源码归档应包含：

```text
app/src、app/schemas、app/build.gradle.kts、app/proguard-rules.pro
gradle/、gradlew、gradlew.bat
build.gradle.kts、settings.gradle.kts、gradle.properties
docs/、README.md
```

不包含 `build/`、`.gradle/`、`.signing/`、`keystore.properties`、`local.properties`。

---

## 5. 发布前检查清单

```text
[ ] versionCode / versionName 已更新（当前 31 / 3.1.0）
[ ] 数据库版本与迁移已同步（当前 10）
[ ] app/schemas 已生成最新 JSON
[ ] :app:testDebugUnitTest 全绿
[ ] :app:lintDebug 无 error
[ ] :app:assembleDebug / assembleRelease 成功
[ ] apksigner verify v2=true、v3=true
[ ] aapt dump badging 确认无 INTERNET 权限
[ ] APK 复制到 outputs/ 并按版本命名
[ ] 源码 ZIP 已更新
[ ] docs/CHANGELOG.md、docs/V3_1_RELEASE_NOTES.md 已更新
```

权限检查：

```bat
"%ANDROID_HOME%\build-tools\35.0.0\aapt.exe" dump badging ^
  app\build\outputs\apk\release\app-release.apk | findstr permission
```

V3.1.0 预期不出现 `android.permission.INTERNET` 与 `POST_NOTIFICATIONS`；仅保留 WorkManager 合并的：

```text
WAKE_LOCK
ACCESS_NETWORK_STATE
RECEIVE_BOOT_COMPLETED
FOREGROUND_SERVICE
```

---

## 6. 安装与升级验证

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

升级验证（从 V3.0.0 覆盖安装）：

1. 先安装 `outputs/StudyOS-V3.0.0-debug.apk`，写入若干旧任务与财务记录；
2. 覆盖安装 V3.1.0；
3. 打开“今日”，确认旧任务已回填为待办；
4. 打开“记录 → 历史记录”，确认旧日期可见；
5. 执行一次完整备份，检查 ZIP 中包含 `daily_tasks` 等表；
6. 卸载重装后恢复该备份，确认数据完整。

> 当前版本未连接真机/模拟器，上述升级验证属于发布前待办事项。

---

## 7. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `JAVA_HOME is not set` | 未配置 JDK 17 | 设置 `JAVA_HOME=D:\jdk17`，重开终端 |
| `SDK location not found` | 缺 `local.properties` | 新建并写入 `sdk.dir=...` |
| Gradle 下载超时 | 镜像不可达 | 修改 `distributionUrl` 为官方或可用镜像 |
| KSP 报 schema 冲突 | 改了实体但未升版本 | 升 `version` 并新增迁移，重新构建生成 JSON |
| Release 未签名 | 缺 `keystore.properties` | 补签名配置，或仅使用 debug 构建 |
| 测试报 `Method not mocked` | 纯 JVM 使用了 Android API | 使用 Robolectric 或注入 Fake |
| 组件不刷新 | 系统限制后台 | 等待 30 分钟兜底任务，或打开 App 触发 `onStart` 刷新 |

---

## 8. 版本号升级步骤

1. `app/build.gradle.kts`：`versionCode += 1`，`versionName` 改为新版本；
2. 若改表：`STUDYOS_DATABASE_VERSION += 1`，新增迁移并注册；
3. 更新 `README.md` 的版本事实表；
4. 更新 `docs/CHANGELOG.md` 与 `docs/V3_1_RELEASE_NOTES.md`；
5. 跑完整检查清单，归档产物。
