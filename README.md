# DevOpts Hider

> **方向说明（2026-10-03 更新）**：原始「hook 隐藏」方案已告一段落。
> 实测确认 HSBC 类 RASP 真正触发的是 **USB 调试开启后的派生属性**
> （`sys.usb.config`/`sys.usb.state = mtp,adb`、`init.svc.adbd = running`、`persist.sys.usb.config = adb`、
> `sys.usb.adb.disabled = 0`）——这些只是 `Settings.Global.adb_enabled = 1` 的副产品。
> **真实把 USB 调试关掉，派生值会一次性自动变干净**，无需逐条伪造，也不会和 HMA-OSS 之类 hook 方案打架。
>
> 因此当前主方案改为：**一个 root 授权的 USB 调试快捷开关 App**（见下方「USB 调试快捷开关」章节）。
> 下面的 LSPosed / Zygisk / 探针 三件套作为**原始方案保留**在 `dist/`，不再主推。

---

## USB 调试快捷开关（当前主方案）

产物：`DevOptsHider-UsbSwitch-v*.apk`

### 它解决什么

不用 hook、不碰任何 App 内部，只是用 root（`su`）改写 `Settings.Global.adb_enabled`，
把 USB 调试**真实地**开 / 关。关掉后，上面那些让 HSBC 弹窗的派生属性会一次性归零。

### 三个能力

1. **主界面开关**：打开 App 即可一键切换当前 USB 调试状态（需要 root 授权）。
2. **通知控制中心快捷磁贴（QS Tile）**：下拉通知栏长按/编辑加入「USB 调试」磁贴，点一下即切换；
   关掉时会自动拉起常驻通知，开掉时自动撤掉。
3. **临时关闭 + 常驻通知**：点「临时关闭（常驻通知）」→ 关闭 USB 调试，并在通知栏常驻一条
   **「USB 调试：当前已关闭」** 通知，附带「恢复打开」一键动作；恢复正常即自动消失。

### 「启动时自动切换」（默认关闭）

主界面还有一个默认关闭的开关 **「启动时自动切换」**。开启后，App 被唤起（例如被设备自带的
自动任务「打开汇丰 App 就打开本 App」触发）时会**做一次切换**然后自动退场，把前台交还给汇丰。

> 配合自动任务建议：让触发器只命中汇丰**冷启动**（应用首次打开），避免每次回到前台都翻一次状态。
> 若你的自动任务会反复触发，请改用主界面的「临时关闭」按钮流程，或手动用磁贴/通知恢复，避免来回翻状态。

### 安装与使用

1. 安装 `DevOptsHider-UsbSwitch-v*.apk`，首次打开**授予 root（超级用户）权限**。
2. 想要磁贴：下拉通知栏 → 编辑快捷开关 → 把「USB 调试」拖进快捷区。
3. 想临时过检：点「临时关闭（常驻通知）」，用完后点通知上的「恢复打开」即可。

### 原理与边界

- 核心命令：`su -c 'settings put global adb_enabled 0/1'`。系统 `UsbDeviceManager` 会观察该值并重新配置
  USB，使 `sys.usb.config` 自动去掉 `adb`。
- 没有 root 时主界面会提示未检测到 root，开关不可用。
- 这是**真实**开关，不是伪装：关掉后开发者选项里 USB 调试那一项也会显示关闭（但开发者选项总开关仍可保留开启，
  不影响其它已设置项）。

---



典型场景：香港汇丰（HSBC HK）一类带 RASP/加固的 App，一开屏就弹「请关闭设备的开发者选项」。

这类检测一般读 `Settings.Global.development_settings_enabled` / `adb_enabled`、系统属性
（`ro.debuggable`、`sys.usb.state`、`init.svc.adbd`）、`/sys/class/android_usb/...`、无线调试端口等。

**为什么 HMA-OSS 没用**：HMA-OSS 其实已经**对每个 App 伪造了** `adb_enabled` / `adb_wifi_enabled` /
`development_settings_enabled` / `hidden_api_policy` / `mock_location` 这些 Settings 键，但 HSBC 仍然报「请关闭开发者选项」——
说明 HSBC 的检测**不（只）走 Settings 键**，而是走更底层的通道：它直接通过 native 的
`__system_property_get` 读 `ro.debuggable` / `ro.build.type` / `sys.usb.state`（这些值在 App 进程启动时就被
`Build.TYPE`、`Build.IS_DEBUGGABLE` 读走），或者读 `/sys/class/android_usb/...` 文件状态。
**HMA-OSS 只 hook 了 Java 层的 `SystemProperties`，摸不到 native 这一层**，所以无效。

**本方案的关键差异**：额外的 **Zygisk 模块**用 PLT hook 拦截了 native 的 `__system_property_get` /
`__system_property_read` / `__system_property_read_callback` / `__system_property_get_bool` 以及
`open/openat/access/faccessat` / `connect`，把 HMA-OSS 够不到的那条 native 通道也堵上了。这就是能跑通 HSBC 的关键。

