# 更新日志

历史版本 APK 见 [GitHub Releases](https://github.com/gaoyilun/adbSwitch/releases)；国内用户亦可到[蒲公英安装页](https://www.pgyer.com/adbSwitch)获取。

## v1.0.3（2026-10-16）

### 重点：新增「免 root 模式」，授权一次、长期免折腾

- **免 root 模式（WRITE_SECURE_SETTINGS 一次性授权）**：无需 root、也无需 Shizuku，首次授予 `WRITE_SECURE_SETTINGS` 权限后，后续开关 USB 调试 / 无线调试**不再需要反复开关开发者选项或 adb**。授权粘滞，仅卸载重装需重新授权。默认推荐此模式。
- 授权模式现提供三种通道：**免 root / root / Shizuku**，可在「授权模式」中一键切换。

### 安全性

- 修复 `am force-stop` 命令注入：对联动应用包名增加白名单校验。

### 稳定性与体验

- 修复 WSS 模式读取时静默回退 `su` 导致的卡顿 / 误报。
- 修复 FileProvider 在下载回退路径下的崩溃。
- 修复 HTTP 200 续传导致 APK 下载损坏。
- 修复并发写队列竞态（TOCTOU）、`ParcelFileDescriptor` 泄漏、UTF-8 多字节截断、`StringBuilder` 线程不安全、后台通知状态与外部变更失步。
- 适配 Android 15 边到边（edge-to-edge）与导航栏配色。
- 优化「快捷打开」列表图标加载，回前台不再重复解码。

### 交互优化

- 优化授权交互：首次授权后允许直接在「授权模式」中切换到 root / Shizuku 通道，不再被锁死在免 root 引导里（选免 root 且未授权时仍会先弹一次性授权引导）。
- 修复应用内更新：缺「安装未知应用」授权跳系统设置授权后返回，直接复用已下载的安装包继续安装，不再重新下载（重试同样走复用路径）。

### 免 root 模式在 HyperOS 等 ROM 上的修复（参考 Geto / IMD）

- **根因**：纯 WSS（已授权 `WRITE_SECURE_SETTINGS`、无 Shizuku 兜底）时，ContentResolver 读 `adb_enabled` / `adb_wifi_enabled` / `development_settings_enabled` 会被 ROM 隐藏/欺骗；旧实现把被隐藏的读当真值，导致「状态读不对、写后又被假回读打回、开关拨不动」。
- **对齐开源方案**：参考 Geto、IMD（It's My Device）的实现——它们同样走标准 WSS API（无 HyperOS 专属绕过），写用 `Settings.Global.putString` 并**据其布尔返回值识别"行是否真写进去"**，读用 `getString`。本版本相应改用 `getString` / `putString`，并在 `putString` 返回 `false`（被系统静默丢弃、不抛错）时如实标记写入失败（REASON_WRITE_DROPPED），UI 提示「写入未生效」而非静默弹回。
- **纯 WSS 信任自身写入**：无 Shizuku 兜底时，写后不再用被隐藏的 ContentResolver 回读，改为信任本次写入 + 本地持久化的「上次确认状态」（lastKnownState），避免 UI 自相矛盾；后台校准也跳过，防止假值污染本地状态。

