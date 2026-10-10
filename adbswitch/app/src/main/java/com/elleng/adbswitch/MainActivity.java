package com.elleng.adbswitch;

import android.Manifest;
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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.elleng.adbswitch.util.RootUtil;

import java.util.ArrayList;
import java.util.List;
import com.elleng.adbswitch.update.UpdateChecker;
import com.elleng.adbswitch.update.UpdateDialog;
import com.elleng.adbswitch.update.UpdateInfo;

import rikka.shizuku.Shizuku;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * 主界面：Material 3 架构（AppCompatActivity + MaterialToolbar 顶栏
 * + PreferenceFragmentCompat 设置列表 + MaterialSwitch/MaterialAlertDialog），
 * 统一沿用 M3 组件样式（SwitchPreferenceCompat / Preference / PreferenceCategory）。
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_SETTINGS = "extra_settings";
    /** 静态 shortcut「打开主页」的 action：见到它就跳过联动自动跳转 */
    private static final String ACTION_OPEN_HOME = "com.elleng.adbswitch.action.OPEN_HOME";
    /** 桌面快捷方式「快捷打开」：先打开本 App，关掉调试后启动目标应用 */
    private static final String ACTION_DESKTOP_LINKAGE = "com.elleng.adbswitch.action.DESKTOP_LINKAGE";
    private static final String TAG = "AdbSwitch";
    private static final String PREFS = "cfg";
    /**
     * 需要监听外部改动的 Settings 键（与 DebugController 写入的是同一组，三个都不能少）。
     * 少一个就会出现「那一行不随外部改动刷新」——例如开发者选项被别处恢复后，
     * UI 还停在旧值，与相邻两行显示不一致。invalidateAndRefresh 会一次重读三个键，
     * 多监听一个键的代价只是多一次 URI 回调，不会多 fork 通道。
     */
    private static final String[] OBSERVED_KEYS = {DebugController.KEY_USB, DebugController.KEY_WIRELESS,
            DebugController.KEY_DEV};
    private static final String PREF_LINKAGE = "linkage_enabled";
    /** 动态联动应用行的 key 前缀：完整 key = 前缀 + 包名（rebuildLinkageList 据此重建、识别包名） */
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
    /** 动态 shortcut：启动快捷应用 */
    private static final String SC_ID_LAUNCH_APP = "launch_linkage_app";

    private MasterSwitchPreference swMasterPref; // 主开关：自定义 Preference，绑定时即同步 Switch 状态
    private Boolean masterManualExpanded; // 用户手动覆盖的展开态；null=跟随开关状态
    // 两个子开关当前是否已加入 category。
    // 必须初始化为 true：prefs.xml 里它们本来就写在 catDebug 内，
    // 初始化成 false 会导致首次刷新时「关闭态 want=false == 当前 false」而跳过 remove，折叠失效。
    private boolean subSwitchesShown = true;
    private SwitchPreferenceCompat swUsbPref;
    private SwitchPreferenceCompat swLinkagePref;
    private SwitchPreferenceCompat swNotifyPref;
    private SwitchPreferenceCompat swWirelessPref;
    /** 开发者选项入口开关：独立开关，不与主开关联动、不参与折叠（始终挂在 catDebug 里） */
    private SwitchPreferenceCompat swDevOptsPref;
    private SwitchPreferenceCompat swKillPref;
    /** 「测试灵动焦点通知」行当前是否挂在分类里（显隐联动见 updateLiveUpdateState） */
    private boolean liveUpdateShown = true;
    private Preference liveUpdatePref;
    /** 「关于」行：点击弹窗展示版本/构建时间/授权模式（原「构建时间」行） */
    private Preference buildInfoPref;
    private Preference authPref;              // 授权模式（root / Shizuku）与授权状态合并项

    private boolean syncing = false;          // 防止 setChecked 触发回环
    /** 当前授权模式是否可用（即能否真实写入调试开关）。乐观假定可用，后台双检测纠正 */
    private boolean privilegedAvailable = true;
    private boolean prefsLoaded = false;      // 设置界面是否已构建（onNewIntent 复用实例时避免重复加载）
    /** 上次重建「快捷打开」列表的内容指纹（配置没变就不重建，见 rebuildLinkageList） */
    private String lastLinkageSignature = null;
    /** 本启动周期是否已成功确认过授权：确认过则不再重复检测（切模式 / 执行失败会重置重测） */
    private boolean authCheckedThisSession = false;
    /** 底部 Snackbar（未授权提示，带「重试」按钮）；同一时刻只保留一个 */
    private com.google.android.material.snackbar.Snackbar authSnackbar = null;
    /** 上次请求 Shizuku 授权的时间戳（防抖，避免请求->结果->重测->请求的循环） */
    private long lastShizukuRequestAt = 0;
    /**
     * 上次跑授权探测的时间戳。
     *
     * 为什么要节流：root 探测是一次真实的 su fork，未授权时最坏要等满 10s 超时。
     * 旧实现「只有成功才缓存」，失败就每次进页面重跑一遍——在一个没 root 的设备上，
     * 来回切页面等于反复 fork su 干等。失败也记时间戳，30s 内不重跑；
     * 用户主动动作（切模式 / 点重试 / 授权结果回调）走 force 绕开节流。
     */
    private long lastAuthProbeAt = 0;
    private static final long AUTH_PROBE_MIN_INTERVAL_MS = 30_000;
    /** 进程级：本次进程内是否已自动跳转过联动应用（static 保证 Activity 重建后仍记得） */
    private static boolean sLinkageLaunched = false;
    /** 上一次跳转是否为「启动自动跳转」：决定回到设置页时提示什么（手动跳转不该说「启动时已自动跳转」） */
    private static boolean sAutoJumped = false;
    /** 「开了快捷打开但没设默认」的提示每进程只弹一次 */
    private static boolean sNoDefaultToasted = false;
    private long lastPickerOpenAt = 0;        // 打开选择器防抖：行点击与 preference click 可能同时触发
    private long lastLiveOpenAt = 0;          // 同上，实时动态弹窗防抖
    private boolean settingsObserverRegistered = false; // Settings 监听是否已注册（防重复注册/泄漏）
    /** 主线程 Handler：去抖与所有 post 都复用它，否则每次 new 一个 Handler 都拿不到上次的 callback，去抖等于失效 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 状态圆点 Drawable 缓存（key=colorRes）：每次 refresh 都会取多个，复用避免重复分配 */
    private final android.util.SparseArray<GradientDrawable> stateIconCache = new android.util.SparseArray<>();

    /** 关于弹窗外链：署名按钮 → elleng.eu.org；信息图标 → 项目仓库 */
    private static final String ABOUT_CREDIT_URL = "https://elleng.eu.org";
    private static final String ABOUT_REPO_URL = "https://github.com/gaoyilun/AdbSwitch";

    /** 状态变化 listener（替代原进程内广播）：任何写入/校准后刷新界面 */
    private final DebugController.StateListener stateListener = s -> {
        if (isDestroyed()) {
            return;
        }
        refresh(s);
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
        setContentView(R.layout.activity_main);
        setupToolbar();
        LinkagePrefs.migrate(this);
        initShizuku();
        if (!handleEntry(getIntent())) {
            showSettings();
        }
        // Activity 重建路径（深浅模式切换等）：被恢复的 fragment 会在 super.onCreate() 内
        // 同步回调 onPreferencesCreated，早于本方法体的任何初始化（setContentView 都未执行）。
        // 那次回调只挂起（见 onPreferencesCreated），在此统一补跑，保证与冷启动
        // （fragment 事务在 onCreate 返回后才执行）完全相同的初始化时序。
        onCreateFinished = true;
        if (pendingPrefsInit) {
            pendingPrefsInit = false;
            runPrefsInit();
        }
    }

    /** Shizuku 监听器持有引用，onDestroy 成对移除：Shizuku 的监听表是静态的，不移除会一直持有旧 Activity */
    private Shizuku.OnBinderReceivedListener shizukuBinderListener;
    private Shizuku.OnRequestPermissionResultListener shizukuPermListener;
    private Shizuku.OnBinderDeadListener shizukuDeadListener;

    /**
     * 注册 Shizuku 桥接监听：
     *  - OnBinderReceivedListener（sticky）：Shizuku 服务就绪后立刻重新检测授权，
     *    若当前是 Shizuku 模式且尚未授权，则主动弹授权请求；
     *  - OnBinderDeadListener：服务没了立刻让授权状态失效（见下）；
     *  - OnRequestPermissionResultListener：用户点完 Shizuku 授权弹窗后回写状态并重测。
     * 这些监听与「未授权时每次进页面检测」互补：即使页面没重建（例如 Shizuku 在后台启动/被杀），
     * 也能即时感知并刷新授权状态。
     *
     * OnBinderDeadListener 是官方建议成对使用的两个 binder 生命周期监听之一
     * （Shizuku-API README：`addBinderReceivedListener()` 与 `addBinderDeadListener()`）。
     * 缺它的话，无线调试断开 / Shizuku 被杀 / 一次性授权到期之后，
     * 界面会一直停在「已授权」的假象上，用户点开关只会一次次失败、却找不到原因。
     */
    private void initShizuku() {
        try {
            shizukuDeadListener = new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    android.util.Log.i(TAG, "Shizuku binder dead");
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) {
                            return;
                        }
                        // 服务没了：本周期的「已确认」结论必须作废，否则下次进页面不再重测
                        authCheckedThisSession = false;
                        if (AuthManager.getMode(MainActivity.this) == AuthManager.Mode.SHIZUKU) {
                            privilegedAvailable = false;
                            applyRootState();
                            updateAuthStatusUI();
                        }
                    });
                }
            };
            Shizuku.addBinderDeadListener(shizukuDeadListener);
            shizukuBinderListener = new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    android.util.Log.i(TAG, "Shizuku binder received");
                    // 服务就绪：Shizuku 模式且未授权时主动弹授权（force=false 走防抖）
                    maybeRequestShizukuPermission(false);
                    // 无论是否已授权都重测一次，刷新授权状态行与控件可用性
                    performAuthCheck();
                }
            };
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
            shizukuPermListener = new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == AuthManager.SHIZUKU_PERM_REQUEST) {
                        android.util.Log.i(TAG, "Shizuku permission result=" + grantResult);
                        // force：用户刚刚在弹窗里做了选择，必须立刻重测，不能被节流挡住
                        performAuthCheck(true);
                    }
                }
            };
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
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

        // 动态 shortcut「启动快捷应用」：直接跳目标应用（不触发关调试/常驻通知等动作），
        // 故不依赖快捷打开开关，开没开都能用。
        if (SC_ID_LAUNCH_APP.equals(in.getAction())) {
            String pkg = LinkagePrefs.getDefault(this);
            if (pkg.isEmpty()) {
                Toast.makeText(this, R.string.linkage_no_default, Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
            if (!launchApp(pkg)) {
                // 默认目标已失效：提示并落到设置页（已卸载的顺手清掉），不再静默闪回桌面
                handleLinkageTargetGone(pkg);
                return false;
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

        // 桌面快捷方式「创建桌面快捷方式」：先打开本 App，关掉调试后联动启动目标应用。
        // 这是显式触发（用户点了快捷方式），故不走 PREF_LINKAGE 开关、也不受
        // sLinkageLaunched 守卫限制——每次点都应重新关调试并跳目标应用。
        if (ACTION_DESKTOP_LINKAGE.equals(in.getAction())) {
            String pkg = in.getStringExtra(EXTRA_LINKAGE_PKG);
            // 桌面快捷方式始终带包名（见 createDesktopShortcutFor），缺包名的场景已废弃；
            // 真缺了就当普通入口落到设置页，不再单独提示。
            if (pkg == null || pkg.isEmpty()) {
                return false;
            }
            // Android 13+ 缺通知权限时，常驻通知会被系统抑制，联动流程依赖它，
            // 故先提示并落到设置页（showSettings 会补发权限申请），不跳转。
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.linkage_need_notif, Toast.LENGTH_LONG).show();
                return false;
            }
            runLinkage(pkg, false);
            return true;
        }

        boolean linkage = sp.getBoolean(PREF_LINKAGE, false);

        // —— 快捷打开：从启动器打开时，自动关闭调试并打开「默认快捷应用」 ——
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
                // 已经跳过一次：不再自动跳。
                // 否则从联动应用返回本 App（或再次点图标）时会被反复弹回去，
                // 用的人根本来不及用设置页。需要再跳时用动态快捷方式手动触发。
                // 只在上一次是「启动自动跳转」时提示，且只提示一次（手动跳转回来不必说
                // 「启动时已自动跳转」，反复打开也不该反复弹）。
                if (sLinkageLaunched) {
                    if (sAutoJumped) {
                        sAutoJumped = false;
                        Toast.makeText(this, R.string.linkage_skipped, Toast.LENGTH_LONG).show();
                    }
                    return false;
                }
                runLinkage(pkg, true);
                return true;
            }
            // 开了联动却没设默认跳转应用：提示并落到普通界面。
            // 每个进程只提示一次：配置没修好前每次打开都会走到这里，反复弹纯属打扰。
            if (!sNoDefaultToasted) {
                sNoDefaultToasted = true;
                Toast.makeText(this, R.string.linkage_no_default, Toast.LENGTH_LONG).show();
            }
        }
        return false;
    }

    // —— 在线更新 ——

    /**
     * 启动后自动检测更新（3 天节流，最低优先级）。
     *
     * 触发时机：主线程空闲时（{@link MessageQueue#IdleHandler}）才真正发起，不阻塞启动与主要功能；
     * 磁贴 / 桌面快捷方式开关走独立路径、不打开本 Activity，因此天然不会触发本检查。
     *
     * 弱提示策略（对开关类 App 不友好的是强弹窗，这里改成）：
     *   发现新版本 → 仅一次性 Toast（按版本号去重，不重复打扰）+ 把远端版本记下来，
     *   主页「关于」行随即显示「可更新」；绝不强弹 UpdateDialog。
     * 真正的新版下载入口只在「关于」弹窗的手动「检查更新」里。
     *
     * 当前 {@link UpdateConfig#ENABLED} 为 false，本方法第一行就返回，一次网络请求都不会发。
     * 启用后的行为：成功拿到结果才写节流时间戳，网络失败不写，避免弱网用户被节流 3 天。
     */
    private void checkUpdateOnStart() {
        if (!UpdateChecker.isActive() || !UpdateChecker.shouldCheck(this)) {
            return;
        }
        // 主线程空闲后再跑：不抢启动与主要功能，符合「优先级最低」的设定
        Looper.getMainLooper().getQueue().addIdleHandler(new MessageQueue.IdleHandler() {
            @Override
            public boolean queueIdle() {
                runAutoCheck();
                return false; // 一次性，不常驻
            }
        });
    }

    private void runAutoCheck() {
        new Thread(() -> {
            UpdateInfo info = null;
            try {
                info = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
            } catch (Throwable t) {
                android.util.Log.w("AdbSwitch", "update check failed", t);
            }
            if (info == null) {
                return; // 所有通道失败：不写节流，下次启动重试
            }
            UpdateChecker.markChecked(this);
            if (info.hasUpdate) {
                // 记下远端版本，关于行离线也能显示「可更新」
                UpdateChecker.saveLatestKnown(this, info.versionCode, info.versionName);
                // 仅对「未提示过的新版本」弹一次 Toast，不弹窗、不打扰
                if (info.versionCode != UpdateChecker.toastedVersion(this)) {
                    final String name = info.versionName;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            getString(R.string.update_toast_new, name), Toast.LENGTH_LONG).show());
                    UpdateChecker.markToasted(this, info.versionCode);
                }
            }
            // 无论是否有更新，都刷新关于行（可能刚记下了可更新版本）
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                refreshAboutSummary();
            });
        }, "update-check").start();
    }

    /**
     * 刷新主页「关于」行的摘要：默认显示当前版本号（如 1.0.0）；
     * 存在已知新版本时改为「1.0.0 可更新1.0.1」，离线也可见。
     */
    private void refreshAboutSummary() {
        if (buildInfoPref == null) {
            return;
        }
        String cur = BuildConfig.VERSION_NAME;
        if (UpdateChecker.hasKnownUpdate(this, BuildConfig.VERSION_CODE)) {
            String latest = UpdateChecker.getLatestVersionName(this);
            if (latest == null || latest.trim().isEmpty()) {
                latest = String.valueOf(UpdateChecker.getLatestVersionCode(this));
            }
            buildInfoPref.setSummary(getString(R.string.about_summary_update, cur, latest));
        } else {
            buildInfoPref.setSummary(cur);
        }
    }

    /** 「关于」弹窗里的手动检查入口（功能未激活时按钮仍可见，点按提示尚未配置）。 */
    private void manualCheckUpdate() {
        if (!UpdateChecker.isActive()) {
            Toast.makeText(this, R.string.update_not_configured, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            UpdateInfo info = null;
            try {
                info = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
            } catch (Throwable t) {
                android.util.Log.w("AdbSwitch", "update check failed", t);
            }
            if (info != null) {
                UpdateChecker.markChecked(this);
                if (info.hasUpdate) {
                    // 记下远端版本，关于行同步显示「可更新」
                    UpdateChecker.saveLatestKnown(this, info.versionCode, info.versionName);
                }
            }
            final UpdateInfo result = info;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (result == null) {
                    Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                } else if (!result.hasUpdate) {
                    Toast.makeText(this, R.string.update_already_latest, Toast.LENGTH_SHORT).show();
                } else {
                    UpdateDialog.show(this, result, new UpdateDialog.Listener() {
                        @Override
                        public void onOpenUrl(String url) {
                            openExternalUrl(url);
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
            android.util.Log.w("AdbSwitch", "open url failed: " + url, t);
            Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 双模式授权检测（替换原 probeRoot）。
     *
     * 与旧实现的三点差异：
     *  1. **只探测当前模式对应的后端**。Shizuku 模式下不再 fork su —— 用户根本没打算用 root
     *     时，那次 su 可能弹出 Magisk 授权弹窗、最坏还要等满 10s 超时；
     *     Shizuku 侧是纯 binder 调用（微秒级），两种模式都可以顺带探。
     *  2. **失败也节流**（见 {@link #lastAuthProbeAt}）：旧实现只有成功才缓存，
     *     失败就每次进页面重跑一遍 su。
     *  3. 探测结论区分「没有 su」与「有 su 未授权」（RootUtil.probe），文案据此分流。
     *
     * @param force true=忽略「本周期已确认」与节流，立即重测（切模式 / 点重试 / 授权结果回调）
     */
    private void performAuthCheck() {
        performAuthCheck(false);
    }

    private void performAuthCheck(boolean force) {
        if (!prefsLoaded) {
            return;
        }
        // 本启动周期已成功确认过授权：不重复 fork su。
        // 授权一旦拿到不会凭空消失，重复检测纯属浪费（root 检测每次都是一次 su 等待）。
        // 例外：切模式（chooseAuthMode）、执行失败、binder 死亡时会重置本标志，重新检测；
        // 用户主动动作（force）一律重测。
        if (authCheckedThisSession && !force) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastAuthProbeAt < AUTH_PROBE_MIN_INTERVAL_MS) {
            return; // 节流：失败也不反复 fork su 干等
        }
        lastAuthProbeAt = now;
        new Thread(() -> {
            final AuthManager.Mode mode = AuthManager.getMode(MainActivity.this);
            // Shizuku 模式不 fork su（代价高且会惊动 Magisk），root 模式才探
            final RootUtil.RootStatus rootStatus = mode == AuthManager.Mode.ROOT
                    ? RootUtil.probe() : RootUtil.RootStatus.DENIED;
            final boolean rootOk = rootStatus == RootUtil.RootStatus.GRANTED;
            final boolean shizukuRunning = ShizukuUtil.running();
            final boolean shizukuOk = shizukuRunning && ShizukuUtil.permissionGranted();
            final boolean ok = (mode == AuthManager.Mode.SHIZUKU) ? shizukuOk : rootOk;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (isDestroyed() || isFinishing()) {
                    return; // Activity 已销毁，避免 UI/窗口泄漏
                }
                privilegedAvailable = ok;
                // 确认成功才记到本启动周期：失败不缓存，下次仍会重测（授权可能稍后补上）
                if (ok) {
                    authCheckedThisSession = true;
                }
                applyRootState();
                updateAuthStatusUI();
                // Shizuku 模式且服务在跑却没授权：主动弹 Shizuku 授权对话框
                if (!ok && mode == AuthManager.Mode.SHIZUKU) {
                    maybeRequestShizukuPermission(false);
                }
                if (!ok) {
                    showAuthNotice(mode, shizukuRunning, rootStatus);
                }
            });
        }, "auth-probe").start();
    }

    /** M3 顶栏（MaterialToolbar）：标题 + 副标题，Surface 底色（无图标，左端留白）。 */
    private void setupToolbar() {
        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() == null) {
            return;
        }
        // 不显示 App 图标：左端留白，只留标题/副标题
        getSupportActionBar().setDisplayShowHomeEnabled(false);
        getSupportActionBar().setTitle(R.string.app_name);
        getSupportActionBar().setSubtitle(R.string.header_subtitle);
    }

    /**
     * 构建并刷新设置界面（幂等：onNewIntent 复用实例时不可重复装载 fragment）。
     * 首次进入：装载 PreferenceFragmentCompat，树建好后的完整初始化走
     * {@link #onPreferencesCreated()}（fragment 事务异步执行）。
     */
    private void showSettings() {
        if (!prefsLoaded) {
            SettingsFragment existing = (SettingsFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.settings_container);
            if (existing != null) {
                // 重建路径：fragment 由系统从保存状态恢复，补挂字段引用。
                // 此前直接 return 会让 settingsFragment 整个生命周期为 null，
                // 之后所有 pref() 都取不到行；初始化统一由 onCreate 收尾的 runPrefsInit 执行。
                settingsFragment = existing;
                return;
            }
            // Android 13+ 首次进入补发通知权限（常驻通知 / 联动流程依赖）
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            settingsFragment = new SettingsFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, settingsFragment)
                    .commit();
            return;
        }

        performAuthCheck();
        refresh(DebugController.read(this));
        applyRootState();
        rebuildLinkageList();
    }

    /** 设置列表 fragment；preference 树就绪前 findPreference 不可用，刷新方法均有 null 守卫 */
    private SettingsFragment settingsFragment;

    /** onCreate 是否已收尾：重建时 fragment 的恢复回调会早于它触发（见 onPreferencesCreated） */
    private boolean onCreateFinished = false;
    /** 重建路径挂起的初始化标记：onCreate 收尾统一补跑 */
    private boolean pendingPrefsInit = false;

    /**
     * PreferenceFragmentCompat 回调：preference 树已 inflate 完成。
     * 原 showSettings 里「建好界面后」的初始化与首次刷新全部挪到这里
     * （fragment 事务在 onCreate 返回后才执行，原先的同步流程必须异步化）。
     */
    private void onPreferencesCreated() {
        if (!onCreateFinished) {
            // Activity 重建（深浅模式切换等）时，FragmentManager 在 super.onCreate() 内
            // 恢复 fragment 并同步回调到这里；此时 setContentView/setupToolbar/字段均未就绪，
            // 直接初始化会 NPE（曾导致切深浅模式闪退）。挂起，onCreate 收尾统一补跑。
            pendingPrefsInit = true;
            return;
        }
        runPrefsInit();
    }

    /** preference 树就绪后的完整初始化与首刷（原 onPreferencesCreated 主体） */
    private void runPrefsInit() {
        prefsLoaded = true;
        initPrefs();
        checkUpdateOnStart();
        performAuthCheck();
        refresh(DebugController.read(this));
        applyRootState();
        rebuildLinkageList();
        updateLiveUpdateState();
        refreshAboutSummary();
    }

    /** 承载设置列表的 fragment：仅负责 inflate prefs.xml，业务与行刷新都在 Activity */
    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.prefs, null);
            ((MainActivity) requireActivity()).onPreferencesCreated();
        }
    }

    /** 按 key 从 fragment 的 preference 树里取行；树未就绪时返回 null（调用方自行守卫）。
     *  字段为空时回退到 FragmentManager 查找：重建路径的系统恢复回调早于字段赋值。 */
    private <T extends Preference> T pref(String key) {
        SettingsFragment f = settingsFragment;
        if (f == null) {
            f = (SettingsFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.settings_container);
        }
        return f == null ? null : f.findPreference(key);
    }

    private void initPrefs() {
        swMasterPref = pref("swMaster");
        // 开关拨动真正要做的事（写调试开关）注入给自定义 Preference；箭头折叠回调一并注入。
        // null 守卫：极端时序下树未就绪时跳过接线（宁可主开关不动也不闪退），并留日志定位。
        if (swMasterPref != null) {
            swMasterPref.setOnToggleListener(this::applyMasterState);
            swMasterPref.setOnExpandListener(this::toggleExpanded);
        } else {
            Log.w(TAG, "initPrefs: swMaster 为空，跳过主开关接线（preference 树未就绪）");
        }
        swUsbPref = pref("swUsb");
        swLinkagePref = pref("swLinkage");
        swNotifyPref = pref("swNotify");
        swWirelessPref = pref("swWireless");
        swDevOptsPref = pref("swDevOpts");
        swKillPref = pref("swKillBeforeOpen");
        // 分类内按 android:order 排序，而不是按添加顺序：
        // 折叠再展开时 usb / wireless 是 addPreference 回来的，按添加顺序会排到末尾
        //（跑到开发者选项行之后）。固定 order 才能保证任何时候顺序都是「主 > USB > 无线 > 开发者选项」。
        PreferenceCategory catDebug = pref("catDebug");
        if (catDebug != null) {
            catDebug.setOrderingAsAdded(false);
        }
        liveUpdatePref = pref("liveUpdate");
        buildInfoPref = pref("buildInfo");
        // 「关于」行：整行点击弹窗（旧 framework 实现行不可点、需 ListView 行接线；
        // androidx 行点击原生触发 OnPreferenceClickListener，直接挂监听即可）
        if (buildInfoPref != null) {
            buildInfoPref.setOnPreferenceClickListener(p -> {
                showAboutDialog();
                return true;
            });
        }
        // 关于行默认显示当前版本号（有已知可更新版本时改成「版本 可更新最新版」）
        refreshAboutSummary();
        // 双模式授权：模式选择 + 授权状态已合并为同一项。点击整行 = 打开模式选择对话框
        authPref = pref("auth");
        if (authPref != null) {
            authPref.setOnPreferenceClickListener(p -> {
                chooseAuthMode();
                return true;
            });
        }
        // 三个自定义开关键统一以 cfg 为唯一数据源：
        // framework 的 SwitchPreference 默认会把值存进它自己的默认 SharedPreferences，
        // 而真正读这些值的是 DebugController / AdbToggleService（读 cfg）——两份状态一旦不同步，
        // 就会出现「界面显示开、实际按关执行」。这里在进入时按 cfg 显式回填，
        // 并配合 prefs.xml 的 persistent="false" 关掉 framework 自存，杜绝双写。
        SharedPreferences cfg = getSharedPreferences(PREFS, MODE_PRIVATE);
        swLinkagePref.setChecked(cfg.getBoolean(PREF_LINKAGE, false));
        swNotifyPref.setChecked(cfg.getBoolean(PREF_NOTIFY, true));

        // 初次进入即用当前模式渲染一次授权状态（精确结果等后台双检测回来再纠正）
        updateAuthStatusUI();
        // 底部「关于」行：点击弹窗展示版本/构建时间等（构建时间不再直接占用行摘要）

        // 主开关：一键开/关。关闭时记住两个子开关的组合，打开时原样还原。
        // 行内 Switch 由 MasterSwitchPreference 绑定时接线；整行点击（androidx 原生触发）在此接线。
        // 启动窗口期（真值尚未读回）屏蔽操作：点击不执行，但给一次性轻提示（见 P1.1）
        swMasterPref.setOnPreferenceClickListener(p -> {
            if (swallowIfUnconfirmed()) {
                return true;
            }
            toggleMaster();
            return true;
        });

        // 三个子开关（USB / 无线 / 开发者选项）的监听逻辑同构，统一由 bindSubSwitch 收口：
        // 启动窗口期（真值未读回）吞掉点击并给一次性轻提示；写完后按回读真值刷新并提示写入是否生效。
        bindSubSwitch(swUsbPref, (on, cb) -> DebugController.setUsb(this, on, cb),
                s -> s.usb, R.string.toast_write_not_effective, null);
        bindSubSwitch(swWirelessPref, (on, cb) -> DebugController.setWireless(this, on, cb),
                s -> s.wireless, R.string.toast_write_not_effective, null);
        if (swDevOptsPref != null) {
            bindSubSwitch(swDevOptsPref, (on, cb) -> DebugController.setDev(this, on, cb),
                    s -> s.dev, R.string.toast_write_not_effective, R.string.toast_devopts_hidden);
        }

        // 「打开调试前先杀掉联动应用」：真正的执行在 DebugController 里（所有打开调试的入口统一处理）
        if (swKillPref != null) {
            // 默认开启：首次安装即勾选（与 DebugController 读取的默认值一致）
            swKillPref.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_KILL_BEFORE_OPEN, true));
            swKillPref.setOnPreferenceChangeListener((Preference p, Object v) -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PREF_KILL_BEFORE_OPEN, (Boolean) v).apply();
                return true;
            });
        }

        swLinkagePref.setOnPreferenceChangeListener((Preference p, Object v) -> {
            boolean on = (Boolean) v;
            // 未设置默认快捷应用时不允许打开：toast 提示并保持开关关闭（返回 false 不落状态）
            if (on && LinkagePrefs.getDefault(this).isEmpty()) {
                Toast.makeText(this, R.string.linkage_no_default, Toast.LENGTH_LONG).show();
                return false;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_LINKAGE, on).apply();
            // shortcut 组合随联动开关变化：开→「打开主页」，关→两个子开关
            ToggleShortcuts.markDirty(this);
            if (on) {
                // 开启后提醒用户：以后怎么绕过自动跳转、进设置页（均基于现有能力，不新增入口）
                showLinkageHintDialog();
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
                    AdbToggleService.start(this);
                } else {
                    AdbToggleService.stop(this);
                }
            }
            // 「测试灵动焦点通知」行随之显隐（只有打开开关才显示测试）。
            // 必须 post：本回调返回后 framework 才把开关新值落进 isChecked()，
            // 立刻读会拿到旧值，显隐就慢一拍。
            mainHandler.post(this::updateLiveUpdateState);
            return true;
        });

        // 状态变化改走进程内 listener 注册表（App 单进程，广播纯开销）
        DebugController.addStateListener(stateListener);
    }

    // —— 启动窗口期点击吞掉 + 子开关监听收口（P1.1 / P2.6）——

    /** 子开关写入封装：绑定一个开关的「拨动 → 写 DebugController → 回读刷新」逻辑 */
    private interface SubWriter {
        void write(boolean on, DebugController.ResultCallback cb);
    }

    /** 从聚合状态里取出该子开关的布尔字段（判断「写入后是否真的变成想要的值」） */
    private interface SubField {
        boolean get(DebugController.State s);
    }

    /** 启动窗口期（真值尚未经授权通道确认）屏蔽操作：吞掉点击并给一次性轻提示 */
    private boolean swallowIfUnconfirmed() {
        if (DebugController.confirmedThisSession()) {
            return false; // 已确认，正常放行
        }
        toastReading(this);
        return true; // 未确认：吞掉
    }

    /** 「正在读取当前状态…」的轻提示：2s 内只弹一次，避免快速连点刷屏 */
    private static long lastReadingToastAt = 0;

    static void toastReading(Context ctx) {
        if (ctx == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastReadingToastAt < 2000) {
            return;
        }
        lastReadingToastAt = now;
        Toast.makeText(ctx, R.string.master_checking, Toast.LENGTH_SHORT).show();
    }

    /**
     * 绑定一个子开关的监听器，收口三个子开关（USB / 无线 / 开发者选项）同构的逻辑：
     *  - 防止 setChecked 回灌触发；
     *  - 启动窗口期（真值未读回）吞掉点击并给一次性轻提示；
     *  - 写完后按回读真值刷新界面，并在「写入未生效」或（可选）「关闭成功」时给出提示。
     *
     * @param offOkRes 非 null 时，在「关闭成功」后额外弹该提示（开发者选项入口隐藏专用）
     */
    private void bindSubSwitch(SwitchPreferenceCompat pref, SubWriter writer, SubField field,
                               int notEffectiveRes, Integer offOkRes) {
        pref.setOnPreferenceChangeListener((p, v) -> {
            if (syncing) {
                return true;
            }
            if (swallowIfUnconfirmed()) {
                return false; // 未确认：吞掉拨动，开关弹回
            }
            final boolean want = (Boolean) v;
            writer.write(want, (ok, s, reason) -> {
                if (!ok) {
                    authCheckedThisSession = false;
                    Toast.makeText(MainActivity.this,
                            DebugController.failureMessage(MainActivity.this, reason),
                            Toast.LENGTH_SHORT).show();
                } else if (s == null || field.get(s) != want) {
                    // 命令退出码 0 却没写进去（个别 ROM 会屏蔽该键）：界面只会无声弹回，必须明确告知
                    Toast.makeText(MainActivity.this, notEffectiveRes, Toast.LENGTH_SHORT).show();
                } else if (offOkRes != null && !want) {
                    // 关闭成功才提示（如开发者选项入口一隐藏就再也找不到这一项）
                    Toast.makeText(MainActivity.this, offOkRes, Toast.LENGTH_LONG).show();
                }
                refresh(s);
            });
            return true;
        });
    }

    /**
     * 切换主开关（整行点击用）：先经授权通道读回真值、再相对真值翻转。
     * 不用 UI 显示的缓存来决定翻向——刚启动时缓存可能失真（ROM 隐藏键），
     * 按它翻会出现「点了没反应 / 点反了」。DebugController.toggleMaster 内部已处理。
     */
    private void toggleMaster() {
        DebugController.toggleMaster(this, (ok, s, reason) -> {
            if (ok) {
                if (s.anyOn()) {
                    AdbToggleService.stop(MainActivity.this);
                } else {
                    AdbToggleService.syncCloseNotification(MainActivity.this);
                }
            } else {
                // 执行失败：授权大概率已失效（su 超时 / Shizuku 被撤销）。立即刷新授权状态，
                // 让主开关与联动项灰掉并显示「未授权」+「重试」，而不是停在旧的「已授权」假象；
                // 同时提示用户重试或切换授权模式。
                privilegedAvailable = false;
                authCheckedThisSession = false;
                applyRootState();
                Toast.makeText(this, DebugController.failureMessage(this, reason),
                        Toast.LENGTH_SHORT).show();
            }
            refresh(s);
        });
    }

    /**
     * 折叠/展开两个子开关：开启时默认展开，关闭时默认折叠；
     * 用户点箭头可以手动覆盖（但关闭态下强制保持折叠，展开没有意义）。
     */
    private void applyMasterExpanded(boolean anyOn) {
        PreferenceCategory cat = pref("catDebug");
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
        // 箭头图标随展开态同步（androidx：状态存进 Preference，行绑定时也会自动摆）
        swMasterPref.setExpandedState(want);
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
        DebugController.setMaster(this, on, (ok, s, reason) -> {
            if (ok) {
                if (s.anyOn() != on) {
                    // 退出码 0 但真值没跟上（ROM 拦截该键）：明确提示写入未生效，别让用户以为成功
                    Toast.makeText(this, R.string.toast_write_not_effective, Toast.LENGTH_SHORT).show();
                }
                if (on) {
                    AdbToggleService.stop(this);
                } else {
                    // 按「临时关闭时弹通知」开关决定：开->常驻通知，关->静默
                    AdbToggleService.syncCloseNotification(this);
                }
            } else {
                // 写入失败：授权大概率已失效，立即刷新授权状态（主开关/联动灰掉 + 显示未授权），
                // 而不是停在旧的「已授权」假象，让用户误以为操作成功了。
                // 同时清掉本周期的授权缓存，下次操作前重新检测（权限可能稍后恢复）。
                privilegedAvailable = false;
                authCheckedThisSession = false;
                applyRootState();
                Toast.makeText(this, DebugController.failureMessage(this, reason),
                        Toast.LENGTH_SHORT).show();
            }
            refresh(s);
            if (done != null) {
                done.run();
            }
        });
    }

    /**
     * 执行快捷打开：关闭调试 -> 按开关拉起常驻通知 -> 打开目标应用（写入完成后在回调里依序执行）。
     *
     * @param autoJump 是否为「开启快捷打开后启动本 App」的自动跳转（决定是否弹跳转提示）
     */
    private void runLinkage(String pkg, boolean autoJump) {
        sLinkageLaunched = true; // 任意来源跳转都记一笔：回来时不再自动跳，避免反复弹回
        if (autoJump) {
            sAutoJumped = true; // 区分来源：回设置页时只有自动跳转才提示「启动时已自动跳转」
        }
        // 联动走统一入口：一键关闭会记住当前组合并把 USB / 无线都关掉。
        // 常驻通知在 su 写完之后才拉起，天然比旧版的固定 250ms 延时更稳。
        DebugController.setMaster(this, false, (ok, s, reason) -> {
            if (!ok) {
                // 与 toggleMaster / applyMasterState 同规则：写入失败大概率授权失效，立即置灰并重测
                privilegedAvailable = false;
                authCheckedThisSession = false;
                applyRootState();
                Toast.makeText(this, DebugController.failureMessage(this, reason),
                        Toast.LENGTH_SHORT).show();
            } else {
                AdbToggleService.syncCloseNotification(this);
            }
            if (!launchApp(pkg)) {
                // 目标应用已失效（卸载 / 停用）：提示并留在设置页，而不是静默闪回桌面
                handleLinkageTargetGone(pkg);
                if (!prefsLoaded) {
                    showSettings();
                }
                return;
            }
            if (autoJump) {
                Toast.makeText(this, R.string.linkage_auto_jumped, Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }

    /**
     * 快捷打开目标不可用（已卸载 / 已停用 / 无启动入口）的统一处理：
     * 已卸载的顺手从列表清掉（与 rebuildLinkageList 的自动清理一致），已停用的保留（可随时恢复）；
     * 两种情况都给出提示，避免「点了没反应」。
     */
    private void handleLinkageTargetGone(String pkg) {
        if (isAppUninstalled(this, pkg)) {
            List<String> apps = new ArrayList<>(LinkagePrefs.getApps(this));
            if (apps.remove(pkg)) {
                LinkagePrefs.setApps(this, apps);
                ToggleShortcuts.markDirty(this);
            }
            Toast.makeText(this, R.string.linkage_app_uninstalled, Toast.LENGTH_LONG).show();
            if (prefsLoaded) {
                rebuildLinkageList();
            }
        } else {
            Toast.makeText(this, R.string.linkage_app_unlaunchable, Toast.LENGTH_LONG).show();
        }
    }

    /** 目标应用是否已卸载（停用的应用仍算「装着」，不清理、只在跳转失败时提示） */
    private static boolean isAppUninstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return false;
        } catch (Throwable t) {
            return true;
        }
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

        final AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .setNegativeButton(R.string.about_credit, (d, which) -> openUrl(ABOUT_CREDIT_URL))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.update_check, null) // 点击逻辑在 onShow 里接管
                .create();
        dlg.setOnShowListener(d -> {
            Button neutral = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            // 「检查更新」常驻可见（待激活）：功能未配置时仍可点，提示尚未配置；
            // 比「未上线就藏起来」更符合预期——关于页一眼能看到有此功能，配置好后即生效。
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
     * 按完整状态刷新各行开关。
     * 主开关 = 聚合态（任一开着就算开，因为 adbd 在跑）；子开关显示各自真值；
     * 开发者选项行是独立开关，只反映 development_settings_enabled。
     */
    private void refresh(DebugController.State s) {
        if (swMasterPref == null || swUsbPref == null || swWirelessPref == null) {
            return;
        }
        // 真值尚未由授权通道确认时，界面按「上次确认状态」渲染，不用调用方传入的快照：
        // 那多半是 ContentResolver 兜底值（ROM 隐藏这两个键时恒为关），
        // 直接拿来显示会出现「启动先显示关闭、校准完又跳回开启」的闪变。
        if (!DebugController.isCalibrated()) {
            s = DebugController.lastKnownState(this);
        }
        boolean anyOn = s.anyOn();
        // 真值未经本次授权通道确认时，显示「上次确认状态」的文案（保持稳定、不跳字）；
        // 只有首次使用（历史从没确认过、无上次状态）才显示「正在读取当前状态…」并置灰。
        final boolean calibrated = DebugController.isCalibrated();
        // 首次使用（无「上次状态」）才置灰；其余情况显示上次状态、不置灰，
        // 真值不一致时校准回来修正一次（见 MasterSwitchPreference 的显示规则）
        final boolean known = calibrated || DebugController.hasConfirmedBefore(this);
        swMasterPref.applyState(anyOn, known);
        // 展开态跟随主开关：开→展开，关→折叠
        applyMasterExpanded(anyOn);
        // 全开或全关显示明确结论；只有一个开着时提示"部分开启"，避免误以为已安全
        int masterSum;
        int masterColor;
        if (!known) {
            masterSum = R.string.master_checking;
            masterColor = R.color.stateUnknown;
        } else if (s.allOff()) {
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

        if (swDevOptsPref != null) {
            swDevOptsPref.setSummary(s.dev ? R.string.devopts_on : R.string.devopts_off);
            swDevOptsPref.setIcon(stateIcon(s.dev ? R.color.stateOn : R.color.stateOff));
        }

        syncing = true;
        swUsbPref.setChecked(s.usb);
        swWirelessPref.setChecked(s.wireless);
        if (swDevOptsPref != null) {
            swDevOptsPref.setChecked(s.dev);
        }
        syncing = false;

        // 长按快捷方式的状态灯与界面同步
        ToggleShortcuts.markDirty(this);

        // 校准状态变化后无需重挂点击：监听常驻，窗口期由回调内部自行屏蔽
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
            if (swDevOptsPref != null) {
                swDevOptsPref.setEnabled(true);
            }
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
            if (swDevOptsPref != null) {
                swDevOptsPref.setEnabled(false);
            }
            // 联动分类下所有项一并禁用（无授权则无法写入调试开关，联动也失去意义）
            setLinkageEnabled(false);
        }
        // 实时动态项的可用性只跟系统版本有关，不受授权模式影响
        updateLiveUpdateState();
    }

    /** 联动分类下所有项（开关 + 动态填充的默认 / 应用 / 管理行）一并启用或禁用 */
    private void setLinkageEnabled(boolean enabled) {
        PreferenceCategory cat = pref("catLinkage");
        if (cat == null) {
            return;
        }
        for (int i = 0; i < cat.getPreferenceCount(); i++) {
            cat.getPreference(i).setEnabled(enabled);
        }
    }

    /**
     * 按当前快捷打开配置动态重建「快捷打开」分类下的列表：
     *  1) 默认快捷应用行（图标在右侧）；2) 每个快捷应用一行（点击=快捷打开 / 长按=创建快捷方式）；
     *  3) 管理快捷应用行。
     *  每次配置变化（增删 app、改默认、授权状态变化）都重建，保证 UI 与配置一致。
     */
    private void rebuildLinkageList() {
        PreferenceCategory cat = pref("catLinkage");
        if (cat == null) {
            return;
        }
        List<String> apps = LinkagePrefs.getApps(this);
        // 已卸载的快捷应用自动清掉：图标/标签都取不到，留着只会在每次跳转时失败。
        // 每次回到前台都会走到这里（onResume），故卸载后无需手动整理；
        // 停用的应用不算（可随时恢复），只在跳转失败时提示。
        List<String> alive = new ArrayList<>();
        for (String p : apps) {
            if (!isAppUninstalled(this, p)) {
                alive.add(p);
            }
        }
        if (alive.size() != apps.size()) {
            LinkagePrefs.setApps(this, alive);
            apps = alive;
            ToggleShortcuts.markDirty(this); // 默认目标可能随之清掉，动态快捷方式同步
            Toast.makeText(this, R.string.linkage_app_uninstalled, Toast.LENGTH_LONG).show();
        }
        String def = LinkagePrefs.getDefault(this);
        // 只有一个快捷应用时自动选它为默认（兼容旧数据：早先版本不会自动设默认）
        if (apps.size() == 1 && def.isEmpty()) {
            def = apps.get(0);
            LinkagePrefs.setDefault(this, def);
        }
        // 内容指纹：配置与授权状态都没变就不重建。
        // 重建要移除/新建所有 Preference，并在主线程同步 getApplicationIcon（跨进程 + 解码），
        // 而 onResume 每次都会走到这里 —— 每次回前台都为一份没变的列表重做一遍纯属浪费。
        String sig = privilegedAvailable + "|" + def + "|" + android.text.TextUtils.join(",", apps);
        if (sig.equals(lastLinkageSignature)) {
            return;
        }
        lastLinkageSignature = sig;

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

        // 1) 默认快捷应用行：开启快捷打开后要打开的目标；应用图标放在右侧
        // （androidx 行点击/长按原生触发，直接挂监听，无需旧 ListView 行接线）
        Preference defPref = new Preference(this);
        defPref.setKey(LINKAGE_KEY_DEFAULT);
        defPref.setTitle(R.string.linkage_default_title);
        defPref.setLayoutResource(R.layout.pref_row_icon_right);
        // 左侧不保留图标空位（图标在行尾；未设默认时右侧也不留空框）
        defPref.setIconSpaceReserved(false);
        defPref.setOnPreferenceClickListener(p -> {
            openDefaultPicker();
            return true;
        });
        CharSequence defLabel = def.isEmpty() ? getString(R.string.linkage_default_none) : getApplicationLabel(def);
        defPref.setSummary(defLabel);
        defPref.setIcon(def.isEmpty() ? null : loadAppDrawable(def));
        cat.addPreference(defPref);

        // 2) 每个快捷应用一行：整行点击=快捷打开；长按=创建桌面快捷方式
        for (String pkg : apps) {
            LongClickPreference p = new LongClickPreference(this);
            p.setKey(LINKAGE_APP_KEY_PREFIX + pkg);
            p.setLayoutResource(R.layout.pref_row);
            CharSequence label = getApplicationLabel(pkg);
            // 标题只显示应用名；它是否为「默认快捷应用」由上方「默认快捷应用」行统一体现，这里不再重复标注
            p.setTitle(label == null ? pkg : label);
            // 摘要改为操作提示，不再显示包名
            p.setSummary(R.string.quick_hint);
            p.setIcon(loadAppDrawable(pkg));
            p.setOnPreferenceClickListener(pv -> {
                if (!privilegedAvailable) {
                    Toast.makeText(this, R.string.auth_need_grant, Toast.LENGTH_SHORT).show();
                    return true;
                }
                runLinkage(pkg, false);
                return true;
            });
            p.setOnRowLongClickListener(() -> {
                if (!privilegedAvailable) {
                    Toast.makeText(this, R.string.auth_need_grant, Toast.LENGTH_SHORT).show();
                    return true;
                }
                createDesktopShortcutFor(pkg);
                return true;
            });
            cat.addPreference(p);
        }

        // 3) 管理快捷应用行（打开多选弹窗，一次性增删多个 app）
        Preference manage = new Preference(this);
        manage.setKey(LINKAGE_KEY_MANAGE);
        manage.setTitle(R.string.linkage_manage_title);
        manage.setLayoutResource(R.layout.pref_row);
        // 无图标行：不保留图标空位，与其它 pref_row 行左缘对齐（androidx 默认会保留约 50dp）
        manage.setIconSpaceReserved(false);
        manage.setSummary(apps.isEmpty()
                ? getString(R.string.linkage_apps_empty)
                : getString(R.string.linkage_manage_sum));
        manage.setOnPreferenceClickListener(p -> {
            openAppPicker();
            return true;
        });
        cat.addPreference(manage);

        // 联动项跟随授权可用性
        setLinkageEnabled(privilegedAvailable);
    }

    /** 打开联动应用选择器，并在选中后写回配置、刷新行与桌面快捷方式 */
    private void openAppPicker() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastPickerOpenAt > 0 && now - lastPickerOpenAt < 500) {
            return; // 防抖：避免 ListView 行点击与 preference click 同时触发，弹出两个选择器
        }
        lastPickerOpenAt = now;
        // 多选：预勾已选应用，确认后整组写回（勾上=新增，取消=移除）
        List<String> current = LinkagePrefs.getApps(this);
        AppListDialog.showMulti(this, current, chosen -> {
            LinkagePrefs.setApps(this, chosen);
            rebuildLinkageList();
            // 选完立刻刷新动态快捷方式（「启动联动应用」现取默认 app）
            ToggleShortcuts.markDirty(this);
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
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.linkage_default_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> pick[0] = which)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (pick[0] < 0) {
                        return;
                    }
                    LinkagePrefs.setDefault(this, apps.get(pick[0]));
                    rebuildLinkageList();
                    ToggleShortcuts.markDirty(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    /** 开启「快捷打开」后弹窗：告知如何绕过自动跳转、进入设置页（均为现有入口，不新增功能） */
    private void showLinkageHintDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.linkage_hint_title)
                .setMessage(R.string.linkage_hint_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private CharSequence getApplicationLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 状态圆点图标（实心圆，随状态变色）；显示尺寸由 pref_row_state 的 20dp 图标框决定，约等于一行文字高。
     *  按 colorRes 缓存复用：refresh 每次都会取多个同色圆点，避免重复 new Drawable。 */
    private GradientDrawable stateIcon(int colorRes) {
        GradientDrawable d = stateIconCache.get(colorRes);
        if (d == null) {
            d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(getResources().getColor(colorRes, getTheme()));
            d.setSize(40, 40);
            stateIconCache.put(colorRes, d);
        }
        return d;
    }

    /** 启动目标应用；返回是否成功拉起（已卸载 / 已停用 / 无启动入口都会失败） */
    private boolean launchApp(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "launchApp failed: " + pkg, t);
            return false;
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
        // 关于行可能已在后台自动检查后更新（可更新提示），回到前台即时同步
        refreshAboutSummary();
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
     * 只监听 DebugController 管理的这三个键的 URI，不监听整个 Settings.Global.CONTENT_URI：
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

    /**
     * 「测试灵动焦点通知」行的显隐（与「关闭时显示通知」开关联动）：
     *  - 低于 Android 16 本功能完全用不了 → 摘掉；
     *  - 「关闭时显示通知」关闭 → 摘掉：只有打开开关才显示测试入口；
     *  其余情况挂回「通知」分类末尾并保持可点。
     * 框架 Preference 没有 setVisible，只能 remove/add。
     */
    private void updateLiveUpdateState() {
        if (liveUpdatePref == null) {
            return;
        }
        boolean want = Build.VERSION.SDK_INT >= 36
                && swNotifyPref != null && swNotifyPref.isChecked();
        if (want == liveUpdateShown) {
            return;
        }
        PreferenceCategory cat = pref("catNotify");
        if (cat == null) {
            return;
        }
        if (want) {
            cat.addPreference(liveUpdatePref);
            liveUpdatePref.setEnabled(true);
            liveUpdatePref.setSummary(R.string.live_update_sum);
        } else {
            cat.removePreference(liveUpdatePref);
        }
        liveUpdateShown = want;
    }

    // —— 双模式授权 ——

    /** 弹出授权模式选择对话框（root / Shizuku），选中后持久化并立即重测 */
    private void chooseAuthMode() {
        final AuthManager.Mode cur = AuthManager.getMode(this);
        final int checked = (cur == AuthManager.Mode.SHIZUKU) ? 1 : 0;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auth_choose_title)
                .setSingleChoiceItems(
                        new CharSequence[]{getString(R.string.auth_mode_root),
                                getString(R.string.auth_mode_shizuku)},
                        checked,
                        (d, which) -> {
                            AuthManager.Mode mode = (which == 1)
                                    ? AuthManager.Mode.SHIZUKU : AuthManager.Mode.ROOT;
                            AuthManager.setMode(MainActivity.this, mode);
                            // 执行通道变了（su ↔ Shizuku），旧状态缓存作废并立即用新通道重新校准：
                            // 不作废的话，切换后 UI 仍按旧缓存渲染，且校准可能被节流挡住导致卡在旧值。
                            DebugController.invalidateAndRefresh(MainActivity.this);
                            // 切换模式后授权状态必变，重置缓存、强制重新检测
                            authCheckedThisSession = false;
                            d.dismiss();
                            updateAuthStatusUI();
                            // 切到新模式下立刻双检测：root 模式 fork su；
                            // Shizuku 模式先主动请求授权（force，用户是刚明确切过来的）
                            maybeRequestShizukuPermission(true);
                            // force：用户刚切了模式，旧探测结论作废，必须立刻按新通道重测
                            performAuthCheck(true);
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
        // 官方权限三分支里的第三支（Shizuku-API README 示例用 shouldShowRequestPermissionRationale
        // 区分「还能请求」与「用户选过拒绝且不再询问」）。
        //
        // ⚠️ 这个标志的语义两侧并不一致：官方 README 示例把它注成
        // "Users choose 'Deny and don't ask again'"，而 Android 原生同名 API 的语义是
        // 「应该向用户解释、但仍可再次请求」。服务端到底按哪种填无法从客户端确认，
        // 因此这里**不据此阻断请求** —— 阻断的风险是：若实际是原生语义，
        // 用户只是拒绝过一次，就会被永久挡在授权弹窗之外，点「重试」毫无反应。
        // 折中做法：请求照发（永久拒绝时它自然不会弹窗），另外给用户一条自救指引，
        // 且只在用户主动动作（force）时提示，避免每次进页面都弹 Toast 扰民。
        boolean deniedBefore = false;
        try {
            deniedBefore = Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable t) {
            // 老版本 Shizuku 没有这个查询：按「可再次请求」处理，退回原逻辑
            android.util.Log.w(TAG, "shouldShowRequestPermissionRationale unavailable", t);
        }
        if (deniedBefore) {
            android.util.Log.i(TAG, "Shizuku permission rationale set, showing manual guide");
            if (force) {
                Toast.makeText(this, R.string.toast_shizuku_denied_forever, Toast.LENGTH_LONG).show();
            }
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
        // force=true：重试是明确的用户动作，不能被「本周期已确认」或 30s 节流挡住
        // （旧实现走无参版本，一旦 authCheckedThisSession 还是 true 就会静默 return，点了没反应）
        performAuthCheck(true);
    }

    /**
     * 底部未授权提示（原生 Snackbar，带「重试」）。
     *
     * 文案严格绑定当前授权模式 —— 底部提示与「授权模式」那一行是同一件事的两种呈现，
     * 不能出现「选了 root 模式却提示 Shizuku 已授权」这种错位引导（旧实现的 bug：
     * 它按双检测结果挑「哪个后端可用」来提示，跟用户选的模式无关）。
     * Shizuku 模式下再细分「服务没启动」与「已启动但没授权」，否则只说"未授权"用户无从下手；
     * root 模式下细分「根本没有 su」与「有 su 但未授权」——前者换模式才有意义，
     * 后者才该去 Magisk 点允许。
     */
    private void showAuthNotice(AuthManager.Mode mode, boolean shizukuRunning,
                                RootUtil.RootStatus rootStatus) {
        final int textRes;
        if (mode == AuthManager.Mode.SHIZUKU) {
            textRes = shizukuRunning ? R.string.auth_toast_shizuku_no : R.string.auth_need_start;
        } else if (rootStatus == RootUtil.RootStatus.NO_SU) {
            textRes = R.string.toast_no_su;
        } else {
            textRes = R.string.auth_toast_root_no;
        }
        runOnUiThread(() -> showAuthSnackbar(getString(textRes), true));
    }

    /** 真正的 Snackbar：M3 默认反色样式（浅色模式深底 / 深色模式浅底），未授权文字标红作警告 */
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
            TextView tv = v.findViewById(com.google.android.material.R.id.snackbar_text);
            if (tv != null && unauthorized) {
                // 未授权红字警告。条底恒为反色 surface（浅色模式=深条 / 深色模式=浅条），
                // 必须用按模式反转的 error 档 errorInverse（深条→浅红 / 浅条→深红）；
                // 若用当前模式的 error 档，明暗正好与条底同侧、对比度不足。
                // 已授权提示不改文字色，走 M3 默认 colorOnSurfaceInverse（两种模式都正确）。
                tv.setTextColor(getResources().getColor(R.color.errorInverse, getTheme()));
            }
            if (unauthorized) {
                // 重试按钮与红字同色系：同样必须用反色档（硬编码白色在深色模式浅底条上看不清）
                sb.setActionTextColor(getResources().getColor(R.color.errorInverse, getTheme()));
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
            return ContextCompat.getDrawable(this, R.mipmap.ic_launcher);
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
            return; // 行点击传入的包名恒非空（缺包名场景已废弃），此守卫只防编程错误
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

        // Android 8+（minSdk=27 必满足）优先用 LauncherApps 钉选（现代 launcher 才支持），失败再回退老广播
        if (requestPinShortcut(label, launch, icon)) {
            // 真实结果由回调给（PinShortcutReceiver）：钉上才提示「已创建」，
            // 用户取消 / 被 ROM 吞掉则超时兜底提示「未创建」。这里不再发「已请求」这类无结果提示。
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
            // 两条路径都拿不到「是否真的落到桌面」的真实结果，只能提示已发起
            Toast.makeText(this, R.string.create_shortcut_done, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // 旧广播路径基本不会抛异常；真抛了只记日志（创建失败与否无从得知）
            android.util.Log.w(TAG, "INSTALL_SHORTCUT broadcast failed", t);
        }
    }

    /** Android 8+：用 ShortcutManager.requestPinShortcut 把联动应用钉选为桌面图标
     *  （区别于 shortcuts.xml 里的长按菜单「Shortcut」，这里生成的是真实桌面图标），
     *  返回是否成功发出请求。 */
    private boolean requestPinShortcut(CharSequence label, Intent launch, Bitmap icon) {
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
            // 结果回调：按官方契约，只有真正钉上时才会收到（PendingIntent 按官方样例用
            // FLAG_IMMUTABLE）；取消/被拒不回调，故 arm() 挂超时兜底提示。
            Intent cb = new Intent(this, PinShortcutReceiver.class);
            PendingIntent result = PendingIntent.getBroadcast(this, 0, cb,
                    PendingIntent.FLAG_IMMUTABLE);
            boolean accepted = sm.requestPinShortcut(b.build(), result.getIntentSender());
            if (accepted) {
                PinShortcutReceiver.arm(this);
            }
            return accepted;
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
            base = ContextCompat.getDrawable(this, R.mipmap.ic_launcher);
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

        Drawable mine = ContextCompat.getDrawable(this, R.drawable.ic_shortcut_badge);
        mine.setBounds(left, top, left + badge, top + badge);
        mine.draw(c);

        return out;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            DebugController.removeStateListener(stateListener);
        } catch (Throwable ignored) {
        }
        // Shizuku 监听成对移除：其监听表是静态的，桌面快捷方式（CLEAR_TASK）会反复重建本 Activity，
        // 不移除会累积监听器、泄漏旧实例并重复回调。
        try {
            if (shizukuBinderListener != null) {
                Shizuku.removeBinderReceivedListener(shizukuBinderListener);
                shizukuBinderListener = null;
            }
            if (shizukuDeadListener != null) {
                Shizuku.removeBinderDeadListener(shizukuDeadListener);
                shizukuDeadListener = null;
            }
            if (shizukuPermListener != null) {
                Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
                shizukuPermListener = null;
            }
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
        // 测试结果与授权引导合并进同一个弹窗（弹窗+toast 同屏双报太吵，文案复用现有两串）
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.live_update_test_sent_plain) + "\n\n"
                        + getString(R.string.live_update_grant))
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
    }

    /** 测试通知用的固定渠道：只创建一次（幂等），不每次点测试都新建一条 */
    private static final String LIVE_UPDATE_CHANNEL_ID = "live_update_test";
    private static boolean liveChannelEnsured = false;

    /** 发一条测试用的实时动态通知（与常驻通知同一套提升方式） */
    private void sendLiveUpdateTest() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (!liveChannelEnsured) {
            NotificationChannel ch = new NotificationChannel(
                    LIVE_UPDATE_CHANNEL_ID, getString(R.string.live_update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            liveChannelEnsured = true;
        }
        Notification.Builder b = new Notification.Builder(this, LIVE_UPDATE_CHANNEL_ID);
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
