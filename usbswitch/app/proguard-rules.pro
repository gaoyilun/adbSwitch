# release 混淆/压缩规则（isMinifyEnabled + isShrinkResources 均已开启）

# ── 组件 ──────────────────────────────────────────────
# Activity / Service / BroadcastReceiver 会被系统按 manifest 实例化、按类名反射调用，
# 混淆后类名变化会导致系统找不到组件（Unable to instantiate service/activity）。
# AGP 通常会为 manifest 组件自动生成 keep 规则，这里显式再声明一份作为保险。
-keep public class com.elleng.devswitch.MainActivity { *; }
-keep public class com.elleng.devswitch.UsbToggleService { *; }
-keep public class com.elleng.devswitch.NotificationReceiver { *; }
-keep public class com.elleng.devswitch.UsbTileService { *; }

# ── 调试开关的对外 action 常量 ─────────────────────────
# 桌面快捷方式 / 通知按钮 / 磁贴均通过 action 字符串跨进程触发（见 shortcuts.xml、
# UsbToggleService.ACTION_*）。这些是 static final String 编译期常量，正常会被内联；
# 显式 -keepclassmembers 确保它们在混淆后仍以字面量保留，避免任一处漏改导致入口失效。
-keepclassmembers class com.elleng.devswitch.UsbToggleService {
    public static final java.lang.String *;
}

# ── Shizuku（双模式后端）──────────────────────────────
# Shizuku 的 api aar 自带的 proguard.txt 为空，若不显式 keep，
# rikka.shizuku.Shizuku 门面与 moe.shizuku.server.* AIDL 仅靠「被本应用引用」才被 R8 保留，
# 一旦后续重构降低引用度就可能被误删（届时切到 Shizuku 模式会直接 NoClassDefFound）。
# 显式 keep 让链路稳定、可预测。
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# ── 反射调用的 framework 方法 ──────────────────────────
# Live Updates 用反射调用 Android 16 QPR1 新增的 setRequestPromotedOngoing /
# setShortCriticalText / canPostPromotedNotifications。目标都在 framework（不会被混淆），
# 但显式 keep 可防止 R8 在未解析到调用点时误判为「反射目标不存在」而报错。
-keepclassmembers class android.app.Notification$Builder {
    public void setRequestPromotedOngoing(boolean);
    public void setShortCriticalText(java.lang.String);
}
-keepclassmembers class android.app.NotificationManager {
    public boolean canPostPromotedNotifications();
}

# ── 行号与源文件名 ────────────────────────────────────
# 保留行号便于定位 root / 通知相关崩溃。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
