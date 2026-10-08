# 归档：旧方案（Xposed / Zygisk / 探针 三件套）

> 这些代码是项目早期的「hook 隐藏」方案，**已于 2026-10-03 暂停，不再主推**。
> 实测确认 HSBC 类 RASP 真正触发的是 USB 调试开启后的派生属性
> （`sys.usb.config`/`sys.usb.state = mtp,adb`、`init.svc.adbd = running`、`persist.sys.usb.config = adb`、
> `sys.usb.adb.disabled = 0`）——这些只是 `Settings.Global.adb_enabled = 1` 的副产品。
> **真实把 USB 调试关掉，派生值会一次性自动变干净**，无需逐条伪造，也不会和任何 hook 方案打架。
> 因此当前主方案改为 root 授权的调试开关（见仓库根目录 `adbswitch/` 与 `README.md`）。

本目录内容：

| 目录 / 文件 | 作用层 | 说明 |
|---|---|---|
| `lsposed/` | Java（ART hook） | LSPosed 模块 + 探针 APK。覆盖 Settings.Global/Secure/System、`ContentResolver.call/query`、`SystemProperties`、`Build.TYPE/TAGS/FINGERPRINT`、`Debug.isDebuggerConnected`、adb 相关文件路径、5555 端口 |
| `zygisk/` | native（Zygisk + PLT hook） | 拦截 native 的 `__system_property_get/read/read_callback`、`open/openat/access/faccessat`、`connect`，堵 LSPosed 够不到的 native 通道 |
| `dist/DevOptsHider-LSPosed-v1.0.0.apk` | — | 旧 LSPosed 模块产物 |
| `dist/DevOptsHider-Probe-v1.0.0.apk` | — | 旧探针产物 |
| `dist/DevOptsHider-Zygisk-v1.0.0.zip` | — | 旧 Zygisk Magisk 模块产物 |

> 注意：LSPosed 单装对 HSBC 比 Zygisk 安全（Zygisk 的 PLT hook 会让加固后的 HSBC native 库崩溃）。
> 即便要试旧方案，也建议只用 LSPosed，先别开 Zygisk。这些结论仅作历史参考，当前不再维护。
