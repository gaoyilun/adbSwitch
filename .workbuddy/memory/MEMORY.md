# DevOptsHider 项目记忆

## 项目定位
- 当前主方案：**调试开关 (AdbSwitch) App**（`adbswitch/` 模块，包名 **`com.elleng.adbswitch`**，中文名对应「调试开关」；2026-10-09 由 usbswitch/UsbSwitch 整体改名为 adbswitch/AdbSwitch，包名随后也由 com.elleng.devswitch 改为 com.elleng.adbswitch）。
- 用 root（su）或 **Shizuku（shell uid 2000）** 真实改写 `Settings.Global.adb_enabled` / `adb_wifi_enabled`；两个都关掉后让 HSBC 类 RASP 触发的派生属性一次性归零。
- 双模式（`AuthManager.Mode`：ROOT / SHIZUKU）已落地：写通道由 `DebugController.runPrivileged` 按当前模式选 `ShizukuUtil.run` 或 `RootUtil.su`。
- 旧 Xposed / Zygisk / 探针 方案**已归档到 `archive/`**，不再主推，也不在常规构建范围内。

## 关键约定
- 主界面用 framework 原生 `PreferenceActivity`（`adbswitch/app/src/main/res/xml/prefs.xml` + `MainActivity`）。
- 自定义行布局：`pref_row.xml`（普通行，图标框 40dp）、`pref_row_state.xml`（调试三行，图标框 20dp）。
- framework Preference 整行不可点；点击靠 `MainActivity.wireRowClicks()` 派发——**动态项（快捷 app 行/默认快捷行/管理行）按 Preference key 派发**（`list.getItemAtPosition` 拿 Preference 对象 + key 前缀/精确匹配），**绝不靠标题文本**（列表复用会让标题重复，旧实现因此踩坑）。
- **2026-10-09 整体改名（UsbSwitch→AdbSwitch）**：模块文件夹 `usbswitch`→`adbswitch`、Gradle `rootProject.name` `UsbSwitch`→`AdbSwitch`、类 `UsbToggleService`→`AdbToggleService` / `UsbTileService`→`AdbTileService`、日志 TAG `UsbSwitch`→`AdbSwitch`、包名 `com.elleng.devswitch`→`com.elleng.adbswitch`、通知渠道 id `usb_switch_channel`/`dev_switch_channel`→`adb_switch_channel`；文档/脚本（README、build-all.sh、archive/README、icon-review）同步。包名变更后旧 App 卸载即带走旧渠道，故不再保留迁移删除代码。
- **联动功能 UI 改名「快捷打开」**（2026-10-07）：用户觉得「联动」不贴切，统一改名为「快捷打开」。**仅改面向用户的文案与布局**；内部标识符（`LinkagePrefs`、`linkage_pkgs`/`linkage_default`/`linkage_enabled` 等 cfg 键、`EXTRA_LINKAGE_PKG`、`ACTION_DESKTOP_LINKAGE`、`SC_ID_LAUNCH_APP`=launch_linkage_app、`catLinkage`）**一律保持不变**（避免破坏已落地的桌面快捷方式 intent 契约与旧设置迁移）。
  - 关键文案：`card_linkage_title`=快捷打开、`linkage_switch`=开启时自动打开默认应用、`linkage_default_title`=默认快捷应用、`linkage_manage_title`=管理快捷应用、`kill_before_open_title`=开启调试自动关闭快捷应用；快捷应用行摘要改 `quick_hint`=「点击快捷打开，长按创建快捷方式」（不再显示包名），去掉了「（可多选）」。
  - 交互重构：快捷应用行**去掉「打开/快捷」两个按钮** → 整行点击=快捷打开（关调试+启动），**长按整行=创建桌面快捷方式**。默认快捷应用行图标移到**右侧**（`pref_row_icon_right.xml`）。
  - 开关配色：day/night 主题加 `android:colorControlActivated=@color/colorPrimary`，使开关开启态用应用主题蓝（替代默认青色）。
- **任何在 DebugController 的 IO 线程上执行的阻塞调用都必须有超时上限**（2026-10-09）：该执行器是**单线程**的，一次 su/Shizuku 挂起就会永久占死通道，之后所有开关静默失效且只能杀进程恢复。RootUtil/ShizukuUtil 已统一到 `RootUtil.TIMEOUT_MS`（10s），新增通道照抄。
- **真值未经授权通道确认前，UI 不得给出「已关闭 · 可通过检测」这类安全结论**（2026-10-09）：用 `DebugController.isCalibrated()` 判断，未确认显示 `master_checking` + 灰点。道理同「读写必须同源」——HyperOS 向 App 隐藏这两个键，ContentResolver 兜底值不可信。
- **开关类设置项以 cfg 为唯一数据源**：`prefs.xml` 里自定义开关一律 `android:persistent="false"`，进入时按 cfg `setChecked` 回填，杜绝 framework 默认 SharedPreferences 与 cfg 两份状态。
- **授权模式 UI**：「调试开关」分类末尾放 `authMode`（点击选 root/Shizuku）+ `authStatus`（右侧「重试」按钮，未授权时显）；每次进页面且当前模式未授权时双检测，底部悬浮通知（绿勾=Shizuku模式已授权 / 黄警=root模式未授权）。

## 构建环境（2026-10-05 实测可用，勿信「无 SDK」旧记）
- **本机有 Android SDK**：`/Users/ellen/Library/Android/sdk`（platforms 34~37、build-tools 34/35/36），`local.properties` 已指向它；`ANDROID_HOME` 虽空但无关。
- Gradle 8.13 已缓存在 `~/.gradle/wrapper/dists/gradle-8.13-bin/`。AGP 8.7.2、JDK 21 均可用。
- 离线构建：`gradle-8.13/bin/gradle --offline assembleRelease`（产物 `app/build/outputs/apk/release/app-release.apk`，固定 keystore `../../keystore/devhider.jks`）。
- **Shizuku 依赖**：api/provider/aidl 13.1.5 + `androidx.annotation:1.3.0` 全部已缓存，可离线。
- **ProGuard 坑**：Shizuku 的 api aar 自带 `proguard.txt` 为空，必须在本工程 `app/proguard-rules.pro` 显式 `-keep class rikka.shizuku.**` 与 `-keep class moe.shizuku.**`，否则仅靠引用保留、重构后易误删导致切 Shizuku 模式 NoClassDefFound。

## 验证产物小技巧
- 混淆后类名被改；验证新代码真在 APK 里：解压 APK，对 `classes.dex` 用 Python 字节计数（`open(f,'rb').read().count(b'...')`），中文用 `.encode('utf-8')`，别用 shell grep（中文会假阴性）。资源串在 `resources.arsc`（本工程为 UTF-8 编码）同理。
