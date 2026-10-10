<div align="center">

# 调试开关 · AdbSwitch

<img src="icon.png" width="96" style="border-radius: 18px;" alt="app icon"/>

**用 root / Shizuku 真实开关 Android 调试状态的本地工具**

[![Platform](https://img.shields.io/badge/Platform-Android%208.1%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com)
[![Written in](https://img.shields.io/badge/Written%20in-Java-007396?logo=openjdk&logoColor=white)](https://www.java.com)
[![UI](https://img.shields.io/badge/UI-Material%203-6750A4?logo=materialdesign&logoColor=white)](https://m3.material.io)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

</div>

---

一个为「临时关掉调试、通过 App 检测」设计的开源工具：用 root（`su`）或 [Shizuku](https://shizuku.rikka.app/)（shell uid 2000）真实改写系统设置里的调试开关，界面全面采用 Material 3 Design 并支持莫奈动态取色。

## ✨ 功能特性

| 功能 | 说明 |
| :--- | :--- |
| 🔌 **调试主开关** | 一键开关 USB 调试（`adb_enabled`）与无线调试（`adb_wifi_enabled`）：关闭 = 两者全关（adbd 停止，真正可通过检测），打开 = 还原关闭前的组合 |
| 📡 **USB / 无线子开关** | 单独控制每一个键，带状态指示灯；ROM 隐藏这些键时仍能经授权通道读写真实状态 |
| 🛠️ **开发者选项独立开关** | 单独控制开发者选项入口（`development_settings_enabled`），与调试开关互不联动 |
| 📋 **QS 快捷磁贴** | 下拉通知栏加入「调试开关」磁贴，点一下即切换；长按菜单还有 USB / 无线子开关与快捷打开入口 |
| 🔔 **临时关闭 + 常驻通知** | 关闭调试后常驻一条可一键恢复的通知（Android 16+ 支持「实时动态」提升为状态栏胶囊） |
| ⚡ **快捷打开** | 从启动器打开本 App 自动关调试并跳转目标 App（适合「打开 App 前过检测」）；支持创建桌面快捷方式、开启调试前自动停止目标应用 |
| 🎨 **Material 3 + 动态取色** | 全面采用 M3 组件与色彩角色，Android 12+ 跟随壁纸取色 |

## 📱 系统要求

| 项目 | 要求 |
| :--- | :--- |
| 系统版本 | Android 8.1（API 27）及以上 |
| 授权方式 | 已 root，或已安装并授权的 [Shizuku](https://shizuku.rikka.app/)（无需 root） |
| 动态取色 | Android 12+ 跟随壁纸；低版本回落静态配色 |

## 🚀 安装与使用

1. 在 [**Releases**](https://github.com/gaoyilun/adbSwitch/releases) 页面下载最新 APK 并安装；
2. 首次打开选择授权模式（root / Shizuku）并完成授权；
3. 下拉通知栏编辑快捷开关，把「调试开关」磁贴拖进快捷区；
4. 想临时过检：关闭调试，用完点常驻通知上的「恢复打开」；
5. 想一键直达：进入「快捷打开」设置目标应用，可长按创建桌面快捷方式。

## 🛠️ 从源码构建

```bash
# 本机需 JDK 17+ 与 Android SDK（API 36）
git clone https://github.com/gaoyilun/adbSwitch.git
cd adbSwitch
./build-all.sh        # 自动定位 Gradle；无签名文件时自动生成调试用 keystore
# 产物：adbswitch/app/build/outputs/apk/release/*.apk
```

- 蒲公英更新通道密钥从 `adbswitch/pgyer.properties` 注入（gitignored，模板见 `pgyer.properties.example`）；不配置该文件时蒲公英通道自动禁用，仅保留 GitHub 更新通道。
- 正式发布签名：在 `keystore/signing.properties` 里配置你自己的 `storePassword / keyAlias / keyPassword`。

## 🧩 技术要点

- **读写同源**：部分 ROM 会向第三方应用隐藏调试相关键（ContentResolver 读恒 0），本 App 所有读写都经同一授权通道（su / Shizuku shell），带回读确认，杜绝「写入未生效但 UI 显示成功」；
- **单 shell 合并写入**：读前态 → 写入 → 回读后态合并为一条命令，减少 fork 开销；
- **状态机收口**：所有写入经 `DebugController` 单一入口，同键请求去重合并，写失败绝不污染状态缓存；
- **弱提示更新**：版本判定只比 `versionCode`，启动检查节流 3 天、主线程空闲才跑，发现新版本只 Toast + 关于行提示，绝不强制弹窗；下载支持 SHA-256 校验。

## ❓ 常见问题

**Q：关掉调试后，开发者选项总开关还在吗？**

A：在。调试开关只改 `adb_enabled` / `adb_wifi_enabled`；开发者选项入口是独立开关，需要时单独控制。

**Q：关掉开发者选项入口后，怎么恢复？**

A：系统设置 → 关于手机 → 连点「版本号」7 次即可重新开启开发者选项。

**Q：没有 root 能用吗？**

A：可以。安装并授权 [Shizuku](https://shizuku.rikka.app/) 后，本 App 以 shell 身份完成同样的改写，无需 root。

**Q：这是伪装关闭吗？**

A：不是。它通过系统设置真实改写 `adb_enabled`，系统会据此重新配置 USB（`sys.usb.config` 去掉 `adb`）、停止 adbd，是真实的状态变化。

## 🔒 隐私

本 App 无任何遥测与数据上传；唯一的网络请求是「检查更新」（访问 GitHub Release 元数据与蒲公英通道），且可在不联网的情况下正常使用全部功能。

## 📄 许可证

本项目以 [GPL-3.0](LICENSE) 许可证开源。

<p align="center">
  Made with ❤️ on Android by <a href="https://github.com/gaoyilun">@gaoyilun</a>
</p>
