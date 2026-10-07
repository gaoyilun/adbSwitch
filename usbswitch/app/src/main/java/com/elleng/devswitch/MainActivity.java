package com.elleng.devswitch;

import android.Manifest;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.preference.PreferenceActivity;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.view.View;
import android.preference.PreferenceCategory;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.elleng.devswitch.util.RootUtil;

import java.util.ArrayList;
import java.util.List;
import com.elleng.devswitch.update.UpdateChecker;
import com.elleng.devswitch.update.UpdateDialog;
import com.elleng.devswitch.update.UpdateInfo;

import rikka.shizuku.Shizuku;

import com.google.android.material.snackbar.Snackbar;

/**
 * 主界面：使用 framework 原生 PreferenceActivity（Android 原生 Material 设置页），
 * 统一沿用系统设置项样式（SwitchPreference / Preference / PreferenceCategory），
 * 不再手写彩色卡片，整体更贴近原生 Android 应用观感。
 */
public class MainActivity extends PreferenceActivity {

    public static final String EXTRA_SETTINGS = "extra_settings";
    /** 静态 shortcut「打开主页」的 action：见到它就跳过联动自动跳转 */
    private static final String ACTION_OPEN_HOME = "com.elleng.devswitch.action.OPEN_HOME";
    /** 调试用 action：直接拉起联动应用选择器（便于 adb 远程验证弹窗是否正常） */
    private static final String ACTION_DEBUG_PICKER = "com.elleng.devswitch.action.DEBUG_PICKER";
    /**
     * 调试用 action：从前台 Activity 直接拨主开关，等价于用户手动拨。
     * 存在的理由：adb 连发广播会被 HyperOS 的 Greezer 拦截（"need cached broadcast"），
     * 走 Activity 则不受限，可稳定远程验证一键开关。
     * 用法：am start -n .../.MainActivity -a com.elleng.devswitch.action.DEBUG_MASTER --ez master_on false
     */
    private static final String ACTION_DEBUG_MASTER = "com.elleng.devswitch.action.DEBUG_MASTER";
    private static final String EXTRA_MASTER_ON = "master_on";
    /** 桌面快捷方式「创建桌面快捷方式」：先打开本 App，关掉调试后联动启动目标应用 */
    private static final String ACTION_DESKTOP_LINKAGE = "com.elleng.devswitch.action.DESKTOP_LINKAGE";
    private static final String TAG = "UsbSwitch";
    private static final String PREFS = "cfg";
    /** 需要监听外部改动的 Settings 键（与 DebugController 写入的是同一组） */
    private static final String[] OBSERVED_KEYS =
            {DebugController.KEY_USB, DebugController.KEY_WIRELESS};
    private static final String PREF_LINKAGE = "linkage_enabled";
    /** 动态联动应用行的 key 前缀：完整 key = 前缀 + 包名（wireRowClicks 据此识别并取出包名） */
    private static final String LINKAGE_APP_KEY_PREFIX = "linkage_app:";
    /** 默认跳转应用行 / 管理联动应用行的固定 key（动态填充，不在 prefs.xml 写死） */
    private static final String LINKAGE_KEY_DEFAULT = "linkage_default";
    private static final String LINKAGE_KEY_MANAGE = "linkage_manage";
    /** 桌面快捷方式意图里携带「要联动哪个 app」的包名（每个 app 的桌面图标各不相同） */
    private static final String EXTRA_LINKAGE_PKG = "extra_linkage_pkg";
    /** 「临时关闭时弹通知」开关 */
    private static final String PREF_NOTIFY = "notify_on_close";
    /** 「打开调试前先杀掉联动应用」开关（DebugController 读取同一份 cfg） */
    private static final String PREF_KILL_BEFORE_OPEN = "kill_before_open";
    /** 动态 shortcut：启动联动应用 */
    private static final String SC_ID_LAUNCH_APP = "launch_linkage_app";

    private Preference swMasterPref;      // 主开关：普通 Preference + 自定义 widget（箭头 + Switch）
    private Switch masterSwitch;          // 主开关行内的 Switch 控件
    private Boolean masterManualExpanded; // 用户手动覆盖的展开态；null=跟随开关状态
    // 两个子开关当前是否已加入 category。
    // 必须初始化为 true：prefs.xml 里它们本来就写在 catDebug 内，
    // 初始化成 false 会导致首次刷新时「关闭态 want=false == 当前 false」而跳过 remove，折叠失效。
    private boolean subSwitchesShown = true;
    private SwitchPreference swUsbPref;
    private SwitchPreference swLinkagePref;
    private SwitchPreference swNotifyPref;
    private SwitchPreference swWirelessPref;
    private SwitchPreference swKillPref;
    private Preference liveUpdatePref;
    /** 「关于」行：点击弹窗展示版本/构建时间/授权模式（原「构建时间」行） */
    private Preference buildInfoPref;
    private Preference authPref;              // 授权模式（root / Shizuku）与授权状态合并项

    private boolean syncing = false;          // 防止 setChecked 触发回环
    /** 当前授权模式是否可用（即能否真实写入调试开关）。乐观假定可用，后台双检测纠正 */
    private boolean privilegedAvailable = true;
    private boolean prefsLoaded = false;      // 设置界面是否已构建（onNewIntent 复用实例时避免重复加载）
    private boolean rowClicksWired = false;   // 行点击的布局监听是否已注册（避免重复注册）
    /** 底部 Snackbar（未授权提示，带「重试」按钮）；同一时刻只保留一个 */
    private com.google.android.material.snackbar.Snackbar authSnackbar = null;
    /** 上次请求 Shizuku 授权的时间戳（防抖，避免请求->结果->重测->请求的循环） */
    private long lastShizukuRequestAt = 0;
    /** 进程级：本次进程内是否已自动跳转过联动应用（static 保证 Activity 重建后仍记得） */
    private static boolean sLinkageLaunched = false;
    private long lastPickerOpenAt = 0;        // 打开选择器防抖：行点击与 preference click 可能同时触发
    private long lastLiveOpenAt = 0;          // 同上，实时动态弹窗防抖
    private boolean settingsObserverRegistered = false; // Settings 监听是否已注册（防重复注册/泄漏）
    /** 主线程 Handler：去抖与所有 post 都复用它，否则每次 new 一个 Handler 都拿不到上次的 callback，去抖等于失效 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 行点击接线用的标题缓存：布局每帧都会回调，getString 不该每次重做 */
    private String titleMaster, titleLive, titleAuth, titleAbout;

