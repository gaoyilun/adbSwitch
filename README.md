<div align="center">

# 调试开关 · AdbSwitch

<img src="icon.png" width="96" style="border-radius: 18px;" alt="app icon"/>

**一键真实开关 Android 调试状态的本地工具 · 默认免 root**

[![Platform](https://img.shields.io/badge/Platform-Android%208.1%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com)
[![Written in](https://img.shields.io/badge/Written%20in-Java-007396?logo=openjdk&logoColor=white)](https://www.java.com)
[![UI](https://img.shields.io/badge/UI-Material%203-6750A4?logo=materialdesign&logoColor=white)](https://m3.material.io)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

</div>

---

部分银行类应用（例如香港汇丰银行 HSBC 的 App）会检测手机的 adb 调试状态，而每次手动进开发者选项开关一遍相当繁琐。

adbSwitch 就是为这件事做的：一个**真实改写系统调试状态的快捷开关**，支持 USB 调试、无线调试与开发者选项的开关，可从桌面快捷方式或通知磁贴一键操作；关闭调试后会常驻一条通知，点一下即可恢复（Android 16+ 支持「实时动态」，提升为状态栏胶囊）。

它默认走**免 root 模式**：只需一次性授权，之后应用自己就能改写调试状态，既不要求设备已 root，也不需要 Shizuku 常驻后台。root / Shizuku 保留为兜底通道与「代为执行授权」的手段。

项目以 GPL-3.0 开源，代码可自行审查、自行构建。

## 📦 下载

| 渠道 | 地址 |
| :--- | :--- |
| GitHub Releases | <https://github.com/gaoyilun/adbSwitch/releases> |
| 蒲公英（国内下载） | <https://www.pgyer.com/adbSwitch> |
| 城通云 | <https://url05.ctfile.com/f/20347205-17569904400247-fe06bb?p=1245> |
| 蓝奏云 | <https://elleng.lanzouv.com/iy7Pd4bhqhvc> |

> 城通云 / 蓝奏云为网盘镜像，适合 GitHub 与蒲公英访问不畅时备用；各渠道的 APK 均由同一份源码构建。

## ✨ 功能特性

| 功能 | 说明 |
| :--- | :--- |
| 🔌 **调试主开关** | 一键开关 USB 调试（`adb_enabled`）与无线调试（`adb_wifi_enabled`）：关闭 = 两者全关（adbd 停止，真正可通过检测），打开 = 还原关闭前的组合 |
| 🆓 **免root模式（默认）** | 一次性授权后由应用进程内直写系统设置，无需 root、无需 Shizuku 常驻；授权粘滞，跨重启 / 软重启保留 |
| 📡 **USB / 无线子开关** | 单独控制每一个键，带状态指示灯；ROM 隐藏这些键时仍能经授权通道读写真实状态 |
| 🛠️ **开发者选项独立开关** | 单独控制开发者选项入口（`development_settings_enabled`），与调试开关互不联动 |
| 📋 **QS 快捷磁贴** | 下拉通知栏加入「调试开关」磁贴，点一下即切换；长按菜单还有 USB / 无线子开关与快捷打开入口 |
| 🔔 **临时关闭 + 常驻通知** | 关闭调试后常驻一条可一键恢复的通知（Android 16+ 支持「实时动态」提升为状态栏胶囊） |
| ⚡ **快捷打开** | 把会检测调试状态的应用加入快捷应用列表，点列表项或桌面快捷方式即「先关调试、再启动目标应用」；重新开启调试时可自动强制停止这些应用（默认开启），避免其在状态翻转瞬间读到调试 |
| 🎨 **Material 3 + 动态取色** | 全面采用 M3 组件与色彩角色，Android 12+ 跟随壁纸取色 |

## 🔑 授权模式

三种模式，**默认推荐「免root模式」**。三者的目标状态是一致的 —— 拿到 `WRITE_SECURE_SETTINGS` 权限后由应用自己改系统设置；root 与 Shizuku 除了作为兜底写通道，还可以**代为执行那一次授权**。

| 模式 | 定位 | 前置条件 | 说明 |
| :--- | :--- | :--- | :--- |
| 🆓 **免root模式**（推荐） | 默认写通道 | 一次性 `pm grant` | 授权后应用进程内直写 `Settings.Global`，无需 root、无需 Shizuku 常驻 |
| 👑 **root** | 兜底写通道 / 可代跑授权 | 设备已 root | 经 `su` 执行 `settings`；也可代跑一次 `pm grant` 完成免root授权 |
| 🔧 **Shizuku** | 兜底写通道 / 可代跑授权 | Shizuku 已启动并授权本应用 | 以 shell（uid 2000）执行 `settings`；也可代跑一次 `pm grant` |

### 免root模式的一次性授权

该权限无法在系统设置里点击授予，只能用**以下任一方式执行一次** `pm grant`：

```bash
adb shell pm grant com.elleng.adbswitch android.permission.WRITE_SECURE_SETTINGS
```

- **手动 adb**：电脑连接后在终端执行（引导弹窗里可一键复制命令）
- **Shizuku 代跑**：本机操作，无需电脑
- **root 代跑**：本机操作，无需电脑

授权成功后立即生效，**跨重启 / 软重启保留**；仅在卸载重装后需要重新授权一次（授权绑定具体安装）。

### 免root模式的实现原理（WRITE_SECURE_SETTINGS）

免root模式并非取巧，而是 Android 权限体系里一条**官方留出的合法通道**：`WRITE_SECURE_SETTINGS` 的 protectionLevel 为 `signature|privileged|development`，其中的 `development` 使其可经 `pm grant` 授予普通应用而无需系统签名；授权后应用进程内即可 `Settings.Global.putInt()`，与 `adb shell settings put global` 走同一个 `SettingsProvider`，效果等同且无需 fork 进程。授权是安装级状态，跨重启 / 软重启保留，仅卸载重装失效；该权限无法在系统设置里点击授予，这正是需要一次性引导的原因。

- 安全性边界：不同系统的表现存在差异 —— 开启 USB / 无线调试时系统**可能**弹出确认框；若遇到弹窗，也可改用 root / Shizuku 兜底模式来免去它。
- 部分 ROM（如 HyperOS）会向第三方应用的 `ContentResolver` 隐藏调试相关键，因此真值读取优先与写入同源：免root模式下若设备装有 Shizuku，则回退到 shell 读取，避免状态显示失真。

## 📱 系统要求

| 项目 | 要求 |
| :--- | :--- |
| 系统版本 | Android 8.1（API 27）及以上 |
| 无线调试 | Android 11（API 30）及以上（该版本才引入无线调试） |
| 授权方式 | 免root模式需一次性授权（手动 adb，或借助已有的 root / Shizuku 代跑）；无 root 且无 Shizuku 的设备亦可用 |
| 动态取色 | Android 12+ 跟随壁纸；低版本回落静态配色 |

## 🚀 安装与使用

1. 在上方任一渠道下载最新 APK 并安装；
2. 首次打开会自动弹出一次性授权引导，任选一种方式完成授权（默认就是推荐的免root模式）；
3. 下拉通知栏编辑快捷开关，把「调试开关」磁贴拖进快捷区；
4. 想临时过检：关闭调试，用完点常驻通知上的「恢复打开」；
5. 想一键直达：进入「快捷打开」添加目标应用，长按该行可创建桌面快捷方式。

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

- **读写同源**：部分 ROM（如 HyperOS）会向第三方应用隐藏调试相关的键（`ContentResolver` 读恒为 0），因此真值读取优先与写入走同一通道；免root模式下若 Shizuku 可用则回退到 shell 读，避免状态显示失真；
- **写通道按模式分派**：免root模式走应用进程内 `Settings.Global.putInt`（无进程 fork）；root / Shizuku 兜底通道仍走单条 shell（读前态 → 写入 → 回读后态合并为一条命令，减少 fork 开销）；
- **状态机收口**：所有写入经 `DebugController` 单一入口，同键请求去重合并，写失败绝不污染状态缓存；
- **授权粘滞但可撤销**：`pm grant` 绑定具体安装，卸载 / 重装后会失效，故每次回到前台都会重新校验并引导补授权；
- **弱提示更新**：版本判定只比 `versionCode`，启动检查节流 3 天、主线程空闲才跑，发现新版本只 Toast + 关于行提示，不强制弹窗；下载支持 SHA-256 校验。

### 已知限制

- 免root模式下「开启调试前先强制停止快捷应用」需要 shell 能力（`am force-stop`），因此**仅在设备装有 Shizuku 时生效**；纯免root且无 Shizuku 时会跳过这一步，其余功能不受影响。
- 部分厂商会在 `pm grant` 上叠加额外限制。若授权命令报错，可先在开发者选项中确认相关开关，或改用 root / Shizuku 兜底模式。

## ❓ 常见问题

**Q：没有 root 能用吗？**

A：能用，而且这是默认方式。免root模式只需一次性执行一条 `pm grant` 命令（或用已有的 root / Shizuku 代跑一次），之后无需 root、也无需 Shizuku 常驻即可正常开关调试。

**Q：一次性授权要重复做吗？**

A：通常不用。授权粘滞，跨重启 / 软重启都保留；只有卸载重装（授权绑定具体安装）后才需要再做一次。

**Q：关掉调试后，开发者选项总开关还在吗？**

A：在。调试开关只改 `adb_enabled` / `adb_wifi_enabled`；开发者选项入口是独立开关，需要时单独控制。

**Q：关掉开发者选项入口后，怎么恢复？**

A：系统设置 → 关于手机 → 连点「版本号」7 次即可重新开启开发者选项。

**Q：这是伪装关闭吗？**

A：不是。它通过系统设置真实改写 `adb_enabled`，系统会据此重新配置 USB（`sys.usb.config` 去掉 `adb`）、停止 adbd，是真实的状态变化。

**Q：快捷打开里的应用为什么会被强制停止？**

A：重新开启调试前先 `am force-stop` 目标应用，可避免它在调试状态翻转的瞬间读到「已开启」而触发检测。该行为默认开启，可在「快捷打开」设置里关闭。

## 🔒 隐私

本 App 无任何遥测与数据上传；唯一的网络请求是「检查更新」（访问 GitHub Release 元数据与蒲公英通道），且可在不联网的情况下正常使用全部功能。免root模式所需的授权由用户在本地自行完成，不涉及任何账号或服务端。

## 📄 许可证

本项目以 [GPL-3.0](LICENSE) 许可证开源。

<p align="center">
  Made with ❤️ on Android by <a href="https://github.com/gaoyilun">@gaoyilun</a>
</p>
