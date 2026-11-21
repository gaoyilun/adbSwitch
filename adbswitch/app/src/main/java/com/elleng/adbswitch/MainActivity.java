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
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import com.elleng.adbswitch.update.UpdateDialog;
import com.elleng.adbswitch.util.RootUtil;

import rikka.shizuku.Shizuku;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 主界面：Material 3 架构（AppCompatActivity + MaterialToolbar 顶栏
 * + PreferenceFragmentCompat 设置列表 + MaterialSwitch/MaterialAlertDialog），
 * 统一沿用 M3 组件样式（SwitchPreferenceCompat / Preference / PreferenceCategory）。
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_SETTINGS = "extra_settings";
    /** 静态 shortcut「打开主页」的 action：见到它就跳过联动自动跳转 */
    private static final String ACTION_OPEN_HOME = "com.elleng.adbswitch.action.OPEN_HOME";
    /** 控制中心磁贴长按的系统 action：由 manifest 的 QS_TILE_PREFERENCES intent-filter 接住 */
    private static final String ACTION_QS_TILE_PREFERENCES =
            "android.service.quicksettings.action.QS_TILE_PREFERENCES";
    /** 桌面快捷方式「快捷打开」：先打开本 App，关掉调试后启动目标应用 */
    static final String ACTION_DESKTOP_LINKAGE = "com.elleng.adbswitch.action.DESKTOP_LINKAGE";
    private static final String TAG = "AdbSwitch";
    static final String PREFS = "cfg";
    /**
     * 需要监听外部改动的 Settings 键（与 DebugController 写入的是同一组，三个都不能少）。
     * 少一个就会出现「那一行不随外部改动刷新」——例如开发者选项被别处恢复后，
     * UI 还停在旧值，与相邻两行显示不一致。invalidateAndRefresh 会一次重读三个键，
     * 多监听一个键的代价只是多一次 URI 回调，不会多 fork 通道。
     */
    private static final String[] OBSERVED_KEYS = {DebugController.KEY_USB, DebugController.KEY_WIRELESS,
            DebugController.KEY_DEV};
    private static final String PREF_LINKAGE = "linkage_enabled";
    /** 桌面快捷方式意图里携带「要联动哪个 app」的包名（每个 app 的桌面图标各不相同） */
    static final String EXTRA_LINKAGE_PKG = "extra_linkage_pkg";
    /** 「临时关闭时弹通知」开关 */
    private static final String PREF_NOTIFY = "notify_on_close";
    /** 「打开调试前先杀掉联动应用」开关（DebugController 读取同一份 cfg） */
    static final String PREF_KILL_BEFORE_OPEN = "kill_before_open";
    /** 用户是否手动关闭过「自动关闭快捷应用」（仅在具备 kill 权限时由用户手动关闭才记，
     *  用于「有 kill 权限时默认开、仅用户手动关才记住」的体验，见 updateKillPrefState） */
    private static final String PREF_KILL_USER_DISABLED = "kill_before_open_user_disabled";
    /** 动态 shortcut：启动快捷应用 */
    private static final String SC_ID_LAUNCH_APP = "launch_linkage_app";

    private MasterSwitchPreference swMasterPref; // 主开关：自定义 Preference，绑定时即同步 Switch 状态
    private Boolean masterManualExpanded; // 用户手动覆盖的展开态；null=跟随开关状态
    // 两个子开关当前是否已加入 category。
    // 必须初始化为 true：prefs.xml 里它们本来就写在 catDebug 内，
    // 初始化成 false 会导致首次刷新时「关闭态 want=false == 当前 false」而跳过 remove，折叠失效。
    private boolean subSwitchesShown = true;
    private NavSwitchPreference swUsbPref;
    private SwitchPreferenceCompat swLinkagePref;
    private SwitchPreferenceCompat swNotifyPref;
    private NavSwitchPreference swWirelessPref;
    /**
     * 无线调试行是否可用（adb_wifi_enabled 键自 API 30 才存在）。
     * false 时该行已从 catDebug 摘掉、折叠逻辑也不再把它加回来，主开关只管理 USB。
     */
    private boolean wirelessSupported = true;
    /** 开发者选项入口开关：独立开关，不与主开关联动、不参与折叠（始终挂在 catDebug 里） */
    private NavSwitchPreference swDevOptsPref;
    /** 第三方无障碍服务开关：是否跟随主开关由「管理开关项」配置（默认不跟随） */
    private NavSwitchPreference swA11yPref;
    /** 「管理开关项」入口行（配置每项是否显示 / 是否跟随主开关） */
    private Preference manageItemsPref;
    SwitchPreferenceCompat swKillPref;
    /** 「测试灵动焦点通知」行当前是否挂在分类里（显隐联动见 updateLiveUpdateState） */
    private boolean liveUpdateShown = true;
    private Preference liveUpdatePref;
    /** 「关于」行：点击弹窗展示版本/构建时间/授权模式（原「构建时间」行） */
    private Preference buildInfoPref;
    Preference authPref;              // 授权模式（root / Shizuku）与授权状态合并项

    private boolean syncing = false;          // 防止 setChecked 触发回环
    /**
     * 当前授权模式是否可用（即能否真实写入调试开关）。
     * 不再无条件乐观假定 true：本进程若尚未确认过授权，先按「不可用」渲染——否则重建 /
     * 首帧会在探测结果回来前闪现假「已授权」（root 探测最坏 10s），期间用户可能误点写入。
     * 只有确认成功才被 AuthCoordinator 标记，故重建后复用该结论既不自欺也不闪假未授权。
     */
    boolean privilegedAvailable = AuthCoordinator.hasSessionChecked();
    /** 用户是否手动关闭过「自动关闭快捷应用」（见 updateKillPrefState / PREF_KILL_USER_DISABLED） */
    boolean killUserDisabled = false;
    /** 是否刚从后台返回前台（用于 onResume 时强制重测授权，识别 root/Shizuku 授权变化） */
    private boolean cameFromBackground = false;
    boolean prefsLoaded = false;      // 设置界面是否已构建（onNewIntent 复用实例时避免重复加载）
    /** 在线更新检查与「关于」行摘要（自动/手动检查、节流、更新弹窗入口） */
    private final UpdateCheckDelegate updateCheck = new UpdateCheckDelegate(this);
    /** 「快捷打开」列表 / 选择器 / 桌面快捷方式 / 图标缓存 / 卸载清理 */
    private final LinkageController linkage = new LinkageController(this);
    /** 授权 UI：探测结论落地、模式选择、WSS 引导、Shizuku 授权请求与底部提示 */
    private final AuthUiController auth = new AuthUiController(this);
    /** §4.7 写入在途冻结：写任务排队期间把主开关置灰，给出明确反馈（不再停在旧值无所适从） */
    private final Runnable busyListener = () -> runOnUiThread(this::applyBusyState);
    /** 「开了快捷打开但没设默认」的提示每进程只弹一次（其余联动跳转状态见 LinkageLauncher） */
    private static boolean sNoDefaultToasted = false;
    private long lastLiveOpenAt = 0;          // 同上，实时动态弹窗防抖
    private boolean settingsObserverRegistered = false; // Settings 监听是否已注册（防重复注册/泄漏）
    /** Wi-Fi 连接变化监听是否已注册：无线行「未激活」判据是当前网络，必须在 Wi-Fi 断开/连回时即时刷新 */
    private boolean wifiCallbackRegistered = false;
    /** 默认网络变化回调：Wi-Fi 连上/断开时重绘（只刷 UI，不动任何 Settings 键） */
    private final ConnectivityManager.NetworkCallback wifiCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    refreshWirelessFromWifi();
                }

                @Override
                public void onLost(Network network) {
                    refreshWirelessFromWifi();
                }
            };

    /** 网络变化时只重绘无线行摘要/图标，不触发 Settings 重读（key 未变，无谓开销） */
    private void refreshWirelessFromWifi() {
        if (swWirelessPref == null || !prefsLoaded || isDestroyed()) {
            return;
        }
        mainHandler.post(() -> refresh(DebugController.read(this)));
    }
    /** 主线程 Handler：去抖与所有 post 都复用它，否则每次 new 一个 Handler 都拿不到上次的 callback，去抖等于失效 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 状态变化 listener（替代原进程内广播）：任何写入/校准后刷新界面 */
    private final DebugController.StateListener stateListener = s -> {
        if (isDestroyed()) {
            return;
        }
        // 开启调试时若没能杀联动应用，给一次性提示（不再静默失效）。
        // 两类原因分开提示：授权被拒 / 被撤销 → 去补授权；根本没有通道 → 只能换模式或装 root。
        if (DebugController.consumeKillDenied()) {
            Toast.makeText(MainActivity.this, R.string.kill_denied_toast, Toast.LENGTH_LONG).show();
        } else if (DebugController.consumeKillSkipped()) {
            Toast.makeText(MainActivity.this, R.string.kill_unavailable_toast, Toast.LENGTH_LONG).show();
        }
        refresh(s);
    };

    /**
     * 监听 Settings.Global 里两个调试键的外部变化。
     *
     * 为什么必须监听：App 内部状态推送（addStateListener）只覆盖本 App 自己写出的变化
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
        // 不作废就永远刷不出「外部改了设置」的新状态。校准完成后由状态 listener 推送新状态，UI 经 addStateListener 回调刷新。
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
        // §4.7：注册「写入在途」监听，写任务排队期间冻结主开关；Activity 销毁时需移除（静态表会持有引用）。
        DebugController.addBusyListener(busyListener);
    }

    /** §4.7：写任务在途（仅主开关自身写入）时冻结主开关，结束后解冻；解冻会复用最近一次已渲染的 known 态 */
    private void applyBusyState() {
        if (swMasterPref != null) {
            swMasterPref.setBusyFreeze(DebugController.isMasterBusy());
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
                        AuthCoordinator.resetSession();
                        if (AuthManager.getMode(MainActivity.this) == AuthManager.Mode.SHIZUKU) {
                            privilegedAvailable = false;
                            applyRootState();
                            auth.updateAuthStatusUI();
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
                    auth.maybeRequestShizukuPermission(false);
                    // 无论是否已授权都重测一次，刷新授权状态行与控件可用性
                    auth.check();
                }
            };
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
            shizukuPermListener = new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == AuthManager.SHIZUKU_PERM_REQUEST) {
                        android.util.Log.i(TAG, "Shizuku permission result=" + grantResult);
                        // force：用户刚刚在弹窗里做了选择，必须立刻重测，不能被节流挡住
                        auth.check(true);
                        // WSS 引导里「用 Shizuku 代跑」：只有拿到授权才接着跑 pm grant，
                        // 否则清掉挂起标记（用户拒绝 → 就此结束，不要残留状态下次误触发）
                        boolean granted = grantResult
                                == android.content.pm.PackageManager.PERMISSION_GRANTED;
                        auth.onShizukuPermissionResult(granted);
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

        // 动态 shortcut「启动快捷应用」：与桌面快捷方式（ACTION_DESKTOP_LINKAGE）同语义——
        // 先关调试、再打开目标应用，故不依赖「快捷打开」总开关，开没开都能用。
        // 1.0.4 曾把这条简化成「只启动应用、不关调试」，导致用户从长按菜单拖到桌面后
        // 使用时调试没被关掉（用户反馈：只会跳转、不触发关闭总开关）。改为统一走 runLinkage。
        if (SC_ID_LAUNCH_APP.equals(in.getAction())) {
            String pkg = LinkagePrefs.getDefault(this);
            if (pkg.isEmpty()) {
                Toast.makeText(this, R.string.linkage_no_default, Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
            runLinkage(pkg, false);
            return true;
        }

        // 打开主页的三种入口：通知栏「应用设置」传来的 EXTRA_SETTINGS(boolean)、
        // 「打开主页」shortcut 的自定义 action、控制中心磁贴长按的 QS_TILE_PREFERENCES。
        // 磁贴长按必须也算进来：否则开了「快捷打开」且设了默认应用时长按会被当成桌面冷启动，
        // 直接弹到第三方应用，而不是用户期望的本应用主页。
        // 注意不能用 <extra> 传标记：shortcuts 解析会把 "1" 存成 Integer，
        // getStringExtra() 内部吞掉 ClassCastException 返回 null，判定永远失效。
        boolean openSettings = ACTION_OPEN_HOME.equals(in.getAction())
                || ACTION_QS_TILE_PREFERENCES.equals(in.getAction())
                || in.getBooleanExtra(EXTRA_SETTINGS, false);

        // 桌面快捷方式「创建桌面快捷方式」：先打开本 App，关掉调试后联动启动目标应用。
        // 这是显式触发（用户点了快捷方式），故不走 PREF_LINKAGE 开关、也不受
        // LinkageLauncher 守卫限制——每次点都应重新关调试并跳目标应用。
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
                // 「启动时已自动跳转」，反复打开也不该反复弹）。状态机见 LinkageLauncher。
                if (LinkageLauncher.hasLaunched()) {
                    if (LinkageLauncher.consumeAutoJumpHint()) {
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

        auth.check();
        refresh(DebugController.read(this));
        applyRootState();
        linkage.rebuildList();
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
    void onPreferencesCreated() {
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
        // 冷启动首帧的 onResume 会因 prefsLoaded=false 提前 return，那次观察注册被跳过；
        // 若不在这里补一次，首次前台会话全程不监听外部对调试键/无障碍列表的改动，
        // 界面会停在旧值（必须切一次后台再回来才补上）。observeSettings 内部幂等，
        // 与 onResume 末尾的调用重复也安全。
        observeSettings(true);
        // 与 observeSettings 同理：冷启动首帧 onResume 因 prefsLoaded=false 提前 return，
        // 那次网络监听注册被跳过。不在这里补一次，首次前台会话 Wi-Fi 断开/连回都不会重绘无线行。
        // observeNetwork 内部幂等，与 onResume 末尾调用重复也安全。
        observeNetwork(true);
        initPrefs();
        updateCheck.checkOnStart();
        auth.check();
        refresh(DebugController.read(this));
        // 启动时先把上次会话的「恢复待办」复核一遍再展示：旧结论可能早已自愈，
        // 直接摆到界面上就是「明明开着却短暂提示需手动开启」
        if (DebugController.hasPersistedA11yPending(this)) {
            DebugController.recheckA11yRestore(this, () -> refresh(DebugController.read(this)));
        }
        applyRootState();
        linkage.rebuildList();
        updateLiveUpdateState();
        updateCheck.refreshAboutSummary();
    }

    /** 按 key 从 fragment 的 preference 树里取行；树未就绪时返回 null（调用方自行守卫）。
     *  字段为空时回退到 FragmentManager 查找：重建路径的系统恢复回调早于字段赋值。 */
    <T extends Preference> T pref(String key) {
        SettingsFragment f = findSettingsFragment();
        return f == null ? null : f.findPreference(key);
    }

    /**
     * 列表底部为底部浮层（持久提示条）留白：由 {@code AuthUiController} 在 Snackbar 显示后
     * 传入它的顶边窗口坐标，否则最后两行「授权模式 / 关于」永远被条压住。
     */
    void reserveListBottomForOverlay(int overlayTopInWindow, int gapPx) {
        SettingsFragment f = findSettingsFragment();
        if (f != null) {
            f.reserveBottomForOverlay(overlayTopInWindow, gapPx);
        }
    }

    /** 浮层消失后撤销留白 */
    void clearListBottomOverlay() {
        SettingsFragment f = findSettingsFragment();
        if (f != null) {
            f.clearBottomOverlay();
        }
    }

    private SettingsFragment findSettingsFragment() {
        SettingsFragment f = settingsFragment;
        if (f == null) {
            f = (SettingsFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.settings_container);
        }
        return f;
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
        swA11yPref = pref("swA11y");
        manageItemsPref = pref("manageItems");
        // 笔图标用应用强调色（M3 动态 colorPrimary，随壁纸派生）：framework Preference 默认
        // 会用 colorControlNormal 给 XML 里的 vector 染色（次级灰），与开关开启态色系不一致，
        // 故在代码里显式 setTint。只作用于本行，不影响调试三行的状态圆点（它们共用 pref_row_state）。
        if (manageItemsPref != null) {
            try {
                android.util.TypedValue accent = new android.util.TypedValue();
                // resolveAttribute 返回 false 时 accent.data 无意义（可能为 0=透明），
                // 必须判返回值，否则主题异常场景下笔图标会变成看不见的透明图标。
                if (getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, accent, true)) {
                    android.graphics.drawable.Drawable editIcon =
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_edit);
                    if (editIcon != null) {
                        editIcon = androidx.core.graphics.drawable.DrawableCompat.wrap(editIcon).mutate();
                        androidx.core.graphics.drawable.DrawableCompat.setTint(editIcon, accent.data);
                        manageItemsPref.setIcon(editIcon);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "tint edit icon failed", t);
                // 着色失败保留 XML 默认图标，不影响功能
            }
        }
        swKillPref = pref("swKillBeforeOpen");
        // 分类内按 android:order 排序，而不是按添加顺序：
        // 折叠再展开时 usb / wireless 是 addPreference 回来的，按添加顺序会排到末尾
        //（跑到开发者选项行之后）。固定 order 才能保证任何时候顺序都是「主 > USB > 无线 > 开发者选项」。
        PreferenceCategory catDebug = pref("catDebug");
        if (catDebug != null) {
            catDebug.setOrderingAsAdded(false);
        }
        // 无线调试设置键自 Android 11（API 30）才引入：低版本没有该键，读会抛异常、写也是无效键。
        // 直接摘掉无线行，主开关此时只管理 USB（DebugController 侧同步把无线恒按「关」且不写该键）。
        if (Build.VERSION.SDK_INT < 30) {
            wirelessSupported = false;
            if (catDebug != null && swWirelessPref != null) {
                catDebug.removePreference(swWirelessPref);
            }
        }
        liveUpdatePref = pref("liveUpdate");
        buildInfoPref = pref("buildInfo");
        updateCheck.bindAboutPref(buildInfoPref);
        // 「关于」行：整行点击弹窗（旧 framework 实现行不可点、需 ListView 行接线；
        // androidx 行点击原生触发 OnPreferenceClickListener，直接挂监听即可）
        if (buildInfoPref != null) {
            buildInfoPref.setOnPreferenceClickListener(p -> {
                AboutDialog.show(this, privilegedAvailable, updateCheck::manualCheck);
                return true;
            });
        }
        // 关于行默认显示当前版本号（有已知可更新版本时改成「版本 可更新最新版」）
        updateCheck.refreshAboutSummary();
        // 双模式授权：模式选择 + 授权状态已合并为同一项。点击整行 = 打开模式选择对话框
        authPref = pref("auth");
        if (authPref != null) {
            authPref.setOnPreferenceClickListener(p -> {
                // 点击整行一律打开模式选择（免 root / root / Shizuku 三通道），
                // 让用户**随时都能切换通道**——首次授权也允许直接切到 root / Shizuku，
                // 而不是被锁死在 WSS 引导里。选 WSS 且未授权时，由 chooseAuthMode 内部
                // 再弹一次性授权引导（见其 SECURE_SETTINGS 分支），默认弹窗授权不受影响。
                auth.chooseMode();
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
        auth.updateAuthStatusUI();
        // 底部「关于」行：点击弹窗展示版本/构建时间等（构建时间不再直接占用行摘要）

        // 主开关：一键开/关。关闭时记住两个子开关的组合，打开时原样还原。
        // 行内 Switch 由 MasterSwitchPreference 绑定时接线；整行点击（androidx 原生触发）在此接线。
        // 启动窗口期（真值尚未读回）屏蔽操作：点击不执行，但给一次性轻提示（见 P1.1）
        // null 守卫：与上方 setOnToggleListener / setOnExpandListener 同一防御口径——
        // 极端时序下 preference 树未就绪时跳过接线（宁可主开关整行点击不动也不闪退）。
        if (swMasterPref != null) {
            swMasterPref.setOnPreferenceClickListener(p -> {
                if (swallowIfUnconfirmed()) {
                    return true;
                }
                toggleMaster();
                return true;
            });
        }

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
        // 无障碍行：关 = 清第三方条目（写前存记忆）；开 = 还原记忆列表。
        // 「开」前预检有无记忆：无记忆时给明确提示（而非通用「写入未生效」），并吞掉拨动。
        if (swA11yPref != null) {
            swA11yPref.setOnPreferenceChangeListener((p, v) -> {
                if (syncing) {
                    return true;
                }
                if (swallowIfUnconfirmed()) {
                    return false;
                }
                final boolean want = (Boolean) v;
                if (want && !DebugController.hasA11ySave(this)) {
                    Toast.makeText(this, R.string.a11y_no_save, Toast.LENGTH_LONG).show();
                    return false;
                }
                DebugController.setA11y(this, want, (ok, s, reason) -> {
                    if (!ok) {
                        // 「系统隐藏了列表」不是失败，是需要用户确认（见 maybeAskA11yUnverified）
                        if (!maybeAskA11yUnverified(reason)) {
                            AuthCoordinator.resetSession();
                            auth.handleWriteFailure(reason);
                        }
                    } else if (s == null || s.isOn(SwitchItem.A11Y) != want) {
                        Toast.makeText(this, R.string.toast_write_not_effective,
                                Toast.LENGTH_SHORT).show();
                    }
                    refresh(s);
                });
                return true;
            });
        }

        // 调试三行右侧「›」箭头：点击跳系统对应设置项（开发者选项未开启时回退关于 + Toast，见 SettingsNav）。
        // 箭头点击与开关拨动互不干扰：箭头是独立 ImageButton，消费自身点击、不触发整行/开关切换。
        if (swUsbPref != null) {
            swUsbPref.setOnNavClickListener(() -> SettingsNav.navigate(this, SettingsNav.Target.USB));
        }
        if (swWirelessPref != null) {
            swWirelessPref.setOnNavClickListener(() -> SettingsNav.navigate(this, SettingsNav.Target.WIRELESS));
        }
        if (swDevOptsPref != null) {
            swDevOptsPref.setOnNavClickListener(() -> SettingsNav.navigate(this, SettingsNav.Target.DEV_OPTIONS));
        }
        if (swA11yPref != null) {
            swA11yPref.setOnNavClickListener(() -> SettingsNav.navigate(this, SettingsNav.Target.A11Y));
        }
        // 「管理开关项」：弹窗配置每项是否显示 / 是否跟随主开关
        if (manageItemsPref != null) {
            manageItemsPref.setOnPreferenceClickListener(p -> {
                ManageItemsDialog.show(this, () -> {
                    rebuildSwitchList();
                    refresh(DebugController.read(this));
                });
                return true;
            });
        }

        // 「打开调试前先杀掉联动应用」：真正的执行在 DebugController 里（所有打开调试的入口统一处理）
        if (swKillPref != null) {
            SharedPreferences ksp = getSharedPreferences(PREFS, MODE_PRIVATE);
            // 只认「用户显式关闭过」这一个信号（PREF_KILL_USER_DISABLED），默认 = 开。
            // 不从旧键 kill_before_open 反推「以前手动关过」：旧版本在无 kill 权限时会把这个键
            // 强制写成 false（置灰关掉），反推会把「被强制关」误判成用户意愿，导致升级后
            // 明明有 root/Shizuku 授权、开关却一直是关的（1.0.4 实测踩到）。
            killUserDisabled = ksp.getBoolean(PREF_KILL_USER_DISABLED, false);
            swKillPref.setOnPreferenceChangeListener((Preference p, Object v) -> {
                boolean on = (Boolean) v;
                // 仅在具备 kill 权限（开关可用）时记录用户的手动选择；无权限时开关被强制关闭、
                // 显示值由 updateKillPrefState 接管，不应把「被强制关」误记成用户意愿。
                if (swKillPref.isEnabled()) {
                    killUserDisabled = !on;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PREF_KILL_USER_DISABLED, killUserDisabled)
                            .putBoolean(PREF_KILL_BEFORE_OPEN, on).apply();
                }
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
                linkage.showHintDialog();
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
            // 立即同步：若当前主开关已是关闭态，开->弹通知，关->撤通知
            if (DebugController.masterAllOff(this, DebugController.read(this))) {
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

        // 按开关项配置应用各行显隐（管理开关项里改过 shown/follow 后也要重跑）
        rebuildSwitchList();

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
                    AuthCoordinator.resetSession();
                    auth.handleWriteFailure(reason);
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
                if (DebugController.masterAnyOn(this, s)) {
                    AdbToggleService.stop(MainActivity.this);
                } else {
                    AdbToggleService.syncCloseNotification(MainActivity.this);
                }
            } else {
                // 执行失败：授权大概率已失效（su 超时 / Shizuku 被撤销）。立即刷新授权状态，
                // 让主开关与联动项灰掉并显示「未授权」+「重试」，而不是停在旧的「已授权」假象；
                // 同时提示用户重试或切换授权模式。
                // 例外：「系统隐藏了无障碍列表」不是授权问题，走二次确认（全局键此时已写下去了）。
                if (maybeAskA11yUnverified(reason)) {
                    refresh(s);
                    return;
                }
                privilegedAvailable = false;
                AuthCoordinator.resetSession();
                applyRootState();
                auth.handleWriteFailure(reason);
            }
            refresh(s);
        });
    }

    /**
     * 折叠/展开「跟随主开关」的开关项：主开关开启时默认展开、关闭时默认折叠；
     * 用户点箭头可手动覆盖（但关闭态下强制保持折叠，展开没有意义）。
     * 不跟随主开关的显示项（如默认的开发者选项 / 无障碍）不受折叠影响、始终显示。
     */
    private void applyMasterExpanded(boolean masterOn) {
        boolean want = masterOn
                && (masterManualExpanded == null ? true : masterManualExpanded.booleanValue());
        if (want != subSwitchesShown) {
            subSwitchesShown = want;
            rebuildSwitchList();
        }
        // 箭头图标随展开态同步（androidx：状态存进 Preference，行绑定时也会自动摆）
        if (swMasterPref != null) {
            swMasterPref.setExpandedState(want);
        }
    }

    /** 点箭头：手动切换展开态 */
    private void toggleExpanded() {
        if (!DebugController.masterAnyOn(this, DebugController.read(this))) {
            return; // 关闭态强制保持折叠
        }
        masterManualExpanded = !subSwitchesShown;
        applyMasterExpanded(true);
    }

    /**
     * 按开关项配置 + 折叠态重建「调试开关」分类下各行的显隐与顺序：
     *  - 隐藏项（shown=false）= 功能未启用：整行移除（不显示、不写入、不跟随）；
     *  - 跟随主开关的显示项：折叠态（主开关关）时移除、展开态加回；
     *  - 不跟随的显示项：始终显示（如默认的开发者选项 / 无障碍）。
     * 顺序按「管理开关项」里拖出来的 SwitchConfig.getOrder() 映射到固定 order 槽位
     * （10/20/30/40，位于 manageItems/auth 行之前），分类 setOrderingAsAdded(false)。
     */
    private void rebuildSwitchList() {
        PreferenceCategory cat = pref("catDebug");
        if (cat == null) {
            return;
        }
        java.util.List<SwitchItem> order = SwitchConfig.getOrder(this);
        for (int i = 0; i < order.size(); i++) {
            SwitchItem item = order.get(i);
            Preference p = itemPref(item);
            if (p == null) {
                continue;
            }
            p.setOrder(10 + 10 * i);
            boolean visible = item.supported()
                    && SwitchConfig.isShown(this, item)
                    && (!SwitchConfig.followsMaster(this, item) || subSwitchesShown);
            boolean attached = cat.findPreference(p.getKey()) != null;
            if (visible && !attached) {
                cat.addPreference(p);
            } else if (!visible && attached) {
                cat.removePreference(p);
            }
        }
    }

    /** 开关项 → 主界面 Preference 行 */
    private Preference itemPref(SwitchItem item) {
        switch (item) {
            case USB:
                return swUsbPref;
            case WIRELESS:
                return swWirelessPref;
            case DEV:
                return swDevOptsPref;
            case A11Y:
                return swA11yPref;
            default:
                return null;
        }
    }

    /**
     * 「管理开关项」弹窗：每项一个宽行（标题 + 显示/跟随复选框 + 右侧拖动柄），
     * 支持拖动排序；确认后落盘（显示/跟随/顺序）并重建列表。
     */
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
                if (DebugController.masterAnyOn(this, s) != on) {
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
                // 例外：「系统隐藏了无障碍列表」不是授权问题，走二次确认；此时全局键已写下去了，
                // 确认后只需补做 a11y 这一步，不再重复整轮写入。
                if (!maybeAskA11yUnverified(reason)) {
                    privilegedAvailable = false;
                    AuthCoordinator.resetSession();
                    applyRootState();
                    auth.handleWriteFailure(reason);
                }
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
    void runLinkage(String pkg, boolean autoJump) {
        // 从快捷方式冷启动时 onCreate 不会构建设置页（handleEntry 已判定为「自行处理」而跳过 showSettings），
        // 而 setMaster 是异步的、未授权时最坏要等满超时（10s）。若不先建好设置页，这段等待期间界面就是
        // 一个只有 toolbar、内容区空白的主页——表现就是「卡住 / 白屏」。
        // 故先把设置页作为背景建好：成功则稍后 finish 跳走，失败则停在有内容的设置页上（显示未授权 + 重试）。
        if (!prefsLoaded) {
            showSettings();
        }
        // 联动走统一入口：一键关闭会记住当前组合并把 USB / 无线都关掉。
        // 常驻通知在 su 写完之后才拉起，天然比旧版的固定 250ms 延时更稳。
        DebugController.setMaster(this, false, (ok, s, reason) -> {
            if (!ok) {
                // 与 toggleMaster / applyMasterState 同规则：写入失败大概率授权失效，立即置灰并重测
                privilegedAvailable = false;
                AuthCoordinator.resetSession();
                applyRootState();
                Toast.makeText(this, DebugController.failureMessage(this, reason),
                        Toast.LENGTH_SHORT).show();
                // 未授权：保留已建好的设置页（显示「未授权，无法写入」+ 重试按钮），
                // 不再静默跳目标应用 / 关掉本页——否则用户既没关成调试、又看不到任何说明（白屏或闪走）。
                // 注意：本分支未实际跳转，LinkageLauncher 未 markLaunched，下次进页仍可触发自动跳转。
                return;
            }
            AdbToggleService.syncCloseNotification(this);
            if (!linkage.launchApp(pkg)) {
                // 目标应用已失效（卸载 / 停用）：提示并留在设置页，而不是静默闪回桌面。
                // 同样未实际跳转，LinkageLauncher 未 markLaunched，下次进页仍可触发自动跳转。
                linkage.handleTargetGone(pkg);
                if (!prefsLoaded) {
                    showSettings();
                }
                return;
            }
            // 仅当「确实成功跳转」后才记一笔：回来时不再自动跳，避免反复弹回。
            // 旧实现在入口即 markLaunched(true)，写入/启动任一失败时也不回滚，
            // 导致授权过期这类常见失败会让本进程内自动跳转永久失效（只能杀进程恢复）。
            // 状态机（含失败不回滚问题的根因消除）已抽到 LinkageLauncher。
            LinkageLauncher.markLaunched(autoJump);
            if (autoJump) {
                Toast.makeText(this, R.string.linkage_auto_jumped, Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }

    /**
     * 按完整状态刷新各行开关。
     * 主开关 = 跟随主开关的项的聚合态（任一开着就算开）；子开关显示各自真值；
     * 无障碍行显示第三方服务数（恢复失败时显示待办摘要）。
     */
    private void refresh(DebugController.State s) {
        if (swMasterPref == null) {
            return;
        }
        // 真值尚未由授权通道确认时，界面按「上次确认状态」渲染，不用调用方传入的快照：
        // 那多半是 ContentResolver 兜底值（ROM 隐藏这两个键时恒为关），
        // 直接拿来显示会出现「启动先显示关闭、校准完又跳回开启」的闪变。
        // 用 read()（命中 warmUp 预热的内存缓存）而非 lastKnownState：后者每次调用都读
        // 偏好磁盘 + 现算无障碍计数（PM IPC），refresh 调用点极密，启动窗口期会被反复拖慢。
        if (!DebugController.isCalibrated()) {
            s = DebugController.read(this);
        }
        // 主开关聚合：跟随主开关的项中任一开 = 开（a11y / dev 跟随时与 adbd 态会分叉）
        java.util.EnumSet<SwitchItem> followed = SwitchConfig.followedItems(this);
        boolean masterOn = s.anyOn(followed);
        // 真值未经本次授权通道确认时，显示「上次确认状态」的文案（保持稳定、不跳字）；
        // 只有首次使用（历史从没确认过、无上次状态）才显示「正在读取当前状态…」并置灰。
        final boolean calibrated = DebugController.isCalibrated();
        // 首次使用（无「上次状态」）才置灰；其余情况显示上次状态、不置灰，
        // 真值不一致时校准回来修正一次（见 MasterSwitchPreference 的显示规则）
        final boolean known = calibrated || DebugController.hasConfirmedBefore(this);
        swMasterPref.applyState(masterOn, known);
        // 展开态跟随主开关：开→展开，关→折叠
        applyMasterExpanded(masterOn);
        // 全跟随项都开或都关显示明确结论；部分开启时提示"部分开启"，避免误以为已安全
        int masterSum;
        int masterColor;
        if (!known) {
            masterSum = R.string.master_checking;
            masterColor = R.color.stateUnknown;
        } else if (s.allOff(followed)) {
            masterSum = R.string.master_off;
            masterColor = R.color.stateOff;
        } else if (masterOn && allOn(followed, s)) {
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
            setRowIcon(swMasterPref, masterIconSlot, masterColor);
        }

        if (swUsbPref != null) {
            swUsbPref.setSummary(s.usb ? R.string.usb_on : R.string.usb_off);
            setRowIcon(swUsbPref, usbIconSlot, s.usb ? R.color.stateOn : R.color.stateOff);
        }
        if (swWirelessPref != null) {
            // 无线调试三态（仅显示层，不改任何写入/判定语义）：
            //  - 开关关（key=0）→ 已关闭（橙色）
            //  - 开关开（key=1）+ 已连 Wi-Fi → 已开启 · adbd 运行中（绿色）
            //  - 开关开（key=1）+ 未连 Wi-Fi → 已开启 · 未激活（同绿色，只换文案）
            // 判据用「当前是否连着 Wi-Fi」而非底层 prop：实测 K90 / 荣耀关 Wi-Fi 后
            // key 与 persist.adb.tls_server.enable 都保持 1，prop 根本判断不出「此刻不可用」。
            // prop 读取代码保留（调试/历史用途），但不再作为显示判据。三模式统一生效。
            if (!s.wireless) {
                swWirelessPref.setSummary(R.string.wireless_off);
                setRowIcon(swWirelessPref, wirelessIconSlot, R.color.stateOff);
            } else if (com.elleng.adbswitch.util.NetworkUtil.isWifiConnected(this)) {
                swWirelessPref.setSummary(R.string.wireless_on);
                setRowIcon(swWirelessPref, wirelessIconSlot, R.color.stateOn);
            } else {
                swWirelessPref.setSummary(R.string.wireless_inactive);
                setRowIcon(swWirelessPref, wirelessIconSlot, R.color.stateOn);
            }
        }
        if (swDevOptsPref != null) {
            swDevOptsPref.setSummary(s.dev ? R.string.devopts_on : R.string.devopts_off);
            setRowIcon(swDevOptsPref, devIconSlot, s.dev ? R.color.stateOn : R.color.stateOff);
        }
        if (swA11yPref != null) {
            refreshA11yRow(s);
        }

        syncing = true;
        if (swUsbPref != null) {
            swUsbPref.setChecked(s.usb);
        }
        if (swWirelessPref != null) {
            swWirelessPref.setChecked(s.wireless);
        }
        if (swDevOptsPref != null) {
            swDevOptsPref.setChecked(s.dev);
        }
        if (swA11yPref != null) {
            swA11yPref.setChecked(s.a11yCount > 0);
        }
        syncing = false;

        // 无障碍恢复失败的一次性提示（每次最多弹一条，见 consumeA11yRestoreToast）
        maybeToastA11yRestore();

        // 长按快捷方式的状态灯与界面同步
        ToggleShortcuts.markDirty(this);

        // 校准状态变化后无需重挂点击：监听常驻，窗口期由回调内部自行屏蔽
    }

    /** 给定项集合是否全部开启 */
    private static boolean allOn(java.util.Collection<SwitchItem> items, DebugController.State s) {
        for (SwitchItem i : items) {
            if (!s.isOn(i)) {
                return false;
            }
        }
        return !items.isEmpty();
    }

    /** 无障碍行摘要/图标：恢复失败优先显示待办，其次显示第三方服务数 */
    private void refreshA11yRow(DebugController.State s) {
        int pending = DebugController.a11yRestorePending(this);
        if (pending > 0) {
            swA11yPref.setSummary(getString(R.string.a11y_restore_pending, pending));
            setRowIcon(swA11yPref, a11yIconSlot, R.color.error);
            // 懒复核自愈：写入时核对窗口短（慢绑定/ROM 缓存延迟会误报），稍后重核绑上即清
            scheduleA11yRecheck();
        } else if (s.a11yCount > 0) {
            a11yRecheckCount = 0; // 无待办：重置懒复核配额，日后真出待办还能再重核
            swA11yPref.setSummary(getString(R.string.a11y_on, s.a11yCount));
            setRowIcon(swA11yPref, a11yIconSlot, R.color.stateOn);
        } else {
            a11yRecheckCount = 0;
            swA11yPref.setSummary(R.string.a11y_off);
            setRowIcon(swA11yPref, a11yIconSlot, R.color.stateOff);
        }
    }

    /**
     * 懒复核：pending>0 时延后 5s 重核（绑上自动清掉误报）。
     *
     * 必须设上限：复核结论若一直不变（如待办条目已不可能再绑定），「显示 → 排复核 → 结论不变
     * → 再排」会变成每 5 秒一次的无限空转（持续唤醒 + 日志刷屏），用户看到的就是界面一直
     * 卡在同一个状态。达到上限即停止，等下一次真实写入 / 重新进入页面再重新计数。
     */
    private static final int A11Y_RECHECK_MAX = 2;
    private boolean a11yRecheckScheduled = false;
    private int a11yRecheckCount = 0;
    private final Runnable a11yRecheckRun = () -> {
        a11yRecheckScheduled = false;
        DebugController.recheckA11yRestore(this, () -> refresh(DebugController.read(this)));
    };

    private void scheduleA11yRecheck() {
        if (a11yRecheckScheduled || a11yRecheckCount >= A11Y_RECHECK_MAX) {
            return;
        }
        a11yRecheckScheduled = true;
        a11yRecheckCount++;
        mainHandler.postDelayed(a11yRecheckRun, 5000);
    }

    /** 恢复失败后的一次性 Toast（持久化摘要由 refreshA11yRow 常驻显示） */
    private void maybeToastA11yRestore() {
        int pending = DebugController.a11yRestorePending(this);
        if (pending > 0 && DebugController.consumeA11yRestoreToast(this)) {
            Toast.makeText(this, getString(R.string.a11y_restore_partial, pending),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「系统隐藏了无障碍列表」的二次确认。
     *
     * 关闭无障碍 = 把列表里的第三方条目剔除掉再写回；但荣耀这类 ROM 向第三方隐藏了该键，
     * 应用读到空表时无法分辨哪些是系统预装服务，写空表可能把它们一起关掉（不可逆）。
     * 这类情况不替用户做主：拦下来让用户确认——点「继续」才按空表口径执行，点「取消」什么都不写。
     *
     * @return true = 这是「等待确认」的失败原因，已弹框（调用方不要再走通用失败提示/授权重测）
     */
    private boolean maybeAskA11yUnverified(int reason) {
        if (reason != RootUtil.REASON_READ_HIDDEN) {
            return false;
        }
        final int dir = DebugController.a11yConfirmPendingDirection();
        if (dir < 0 || isFinishing() || isDestroyed()) {
            return false; // 没有待确认的写入（或页面已销毁）：按普通失败处理
        }
        final boolean want = dir == 1;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.a11y_hidden_title)
                .setMessage(R.string.a11y_hidden_msg)
                .setNegativeButton(android.R.string.cancel, (d, w) ->
                        refresh(DebugController.read(this)))
                .setPositiveButton(R.string.a11y_hidden_continue, (d, w) ->
                        DebugController.setA11yConfirmed(this, want, (ok, s, r) -> {
                            if (!ok) {
                                if (!maybeAskA11yUnverified(r)) {
                                    AuthCoordinator.resetSession();
                                    auth.handleWriteFailure(r);
                                }
                            } else if (s == null || s.isOn(SwitchItem.A11Y) != want) {
                                Toast.makeText(this, R.string.toast_write_not_effective,
                                        Toast.LENGTH_SHORT).show();
                            }
                            refresh(s);
                        }))
                .show();
        return true;
    }

    /** 根据当前授权模式是否可用刷新控件与提示：未授权时禁用并提示，授权后及时恢复 */
    void applyRootState() {
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
            // 已授权：调试三行恢复为「拨动开关」模式（整行点击切开关）
            swUsbPref.setNavigateMode(false);
            swWirelessPref.setNavigateMode(false);
            if (swDevOptsPref != null) {
                swDevOptsPref.setNavigateMode(false);
            }
            // 无障碍行同调试三行（写 enabled_accessibility_services 同样要写权限）
            if (swA11yPref != null) {
                swA11yPref.setEnabled(true);
                swA11yPref.setNavigateMode(false);
            }
            // 联动分类下（开关 + 动态填充的默认/应用/管理行）一并启用
            setLinkageEnabled(true);
            // 授权恢复：把主开关行从「未授权，无法写入」改回真实状态文案。
            // （refresh 在未授权时不再覆盖文案，故必须在这里补一次，否则会一直停在未授权提示上）
            refresh(DebugController.read(this));
        } else {
            swMasterPref.setSummary(R.string.auth_no_priv);
            setRowIcon(swMasterPref, masterIconSlot, R.color.error);
            swMasterPref.setEnabled(false);
            // §4.1：「关闭调试后是否弹常驻通知」是纯本地偏好，不依赖写权限，未授权时也不禁用，
            // 让用户能先把配置配好（执行点击时才会按现有逻辑提示授权）。
            // 未授权/未激活：调试三行改为「导航模式」——整行点击进入相应设置菜单，开关禁用。
            // 注意：行本身保持 enabled（可点），仅禁用其中的 Switch 控件；若整行 setEnabled(false)
            // 会被 framework 屏蔽触摸事件，导致「整行点击」收不到（见 NavSwitchPreference）。
            swUsbPref.setEnabled(true);
            swWirelessPref.setEnabled(true);
            if (swDevOptsPref != null) {
                swDevOptsPref.setEnabled(true);
            }
            swUsbPref.setNavigateMode(true);
            swWirelessPref.setNavigateMode(true);
            if (swDevOptsPref != null) {
                swDevOptsPref.setNavigateMode(true);
            }
            // 无障碍行同调试三行：未授权时开关置灰（navigate 模式），行点击进系统无障碍设置。
            // 写 enabled_accessibility_services 同样要写权限，不能只有它保持可拨。
            if (swA11yPref != null) {
                swA11yPref.setEnabled(true);
                swA11yPref.setNavigateMode(true);
            }
            // 联动分类下所有项一并禁用（无授权则无法写入调试开关，联动也失去意义）
            setLinkageEnabled(false);
        }
        // 实时动态项的可用性只跟系统版本有关，不受授权模式影响
        updateLiveUpdateState();
        // §3.3：kill 开关可用性由 updateKillPrefState 独占，确保本刷新路径下也被正确置灰/启用
        // （setLinkageEnabled 已不再触碰 swKillPref）。
        auth.updateKillPrefState();
    }

    /** 联动分类下所有项（开关 + 动态填充的默认 / 应用 / 管理行）一并启用或禁用 */
    void setLinkageEnabled(boolean enabled) {
        PreferenceCategory cat = pref("catLinkage");
        if (cat == null) {
            return;
        }
        // §3.3：「开启调试自动关闭快捷应用」(swKillPref) 的可用性由 updateKillPrefState 独占判定，
        // 不该被本批量循环覆盖——否则 Shizuku 可用但 WSS 尚未授权时会出现「整类灰、唯独 kill 可点」。
        for (int i = 0; i < cat.getPreferenceCount(); i++) {
            Preference p = cat.getPreference(i);
            if (p == swKillPref) {
                continue;
            }
            p.setEnabled(enabled);
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

    /**
     * 行状态圆点的缓存槽（**每行一个**，不跨行共享）。
     *
     * 为什么必须缓存：androidx 的 {@code Preference.setIcon} 是**引用比较**——
     * 传入新实例必走 {@code notifyChanged()} → RecyclerView 整行重绑。
     * refresh() 的调用点极密（写入回调 + 状态 listener + Settings 观察者 + 网络回调 + 授权刷新），
     * 每次都 new 一个圆点，等于每次刷新都把主开关行重绑一遍：
     * 用户看到的就是「点任何开关（哪怕是不跟随主开关的项）主开关都闪一下」。
     * 复用同一实例则颜色未变时 setIcon 直接早退，不产生任何重绑。
     * 仍按行独立持有实例，避免旧实现那种多 View 共享同一 Drawable 互相覆盖 setBounds 的问题。
     */
    private static final class IconSlot {
        GradientDrawable drawable;
        int colorRes;
    }

    private final IconSlot masterIconSlot = new IconSlot();
    private final IconSlot usbIconSlot = new IconSlot();
    private final IconSlot wirelessIconSlot = new IconSlot();
    private final IconSlot devIconSlot = new IconSlot();
    private final IconSlot a11yIconSlot = new IconSlot();

    /**
     * 给某一行设置状态圆点：颜色与上次相同就复用同一 Drawable 实例（setIcon 内部引用相等即早退，
     * 不会触发整行重绑）；颜色变了才新建实例。
     */
    private void setRowIcon(androidx.preference.Preference pref, IconSlot slot, int colorRes) {
        if (pref == null) {
            return;
        }
        if (slot.drawable == null || slot.colorRes != colorRes) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(getResources().getColor(colorRes, getTheme()));
            d.setSize(40, 40);
            slot.drawable = d;
            slot.colorRes = colorRes;
        }
        pref.setIcon(slot.drawable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 更新弹窗停在「等待安装授权」且用户刚在系统设置授完「安装未知应用」返回时，
        // 直接复用已下载的 APK 续装（不重新下载）。内部自校验状态，无弹窗时是空操作。
        UpdateDialog.onHostResumed(this);
        // 设置界面尚未构建（例如调试入口只用 postDelayed 延迟构建）时，
        // 偏好项字段都还是 null，下面的刷新会 NPE，必须先跳过。
        if (!prefsLoaded) {
            cameFromBackground = false;
            return;
        }
        boolean fromBg = cameFromBackground;
        cameFromBackground = false;
        // 授权：从后台返回时**一律强制重测**（两个方向都要覆盖，缺一不可）
        //  (a) 以为未授权 → 用户在 root 管理器 / Shizuku 里刚授了权，回来必须立刻生效。
        //  (b) 以为已授权 → 授权可能在后台被撤销（root 被关掉、Magisk 里取消授权、
        //      Shizuku 服务被停 / 撤销授权、pm grant 被 revoke）。旧实现只在 (a) 时重测，
        //      于是 (b) 会一直显示「已授权」，要等下一次写入失败才被动发现，用户毫无反馈。
        // 非强制调用会被 AuthCoordinator 的 30s 节流与「本会话已确认」闸门挡住，必须 force。
        // 代价可控：WSS 仅 checkSelfPermission（零成本）、Shizuku 仅 binder 调用、
        // root 才 fork 一次 su（已授权时通常瞬间返回；被撤销时正好触发系统授权提示，用户可见）。
        // 探测在后台线程执行，不阻塞 UI；结果经 AuthResult 回主线程刷新状态与提示。
        if (fromBg || !privilegedAvailable) {
            AuthCoordinator.resetSession();
            auth.check(true);
        }
        // 状态（bug4：在系统里关了调试、回到应用却仍显示开启；首次回常错、再回才对）：
        // 从后台返回时作废缓存、强制用授权通道重读。此前只 refresh(read())——read 命中仍在
        // 有效期内的缓存（shell 通道 5s）就直接返回旧值，不会重读；而 settingsObserver 在后台
        // 已被反注册，错过的外部改动无人补偿。invalidateAndRefresh 会把缓存标记为「未校准」、
        // 清掉节流并立即后台重读，读完经 stateListener 推送 → refresh() 更新 UI。
        if (fromBg) {
            DebugController.invalidateAndRefresh(this);
        }
        // 先按当前缓存即时渲染（可能仍是旧值），真值读回后由 stateListener 再刷新一次
        refresh(DebugController.read(this));
        linkage.rebuildList();
        // §2.5a：已卸载快捷应用清理移到后台线程，避免在主线程做 PM IPC
        linkage.maybeCleanUninstalled();
        updateLiveUpdateState();
        // 关于行可能已在后台自动检查后更新（可更新提示），回到前台即时同步
        updateCheck.refreshAboutSummary();
        // 开始监听外部对这两个 Settings 键的改动（App 在前台的场景，onResume 的回读覆盖不到）
        observeSettings(true);
        // 监听 Wi-Fi 连接变化：无线行的「未激活」判据是当前网络，断开/连回都要即时重绘
        observeNetwork(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 记下「离开过前台」：下次 onResume 据此强制重测授权 / 重读状态，
        // 补偿在后台期间（observer 已反注册）错过的外部改动与授权变化。
        cameFromBackground = true;
        observeSettings(false);
        observeNetwork(false);
        // 停掉待执行的 a11y 懒复核（回前台后 refresh 会重新排）
        mainHandler.removeCallbacks(a11yRecheckRun);
        a11yRecheckScheduled = false;
        // 回到前台是一次全新观察窗口：重置配额，避免上次会话用完次数后永远不再重核
        a11yRecheckCount = 0;
    }

    /**
     * 注册/反注册 Settings 监听，避免泄漏与无谓开销。
     *
     * 只监听 DebugController 管理的键（三个 Settings.Global 键 + Settings.Secure 的
     * 无障碍列表键）的 URI，不监听整个 Settings.Global.CONTENT_URI：
     * 后者会让任何 global 设置变更（亮度、飞行模式等）都回调一次，白白触发一轮状态刷新。
     * 无障碍列表在 Secure 命名空间，URI 不同，必须单独注册——外部在系统设置里增删
     * 无障碍服务同样要即时刷新数量/开关态（实测 bug：不注册就只能等写路径重数）。
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
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(A11yHelper.KEY), false, settingsObserver);
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

    /** 注册/反注册默认网络监听，随前台生命周期走，避免泄漏与后台无谓回调 */
    private void observeNetwork(boolean register) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            if (register) {
                if (wifiCallbackRegistered) {
                    return;
                }
                cm.registerDefaultNetworkCallback(wifiCallback, mainHandler);
                wifiCallbackRegistered = true;
            } else {
                if (!wifiCallbackRegistered) {
                    return;
                }
                cm.unregisterNetworkCallback(wifiCallback);
                wifiCallbackRegistered = false;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "observe network failed", t);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            DebugController.removeStateListener(stateListener);
        } catch (Throwable ignored) {
        }
        // §4.7：移除「写入在途」监听，避免静态表持有旧 Activity 实例
        try {
            DebugController.removeBusyListener(busyListener);
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
