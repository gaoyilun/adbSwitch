package com.elleng.usbdebug;

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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
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
import android.preference.PreferenceActivity;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.view.View;
import android.preference.PreferenceCategory;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.elleng.usbdebug.util.RootUtil;

/**
 * 主界面：使用 framework 原生 PreferenceActivity（Android 原生 Material 设置页），
 * 统一沿用系统设置项样式（SwitchPreference / Preference / PreferenceCategory），
 * 不再手写彩色卡片，整体更贴近原生 Android 应用观感。
 */
public class MainActivity extends PreferenceActivity {

    public static final String EXTRA_SETTINGS = "extra_settings";
    /** 静态 shortcut「打开主页」的 action：见到它就跳过联动自动跳转 */
    private static final String ACTION_OPEN_HOME = "com.elleng.usbdebug.action.OPEN_HOME";
    /** 调试用 action：直接拉起联动应用选择器（便于 adb 远程验证弹窗是否正常） */
    private static final String ACTION_DEBUG_PICKER = "com.elleng.usbdebug.action.DEBUG_PICKER";
    /**
     * 调试用 action：从前台 Activity 直接拨主开关，等价于用户手动拨。
     * 存在的理由：adb 连发广播会被 HyperOS 的 Greezer 拦截（"need cached broadcast"），
     * 走 Activity 则不受限，可稳定远程验证一键开关。
     * 用法：am start -n .../.MainActivity -a com.elleng.usbdebug.action.DEBUG_MASTER --ez master_on false
     */
    private static final String ACTION_DEBUG_MASTER = "com.elleng.usbdebug.action.DEBUG_MASTER";
    private static final String EXTRA_MASTER_ON = "master_on";
    /** 桌面快捷方式「一键开关」：切换主开关，执行完直接退出 */
    private static final String ACTION_TOGGLE_MASTER = "com.elleng.usbdebug.action.TOGGLE_MASTER";
    /** 桌面快捷方式「联动启动」：强制执行一次联动（关调试 + 跳转），不进设置页 */
    private static final String ACTION_LINKAGE_LAUNCH = "com.elleng.usbdebug.action.LINKAGE_LAUNCH";
    private static final String TAG = "UsbSwitch";
    private static final String PREFS = "cfg";
    private static final String PREF_LINKAGE = "linkage_enabled";
    private static final String PREF_PKG = "linkage_pkg";
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
    private Preference appPref;
    private Preference runNowPref;
    private Preference liveUpdatePref;
    private Preference buildInfoPref;

