<p align="center">
  <a href="README.md">🇨🇳 中文</a> · <a href="README.en.md">🇺🇸 English</a>
</p>

<div align="center">

# 调试开关 · AdbSwitch

<img src="icon.png" width="96" style="border-radius: 18px;" alt="app icon"/>

**一键开关过检，处理调试与无障碍等痕迹 · 默认免 root**

[![Platform](https://img.shields.io/badge/Platform-Android%208.1%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com)
[![Written in](https://img.shields.io/badge/Written%20in-Java-007396?logo=openjdk&logoColor=white)](https://www.java.com)
[![UI](https://img.shields.io/badge/UI-Material%203-6750A4?logo=materialdesign&logoColor=white)](https://m3.material.io)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

</div>

---

部分银行类应用（例如香港汇丰银行 HSBC 的 App）会检测手机的 adb 调试状态或无障碍服务痕迹，而每次手动进设置逐项开关一遍相当繁琐。

adbSwitch 就是为这件事做的：一个**一键真实改写系统状态的本地工具**——关一次就把调试与无障碍痕迹一起处理掉，可从桌面快捷方式或通知磁贴操作；关闭后常驻通知，点一下即可恢复（Android 16+ 支持「实时动态」状态栏胶囊）。开关项支持自定义。

它默认走 Android 系统预留的**免 root 通道**：只需在首次使用时完成一次授权，之后应用自己在进程内改写调试状态，既不需要设备已 root，也不需要 Shizuku 常驻后台。不过 root 与 Shizuku 依然很有用：它们能让用户更轻松地完成那一次授权（本机即可代跑 `pm grant`，无需电脑），拿到权限后还能解锁更多高级功能；并且它们各自带有对应的写入模式，可经 shell 原生直写系统设置，供有需要的用户直接使用。

项目以 GPL-3.0 开源，代码可自行审查、自行构建。

## 📦 下载

| 渠道 | 地址 |
| :--- | :--- |
| GitHub Releases | <https://github.com/gaoyilun/adbSwitch/releases> |
| Gitee Releases | <https://gitee.com/gaoyilun/adbSwitch/releases> |
| 蒲公英（国内下载） | <https://www.pgyer.com/adbSwitch> |

## ✨ 功能特性

| 功能 | 说明 |
| :--- | :--- |
| 🔌 **调试主开关** | 一键把调试与无障碍痕迹一起关掉（adbd 停止，真正可通过检测）；状态按「跟随主开关」的项聚合，打开时还原关闭前的组合 |
| 🧩 **开关解耦，自由自定义** | USB 调试、无线调试、开发者选项、第三方无障碍服务是四项独立开关项，可在「管理开关项」中逐项设置是否显示、是否跟随主开关、是否加入桌面快捷菜单并拖动排序；隐藏的项本应用完全不接管 |
| 🆓 **支持免root / Shizuku / root 多种环境** | 默认免root：一次性授权后由应用进程内直写系统设置，无需 root、无需 Shizuku 常驻；也可切换 root / Shizuku 写通道，授权粘滞，跨重启 / 软重启保留 |
| ♿ **无障碍服务一键开关** | 一键关闭 / 恢复第三方无障碍服务，只统计第三方项；系统向本应用隐藏列表的机型会先弹风险确认，不盲关系统预装项。恢复写后回读确认，绑定慢的机型也不会卡在「需手动开启」 |
| 🧭 **直达系统设置** | 调试三行右侧有「›」箭头，点一下即跳到对应系统设置页，长按整行亦可；未授权、甚至开关置灰时也能用，方便自己动手去系统里开 |
| 📋 **QS 快捷磁贴** | 下拉通知栏加入「调试开关」磁贴，点一下即按主开关语义切换；长按直达应用主页，状态灯与标签按跟随项聚合显示 |
| 🔔 **临时关闭并上岛，Live Updates 通知一键恢复** | 关闭调试后常驻一条通知，点一下即可恢复；Android 16+ 提升为「实时动态」状态栏胶囊 |
| ⚡ **快捷打开** | 把会检测调试状态的应用加入快捷应用列表，点列表项或桌面快捷方式即「先关调试、再启动目标应用」；重新开启调试时可自动强制停止这些应用（默认开启），避免其在状态翻转瞬间读到调试 |
| 🌐 **多语言界面** | 内置英文（res/values-en） |
| 🎨 **Material 3 + 动态取色** | 全面采用 M3 组件与色彩角色，Android 12+ 跟随壁纸取色 |

## 🔑 工作原理与授权

### 默认通道：WRITE_SECURE_SETTINGS（免 root）

adbSwitch 的核心写入走 Android 权限体系里一条**官方预留的合法通道**：`WRITE_SECURE_SETTINGS` 的 protectionLevel 含 `development`，使其可被 `pm grant` 授予普通应用而无需系统签名。授权后，应用进程内直接调用 `Settings.Global.putInt()`，与 `adb shell settings put global` 走同一个 `SettingsProvider`，效果等同且无需 fork 进程。

这是本应用的默认工作方式，也支持切换为 Shizuku / root 模式。

### 首次授权（三种方式，任选其一）

该权限无法在系统设置里点击授予，只能在首次使用时执行一次 `pm grant`：

```bash
adb shell pm grant com.elleng.adbswitch android.permission.WRITE_SECURE_SETTINGS
```

| 方式 | 前置条件 | 说明 |
| :--- | :--- | :--- |
| 手动 adb | 一台电脑 | 复制上面的命令在终端执行（应用内有引导，可一键复制） |
| Shizuku 代跑 | Shizuku 已启动并授权本应用 | 本机操作，无需电脑 |
| root 代跑 | 设备已 root | 本机操作，无需电脑 |

授权成功后立即生效，**跨重启 / 软重启保留**；仅在卸载重装后需要重新授权一次（授权绑定具体安装）。

### Shizuku / root 模式

除默认的免 root 通道外，也可在应用设置里切换到 Shizuku 或 root 模式，经 shell 原生直写系统设置：

- **Shizuku 模式**：借 Shizuku 的 shell（uid 2000）身份执行 `settings`，无需设备 root；
- **root 模式**：通过 `su` 执行 `settings`，适合已 root 的设备；
- **免去系统确认框**：开启 USB / 无线调试时个别系统会弹确认框，这两种模式通常可以免去。

> 三种模式目标一致——都是真实改写系统调试状态，只是拿权限的手段不同；免 root 是默认方式，Shizuku / root 可按需切换。

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
3. 按需定制开关项：进入「管理开关项」，逐项设置显示 / 跟随主开关 / 是否加入快捷菜单并拖动排序；不想让本应用接管的项直接隐藏；
4. 下拉通知栏编辑快捷开关，把「调试开关」磁贴拖进快捷区（长按磁贴可回到主页）；
5. 想临时过检：关闭调试，需要时连无障碍痕迹一起关；用完点常驻通知上的「恢复打开」；
6. 想一键直达：进入「快捷打开」添加目标应用，长按该行可创建桌面快捷方式。

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
- **状态机收口**：所有写入经 `DebugController` 单一入口，同键请求去重合并，写失败绝不污染状态缓存；无障碍写入把系统设置键回读与写入合并为一次 shell，写完秒级显示结果，绑定核验放到后台静默纠正，避免慢绑定机型长时间卡在「需手动开启」；
- **快捷方式鉴权**：动态快捷方式携带安装级随机 token，中转页校验通过才执行，杜绝第三方应用构造相同 intent 绕过界面改系统设置；
- **授权粘滞但可撤销**：`pm grant` 绑定具体安装，卸载 / 重装后会失效，故每次回到前台都会重新校验并引导补授权；
- **弱提示更新**：版本判定只比 `versionCode`，启动检查节流 3 天、主线程空闲才跑，发现新版本只 Toast + 关于行提示，不强制弹窗；下载支持 SHA-256 校验。

### 已知限制

- 免root模式下「开启调试前先强制停止快捷应用」需要 shell 能力（`am force-stop`），因此**仅在设备装有 Shizuku 时生效**；纯免root且无 Shizuku 时会跳过这一步，其余功能不受影响。
- 部分厂商会在 `pm grant` 上叠加额外限制。若授权命令报错，可先在开发者选项中确认相关开关，或改用 root / Shizuku 模式。

## ❓ 常见问题

**Q：没有 root 能用吗？**

A：能用，而且这是默认方式。免root模式只需一次性执行一条 `pm grant` 命令（或用已有的 root / Shizuku 代跑一次），之后无需 root、也无需 Shizuku 常驻即可正常开关调试。

**Q：一次性授权要重复做吗？**

A：通常不用。授权粘滞，跨重启 / 软重启都保留；只有卸载重装（授权绑定具体安装）后才需要再做一次。

**Q：主页上有些开关我不想要，或者不想被主开关一起关掉？**

A：进入「管理开关项」逐项设置即可。隐藏的项本应用完全不接管（不显示、不写入、不跟随）；想保留但独立拨动，就取消「跟随主开关」；想让某一项出现在桌面长按菜单，就勾选「快捷菜单」。顺序也可拖动调整。

**Q：无线调试显示「已开启 · 未激活」是什么意思？**

A：系统的无线调试开关是开的，但当前连接的默认网络不是 Wi-Fi，adb over network 此时不可用，部分 ROM 可能会在设置中显示开关为关闭。连回 Wi-Fi 会自动恢复为「已开启 · adbd 运行中」。

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