---

## 原始方案产物（已暂停，保留在 dist/）

> 以下三件套是 hook 隐藏的原始方案，已被上面的 USB 调试快捷开关取代。仅作参考保留。

| 产物 | 作用层 | 覆盖内容 |
|---|---|---|
| `DevOptsHider-LSPosed-v*.apk` | Java（ART hook） | Settings.Global/Secure/System、`ContentResolver.call/query`、`SystemProperties`、`Build.TYPE/TAGS/FINGERPRINT`、`Debug.isDebuggerConnected`、adb 相关文件路径、5555 端口 |
| `DevOptsHider-Zygisk-v*.zip` | native（Zygisk + PLT hook） | `__system_property_get/read/read_callback`（RASP 的 .so 直接读属性这条路径 LSPosed 够不到）、`open/openat/access/faccessat`（adb 相关 sysfs/文件）、`connect`（无线调试端口）、`android.os.SystemProperties` 的 JNI native 方法 |
| `DevOptsHider-Probe-v*.apk` | 测试工具 | 用和 RASP 一样的方式读一遍所有检测点，列出「读到的值 / 是否已隐藏」，一眼看出哪条通道没堵住 |

LSPosed 覆盖 Java 层，Zygisk 覆盖 native 层，**两条路都要堵**，单装一个都可能被绕。

---

## 安装

### 1. LSPosed 模块

1. 安装 `DevOptsHider-LSPosed-v*.apk`
2. LSPosed 管理器 → 模块 → 勾选 **DevOpts Hider** → 在作用域里勾选目标 App（如 `com.hsbc.hk.personal.banking`，以及 `DevHider 探针` 用来验证）
3. **强制停止**目标 App 再打开（hook 只在进程启动时注入，热重启无效）

### 2. Zygisk / Magisk 模块

1. Magisk App → 模块 → 从本地安装 → 选 `DevOptsHider-Zygisk-v*.zip` → 重启
2. 默认 `blacklist` 模式：**除排除项外对所有 App 生效**，不需要手动配名单
3. 想只对特定 App 生效：
   ```
   adb shell su -c '/data/adb/modules/devopts-hider/action.sh mode whitelist'
   adb shell su -c '/data/adb/modules/devopts-hider/action.sh add com.hsbc.hk.personal.banking'
   ```
   或在 Magisk 里点模块右下角的「操作」按钮查看/切换配置。
4. **强制停止**目标 App 再打开

配置文件在 `/data/adb/devhider/`：

| 文件 | 含义 |
|---|---|
| `mode.txt` | `blacklist`（默认，全部生效） / `whitelist`（仅名单内生效） |
| `packages.txt` | 一行一个包名（whitelist 模式下的目标名单） |
| `exclude.txt` | 一行一个包名，永远不处理 |
| `debug` | 存在此文件则打开日志：`adb logcat -s DevHider` |

> 说明：`com.android.settings`、`com.android.systemui` 等已在代码里写死排除，
> 否则「设置」里的开发者选项开关会显示错乱。

### 3. 验证（建议先做这一步）

1. 安装 `DevOptsHider-Probe-v*.apk`
2. 把它加入 LSPosed 作用域，强制停止后打开
3. 全绿 = 目标 App 读到的就是「没开开发者选项」的状态；红色项 = 那条通道还没堵住

### 4. 测试顺序建议（针对 App 闪退）—— 实测结论

实测下来，关键事实是：

- **Zygisk 单装会让 HSBC 闪退**，而且即便不闪退也**挡不住 Settings 键**
  （探针实测 `Settings: adb_enabled = 1`、`development_settings_enabled = 1` 仍是暴露的）。
  原因：这两个键是经 `ContentResolver` / `SettingsProvider` 读的 **Java 层**，Zygisk 的 native PLT hook 摸不到；
  而 HSBC 的 native 库有加固/完整性校验，PLT hook 它必然崩。
- **LSPosed 单装能把 Settings 挡住**（探针实测那几行从「仍暴露」消失），而且 HMA-OSS（也是 LSPosed 系）从没让 HSBC 崩过。
  说明 **对 HSBC 来说，LSPosed 远比 Zygisk 安全**。

所以**对 HSBC 推荐只用 LSPosed，先别开 Zygisk**。顺序：

1. **关掉 Zygisk 模块**（Magisk 里取消勾选 / 或白名单把 HSBC 排除），**只留 LSPosed**。
   - 把 HSBC 和探针都加进 LSPosed 作用域，强制停止后重开。
   - 跑探针看「Settings:」「query 全表」「prop:」是否全绿。
