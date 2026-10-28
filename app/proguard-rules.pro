# V3.1.0 发布压缩规则
#
# 目标：在不删减功能、不改变 ABI 支持范围的前提下，让 release APK 小于 50 MB。
# 策略：R8 代码压缩 + 资源收缩 + 压缩 .so；仅对反射/资源加载入口做必要保留。

# 保留泛型、注解与内部类信息，避免 Room/Compose/ML Kit 的泛型签名失效。
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# 桌面小组件与 Worker 由系统 / WorkManager 按类名实例化。
-keep class com.zhixu.studyos.widget.** { *; }
-keepnames class * extends androidx.work.ListenableWorker

# PDFBox / FontBox 通过反射与资源名加载字体、CMap；题库归档功能仍可能读取旧文件。
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.pdfbox.**

# ML Kit 的文字识别入口、组件发现与可选依赖：保留全部实现，避免 R8 内联/移除影响 OCR。
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
