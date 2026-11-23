<p align="center">
  <a href="README.md">🇨🇳 中文</a> · <a href="README.en.md">🇺🇸 English</a>
</p>

<div align="center">

# Debug Switch · AdbSwitch

<img src="icon.png" width="96" style="border-radius: 18px;" alt="app icon"/>

**One tap to pass detection — debug & accessibility traces handled · root-free by default**

[![Platform](https://img.shields.io/badge/Platform-Android%208.1%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com)
[![Written in](https://img.shields.io/badge/Written%20in-Java-007396?logo=openjdk&logoColor=white)](https://www.java.com)
[![UI](https://img.shields.io/badge/UI-Material%203-6750A4?logo=materialdesign&logoColor=white)](https://m3.material.io)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

</div>

---

Some banking apps (for example HSBC's app in Hong Kong) detect a phone's ADB debugging state or accessibility-service traces, and toggling each one manually in system settings every time is tedious.

AdbSwitch exists for exactly this: a **local tool that truly rewrites system state in one tap** — turn debugging and accessibility traces off together from a home-screen shortcut or the notification tile; a persistent notification restores them with one tap (a status-bar "Live Update" capsule on Android 16+). Switch items are customizable.

By default it uses Android's system-reserved **root-free channel**: you only complete one authorization on first use, after which the app rewrites the debugging state in-process — no rooted device and no always-on Shizuku required. That said, root and Shizuku are still valuable: they let users complete that one-time authorization far more easily (delegating `pm grant` right on the device, no PC needed), and once granted they unlock further advanced features; each also ships a corresponding write mode that writes system settings natively through the shell, for users who need it.

The project is open source under GPL-3.0; the code can be reviewed and built by yourself.

## 📦 Download

| Channel | Link |
| :--- | :--- |
| GitHub Releases | <https://github.com/gaoyilun/adbSwitch/releases> |
| Gitee Releases | <https://gitee.com/gaoyilun/adbSwitch/releases> |
| Pgyer (China mirror) | <https://www.pgyer.com/adbSwitch> |

## ✨ Features

| Feature | Description |
| :--- | :--- |
| 🔌 **Main switch** | One tap turns debugging and accessibility traces off together (adbd stops, truly passes detection); state aggregates the items set to follow it, and turning it back on restores the previous combination |
| 🧩 **Decoupled, customizable switches** | USB debugging, wireless debugging, Developer options and third-party accessibility services are four independent items. In "Manage switch items" set each one's visibility, whether it follows the main switch, and whether it appears in the desktop shortcut menu, and drag to reorder; hidden items are not managed by this app at all |
| 🆓 **Works with no-root / Shizuku / root** | No-root by default: after a one-time authorization the app writes system settings in-process — no root, no always-on Shizuku; root / Shizuku write modes are also available. The grant is sticky, surviving reboots / soft reboots |
| ♿ **One-tap accessibility toggle** | Turn third-party accessibility services off / back on in one tap; only third-party services are counted. On ROMs that hide the list from this app, a risk confirmation appears first — system preinstalls are never blindly turned off. Writes are confirmed by reading back, and slow-binding devices no longer stay stuck on "needs manual enable" |
| 🧭 **Jump to system settings** | Each of the three debugging rows has a "›" arrow on the right that opens the corresponding system settings page; long-pressing the whole row does the same. Works even when unauthorized, or even when the switch is greyed out, so you can go flip it in the system yourself |
| 📋 **Quick Settings tile** | A "Debug Switch" tile in the notification shade toggles with the main-switch semantics; long-press opens the app home. Tile state and label aggregate only the items that follow the main switch |
| 🔔 **Temporary off with Live Update, one-tap restore** | After turning debugging off, a persistent notification lets you restore with one tap (upgraded to a status-bar "Live Update" capsule on Android 16+) |
| ⚡ **Quick Launch** | Add apps that probe the debug state to a Quick-Launch list; tapping a list item or a home-screen shortcut does "turn debug off first, then launch the target app"; when re-enabling debugging it can auto force-stop those apps (on by default) to stop them reading the debug state at the moment of the flip |
| 🌐 **Multilingual UI** | Ships English (`res/values-en`) |
| 🎨 **Material 3 + dynamic color** | Full M3 components and color roles, with wallpaper-based dynamic color on Android 12+ |

## 🔑 How it works & authorization

### Default channel: WRITE_SECURE_SETTINGS (no-root)

AdbSwitch's core writes go through an **officially reserved, legitimate channel** in Android's permission system: `WRITE_SECURE_SETTINGS`'s protectionLevel includes `development`, which lets it be granted to a normal app via `pm grant` without a system signature. Once granted, the app calls `Settings.Global.putInt()` directly in-process, hitting the same `SettingsProvider` as `adb shell settings put global` — equivalent effect, no process fork.

This is the app's default mode; you can also switch to Shizuku / root mode.

### First-time authorization (any one of three ways)

This permission cannot be granted by tapping in system settings; you must run `pm grant` once on first use:

```bash
adb shell pm grant com.elleng.adbswitch android.permission.WRITE_SECURE_SETTINGS
```

| Method | Prerequisite | Notes |
| :--- | :--- | :--- |
| Manual adb | A PC | Copy the command above and run it in a terminal (the app has an in-app guide with one-tap copy) |
| Shizuku delegation | Shizuku started and authorized for this app | On-device, no PC needed |
| root delegation | Device is rooted | On-device, no PC needed |

The grant takes effect immediately and **survives reboots / soft reboots**; you only need to re-grant after an uninstall-reinstall (the grant is bound to the specific install).

### Shizuku / root mode

Besides the default no-root channel, you can switch to Shizuku or root mode in the app settings to write system settings natively through the shell:

- **Shizuku mode**: runs `settings` under Shizuku's shell identity (uid 2000) — no root required;
- **root mode**: runs `settings` via `su`, for rooted devices;
- **Avoid the system confirmation dialog**: when enabling USB / wireless debugging, some systems pop a confirmation dialog; these two modes usually avoid it.

> All three modes share the same goal — truly rewriting the system debugging state — differing only in how permission is obtained. No-root is the default; switch to Shizuku / root as needed.

## 📱 System requirements

| Item | Requirement |
| :--- | :--- |
| Android version | Android 8.1 (API 27) and above |
| Wireless debugging | Android 11 (API 30) and above (wireless debugging was introduced in that version) |
| Authorization | No-root mode needs a one-time authorization (manual adb, or delegate via existing root / Shizuku); devices with no root and no Shizuku also work |
| Dynamic color | Android 12+ follows the wallpaper; lower versions fall back to static colors |

## 🚀 Install & use

1. Download the latest APK from any channel above and install;
2. On first launch the one-time authorization guide pops up automatically — pick any method to finish (the default is the recommended no-root mode);
3. Customize the items: open "Manage switch items" to set visibility / follow-main-switch / shortcut-menu per item and drag to reorder; hide anything you do not want this app to manage;
4. Pull down the notification shade, edit the quick tiles, and drag the "Debug Switch" tile into the quick settings area (long-press the tile to return to the app);
5. To pass a check temporarily: turn debugging off, include accessibility traces if needed, and when done tap "Restore" on the persistent notification;
6. For one-tap access: go to "Quick Launch", add the target app, and long-press that row to create a home-screen shortcut.

## 🛠️ Build from source

```bash
# Requires JDK 17+ and Android SDK (API 36) locally
git clone https://github.com/gaoyilun/adbSwitch.git
cd adbSwitch
./build-all.sh        # auto-locates Gradle; generates a debug keystore automatically when none exists
# Output: adbswitch/app/build/outputs/apk/release/*.apk
```

- The Pgyer update channel key is injected from `adbswitch/pgyer.properties` (gitignored; template at `pgyer.properties.example`); when that file is absent the Pgyer channel auto-disables, leaving only the GitHub update channel.
- Release signing: configure your own `storePassword / keyAlias / keyPassword` in `keystore/signing.properties`.

## 🧩 Technical notes

- **Same source for read & write**: some ROMs (e.g. HyperOS) hide the debug keys from third-party apps (`ContentResolver` reads always 0), so the true-state read prefers the same channel as the write; in no-root mode, if Shizuku is available it falls back to a shell read to avoid a wrong display;
- **Write channel dispatched by mode**: no-root mode writes in-process via `Settings.Global.putInt` (no process fork); the root / Shizuku fallback channel still uses a single shell (read-before → write → read-after merged into one command, reducing fork overhead);
- **State machine convergence**: all writes go through the single `DebugController` entry point, with same-key requests de-duplicated and merged, and a write failure never pollutes the state cache. Accessibility writes fold the system-setting read-back into the same shell command, show the result immediately, and verify binding silently in the background so slow-binding devices do not stay on "needs manual enable";
- **Shortcut authentication**: dynamic shortcuts carry an install-scoped random token; the trampoline activity checks it before writing, so a third-party app cannot forge the intent to change system settings;
- **Sticky but revocable grant**: `pm grant` is bound to the specific install and expires on uninstall / reinstall, so every return to foreground re-validates and guides re-authorization;
- **Weak-update hints**: version judgment only compares `versionCode`; the startup check is throttled to once per 3 days and runs only when the main thread is idle; on finding a new version it only Toasts + shows a hint on the About row, without forcing a dialog; downloads support SHA-256 verification.

### Known limitations

- In no-root mode, "auto force-stop linked apps before enabling debugging" needs shell capability (`am force-stop`), so it **only takes effect when the device has Shizuku**; in pure no-root without Shizuku that step is skipped and everything else is unaffected.
- Some vendors add extra restrictions on `pm grant`. If the authorization command errors out, first confirm the relevant switches in Developer options, or switch to the root / Shizuku mode.

## ❓ FAQ

**Q: Can I use it without root?**

A: Yes, and that's the default. No-root mode only needs a one-time `pm grant` command (or delegate it once via existing root / Shizuku); after that you can toggle debugging normally with no root and no always-on Shizuku.

**Q: Do I need to repeat the one-time authorization?**

A: Usually not. The grant is sticky and survives reboots / soft reboots; you only need to do it again after an uninstall-reinstall (the grant is bound to the specific install).

**Q: Can I hide rows I don't need, or keep them out of the main switch?**

A: Yes — open "Manage switch items" and set each item individually. Hidden items are not touched by this app at all (not shown, not written, not followed); to keep a row but toggle it alone, just turn off "Follow main switch"; to show an item in the desktop long-press menu, check "Shortcut menu". You can also drag to reorder.

**Q: What does "On · inactive" on the wireless debugging row mean?**

A: The system's wireless-debugging setting is on, but the currently connected default network is not Wi-Fi, so adb over network is unavailable — some ROMs may even show the switch as off in Settings. It returns to "On · adbd running" automatically once Wi-Fi is back.

**Q: After turning debugging off, does the Developer-options master switch still exist?**

A: Yes. The debug switch only changes `adb_enabled` / `adb_wifi_enabled`; the Developer-options entry is an independent switch you control separately when needed.

**Q: How do I restore Developer options after turning its entry off?**

A: System settings → About phone → tap "Build number" 7 times to re-enable Developer options.

**Q: Is this a fake off?**

A: No. It truly rewrites `adb_enabled` via system settings; the system then reconfigures USB (removing `adb` from `sys.usb.config`) and stops adbd — a real state change.

**Q: Why are apps in Quick Launch force-stopped?**

A: Force-stopping the target app via `am force-stop` right before re-enabling debugging prevents it from reading "enabled" at the instant the debug state flips and triggering detection. This behavior is on by default and can be turned off in Quick Launch settings.

## 🔒 Privacy

This app has no telemetry and uploads no data; the only network request is "check for updates" (hitting GitHub Release metadata and the Pgyer channel), and it works fully offline. The authorization required by no-root mode is completed locally by the user and involves no account or server.

## 📄 License

This project is open source under the [GPL-3.0](LICENSE) license.

<p align="center">
  Made with ❤️ on Android by <a href="https://github.com/gaoyilun">@gaoyilun</a>
</p>