    /** 关于弹窗外链：署名按钮 → elleng.eu.org；信息图标 → 项目仓库 */
    private static final String ABOUT_CREDIT_URL = "https://elleng.eu.org";
    private static final String ABOUT_REPO_URL = "https://github.com/gaoyilun/DevSwitch";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (DebugController.ACTION_STATE.equals(i.getAction())) {
                refresh(new DebugController.State(
                        i.getBooleanExtra(DebugController.EXTRA_USB, false),
                        i.getBooleanExtra(DebugController.EXTRA_WIRELESS, false)));
            }
        }
    };

    /**
     * 监听 Settings.Global 里两个调试键的外部变化。
     *
     * 为什么必须监听：stateReceiver 只覆盖「本 App 自己写的」情况（ACTION_STATE）。
     * 但调试状态还可能被本 App 之外的东西改掉 —— 用户手动改系统开发者选项、
     * 系统按场景自动收无线调试、其他 App 改设置等。此时 App 不知情，
     * 界面会一直停在旧值上，出现「明明 adbd 已停、界面却显示已开启」的骗人状态。
     * 而这个 App 存在的意义恰恰是让用户确认真实状态，所以必须监听。
     *
     * 一次外部操作往往连改多个键，故做去抖合并，延迟 300ms 再统一刷新。
     */
    private final android.database.ContentObserver settingsObserver =
            new android.database.ContentObserver(new android.os.Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    scheduleResync();
                }
            };

    private final Runnable resyncRunnable = () -> {
        if (!prefsLoaded) {
            return;
        }
        // 必须把缓存作废再校准：HyperOS 会向第三方 App 隐藏这两个键，直接回读拿到的仍是缓存，
        // 不作废就永远刷不出「外部改了设置」的新状态。校准完成后会广播，UI 由 stateReceiver 更新。
        DebugController.invalidateAndRefresh(this);
    };

    private void scheduleResync() {
        mainHandler.removeCallbacks(resyncRunnable);
        mainHandler.postDelayed(resyncRunnable, 300);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinkagePrefs.migrate(this);
        initShizuku();
        if (!handleEntry(getIntent())) {
            showSettings();
        }
    }

    /**
     * 注册 Shizuku 桥接监听：
     *  - OnBinderReceivedListener（sticky）：Shizuku 服务就绪后立刻重新检测授权，
     *    若当前是 Shizuku 模式且尚未授权，则主动弹授权请求；
     *  - OnRequestPermissionResultListener：用户点完 Shizuku 授权弹窗后回写状态并重测。
     * 这些监听与「未授权时每次进页面双检测」互补：即使页面没重建（例如 Shizuku 在后台启动），
     * 也能即时感知并刷新授权状态。
     */
    private void initShizuku() {
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    android.util.Log.i(TAG, "Shizuku binder received");
                    // 服务就绪：Shizuku 模式且未授权时主动弹授权（force=false 走防抖）
                    maybeRequestShizukuPermission(false);
                    // 无论是否已授权都重测一次，刷新授权状态行与控件可用性
                    performAuthCheck();
                }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == AuthManager.SHIZUKU_PERM_REQUEST) {
                        android.util.Log.i(TAG, "Shizuku permission result=" + grantResult);
                        performAuthCheck();
                    }
                }
            });
        } catch (Throwable t) {
            android.util.Log.w(TAG, "initShizuku failed", t);
        }
    }

    /**
     * shortcute / 通知栏入口在 Activity 已存在时不会走 onCreate（FLAG_ACTIVITY_NEW_TASK
     * 命中已有任务只会把任务带到前台，既不重建也不回调），故必须同时处理 onNewIntent，
     * 否则「任务已在后台时点快捷方式」将毫无反应。launchMode=singleTask 保证能收到。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleEntry(intent)) {
            showSettings();
        }
    }

    /**
     * 按入口 Intent 决定行为。
     *
     * @return true 表示该入口已自行处理（跳转并 finish），调用方不要再构建设置界面
     */
    private boolean handleEntry(Intent in) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        // 动态 shortcut「启动联动应用」：直接跳目标应用（不触发关调试/常驻通知等联动动作），
        // 故不依赖联动模式开关，开没开联动都能用。
        if (SC_ID_LAUNCH_APP.equals(in.getAction())) {
            String pkg = LinkagePrefs.getDefault(this);
            if (pkg.isEmpty()) {
                Toast.makeText(this, R.string.linkage_no_default, Toast.LENGTH_LONG).show();
            } else {
                launchApp(pkg);
            }
            finish();
            return true;
        }

        // 打开主页的两种入口：通知栏「应用设置」传来的 EXTRA_SETTINGS(boolean)，
        // 或「打开主页」shortcut 携带的自定义 action。
        // 注意不能用 <extra> 传标记：shortcuts 解析会把 "1" 存成 Integer，
        // getStringExtra() 内部吞掉 ClassCastException 返回 null，判定永远失效。
        boolean openSettings = ACTION_OPEN_HOME.equals(in.getAction())
                || in.getBooleanExtra(EXTRA_SETTINGS, false);

        // 调试入口：设置页建好后自动弹出应用选择器，供 adb 远程验证
        if (ACTION_DEBUG_PICKER.equals(in.getAction())) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                showSettings();
                openAppPicker();
            }, 600);
            return true;
        }

        // 调试入口：从前台直接拨主开关（默认关），供 adb 远程验证一键开关
        if (ACTION_DEBUG_MASTER.equals(in.getAction())) {
            final boolean masterOn = in.getBooleanExtra(EXTRA_MASTER_ON, false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                showSettings();
                // 写入是异步的：日志打在完成回调里，才反映写入后的真值
                applyMasterState(masterOn, () -> android.util.Log.i(TAG,
                        "DEBUG_MASTER done, on=" + masterOn
                                + " usb=" + DebugController.read(this).usb
                                + " wireless=" + DebugController.read(this).wireless));
            }, 600);
            return true;
        }

        // 桌面快捷方式「创建桌面快捷方式」：先打开本 App，关掉调试后联动启动目标应用。
        // 这是显式触发（用户点了快捷方式），故不走 PREF_LINKAGE 开关、也不受
        // sLinkageLaunched 守卫限制——每次点都应重新关调试并跳目标应用。
        if (ACTION_DESKTOP_LINKAGE.equals(in.getAction())) {
            String pkg = in.getStringExtra(EXTRA_LINKAGE_PKG);
            if (pkg == null || pkg.isEmpty()) {
                Toast.makeText(this, R.string.linkage_no_app, Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
            // Android 13+ 缺通知权限时，常驻通知会被系统抑制，联动流程依赖它，
            // 故先提示并落到设置页（showSettings 会补发权限申请），不跳转。
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.linkage_need_notif, Toast.LENGTH_LONG).show();
                return false;
            }
            runLinkage(pkg);
            return true;
        }

        boolean linkage = sp.getBoolean(PREF_LINKAGE, false);

        // —— 联动模式：从启动器打开时，自动关闭调试并跳转「默认跳转应用」 ——
        if (linkage && !openSettings) {
            String pkg = LinkagePrefs.getDefault(this);
            if (!pkg.isEmpty()) {
                // Android 13+ 未授予通知权限时，前台服务通知会被系统抑制；
                // 联动整条流程依赖这条常驻通知，故缺权限时先申请、不跳转，
                // 留在设置页（showSettings 里会补发申请）。
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.linkage_need_notif,
                            Toast.LENGTH_LONG).show();
                    return false;
                }
                // 已经自动跳转过一次：不再自动跳。
                // 否则从联动应用返回本 App（或再次点图标）时会被反复弹回去，
                // 用的人根本来不及用设置页。需要再跳时用动态快捷方式手动触发。
                if (sLinkageLaunched) {
                    Toast.makeText(this, R.string.linkage_skipped, Toast.LENGTH_LONG).show();
                    return false;
                }
                runLinkage(pkg);
                return true;
            }
            // 开了联动却没设默认跳转应用：提示并落到普通界面
            Toast.makeText(this, R.string.linkage_no_default, Toast.LENGTH_LONG).show();
        }
        return false;
    }

    /** 构建并刷新设置界面（幂等：onNewIntent 复用实例时不可重复 addPreferencesFromResource） */
    private void showSettings() {
        if (!prefsLoaded) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            addPreferencesFromResource(R.xml.prefs);
            setupActionBar();
            initPrefs();
            wireRowClicks();
            scheduleWireRowClicks();
            prefsLoaded = true;
            checkUpdateOnStart();
        }

        performAuthCheck();
        refresh(DebugController.read(this));
        applyRootState();
        rebuildLinkageList();
    }

    // —— 在线更新 ——

    /**
     * 启动后自动检测更新（24h 节流）。
     *
     * 当前 {@link UpdateConfig#ENABLED} 为 false，本方法第一行就返回，一次网络请求都不会发。
     * 启用后的行为与 LivePhoto 一致：成功拿到结果才写节流时间戳，网络失败不写，
     * 避免弱网用户被节流 24 小时。
     */
    private void checkUpdateOnStart() {
        if (!UpdateChecker.isActive() || !UpdateChecker.shouldCheck(this)) {
            return;
        }
        new Thread(() -> {
            UpdateInfo info = null;
            try {
                info = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
            } catch (Throwable t) {
                android.util.Log.w("UsbSwitch", "update check failed", t);
            }
            if (info == null) {
                return; // 所有通道失败：不写节流，下次启动重试
            }
            UpdateChecker.markChecked(this);
            if (!info.hasUpdate || info.versionCode <= UpdateChecker.ignoredVersion(this)) {
                return;
            }
            final UpdateInfo found = info;
            runOnUiThread(() -> UpdateDialog.show(this, found, new UpdateDialog.Listener() {
                @Override
                public void onOpenUrl(String url) {
                    openExternalUrl(url);
                }

                @Override
                public void onIgnored(UpdateInfo ignored) {
                    // 记住该版本，之后不再弹（仅靠 24h 节流的话同版本一过节流就会再来一次）
                    UpdateChecker.ignoreVersion(MainActivity.this, ignored.versionCode);
                }
            }));
        }, "update-check").start();
    }

    /** 「关于」弹窗里的手动检查入口（总开关关闭时按钮不出现）。 */
    private void manualCheckUpdate() {
        if (!UpdateChecker.isActive()) {
            Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            UpdateInfo info = null;
            try {
                info = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
            } catch (Throwable t) {
                android.util.Log.w("UsbSwitch", "update check failed", t);
            }
            if (info != null) {
                UpdateChecker.markChecked(this);
            }
            final UpdateInfo result = info;
            runOnUiThread(() -> {
                if (result == null) {
                    Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                } else if (!result.hasUpdate
                        || result.versionCode <= UpdateChecker.ignoredVersion(this)) {
                    Toast.makeText(this, R.string.update_already_latest, Toast.LENGTH_SHORT).show();
                } else {
                    UpdateDialog.show(this, result, new UpdateDialog.Listener() {
                        @Override
                        public void onOpenUrl(String url) {
                            openExternalUrl(url);
                        }

                        @Override
                        public void onIgnored(UpdateInfo ignored) {
                            UpdateChecker.ignoreVersion(MainActivity.this, ignored.versionCode);
                        }
                    });
                }
            });
        }, "update-check-manual").start();
    }

    private void openExternalUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) {
            android.util.Log.w("UsbSwitch", "open url failed: " + url, t);
            Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 双模式授权检测（替换原 probeRoot）：
     * 后台同时探测 root 与 Shizuku 两个后端是否可用（root 要 fork su，较慢；Shizuku 是 binder 调用，很快），
     * 回主线程后据「当前选中的模式」决定 privilegedAvailable（控件是否可写），
     * 刷新授权状态行与重试按钮，并在当前模式未授权时弹出底部悬浮通知。
     */
    private void performAuthCheck() {
        if (!prefsLoaded) {
            return;
        }
        new Thread(() -> {
            final boolean rootOk = RootUtil.hasRoot();
            final boolean shizukuOk = ShizukuUtil.available();
            final boolean shizukuRunning = ShizukuUtil.running();
            final AuthManager.Mode mode = AuthManager.getMode(MainActivity.this);
            final boolean ok = (mode == AuthManager.Mode.SHIZUKU) ? shizukuOk : rootOk;
            new Handler(Looper.getMainLooper()).post(() -> {
                privilegedAvailable = ok;
                applyRootState();
                updateAuthStatusUI();
                // Shizuku 模式且服务在跑却没授权：主动弹 Shizuku 授权对话框
                if (!ok && mode == AuthManager.Mode.SHIZUKU) {
                    maybeRequestShizukuPermission(false);
                }
                if (!ok) {
                    showAuthNotice(mode, shizukuRunning);
                }
            });
        }, "auth-probe").start();
    }

    /** 原生顶栏：标题 + 副标题 + 主色背景（系统自动预留状态栏区域）。
     *  不显示 App 图标：左端留白，只留标题/副标题。 */
    private void setupActionBar() {
        ActionBar ab = getActionBar();
        if (ab == null) {
            return;
        }
        // 关掉 Home/Logo 显示开关，否则 framework 会把 App 图标画在标题左边
        //（注意：framework 的 ActionBar 没有 setDisplayHomeAsActionEnabled，那是 androidx 的 API）
        ab.setDisplayShowHomeEnabled(false);
        ab.setDisplayUseLogoEnabled(false);
        ab.setLogo(null);
        ab.setTitle(R.string.app_name);
        ab.setSubtitle(R.string.header_subtitle);
        ab.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.colorPrimary)));
    }

    private void initPrefs() {
        // 行点击按标题文本识别，标题每次布局都要比一次 —— 在此取一次缓存起来，别每帧 getString
        titleMaster = getString(R.string.master_title);
        titleLive = getString(R.string.live_update_title);
        titleAbout = getString(R.string.about_title);
        titleAuth = getString(R.string.auth_mode_title);

        swMasterPref = findPreference("swMaster");
        swUsbPref = (SwitchPreference) findPreference("swUsb");
        swLinkagePref = (SwitchPreference) findPreference("swLinkage");
        swNotifyPref = (SwitchPreference) findPreference("swNotify");
        swWirelessPref = (SwitchPreference) findPreference("swWireless");
        swKillPref = (SwitchPreference) findPreference("swKillBeforeOpen");
        liveUpdatePref = findPreference("liveUpdate");
        buildInfoPref = findPreference("buildInfo");
        // 双模式授权：模式选择 + 授权状态已合并为同一项。点击整行 = 打开模式选择对话框
        authPref = findPreference("auth");
        if (authPref != null) {
            authPref.setOnPreferenceClickListener(p -> {
                chooseAuthMode();
                return true;
            });
        }
        // 初次进入即用当前模式渲染一次授权状态（精确结果等后台双检测回来再纠正）
        updateAuthStatusUI();
        // 底部「关于」行：点击弹窗展示版本/构建时间等（构建时间不再直接占用行摘要）

        // 主开关：一键开/关。关闭时记住两个子开关的组合，打开时原样还原。
        // 注意它是普通 Preference：整行点击与行内 Switch 都在 wireRowClicks() 里接线，
        // 因为 widget 里的 Switch 要等布局完成后才拿得到。
        swMasterPref.setOnPreferenceClickListener(p -> {
            toggleMaster();
            return true;
        });

        // 子开关只改自己那一个键，不动记忆；改完主开关状态随之变化
        swUsbPref.setOnPreferenceChangeListener((Preference p, Object v) -> {
            if (syncing) {
                return true;
            }
            final boolean want = (Boolean) v;
            DebugController.setUsb(this, want, (ok, s) -> {
                if (!ok) {
                    Toast.makeText(this, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
                }
                refresh(s);
            });
            return true;
        });

        swWirelessPref.setOnPreferenceChangeListener((Preference p, Object v) -> {
            if (syncing) {
                return true;
            }
            final boolean want = (Boolean) v;
            DebugController.setWireless(this, want, (ok, s) -> {
                if (!ok) {
                    Toast.makeText(this, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
                }
                refresh(s);
            });
            return true;
        });

        // 「打开调试前先杀掉联动应用」：真正的执行在 DebugController 里（所有打开调试的入口统一处理）
        if (swKillPref != null) {
            swKillPref.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_KILL_BEFORE_OPEN, false));
            swKillPref.setOnPreferenceChangeListener((Preference p, Object v) -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PREF_KILL_BEFORE_OPEN, (Boolean) v).apply();
                return true;
            });
        }

        swLinkagePref.setOnPreferenceChangeListener((Preference p, Object v) -> {
            boolean on = (Boolean) v;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_LINKAGE, on).apply();
            // shortcut 组合随联动开关变化：开→「打开主页」，关→两个子开关
            ToggleShortcuts.publish(this);
            if (on) {
                // 开启自动跳转：必须先有联动应用、且设置默认跳转目标
                List<String> apps = LinkagePrefs.getApps(this);
                if (apps.isEmpty()) {
                    Toast.makeText(this, R.string.linkage_apps_empty_toast, Toast.LENGTH_LONG).show();
                } else if (LinkagePrefs.getDefault(this).isEmpty()) {
                    Toast.makeText(this, R.string.linkage_need_default, Toast.LENGTH_LONG).show();
                    openDefaultPicker();
                } else {
                    Toast.makeText(this, R.string.linkage_on, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, R.string.linkage_off, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        liveUpdatePref.setOnPreferenceClickListener(p -> {
            openLiveUpdate();
            return true;
        });

        // 「临时关闭时弹通知」开关：开启则关闭调试时弹常驻通知，关闭则静默（无通知能力）
        swNotifyPref.setOnPreferenceChangeListener((Preference p, Object v) -> {
            boolean on = (Boolean) v;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_NOTIFY, on).apply();
            // 立即同步：若当前已是关闭态，开->弹通知，关->撤通知
            if (DebugController.read(this).allOff()) {
                if (on) {
                    UsbToggleService.start(this);
                } else {
                    UsbToggleService.stop(this);
                }
            }
            return true;
        });

        IntentFilter f = new IntentFilter(DebugController.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
    }

    /** 切换主开关（行点击与 widget 内 Switch 共用） */
    private void toggleMaster() {
        boolean next = !DebugController.read(this).anyOn();
        applyMasterState(next);
    }

    /**
     * 折叠/展开两个子开关：开启时默认展开，关闭时默认折叠；
     * 用户点箭头可以手动覆盖（但关闭态下强制保持折叠，展开没有意义）。
     */
    private void applyMasterExpanded(boolean anyOn) {
        PreferenceCategory cat = (PreferenceCategory) findPreference("catDebug");
        if (cat == null || swUsbPref == null || swWirelessPref == null) {
            return;
        }
        boolean want = anyOn
                && (masterManualExpanded == null ? true : masterManualExpanded.booleanValue());
        if (want != subSwitchesShown) {
            if (want) {
                cat.addPreference(swUsbPref);
                cat.addPreference(swWirelessPref);
            } else {
                cat.removePreference(swUsbPref);
                cat.removePreference(swWirelessPref);
            }
            subSwitchesShown = want;
        }
        updateExpandIcon(want);
    }

    /** 更新主开关行的展开箭头图标 */
    private void updateExpandIcon(boolean expanded) {
        View btn = findViewDeep(R.id.btn_expand);
        if (btn instanceof android.widget.ImageButton) {
            ((android.widget.ImageButton) btn).setImageResource(
                    expanded ? R.drawable.ic_collapse : R.drawable.ic_expand);
        }
    }

    /** 点箭头：手动切换展开态 */
    private void toggleExpanded() {
        if (!DebugController.read(this).anyOn()) {
            return; // 关闭态强制保持折叠
        }
        masterManualExpanded = !subSwitchesShown;
        applyMasterExpanded(true);
    }

    /**
     * 主开关一键写入：唯一入口，两个子开关一起处理（su 写入在后台线程，回调回主线程）。
     * 关 -> 记忆当前组合并全关，按「临时关闭时弹通知」决定是否拉起常驻通知；
     * 开 -> 还原记忆的组合并撤掉常驻通知。
     */
    private void applyMasterState(boolean on) {
        applyMasterState(on, null);
    }

    private void applyMasterState(boolean on, final Runnable done) {
        DebugController.setMaster(this, on, (ok, s) -> {
            if (!ok) {
                Toast.makeText(this, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
            } else if (on) {
                UsbToggleService.stop(this);
            } else {
                // 按「临时关闭时弹通知」开关决定：开->常驻通知，关->静默
                UsbToggleService.syncCloseNotification(this);
            }
            refresh(s);
            if (done != null) {
                done.run();
            }
        });
    }

    /** 执行联动：关闭调试 -> 按开关拉起常驻通知 -> 跳转联动应用（写入完成后在回调里依序执行） */
    private void runLinkage(String pkg) {
        sLinkageLaunched = true;
        // 联动走统一入口：一键关闭会记住当前组合并把 USB / 无线都关掉。
        // 常驻通知在 su 写完之后才拉起，天然比旧版的固定 250ms 延时更稳。
        DebugController.setMaster(this, false, (ok, s) -> {
            if (!ok) {
                Toast.makeText(this, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
            } else {
                UsbToggleService.syncCloseNotification(this);
            }
            launchApp(pkg);
            finish();
        });
    }

    /**
     * 「关于」弹窗：版本号 / 包名 / 构建时间 / 当前授权模式 / 签名指纹。
     *
     * 构建时间放在这里而不是行摘要上：行摘要只放一行字，塞不下这些；
     * 而「设备上装的是不是最新版本」恰恰是排查问题时最常要的信息，
     * 放进弹窗后仍可一键查看，不会因为折叠而看不到。
     */
    private void showAboutDialog() {
        String buildTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault())
                .format(new java.util.Date(BuildConfig.BUILD_TIME_MS));
        AuthManager.Mode mode = AuthManager.getMode(this);

        // 授权模式行与主界面「授权模式」保持一致文案（root/Shizuku模式 · 已授权/未授权）
        int authStatusRes = (mode == AuthManager.Mode.SHIZUKU
                ? (privilegedAvailable ? R.string.auth_status_shizuku_ok : R.string.auth_status_shizuku_no)
                : (privilegedAvailable ? R.string.auth_status_root_ok : R.string.auth_status_root_no));

        // 结构化布局（dialog_about.xml）：直接给各 TextView 设文本，不使用带 %1$s/%1$d 占位符的字符串，
        // 避免占位符未被 String.format 填充而原样显示。
        android.view.View view = getLayoutInflater().inflate(R.layout.dialog_about, null);
        ((TextView) view.findViewById(R.id.about_name_version))
                .setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
        ((TextView) view.findViewById(R.id.about_package)).setText(getPackageName());
        ((TextView) view.findViewById(R.id.about_build_time))
                .setText(getString(R.string.about_build_time_label) + buildTime);
        ((TextView) view.findViewById(R.id.about_auth_mode)).setText(getString(authStatusRes));
        // 右侧信息图标：跳转项目仓库
        view.findViewById(R.id.about_info).setOnClickListener(v -> openUrl(ABOUT_REPO_URL));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton(R.string.about_credit, (d, which) -> openUrl(ABOUT_CREDIT_URL))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.update_check, null) // 点击逻辑在 onShow 里接管
                .create();
        dlg.setOnShowListener(d -> {
            Button neutral = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            // 功能未上线时隐藏该入口：出现一个必然失败的按钮比不出现更糟
            neutral.setVisibility(UpdateChecker.isActive() ? View.VISIBLE : View.GONE);
            neutral.setOnClickListener(v -> manualCheckUpdate());
        });
        dlg.show();
    }

    /** 跳转外部链接（关于弹窗的署名按钮与信息图标用）；失败给 Toast 不闪退 */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 按完整状态刷新三行开关。
     * 主开关 = 聚合态（任一开着就算开，因为 adbd 在跑）；两个子开关显示各自真值。
     */
    private void refresh(DebugController.State s) {
        if (swMasterPref == null || swUsbPref == null || swWirelessPref == null) {
            return;
        }
        boolean anyOn = s.anyOn();
        // 主开关是自定义 widget（箭头 + Switch），手动同步 Switch 勾选
        if (masterSwitch != null) {
            syncing = true;
            masterSwitch.setChecked(anyOn);
            syncing = false;
        }
        // 展开态跟随主开关：开→展开，关→折叠
        applyMasterExpanded(anyOn);
        // 全开或全关显示明确结论；只有一个开着时提示"部分开启"，避免误以为已安全
        int masterSum;
        int masterColor;
        if (s.allOff()) {
            masterSum = R.string.master_off;
            masterColor = R.color.stateOff;
        } else if (s.usb && s.wireless) {
            masterSum = R.string.master_on;
            masterColor = R.color.stateOn;
        } else {
            masterSum = R.string.master_partial;
            masterColor = R.color.error;
        }
        // 未授权时主开关行显示的是「未授权，无法写入调试开关」（见 applyRootState），
        // 这里不能覆盖它 —— 否则一次状态广播就把未授权提示冲掉了。
        if (privilegedAvailable) {
            swMasterPref.setSummary(masterSum);
            swMasterPref.setIcon(stateIcon(masterColor));
        }

        swUsbPref.setSummary(s.usb ? R.string.usb_on : R.string.usb_off);
        swUsbPref.setIcon(stateIcon(s.usb ? R.color.stateOn : R.color.stateOff));

        swWirelessPref.setSummary(s.wireless ? R.string.wireless_on : R.string.wireless_off);
        swWirelessPref.setIcon(stateIcon(s.wireless ? R.color.stateOn : R.color.stateOff));

        syncing = true;
        swUsbPref.setChecked(s.usb);
        swWirelessPref.setChecked(s.wireless);
        syncing = false;

        // 长按快捷方式的状态灯与界面同步
        ToggleShortcuts.publish(this);
    }

    /** 根据当前授权模式是否可用刷新控件与提示：未授权时禁用并提示，授权后及时恢复 */
    private void applyRootState() {
        // 偏好项任一为 null 说明设置页还没建好，直接跳过刷新
        if (swMasterPref == null || swUsbPref == null || swLinkagePref == null
                || swNotifyPref == null || swWirelessPref == null) {
            return;
        }
        if (privilegedAvailable) {
            swMasterPref.setEnabled(true);
            swUsbPref.setEnabled(true);
            swNotifyPref.setEnabled(true);
            swWirelessPref.setEnabled(true);
            // 联动分类下（开关 + 动态填充的默认/应用/管理行）一并启用
            setLinkageEnabled(true);
            // 授权恢复：把主开关行从「未授权，无法写入」改回真实状态文案。
            // （refresh 在未授权时不再覆盖文案，故必须在这里补一次，否则会一直停在未授权提示上）
            refresh(DebugController.read(this));
        } else {
            swMasterPref.setSummary(R.string.auth_no_priv);
            swMasterPref.setIcon(stateIcon(R.color.error));
            swMasterPref.setEnabled(false);
            swUsbPref.setEnabled(false);
            swNotifyPref.setEnabled(false);
            swWirelessPref.setEnabled(false);
            // 联动分类下所有项一并禁用（无授权则无法写入调试开关，联动也失去意义）
            setLinkageEnabled(false);
        }
        // 实时动态项的可用性只跟系统版本有关，不受授权模式影响
        updateLiveUpdateState();
    }

    /** 联动分类下所有项（开关 + 动态填充的默认 / 应用 / 管理行）一并启用或禁用 */
    private void setLinkageEnabled(boolean enabled) {
        PreferenceCategory cat = (PreferenceCategory) findPreference("catLinkage");
        if (cat == null) {
            return;
        }
        for (int i = 0; i < cat.getPreferenceCount(); i++) {
            cat.getPreference(i).setEnabled(enabled);
        }
    }

    /**
     * 按当前联动配置动态重建「联动」分类下的列表：
     *  1) 默认跳转应用行；2) 每个联动应用一行；3) 管理联动应用行。
     *  每次配置变化（增删 app、改默认、授权状态变化）都重建，保证 UI 与配置一致。
     */
    private void rebuildLinkageList() {
        PreferenceCategory cat = (PreferenceCategory) findPreference("catLinkage");
        if (cat == null) {
            return;
        }
        // 先移除上一轮动态项（保留静态的 swLinkage / swKillBeforeOpen）
        List<Preference> stale = new ArrayList<>();
        for (int i = 0; i < cat.getPreferenceCount(); i++) {
            Preference p = cat.getPreference(i);
            String k = p.getKey();
            if (k != null && (k.equals(LINKAGE_KEY_DEFAULT)
                    || k.equals(LINKAGE_KEY_MANAGE)
                    || k.startsWith(LINKAGE_APP_KEY_PREFIX))) {
                stale.add(p);
            }
        }
        for (Preference p : stale) {
            cat.removePreference(p);
        }

        List<String> apps = LinkagePrefs.getApps(this);
        String def = LinkagePrefs.getDefault(this);

        // 1) 默认跳转应用行：开启自动联动跳转后要跳去的目标
        Preference defPref = new Preference(this);
        defPref.setKey(LINKAGE_KEY_DEFAULT);
        defPref.setTitle(R.string.linkage_default_title);
        defPref.setLayoutResource(R.layout.pref_row);
        CharSequence defLabel = def.isEmpty() ? getString(R.string.linkage_default_none) : getApplicationLabel(def);
        defPref.setSummary(defLabel);
        defPref.setIcon(def.isEmpty() ? null : loadAppDrawable(def));
        cat.addPreference(defPref);

        // 2) 每个联动应用一行（点击=联动打开；行内「打开」「快捷」按钮见 wireAppRow）
        for (String pkg : apps) {
            Preference p = new Preference(this);
            p.setKey(LINKAGE_APP_KEY_PREFIX + pkg);
            p.setLayoutResource(R.layout.pref_row);
            CharSequence label = getApplicationLabel(pkg);
            p.setTitle(label == null ? pkg : label);
            // 摘要显示包名；若它是默认跳转应用，附加标记
            String summary = pkg + (pkg.equals(def) ? getString(R.string.linkage_default_mark) : "");
            p.setSummary(summary);
            p.setIcon(loadAppDrawable(pkg));
            p.setWidgetLayoutResource(R.layout.pref_widget_app_actions);
            cat.addPreference(p);
        }

        // 3) 管理联动应用行（打开多选弹窗，一次性增删多个 app）
        Preference manage = new Preference(this);
        manage.setKey(LINKAGE_KEY_MANAGE);
        manage.setTitle(R.string.linkage_manage_title);
        manage.setLayoutResource(R.layout.pref_row);
        manage.setSummary(apps.isEmpty()
                ? getString(R.string.linkage_apps_empty)
                : getString(R.string.linkage_manage_sum));
        cat.addPreference(manage);

        // 联动项跟随授权可用性
        setLinkageEnabled(privilegedAvailable);
        // 列表变化后重新接线（布局完成后 wireRowClicks 也会自动跑，这里兜底）
        scheduleWireRowClicks();
    }

    private View findViewDeep(int id) {
        return findDeep(getWindow().getDecorView(), id);
    }

    private View findDeep(View root, int id) {
        if (root == null) {
            return null;
        }
        if (root.getId() == id) {
            return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findDeep(vg.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 打开联动应用选择器，并在选中后写回配置、刷新行与桌面快捷方式 */
    private void openAppPicker() {
        android.util.Log.i(TAG, "openAppPicker() entered, lastPickerOpenAt=" + lastPickerOpenAt);
        long now = android.os.SystemClock.uptimeMillis();
        if (lastPickerOpenAt > 0 && now - lastPickerOpenAt < 500) {
            android.util.Log.i(TAG, "openAppPicker() debounced");
            return; // 防抖：避免 ListView 行点击与 preference click 同时触发，弹出两个选择器
        }
        lastPickerOpenAt = now;
        // 多选：预勾已选应用，确认后整组写回（勾上=新增，取消=移除）
        List<String> current = LinkagePrefs.getApps(this);
        AppListDialog.showMulti(this, current, chosen -> {
            LinkagePrefs.setApps(this, chosen);
            rebuildLinkageList();
            // 选完立刻刷新动态快捷方式（「启动联动应用」现取默认 app）
            ToggleShortcuts.publish(this);
            Toast.makeText(this, R.string.linkage_added, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 「默认跳转应用」选择：列出当前联动应用，点选即设为默认跳转目标。
     * 开启「自动联动跳转」后必须设置它（决定启动本 App 时跳去哪个）。
     * 没添加任何联动应用时提示先去添加。
     */
    private void openDefaultPicker() {
        List<String> apps = LinkagePrefs.getApps(this);
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.linkage_apps_empty_toast, Toast.LENGTH_LONG).show();
            return;
        }
        String cur = LinkagePrefs.getDefault(this);
        int checked = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).equals(cur)) {
                checked = i;
                break;
            }
        }
        CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            CharSequence l = getApplicationLabel(apps.get(i));
            labels[i] = (l == null ? apps.get(i) : l);
        }
        final int[] pick = {checked};
        new AlertDialog.Builder(this)
                .setTitle(R.string.linkage_default_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> pick[0] = which)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (pick[0] < 0) {
                        return;
                    }
                    LinkagePrefs.setDefault(this, apps.get(pick[0]));
                    rebuildLinkageList();
                    ToggleShortcuts.publish(this);
                    Toast.makeText(this, R.string.linkage_set_default, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 在布局完成、ListView 子行已 inflate 后再挂行点击/按钮。
     *
     * 坑：用 {@code View.post()} 只保证"下一帧执行"，不保证在 layout 之后执行，
     * 实测仍会出现 getChildCount()==0、按钮 findViewById 返回 null 的情况，
     * 结果就是所有点击入口都静默失效。这里改用 OnGlobalLayoutListener：
     * 每次布局完成都重挂一次，既能覆盖首次布局，也能覆盖滚动/复用后重新出现的行。
     */
    private void scheduleWireRowClicks() {
        View root = getWindow().getDecorView();
        if (root == null || rowClicksWired) {
            return;
        }
        rowClicksWired = true;
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::wireRowClicks);
    }

    /**
     * 给「非开关型」设置行整行挂 OnClickListener。
     *
     * 背景：framework 的 android.preference.Preference 在本工程里整行都不可点击
     * （uiautomator dump 显示各行 clickable="false"），其 performClick / OnItemClickListener
     * 都不生效，导致「联动应用」「实时动态」这类纯靠行点击的项点了没反应。
     * 这里直接给行的 itemView 设 OnClickListener（会同时把行置为 clickable），彻底绕开 framework。
     *
     * 行为：点整行（标题/摘要/图标区域）→ 打开对应功能；
     *      点右侧「打开」按钮 → 由按钮自己消费，触发启动已选应用（见 bindOpenButton）。
     */
    private void wireRowClicks() {
        android.widget.ListView list = findViewById(android.R.id.list);
        if (list == null) {
            return;
        }
        int first = list.getFirstVisiblePosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row == null) {
                continue;
            }
            // —— 动态联动项：按所在 Preference 的 key 识别（应用标题可能重复，不能用标题） ——
            Object item = list.getItemAtPosition(first + i);
            if (item instanceof Preference) {
                String key = ((Preference) item).getKey();
                if (key != null) {
                    if (key.startsWith(LINKAGE_APP_KEY_PREFIX)) {
                        wireAppRow(row, key.substring(LINKAGE_APP_KEY_PREFIX.length()));
                        continue;
                    } else if (LINKAGE_KEY_MANAGE.equals(key)) {
                        wireSimpleRow(row, this::openAppPicker);
                        continue;
                    } else if (LINKAGE_KEY_DEFAULT.equals(key)) {
                        wireSimpleRow(row, this::openDefaultPicker);
                        continue;
                    }
                }
            }
            // —— 固定行：按标题文本识别（沿用旧机制） ——
            // 按标题文本识别行：原先靠行内的「修改」按钮识别，该按钮已移除；
            // 按 position 映射到 getPreferenceScreen().getPreference() 又受 header/复用影响，故统一用标题。
            TextView tv = row.findViewById(android.R.id.title);
            CharSequence title = (tv == null) ? null : tv.getText();
            if (title == null) {
                continue;
            }
            // 本回调每次布局都会跑（OnGlobalLayoutListener），行没变就没必要重挂监听。
            // 用「行上记的已接线标题」判断：ListView 复用后标题变了会重新接线，标题没变则跳过。
            final boolean wired = title.equals(row.getTag(R.id.tag_row_wired));
            row.setTag(R.id.tag_row_wired, title);
            if (titleMaster.equals(title)) {
                if (!wired) {
                    // 主开关行：整行切换开关；箭头按钮单独消费点击，用于折叠/展开
                    row.setOnClickListener(v -> toggleMaster());
                    View arrow = row.findViewById(R.id.btn_expand);
                    if (arrow != null) {
                        arrow.setOnClickListener(v -> toggleExpanded());
                    }
                }
                Switch sw = row.findViewById(R.id.sw_master);
                if (sw != null) {
                    if (masterSwitch != sw) {
                        masterSwitch = sw;
                        // 用 checked 回调而不是 click：Switch 点击后自身状态已翻转，
                        // 以回调里给的 isChecked 为目标状态，才不会和真值打架。
                        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                            if (syncing) {
                                return;
                            }
                            applyMasterState(isChecked);
                        });
                    }
                    // 关键：widget 是布局完成后才拿到的，refresh() 往往跑在它之前而漏掉同步，
                    // 这里每次布局完都按真值同步一次，避免 Switch 停在 XML 的默认关闭态。
                    syncing = true;
                    sw.setChecked(DebugController.read(this).anyOn());
                    syncing = false;
                }
                updateExpandIcon(subSwitchesShown);
            } else if (titleLive.equals(title)) {
                if (!wired) {
                    row.setOnClickListener(v -> openLiveUpdate());
                }
            } else if (titleAbout.equals(title)) {
                if (!wired) {
                    row.setOnClickListener(v -> showAboutDialog());
                }
            } else if (titleAuth.equals(title)) {
                // 合并项「授权模式」整行点击 = 打开授权模式选择对话框
                if (!wired) {
                    row.setOnClickListener(v -> chooseAuthMode());
                }
            }
        }
    }

    /** 给整行设点击（动态项每次布局都重挂，直接替换监听即可，无需 wired 守卫） */
    private void wireSimpleRow(View row, Runnable action) {
        row.setOnClickListener(v -> action.run());
    }

    /**
     * 联动应用行接线：
     *  - 整行点击 = 联动打开（关调试 + 跳到它，即一次完整联动）；
     *  - 「打开」按钮 = 仅启动该应用（不动调试开关）；
     *  - 「快捷」按钮 = 在桌面生成该应用的原生快捷方式（点了会关调试并跳到它）。
     */
    private void wireAppRow(View row, String pkg) {
        row.setOnClickListener(v -> {
            if (!privilegedAvailable) {
                Toast.makeText(this, R.string.auth_need_grant, Toast.LENGTH_SHORT).show();
                return;
            }
            runLinkage(pkg);
        });
        View openBtn = row.findViewById(R.id.btn_app_open);
        if (openBtn != null) {
            openBtn.setOnClickListener(v -> launchApp(pkg));
        }
        View scBtn = row.findViewById(R.id.btn_app_shortcut);
        if (scBtn != null) {
            scBtn.setOnClickListener(v -> createDesktopShortcutFor(pkg));
        }
    }

    /** 打开「实时动态」引导（防抖，避免行点击与 preference click 同时触发） */
    private void openLiveUpdate() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLiveOpenAt < 500) {
            return;
        }
        lastLiveOpenAt = now;
        checkAndPromptLiveUpdate();
    }

    private CharSequence getApplicationLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 状态圆点图标（实心圆，随状态变色）；显示尺寸由 pref_row_state 的 20dp 图标框决定，约等于一行文字高 */
    private GradientDrawable stateIcon(int colorRes) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(getResources().getColor(colorRes, getTheme()));
        d.setSize(40, 40);
        return d;
    }

    private void launchApp(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置界面尚未构建（例如调试入口只用 postDelayed 延迟构建）时，
        // 偏好项字段都还是 null，下面的刷新会 NPE，必须先跳过。
        if (!prefsLoaded) {
            return;
        }
        // 当前模式未授权时，每次回到前台都重新双检测：用户在别处授权（superuser / Shizuku）后回到本 App 可即时恢复
        if (!privilegedAvailable) {
            performAuthCheck();
        }
        // 每次回到前台都回读真值：可能在别处（通知/磁贴/adb）被改过
        refresh(DebugController.read(this));
        rebuildLinkageList();
        updateLiveUpdateState();
        // 防御性重挂行点击：framework 可能在 resume/重绑时重置行状态，导致我们的 OnClickListener 失效；
        // 且滚动后新出现的行也需要重新挂，故每次 resume 都重新挂一遍。
        if (prefsLoaded) {
            scheduleWireRowClicks();
        }
        // 开始监听外部对这两个 Settings 键的改动（App 在前台的场景，onResume 的回读覆盖不到）
        observeSettings(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        observeSettings(false);
    }

    /**
     * 注册/反注册 Settings 监听，避免泄漏与无谓开销。
     *
     * 只监听 adb_enabled / adb_wifi_enabled 两个键的 URI，不监听整个 Settings.Global.CONTENT_URI：
     * 后者会让任何 global 设置变更（亮度、飞行模式等）都回调一次，白白触发一轮状态刷新。
     */
    private void observeSettings(boolean register) {
        try {
            android.content.ContentResolver cr = getContentResolver();
            if (register) {
                if (settingsObserverRegistered) {
                    return;
                }
                for (String key : OBSERVED_KEYS) {
                    cr.registerContentObserver(
                            Settings.Global.getUriFor(key), false, settingsObserver);
                }
                settingsObserverRegistered = true;
            } else {
                if (!settingsObserverRegistered) {
                    return;
                }
                cr.unregisterContentObserver(settingsObserver);
                settingsObserverRegistered = false;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "observe settings failed", t);
        }
    }

    /** 实时动态仅 Android 16+ 才有意义，低版本隐藏该设置项 */
    private void updateLiveUpdateState() {
        if (liveUpdatePref == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < 36) {
            liveUpdatePref.setEnabled(false);
            liveUpdatePref.setSummary(R.string.live_update_unsupported);
        } else {
            liveUpdatePref.setEnabled(true);
        }
    }

    // —— 双模式授权 ——

    /** 弹出授权模式选择对话框（root / Shizuku），选中后持久化并立即重测 */
    private void chooseAuthMode() {
        final AuthManager.Mode cur = AuthManager.getMode(this);
        final int checked = (cur == AuthManager.Mode.SHIZUKU) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.auth_choose_title)
                .setSingleChoiceItems(
                        new CharSequence[]{getString(R.string.auth_mode_root),
                                getString(R.string.auth_mode_shizuku)},
                        checked,
                        (d, which) -> {
                            AuthManager.Mode mode = (which == 1)
                                    ? AuthManager.Mode.SHIZUKU : AuthManager.Mode.ROOT;
                            AuthManager.setMode(MainActivity.this, mode);
                            d.dismiss();
                            updateAuthStatusUI();
                            // 切到新模式下立刻双检测：root 模式 fork su；
                            // Shizuku 模式先主动请求授权（force，用户是刚明确切过来的）
                            maybeRequestShizukuPermission(true);
                            performAuthCheck();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 渲染合并的「授权模式」行（标题=授权模式，副标题直接显示「root模式 · 未授权」等）
     *  规则：未授权 → 摘要整段改红字作警告（不挂圆形状态图标）；已授权 → 还原默认样式，
     *  并关掉底部未授权的 Snackbar。 */
    private void updateAuthStatusUI() {
        if (authPref == null) {
            return;
        }
        AuthManager.Mode mode = AuthManager.getMode(this);
        boolean ok = privilegedAvailable;
        int statusRes = (mode == AuthManager.Mode.SHIZUKU)
                ? (ok ? R.string.auth_status_shizuku_ok : R.string.auth_status_shizuku_no)
                : (ok ? R.string.auth_status_root_ok : R.string.auth_status_root_no);

        if (ok) {
            // 已授权：还原默认样式，不单独变色、不挂图标
            authPref.setSummary(statusRes);
            // 授权成功后关掉底部未授权提示（含「重试」那条）
            dismissAuthSnackbar();
        } else {
            // 未授权：整段摘要改红字警告（行首不加圆形指示图标）
            SpannableString sp = new SpannableString(getString(statusRes));
            sp.setSpan(new ForegroundColorSpan(
                            getResources().getColor(R.color.error, getTheme())),
                    0, sp.length(), SpannableString.SPAN_INCLUSIVE_INCLUSIVE);
            authPref.setSummary(sp);
        }
    }

    /** 关掉底部未授权提示（带「重试」的那条） */
    private void dismissAuthSnackbar() {
        if (authSnackbar != null) {
            authSnackbar.dismiss();
            authSnackbar = null;
        }
    }

    /**
     * Shizuku 模式且尚未授权时，向 Shizuku 管理器请求授权（弹出 Shizuku 授权对话框）。
     *
     * 触发时机：① Shizuku 服务就绪（binder 到达）；② 切到 Shizuku 模式；③ Shizuku 模式点「重试」。
     * 前置条件：Shizuku 已在运行（pingBinder）—— 服务没起来时请求无意义，Shizuku 会直接拒绝、也不弹窗。
     *
     * @param force true=忽略防抖立即请求（用户主动动作：切模式 / 点重试）；
     *              false=走 5s 防抖。防抖是必要的：授权结果回调会再次触发 performAuthCheck，
     *              若未授权就无防抖地再次请求，会形成「请求 -> 结果 -> 重测 -> 请求」的循环。
     */
    private void maybeRequestShizukuPermission(boolean force) {
        if (AuthManager.getMode(this) != AuthManager.Mode.SHIZUKU) {
            return;
        }
        if (!ShizukuUtil.running()) {
            android.util.Log.i(TAG, "Shizuku binder not alive, skip permission request");
            return;
        }
        if (ShizukuUtil.permissionGranted()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastShizukuRequestAt < 5000) {
            return;
        }
        lastShizukuRequestAt = now;
        android.util.Log.i(TAG, "requesting Shizuku permission (force=" + force + ")");
        try {
            Shizuku.requestPermission(AuthManager.SHIZUKU_PERM_REQUEST);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "requestPermission failed", t);
        }
    }

    /** 未授权时「重试」：root 模式直接重测；Shizuku 模式先确认服务在跑，未授权则弹授权请求 */
    private void onAuthRetry() {
        AuthManager.Mode mode = AuthManager.getMode(this);
        if (mode == AuthManager.Mode.SHIZUKU) {
            if (!ShizukuUtil.running()) {
                Toast.makeText(this, R.string.auth_need_start, Toast.LENGTH_LONG).show();
                return;
            }
            if (!ShizukuUtil.permissionGranted()) {
                // force=true：用户明确点了「重试」，必须立即弹授权
                maybeRequestShizukuPermission(true);
                return; // 结果由 OnRequestPermissionResultListener 收后再重测
            }
        }
        performAuthCheck();
    }

    /**
     * 底部未授权提示（原生 Snackbar，带「重试」）。
     *
     * 文案严格绑定当前授权模式 —— 底部提示与「授权模式」那一行是同一件事的两种呈现，
     * 不能出现「选了 root 模式却提示 Shizuku 已授权」这种错位引导（旧实现的 bug：
     * 它按双检测结果挑「哪个后端可用」来提示，跟用户选的模式无关）。
     * Shizuku 模式下再细分「服务没启动」与「已启动但没授权」，否则只说"未授权"用户无从下手。
     */
    private void showAuthNotice(AuthManager.Mode mode, boolean shizukuRunning) {
        final int textRes;
        if (mode == AuthManager.Mode.SHIZUKU) {
            textRes = shizukuRunning ? R.string.auth_toast_shizuku_no : R.string.auth_need_start;
        } else {
            textRes = R.string.auth_toast_root_no;
        }
        runOnUiThread(() -> showAuthSnackbar(getString(textRes), true));
    }

    /** 真正的 Snackbar：与应用主题无关的显式配色，避免在 framework 主题下取不到 Material 色值而显示异常 */
    private void showAuthSnackbar(String text, boolean unauthorized) {
        try {
            View root = getWindow().getDecorView().findViewById(android.R.id.content);
            if (root == null) {
                // 兜底：拿不到内容视图时退化为普通 Toast
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                return;
            }
            // 同一时刻只保留一个提示
            if (authSnackbar != null) {
                authSnackbar.dismiss();
                authSnackbar = null;
            }
            Snackbar sb = Snackbar.make(root, text,
                    unauthorized ? Snackbar.LENGTH_INDEFINITE : Snackbar.LENGTH_LONG);
            View v = sb.getView();
            v.setBackgroundColor(0xFF323232); // 与原生 Snackbar 一致的深色底
            TextView tv = v.findViewById(com.google.android.material.R.id.snackbar_text);
            if (tv != null) {
                // 未授权用红色作警告；已授权类提示保持白字
                tv.setTextColor(unauthorized
                        ? getResources().getColor(R.color.error, getTheme())
                        : 0xFFFFFFFF);
            }
            if (unauthorized) {
                sb.setActionTextColor(0xFFFFFFFF);
                sb.setAction(R.string.auth_retry, x -> onAuthRetry());
            }
            authSnackbar = sb;
            sb.addCallback(new Snackbar.Callback() {
                @Override
                public void onDismissed(Snackbar transientBottomBar, int event) {
                    if (authSnackbar == transientBottomBar) {
                        authSnackbar = null;
                    }
                }
            });
            sb.show();
        } catch (Throwable t) {
            // Snackbar 在个别主题/ROM 下可能 inflate 失败：绝不因此闪退，降级为 Toast
            android.util.Log.w(TAG, "Snackbar show failed, fallback to Toast", t);
            Toast.makeText(this, text, unauthorized ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
    }

    /** dp -> px 助手 */
    /** 取目标应用图标（Drawable，供 Preference 左侧显示）；失败回退本应用图标 */
    private Drawable loadAppDrawable(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return getResources().getDrawable(R.mipmap.ic_launcher);
        }
    }

    /**
     * 创建「原生桌面快捷方式」：在桌面（主屏幕）生成一个真实图标，点击直接拉起联动应用。
     *
     * 与 ShortcutManager 的「Shortcut」（长按应用图标弹出的菜单项）不同，这里用的是
     * Android 自 1.6/2.3 起就有的「添加到主屏幕」机制：
     *   - Android 7.1 及以下：发 {@code com.android.launcher.action.INSTALL_SHORTCUT} 广播；
     *   - Android 8.0+：多数 launcher 不再响应上面的广播，改用 ShortcutManager.requestPinShortcut
     *     钉选（同样生成真实桌面图标，且需用户确认）。
     * 二者都会在桌面落下实体图标，区别于其它 agent 误用的「Shortcut」长按菜单项。
     */
    /**
     * 为某个联动应用创建「原生桌面快捷方式」：在桌面（主屏幕）生成一个真实图标，点击直接关调试并联动启动该应用。
     * 与 ShortcutManager 的「Shortcut」（长按菜单项）不同，这里用的是
     * Android 自 1.6/2.3 起就有的「添加到主屏幕」机制（见 requestPinShortcut）。
     *
     * @param pkg 要创建快捷方式的联动应用包名（每个 app 的桌面图标各不相同，故意图里携带包名）
     */
    private void createDesktopShortcutFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            Toast.makeText(this, R.string.create_shortcut_no_app, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence label = getApplicationLabel(pkg);
        // 关键：快捷方式必须先打开「本 App」，由 App 关掉调试再联动启动目标应用，
        // 而不是直接拉起目标 app（那样会绕过关调试的联动逻辑，等于什么都不做）。
        // 用显式 Intent 指向 MainActivity + 自定义 action，并把包名带进去，
        // 图标仍用「目标应用 + 本 App 角标」以便一眼认出这是哪个联动应用的快捷方式。
        Intent launch = new Intent(this, MainActivity.class);
        launch.setAction(ACTION_DESKTOP_LINKAGE);
        launch.putExtra(EXTRA_LINKAGE_PKG, pkg);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bitmap icon = badgedShortcutIcon(pkg);

        // Android 8+ 优先用 LauncherApps 钉选（现代 launcher 才支持），失败再回退老广播
        if (Build.VERSION.SDK_INT >= 26 && requestPinShortcut(label, launch, icon)) {
            Toast.makeText(this, R.string.create_shortcut_done, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label != null ? label.toString() : pkg);
        if (icon != null) {
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
        }
        intent.putExtra("duplicate", false);
        try {
            sendBroadcast(intent);
            Toast.makeText(this, R.string.create_shortcut_done, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.create_shortcut_fail, Toast.LENGTH_SHORT).show();
        }
    }

    /** Android 8+：用 ShortcutManager.requestPinShortcut 把联动应用钉选为桌面图标
     *  （区别于 shortcuts.xml 里的长按菜单「Shortcut」，这里生成的是真实桌面图标），
     *  返回是否成功发出请求。 */
    private boolean requestPinShortcut(CharSequence label, Intent launch, Bitmap icon) {
        if (Build.VERSION.SDK_INT < 26) {
            return false;
        }
        ShortcutManager sm = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            return false;
        }
        ShortcutInfo.Builder b = new ShortcutInfo.Builder(this,
                "desktop_linkage_" + System.currentTimeMillis());
        b.setIntent(launch);
        b.setShortLabel(label != null ? label : getString(R.string.app_name));
        if (icon != null) {
            b.setIcon(Icon.createWithBitmap(icon));
        }
        try {
            return sm.requestPinShortcut(b.build(), null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 生成「联动应用图标 + 本 App 角标」的合成位图，避免桌面快捷方式图标与原 App 图标混淆。
     * 底层为联动应用图标，右下角叠加本 App 的小图标（白底圆），示意「由本 App 创建」。
     */
    private Bitmap badgedShortcutIcon(String pkg) {
        int size = Math.max(1, (int) (48 * getResources().getDisplayMetrics().density));
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // 底层：联动应用图标
        Drawable base;
        try {
            base = getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            base = getResources().getDrawable(R.mipmap.ic_launcher, getTheme());
        }
        base.setBounds(0, 0, size, size);
        base.draw(c);

        // 角标：本 App 图标（右下角，约 1/3 大小），白底圆提高辨识度
        int badge = (int) (size * 0.36f);
        int margin = (int) (size * 0.05f);
        int left = size - badge - margin;
        int top = size - badge - margin;
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(left + badge / 2f, top + badge / 2f, badge / 2f, dot);

        Drawable mine = getResources().getDrawable(R.drawable.ic_shortcut_badge, getTheme());
        mine.setBounds(left, top, left + badge, top + badge);
        mine.draw(c);

        return out;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
    }

    // 通知权限的授予结果不覆写处理：是否授予只影响常驻通知可见性，不影响开关写入功能，
    // 且发送通知处（checkAndPromptLiveUpdate / syncCloseNotification）各自做了能力判定。

    /**
     * Android 16 Live Updates：检查本应用是否被允许发布「实时动态」通知。
     * 已允许则提示已开启；未允许则弹窗引导到系统「实时动态」授权页。
     * 低于 Android 16 或厂商未实现时，自动隐藏该设置项。
     */
    /**
     * 「实时动态」一次点完两件事：先检测系统是否授予了实时动态能力，
     * 再立刻发一条测试通知——这样不用分开点，直接就能在状态栏/锁屏看到实际效果。
     */
    private void checkAndPromptLiveUpdate() {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(this, R.string.live_update_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean canPost = false;
        try {
            // 反射调用：该 API 为 Android 16 QPR1 引入，编译期 SDK 36 仍可能拒绝直接引用
            canPost = (Boolean) NotificationManager.class
                    .getMethod("canPostPromotedNotifications")
                    .invoke(nm);
        } catch (Throwable ignored) {
        }

        sendLiveUpdateTest();

        if (canPost) {
            Toast.makeText(this, R.string.live_update_test_sent, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.live_update_grant)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        // 用字面量 action，避免新常量在编译期不可引用
                        Intent i = new Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
                                .setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Throwable t) {
                        // 厂商未实现该设置页时，退回本应用通知设置页
                        try {
                            Intent j = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .setData(Uri.parse("package:" + getPackageName()));
                            startActivity(j);
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Toast.makeText(this, R.string.live_update_test_sent_plain, Toast.LENGTH_LONG).show();
    }

    /** 发一条测试用的实时动态通知（与常驻通知同一套提升方式） */
    private void sendLiveUpdateTest() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    "live_update_test", "实时动态测试", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, "live_update_test")
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .setContentTitle(getString(R.string.live_update_test_title))
                .setContentText(getString(R.string.live_update_test_text))
                .setPriority(Notification.PRIORITY_LOW);
        try {
            Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(b, true);
            Notification.Builder.class
                    .getMethod("setShortCriticalText", String.class)
                    .invoke(b, "测试中");
        } catch (Throwable ignored) {
        }
        // 点测试通知回到本应用设置页
        Intent main = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_SETTINGS, true);
        PendingIntent pi = PendingIntent.getActivity(this, 9, main, PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(pi);
        try {
            nm.notify(99, b.build());
        } catch (Throwable ignored) {
        }
        // 测试通知必须能自行消失：它是 ongoing（滑动去不掉），若不主动取消就会永久驻留
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                nm.cancel(99);
            } catch (Throwable ignored) {
            }
        }, 10_000);
    }
}
