# release 混淆/压缩规则（isMinifyEnabled + isShrinkResources 均已开启）

# ── 组件 ──────────────────────────────────────────────
# Activity / Service / BroadcastReceiver 会被系统按 manifest 实例化、按类名反射调用，
# 混淆后类名变化会导致系统找不到组件（Unable to instantiate service/activity）。
# AGP 通常会为 manifest 组件自动生成 keep 规则，这里显式再声明一份作为保险。
-keep public class com.elleng.adbswitch.MainActivity { *; }
-keep public class com.elleng.adbswitch.AdbToggleService { *; }
-keep public class com.elleng.adbswitch.NotificationReceiver { *; }
-keep public class com.elleng.adbswitch.PinShortcutReceiver { *; }
-keep public class com.elleng.adbswitch.AdbTileService { *; }

# ── 调试开关的对外 action 常量 ─────────────────────────
# 桌面快捷方式 / 通知按钮 / 磁贴均通过 action 字符串跨进程触发（见 shortcuts.xml、
# AdbToggleService.ACTION_*）。这些是 static final String 编译期常量，正常会被内联；
# 显式 -keepclassmembers 确保它们在混淆后仍以字面量保留，避免任一处漏改导致入口失效。
-keepclassmembers class com.elleng.adbswitch.AdbToggleService {
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

# ── 在线更新（当前 ENABLED=false，休眠状态）────────────────────
# 这个包必须显式 keep，原因不是反射，而是**资源裁剪**：
#   UpdateConfig.ENABLED 是编译期常量 false，R8 会把 isActive() 折叠成常量，
#   于是「检查更新 → 弹窗 → 下载」这条链上的调用点被判定为不可达而整段移除，
#   连带它们对 R.layout/dialog_update、R.string/update_* 的引用也消失；
#   随后 isShrinkResources 判定这些资源无人引用，直接从 resources.arsc 里裁掉。
#   实测后果：代码在、dialog_update 布局和 7 个 update_* 字符串没了，
#   等正式上线把 ENABLED 打开就会 Resources.NotFoundException 崩溃。
# 保留整包即可让资源引用活下来；等更新功能真正上线并稳定后，可考虑去掉本段。
-keep class com.elleng.adbswitch.update.** { *; }

# ── 行号与源文件名 ────────────────────────────────────
# 保留行号便于定位 root / 通知相关崩溃。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