2. 打开 HSBC：
   - 不弹窗、不闪退 → **LSPosed 单装就够**，Zygisk 不用管了。
   - 仍弹「请关闭开发者选项」→ 说明 HSBC 还读了 **native 系统属性**（`__system_property_get` 读的 `ro.debuggable` /
     `ro.build.type`，LSPosed 的 Java hook 够不到）。这时再看探针「native prop:」那几行：若它们是**真实值**
     （不是 0 / user），就确认了是 native 通道。这一步才需要考虑怎么在不崩 HSBC 的前提下盖 native 层（见下）。
3. 若 LSPosed 单装下 HSBC 反而闪退（理论上可能性低）→ 告诉我，我们排查是哪个 hook 触发了 HSBC 的反 hook。

> 为什么不直接用 Zygisk 盖 native 层：HSBC 的 native 库被加固，PLT hook 它必崩；要盖 native 又不崩，
> 只能 hook libc 函数体（inline hook），更重且同样可能被校验抓到。所以优先确认 HSBC 到底走不走 native 通道
> （第 2 步），不要一上来就挂 Zygisk。

> 探针的「native prop:」一栏直接调 libc `__system_property_get`（绕过 Java）。在 **LSPosed 单装**下它显示的是
> **真值**（因为 LSPosed 不盖 native）；这正好用来判断「HSBC 是不是靠 native 通道检测」——若 HSBC 在 LSPosed
> 单装下已经不弹窗，那 native 通道根本不是问题，无视真值即可。

---

## 被拦截的检测点

**Settings**（三个 namespace 用同一份规则表，因为不同 ROM / 不同 RASP 查的 namespace 不一样）

- 核心：`development_settings_enabled`、`adb_enabled`、`adb_wifi_enabled`、`adb_allowed_connection_time`
- 变体：`developer_options_enabled`、`usb_debugging_enabled`、`wireless_debug_enabled` 等
- 调试相关：`debug_app`、`wait_for_debugger`、`mock_location*`
- 开发者选项条目：`show_touches`、`pointer_location`、动画缩放、`enable_gpu_debug_layers`、
  `bugreport_in_power_menu`、`stay_on_while_plugged_in`、`always_finish_activities`、`oem_unlock_allowed` 等
- 兜底：key 里含 `adb / debug / developer / development / userdebug` 的一律按 0 返回（可在 UI 里关掉）

**系统属性**

- `ro.debuggable`→0、`ro.secure`→1、`ro.build.type`→user、`ro.build.tags`→release-keys、`ro.adb.secure`→1
- `init.svc.adbd`→stopped、`service.adb.tcp.port`→-1、`persist.adb.tcp.port`→-1
- `sys.usb.state` / `sys.usb.config` / `persist.sys.usb.config`：从逗号列表里把 `adb` 剔掉
- fingerprint / display.id 里的 `userdebug`、`/eng/`、`test-keys` 替换成 `user`、`release-keys`

**文件 / 端口**

- `/sys/class/android_usb/**`、`/sys/kernel/config/usb_gadget/**`、`/dev/usb-ffs/adb`、
  `/data/misc/adb/**`、`/apex/com.android.adbd` → open/access 一律返回「不存在」
- `127.0.0.1:5555` 等无线调试常见端口 → connect 直接拒绝

**其它**

- `Debug.isDebuggerConnected()` / `waitingForDebugger()` → false
- `ActivityManager.isUserAMonkey()` / `isRunningInTestHarness()` → false
- `Build.TYPE` / `Build.TAGS` / `Build.FINGERPRINT` / `Build.IS_DEBUGGABLE`

---

## 排查

**目标 App 仍然弹窗**

1. 打开探针 App 看哪一项还是红的 —— 那一列就是漏掉的通道
2. 开日志：
   - Zygisk：`adb shell su -c 'touch /data/adb/devhider/debug'`，然后 `adb logcat -s DevHider`
   - LSPosed：模块 UI 里打开「调试日志」（默认关闭，避免日志本身暴露模块存在）
3. 确认进程真的是新起的：设置 → 应用 → 目标 App → 强制停止，再打开
4. 在日志里搜 `pkg=` 确认 Zygisk 识别到了正确的包名；搜 `settings ` / `prop ` 看伪造是否命中

**手机起不来 / 某个 App 崩溃**

Zygisk 模块的 hook 全部包了判空与异常兜底，正常不会 bootloop。真出问题：Magisk 里关掉模块，
或在 recovery 里删 `/data/adb/modules/devopts-hider`。

---

## 从源码构建

```bash
cd DevOptsHider
./build-all.sh
```

产物在 `dist/`。

- LSPosed / 探针 APK：Gradle 8.13 + AGP 8.7.2 + JDK 21（本机已缓存的发行版）
- Zygisk 模块：直接用 NDK r27d 的 clang++ 编译（`zygisk/build.sh`），不依赖 Gradle/CMake

Xposed API 用的是本机 Gradle 缓存里的 `api-82.jar`（mavenCentral 在本机被网络策略拦了）。
换机器构建时把 jar 放到 `lsposed/app/libs/` 即可，`build.gradle.kts` 会自动优先用它。
