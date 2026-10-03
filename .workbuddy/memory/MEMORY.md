# DevOptsHider 项目记忆

## 项目定位
- 当前主方案：**USB 调试一键开关 App**（`usbswitch/` 模块，包名 `com.elleng.usbdebug`），
  用 root 真实改写 `Settings.Global.adb_enabled`，关掉后让 HSBC 类 RASP 触发的派生属性一次性归零。
- 旧 Xposed / Zygisk / 探针 方案**已归档到 `archive/`**，不再主推，也不在常规构建范围内。

## 关键约定
- 主界面用 framework 原生 `PreferenceActivity`（`usbswitch/app/src/main/res/xml/prefs.xml` + `MainActivity`）。
- 自定义行布局：`pref_row.xml`（普通行，图标框 40dp）、`pref_row_state.xml`（调试三行，图标框 20dp）。
- framework Preference 整行不可点；点击靠 `MainActivity.wireRowClicks()` 按标题文本给 itemView 挂监听。
- 桌面快捷方式相关 action：`TOGGLE_MASTER`（一键开关）、`OPEN_HOME`（打开主页）；
  动态"启动联动应用"快捷方式用 ShortcutManager `SC_ID_LAUNCH_APP`（非桌面图标，区别于"创建桌面快捷方式"）。

## 环境
- 本机无 Android SDK（`ANDROID_HOME` 空），无法在此编译；构建用 `./build-all.sh`（Gradle 8.13 + SDK 36，零三方依赖）。