    private boolean syncing = false;          // 防止 setChecked 触发回环
    private boolean rootAvailable = false;    // 是否已拿到 root，进入页面时刷新
    private boolean prefsLoaded = false;      // 设置界面是否已构建（onNewIntent 复用实例时避免重复加载）
    private boolean rowClicksWired = false;   // 行点击的布局监听是否已注册（避免重复注册）
    /** 进程级：本次进程内是否已自动跳转过联动应用（static 保证 Activity 重建后仍记得） */
    private static boolean sLinkageLaunched = false;
    private long lastPickerOpenAt = 0;        // 打开选择器防抖：行点击与 preference click 可能同时触发
    private long lastLiveOpenAt = 0;          // 同上，实时动态弹窗防抖

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!handleEntry(getIntent())) {
            showSettings();
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
            String pkg = sp.getString(PREF_PKG, "");
            if (pkg.isEmpty()) {
                Toast.makeText(this, R.string.linkage_no_app, Toast.LENGTH_LONG).show();
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
                applyMasterState(masterOn);
                android.util.Log.i(TAG, "DEBUG_MASTER done, on=" + masterOn
                        + " usb=" + DebugController.read().usb
                        + " wireless=" + DebugController.read().wireless);
            }, 600);
            return true;
        }

        // 桌面快捷方式「一键开关」：切换主开关后直接退出，不显示界面
        if (ACTION_TOGGLE_MASTER.equals(in.getAction())) {
            final boolean next = !DebugController.read().anyOn();
            applyMasterState(next);
            Toast.makeText(this, next ? R.string.master_on : R.string.master_off,
                    Toast.LENGTH_SHORT).show();
            finish();
            return true;
        }

        // 桌面快捷方式「联动启动」：强制执行一次联动（关调试 + 跳转），不受自动跳转次数限制
        if (ACTION_LINKAGE_LAUNCH.equals(in.getAction())) {
            String p = sp.getString(PREF_PKG, "");
            if (p.isEmpty()) {
                Toast.makeText(this, R.string.linkage_no_app, Toast.LENGTH_LONG).show();
                return false;
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.linkage_need_notif, Toast.LENGTH_LONG).show();
                return false;
            }
            runLinkage(p);
            return true;
        }

        boolean linkage = sp.getBoolean(PREF_LINKAGE, false);

        // —— 联动模式：从启动器打开时，自动关闭调试并跳转联动应用 ——
        if (linkage && !openSettings) {
            String pkg = sp.getString(PREF_PKG, "");
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
                // 用的人根本来不及用设置页。需要再跳时用「立即执行联动」手动触发。
                if (sLinkageLaunched) {
                    Toast.makeText(this, R.string.linkage_skipped, Toast.LENGTH_LONG).show();
                    return false;
                }
                runLinkage(pkg);
                return true;
            }
            // 没选联动应用：提示并落到普通界面
            Toast.makeText(this, R.string.linkage_no_app, Toast.LENGTH_LONG).show();
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
        }

        rootAvailable = RootUtil.hasRoot();
        refresh(DebugController.read());
        applyRootState();
        updateAppLabel();
    }

    /** 原生顶栏：标题 + 副标题 + USB 图标 + 主色背景（系统自动预留状态栏区域） */
    private void setupActionBar() {
        ActionBar ab = getActionBar();
        if (ab == null) {
            return;
        }
        ab.setDisplayShowHomeEnabled(true);
        ab.setDisplayUseLogoEnabled(true);
        ab.setLogo(R.drawable.ic_launcher);
        ab.setTitle(R.string.app_name);
        ab.setSubtitle(R.string.header_subtitle);
        ab.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.colorPrimary)));
    }

    private void initPrefs() {
        swMasterPref = findPreference("swMaster");
        swUsbPref = (SwitchPreference) findPreference("swUsb");
        swLinkagePref = (SwitchPreference) findPreference("swLinkage");
        swNotifyPref = (SwitchPreference) findPreference("swNotify");
        swWirelessPref = (SwitchPreference) findPreference("swWireless");
        swKillPref = (SwitchPreference) findPreference("swKillBeforeOpen");
        appPref = findPreference("linkageApp");
        runNowPref = findPreference("linkageRunNow");
        liveUpdatePref = findPreference("liveUpdate");
        buildInfoPref = findPreference("buildInfo");
        // 底部显示本 APK 的构建时间，便于确认设备上装的是否为最新版本
        if (buildInfoPref != null) {
            String t = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date(BuildConfig.BUILD_TIME_MS));
            buildInfoPref.setSummary(t);
        }

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
            if (!DebugController.setUsb(this, (Boolean) v)) {
                Toast.makeText(this, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
            }
            refresh(DebugController.read());
            return true;
        });

        swWirelessPref.setOnPreferenceChangeListener((Preference p, Object v) -> {
            if (syncing) {
                return true;
            }
            if (!DebugController.setWireless(this, (Boolean) v)) {
                Toast.makeText(this, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
            }
            refresh(DebugController.read());
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
            Toast.makeText(this, on ? R.string.linkage_on : R.string.linkage_off, Toast.LENGTH_SHORT).show();
            return true;
        });

        // 「联动应用」行的点击与「打开」按钮都在 bindOpenButton() 里手动接线；
        // 另外这里也挂 framework 的 click listener 作为第二条路径——
        // framework Preference 行本身 clickable="false"，谁先谁后不确定，故两条路径都留，
        // 靠 openAppPicker()/openLiveUpdate() 的防抖保证只弹一次。
        appPref.setOnPreferenceClickListener(p -> {
            openAppPicker();
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
            if (DebugController.read().allOff()) {
                if (on) {
                    UsbToggleService.start(this);
                } else {
                    UsbToggleService.stop(this);
                }
            }
            return true;
        });

        liveUpdatePref.setOnPreferenceClickListener(p -> {
            checkAndPromptLiveUpdate();
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
        boolean next = !DebugController.read().anyOn();
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
        if (!DebugController.read().anyOn()) {
            return; // 关闭态强制保持折叠
        }
        masterManualExpanded = !subSwitchesShown;
        applyMasterExpanded(true);
    }

    /**
     * 主开关一键写入：唯一入口，两个子开关一起处理。
     * 关 -> 记忆当前组合并全关，按「临时关闭时弹通知」决定是否拉起常驻通知；
     * 开 -> 还原记忆的组合并撤掉常驻通知。
     */
    private void applyMasterState(boolean on) {
        boolean ok = DebugController.setMaster(this, on);
        if (!ok) {
            Toast.makeText(this, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
            refresh(DebugController.read());
            return;
        }
        if (on) {
            UsbToggleService.stop(this);
        } else {
            if (notifyOnClose()) {
                UsbToggleService.start(this);
            } else {
                // 开关关闭：静默临时关闭，不具备常驻通知
                UsbToggleService.stop(this);
            }
        }
        refresh(DebugController.read());
    }

    private boolean notifyOnClose() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_NOTIFY, true);
    }

    /** 执行联动：关闭调试 -> 拉起常驻通知 -> 跳转联动应用 */
    private void runLinkage(String pkg) {
        // 联动走统一入口：一键关闭会记住当前组合并把 USB / 无线都关掉
        DebugController.setMaster(this, false);
        // 先拉起常驻通知（若开关开启），再跳联动应用。
        // 不立刻 finish：给前台服务一点时间完成 startForeground，
        // 否则 Activity 立即退场会让通知迟到（要等回到本 App 才出现）。
        if (notifyOnClose()) {
            UsbToggleService.start(this);
        } else {
            UsbToggleService.stop(this);
        }
        sLinkageLaunched = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            launchApp(pkg);
            finish();
        }, 250);
    }

    /** 「立即执行联动」手动入口：不受"只自动跳一次"限制 */
    private void runLinkageManual() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pkg = sp.getString(PREF_PKG, "");
        if (pkg.isEmpty()) {
            Toast.makeText(this, R.string.linkage_no_app, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.linkage_need_notif, Toast.LENGTH_LONG).show();
            return;
        }
        runLinkage(pkg);
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
            masterColor = R.color.stateSafe;
        } else if (s.usb && s.wireless) {
            masterSum = R.string.master_on;
            masterColor = R.color.stateOn;
        } else {
            masterSum = R.string.master_partial;
            masterColor = R.color.error;
        }
        swMasterPref.setSummary(masterSum);
        swMasterPref.setIcon(stateIcon(masterColor));

        swUsbPref.setSummary(s.usb ? R.string.usb_on : R.string.usb_off);
        swUsbPref.setIcon(stateIcon(s.usb ? R.color.stateOn : R.color.stateSafe));

        swWirelessPref.setSummary(s.wireless ? R.string.wireless_on : R.string.wireless_off);
        swWirelessPref.setIcon(stateIcon(s.wireless ? R.color.error : R.color.stateSafe));

        syncing = true;
        swUsbPref.setChecked(s.usb);
        swWirelessPref.setChecked(s.wireless);
        syncing = false;
    }

    /** 根据 root 可用性刷新控件与提示：无 root 时禁用并提示，授权后及时恢复 */
    private void applyRootState() {
        // 偏好项任一为 null 说明设置页还没建好，直接跳过刷新
        if (swMasterPref == null || swUsbPref == null || swLinkagePref == null
                || swNotifyPref == null || swWirelessPref == null || appPref == null) {
            return;
        }
        if (rootAvailable) {
            swMasterPref.setEnabled(true);
            swUsbPref.setEnabled(true);
            swLinkagePref.setEnabled(true);
            appPref.setEnabled(true);
            swNotifyPref.setEnabled(true);
            swWirelessPref.setEnabled(true);
        } else {
            swMasterPref.setSummary(R.string.root_missing);
            swMasterPref.setIcon(stateIcon(R.color.error));
            swMasterPref.setEnabled(false);
            swUsbPref.setEnabled(false);
            swLinkagePref.setEnabled(false);
            appPref.setEnabled(false);
            swNotifyPref.setEnabled(false);
            swWirelessPref.setEnabled(false);
        }
        // 实时动态项的可用性只跟系统版本有关，不受 root 影响
        updateLiveUpdateState();
    }

    private void updateAppLabel() {
        if (appPref == null) {
            return;
        }
        String pkg = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_PKG, "");
        if (pkg.isEmpty()) {
            appPref.setSummary(R.string.linkage_app_none);
            appPref.setIcon(null);
        } else {
            CharSequence label = getApplicationLabel(pkg);
            appPref.setSummary(label == null ? pkg : label);
            // 左侧显示联动应用图标（framework Preference 用 Drawable 版本）
            appPref.setIcon(loadAppDrawable(pkg));
        }
        bindOpenButton(pkg);
    }

    /**
     * 给「联动应用」行右侧按钮接线：
     *   - 「修改」：打开应用选择器（framework 整行点击不可靠，按钮入口最稳，始终可见）；
     *   - 「打开」：直接启动已选应用（未选应用时隐藏）。
     */
    private void bindOpenButton(String pkg) {
        if (appPref == null) {
            return;
        }
        // 只接「打开」按钮；「修改」按钮已移除，改由整行点击打开选择器
        View openBtn = findViewDeep(R.id.btn_open_app);
        if (openBtn == null) {
            return;
        }
        if (pkg.isEmpty()) {
            openBtn.setVisibility(View.GONE);
            return;
        }
        openBtn.setVisibility(View.VISIBLE);
        openBtn.setOnClickListener(v -> {
            if (rootAvailable) {
                launchApp(pkg);
            } else {
                Toast.makeText(this, R.string.root_missing, Toast.LENGTH_SHORT).show();
            }
        });
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
        AppListDialog.show(this, pkg -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_PKG, pkg).apply();
            updateAppLabel();
            // 选完立刻刷新「启动联动应用」快捷方式的图标与名称
            updateLaunchAppShortcut();
            Toast.makeText(this, R.string.linkage_app_set, Toast.LENGTH_SHORT).show();
        });
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
        String pkg = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_PKG, "");
        String liveTitle = getString(R.string.live_update_title);
        String appTitle = getString(R.string.linkage_app_label);
        String runNowTitle = getString(R.string.linkage_run_now_title);
        String masterTitle = getString(R.string.master_title);
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row == null) {
                continue;
            }
            // 按标题文本识别行：原先靠行内的「修改」按钮识别，该按钮已移除；
            // 按 position 映射到 getPreferenceScreen().getPreference() 又受 header/复用影响，故统一用标题。
            TextView tv = row.findViewById(android.R.id.title);
            CharSequence title = (tv == null) ? null : tv.getText();
            if (title == null) {
                continue;
            }
            if (masterTitle.equals(title)) {
                // 主开关行：整行切换开关；箭头按钮单独消费点击，用于折叠/展开
                row.setOnClickListener(v -> toggleMaster());
                View arrow = row.findViewById(R.id.btn_expand);
                if (arrow != null) {
                    arrow.setOnClickListener(v -> toggleExpanded());
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
                    sw.setChecked(DebugController.read().anyOn());
                    syncing = false;
                }
                updateExpandIcon(subSwitchesShown);
            } else if (appTitle.equals(title)) {
                // 整行触发选择器（「修改」按钮已移除，整行点击即选择应用）
                row.setOnClickListener(v -> openAppPicker());
                final String curPkg = pkg;
                View openBtn = row.findViewById(R.id.btn_open_app);
                if (openBtn != null) {
                    if (curPkg.isEmpty()) {
                        openBtn.setVisibility(View.GONE);
                    } else {
                        openBtn.setVisibility(View.VISIBLE);
                        openBtn.setOnClickListener(v -> {
                            if (rootAvailable) {
                                launchApp(curPkg);
                            } else {
                                Toast.makeText(this, R.string.root_missing, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            } else if (liveTitle.equals(title)) {
                row.setOnClickListener(v -> openLiveUpdate());
            } else if (runNowTitle.equals(title)) {
                row.setOnClickListener(v -> runLinkageManual());
            }
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

    private View findOpenButton(View root) {
        if (root == null) {
            return null;
        }
        if (root.getId() == R.id.btn_open_app) {
            return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findOpenButton(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private CharSequence getApplicationLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 状态圆点图标（实心圆，随状态变色）；尺寸需与 pref_row 的 icon 区（40dp）协调 */
    private GradientDrawable stateIcon(int colorRes) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(getResources().getColor(colorRes, getTheme()));
        d.setSize(28, 28);
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
        // 无 root 时每次进入页面都重新探测：用户在别处授权 superuser 后回到本 App 可即时恢复
        if (!rootAvailable) {
            rootAvailable = RootUtil.hasRoot();
            applyRootState();
        }
        // 每次回到前台都回读真值：可能在别处（通知/磁贴/adb）被改过
        refresh(DebugController.read());
        updateAppLabel();
        updateLiveUpdateState();
        updateLaunchAppShortcut();
        // 防御性重挂行点击：framework 可能在 resume/重绑时重置行状态，导致我们的 OnClickListener 失效；
        // 且滚动后新出现的行也需要重新挂，故每次 resume 都重新挂一遍。
        if (prefsLoaded) {
            scheduleWireRowClicks();
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

    /**
     * 注册/刷新动态快捷方式「启动联动应用」：
     *  - 图标与名称取自用户已选择的联动应用；
     *  - 点击后由本 Activity 中转后立刻跳到目标应用（不做关调试等联动动作），
     *    因此该快捷方式在「联动模式」关闭时同样可用。
     * 未选择联动应用时不发布该快捷方式。
     */
    private void updateLaunchAppShortcut() {
        if (Build.VERSION.SDK_INT < 25) {
            return;
        }
        ShortcutManager sm = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
        if (sm == null) {
            return;
        }
        String pkg = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_PKG, "");
        if (pkg.isEmpty()) {
            // 未选择：移除该动态快捷方式
            try {
                sm.removeDynamicShortcuts(java.util.Collections.singletonList(SC_ID_LAUNCH_APP));
            } catch (Throwable ignored) {
            }
            return;
        }

        CharSequence label = getApplicationLabel(pkg);
        Icon icon = loadAppIcon(pkg);

        Intent target = new Intent(this, MainActivity.class)
                .setAction(SC_ID_LAUNCH_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        ShortcutInfo info = new ShortcutInfo.Builder(this, SC_ID_LAUNCH_APP)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(target)
                .build();
        try {
            sm.setDynamicShortcuts(java.util.Collections.singletonList(info));
        } catch (Throwable t) {
            // 超过动态快捷方式数量上限等异常时，忽略，不影响主功能
        }
    }

    /** 取目标应用图标（Drawable，供 Preference 左侧显示）；失败回退本应用图标 */
    private Drawable loadAppDrawable(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return getResources().getDrawable(R.drawable.ic_launcher_foreground);
        }
    }

    /** 取目标应用图标；失败则回退到本应用图标 */
    private Icon loadAppIcon(String pkg) {
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            if (d instanceof BitmapDrawable) {
                return Icon.createWithBitmap(((BitmapDrawable) d).getBitmap());
            }
            // 非位图 drawable：渲染成位图
            Bitmap bmp = Bitmap.createBitmap(
                    Math.max(1, d.getIntrinsicWidth()),
                    Math.max(1, d.getIntrinsicHeight()),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
            d.draw(c);
            return Icon.createWithBitmap(bmp);
        } catch (Throwable t) {
            return Icon.createWithResource(this, R.drawable.ic_launcher_foreground);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 是否授予只影响常驻通知是否可见，不影响 root 开关功能。
    }

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
    }
}
