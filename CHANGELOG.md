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

