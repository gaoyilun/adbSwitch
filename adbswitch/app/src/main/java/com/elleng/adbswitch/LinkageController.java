package com.elleng.adbswitch;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「快捷打开」（联动）的列表与目标管理：动态行重建、应用/默认选择器、
 * 桌面快捷方式创建、图标缓存、已卸载目标清理、目标应用拉起。
 *
 * 写入编排（runLinkage：关调试 → 通知 → 跳转）仍留在 MainActivity，
 * 因为它与设置页构建、授权状态、Activity 生命周期强耦合；
 * 本类只做「列表 / 目标 / 图标 / 快捷方式」这一层，经包内可见的
 * pref / privilegedAvailable / prefsLoaded / runLinkage / setLinkageEnabled 与主界面交互。
 */
final class LinkageController {

    private static final String TAG = "AdbSwitch";

    /** 动态联动应用行的 key 前缀：完整 key = 前缀 + 包名（rebuildList 据此重建、识别包名） */
    private static final String LINKAGE_APP_KEY_PREFIX = "linkage_app:";
    /** 默认跳转应用行 / 管理联动应用行的固定 key（动态填充，不在 prefs.xml 写死） */
    private static final String LINKAGE_KEY_DEFAULT = "linkage_default";
    private static final String LINKAGE_KEY_MANAGE = "linkage_manage";

    private final MainActivity activity;
    /** 上次重建「快捷打开」列表的内容指纹（配置没变就不重建，见 rebuildList） */
    private String lastLinkageSignature = null;
    /** 联动应用图标缓存：按包名缓存已解码的 Drawable，rebuildList 复用，避免每次回前台重复跨进程 getApplicationIcon 解码 */
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private long lastPickerOpenAt = 0;        // 打开选择器防抖：行点击与 preference click 可能同时触发

    // §2.5a：已卸载的快捷应用清理移到后台线程，避免每次 onResume 在主线程做 PM IPC。
    private static final Object CLEAN_LOCK = new Object();
    private static final java.util.concurrent.Executor CLEAN_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile long lastCleanAt = 0;

    LinkageController(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * 按当前快捷打开配置动态重建「快捷打开」分类下的列表：
     *  1) 默认快捷应用行（图标在右侧）；2) 每个快捷应用一行（点击=快捷打开 / 长按=创建快捷方式）；
     *  3) 管理快捷应用行。
     *  每次配置变化（增删 app、改默认、授权状态变化）都重建，保证 UI 与配置一致。
     */
    void rebuildList() {
        PreferenceCategory cat = activity.pref("catLinkage");
        if (cat == null) {
            return;
        }
        List<String> apps = LinkagePrefs.getApps(activity);
        // §2.5a：已卸载的快捷应用清理已移到后台线程（见 maybeCleanUninstalled），此处不再在主线程
        // 做 PM IPC（getApplicationInfo）。卸载的应用由后台清理异步移除；清理到达前 UI 用
        // loadAppDrawable / getApplicationLabel 的兜底层显示（图标=本应用、标签=包名），不会崩。
        String def = LinkagePrefs.getDefault(activity);
        // 只有一个快捷应用时自动选它为默认（兼容旧数据：早先版本不会自动设默认）
        if (apps.size() == 1 && def.isEmpty()) {
            def = apps.get(0);
            LinkagePrefs.setDefault(activity, def);
        }
        // 内容指纹：配置与授权状态都没变就不重建。
        // 重建要移除/新建所有 Preference，并在主线程同步 getApplicationIcon（跨进程 + 解码），
        // 而 onResume 每次都会走到这里 —— 每次回前台都为一份没变的列表重做一遍纯属浪费。
        String sig = activity.privilegedAvailable + "|" + def + "|" + android.text.TextUtils.join(",", apps);
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
        Preference defPref = new Preference(activity);
        defPref.setKey(LINKAGE_KEY_DEFAULT);
        defPref.setTitle(R.string.linkage_default_title);
        defPref.setLayoutResource(R.layout.pref_row_icon_right);
        // 左侧不保留图标空位（图标在行尾；未设默认时右侧也不留空框）
        defPref.setIconSpaceReserved(false);
        defPref.setOnPreferenceClickListener(p -> {
            openDefaultPicker();
            return true;
        });
        CharSequence defLabel = def.isEmpty() ? activity.getString(R.string.linkage_default_none) : getApplicationLabel(def);
        defPref.setSummary(defLabel);
        defPref.setIcon(def.isEmpty() ? null : loadAppDrawableCached(def));
        cat.addPreference(defPref);

        // 2) 每个快捷应用一行：整行点击=快捷打开；长按=创建桌面快捷方式
        for (String pkg : apps) {
            LongClickPreference p = new LongClickPreference(activity);
            p.setKey(LINKAGE_APP_KEY_PREFIX + pkg);
            p.setLayoutResource(R.layout.pref_row);
            CharSequence label = getApplicationLabel(pkg);
            // 标题只显示应用名；它是否为「默认快捷应用」由上方「默认快捷应用」行统一体现，这里不再重复标注
            p.setTitle(label == null ? pkg : label);
            // 摘要改为操作提示，不再显示包名
            p.setSummary(R.string.quick_hint);
            p.setIcon(loadAppDrawableCached(pkg));
            p.setOnPreferenceClickListener(pv -> {
                if (!activity.privilegedAvailable) {
                    Toast.makeText(activity, R.string.auth_need_grant, Toast.LENGTH_SHORT).show();
                    return true;
                }
                activity.runLinkage(pkg, false);
                return true;
            });
            p.setOnRowLongClickListener(() -> {
                if (!activity.privilegedAvailable) {
                    Toast.makeText(activity, R.string.auth_need_grant, Toast.LENGTH_SHORT).show();
                    return true;
                }
                createDesktopShortcutFor(pkg);
                return true;
            });
            cat.addPreference(p);
        }

        // 3) 管理快捷应用行（打开多选弹窗，一次性增删多个 app）
        Preference manage = new Preference(activity);
        manage.setKey(LINKAGE_KEY_MANAGE);
        manage.setTitle(R.string.linkage_manage_title);
        manage.setLayoutResource(R.layout.pref_row);
        // 无图标行：不保留图标空位，与其它 pref_row 行左缘对齐（androidx 默认会保留约 50dp）
        manage.setIconSpaceReserved(false);
        manage.setSummary(apps.isEmpty()
                ? activity.getString(R.string.linkage_apps_empty)
                : activity.getString(R.string.linkage_manage_sum));
        manage.setOnPreferenceClickListener(p -> {
            openAppPicker();
            return true;
        });
        cat.addPreference(manage);

        // 联动项跟随授权可用性
        activity.setLinkageEnabled(activity.privilegedAvailable);
    }

    /** 打开联动应用选择器，并在选中后写回配置、刷新行与桌面快捷方式 */
    void openAppPicker() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastPickerOpenAt > 0 && now - lastPickerOpenAt < 500) {
            return; // 防抖：避免 ListView 行点击与 preference click 同时触发，弹出两个选择器
        }
        lastPickerOpenAt = now;
        // 多选：预勾已选应用，确认后整组写回（勾上=新增，取消=移除）
        List<String> current = LinkagePrefs.getApps(activity);
        AppListDialog.showMulti(activity, current, chosen -> {
            LinkagePrefs.setApps(activity, chosen);
            rebuildList();
            // 选完立刻刷新动态快捷方式（「启动联动应用」现取默认 app）
            ToggleShortcuts.markDirty(activity);
        });
    }

    /**
     * 「默认跳转应用」选择：列出当前联动应用，点选即设为默认跳转目标。
     * 开启「自动联动跳转」后必须设置它（决定启动本 App 时跳去哪个）。
     * 没添加任何联动应用时提示先去添加。
     */
    void openDefaultPicker() {
        List<String> apps = LinkagePrefs.getApps(activity);
        if (apps.isEmpty()) {
            Toast.makeText(activity, R.string.linkage_apps_empty_toast, Toast.LENGTH_LONG).show();
            return;
        }
        String cur = LinkagePrefs.getDefault(activity);
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
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.linkage_default_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> pick[0] = which)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (pick[0] < 0) {
                        return;
                    }
                    LinkagePrefs.setDefault(activity, apps.get(pick[0]));
                    rebuildList();
                    ToggleShortcuts.markDirty(activity);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 开启「快捷打开」后弹窗：告知如何绕过自动跳转、进入设置页（均为现有入口，不新增功能） */
    void showHintDialog() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.linkage_hint_title)
                .setMessage(R.string.linkage_hint_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    CharSequence getApplicationLabel(String pkg) {
        try {
            return activity.getPackageManager().getApplicationLabel(
                    activity.getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 启动目标应用；返回是否成功拉起（已卸载 / 已停用 / 无启动入口都会失败） */
    boolean launchApp(String pkg) {
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "launchApp failed: " + pkg, t);
            return false;
        }
    }

    /**
     * 快捷打开目标不可用（已卸载 / 已停用 / 无启动入口）的统一处理：
     * 已卸载的顺手从列表清掉（与 rebuildList 的自动清理一致），已停用的保留（可随时恢复）；
     * 两种情况都给出提示，避免「点了没反应」。
     */
    void handleTargetGone(String pkg) {
        if (isAppUninstalled(activity, pkg)) {
            List<String> apps = new ArrayList<>(LinkagePrefs.getApps(activity));
            if (apps.remove(pkg)) {
                LinkagePrefs.setApps(activity, apps);
                ToggleShortcuts.markDirty(activity);
            }
            Toast.makeText(activity, R.string.linkage_app_uninstalled, Toast.LENGTH_LONG).show();
            if (activity.prefsLoaded) {
                rebuildList();
            }
        } else {
            Toast.makeText(activity, R.string.linkage_app_unlaunchable, Toast.LENGTH_LONG).show();
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
     * 后台线程检测并清理已卸载的快捷应用（§2.5a）。
     * PM IPC（getApplicationInfo）在后台线程执行，不卡 UI；清理结果回主线程写配置 + 重建列表。
     * 节流（每 2s 最多一次）+ 生命周期守卫（Activity 销毁后不回主线程操作）。
     */
    void maybeCleanUninstalled() {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (CLEAN_LOCK) {
            if (now - lastCleanAt < 2000) {
                return;
            }
            lastCleanAt = now;
        }
        final List<String> apps = LinkagePrefs.getApps(activity);
        if (apps.isEmpty()) {
            return;
        }
        CLEAN_EXEC.execute(() -> {
            List<String> removed = new ArrayList<>();
            for (String p : apps) {
                if (isAppUninstalled(activity, p)) {
                    removed.add(p);
                }
            }
            if (removed.isEmpty()) {
                return;
            }
            final List<String> finalRemoved = new ArrayList<>(removed);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                for (String r : finalRemoved) {
                    iconCache.remove(r);
                }
                // 后台 PM IPC 耗时期间，用户可能已通过多选弹窗增删了快捷应用。
                // 若用调度时的旧快照 apps 回写，会把用户的新改动整体冲掉（数据丢失）。
                // 回写前重读最新配置，只从最新列表里剔除已卸载项。
                List<String> alive = new ArrayList<>(LinkagePrefs.getApps(activity));
                alive.removeAll(finalRemoved);
                LinkagePrefs.setApps(activity, alive);
                ToggleShortcuts.markDirty(activity); // 默认目标可能随之清掉，动态快捷方式同步
                if (activity.prefsLoaded) {
                    rebuildList();
                }
                Toast.makeText(activity, R.string.linkage_app_uninstalled, Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 取目标应用图标（Drawable，供 Preference 左侧显示）；失败回退本应用图标 */
    private Drawable loadAppDrawable(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return ContextCompat.getDrawable(activity, R.mipmap.ic_launcher);
        }
    }

    /**
     * 取联动应用图标（带内存缓存）：优先命中 {@link #iconCache}，命中时经 ConstantState
     * 克隆一个新 Drawable 实例（共享底层位图、不再跨进程解码），未命中才走 {@link #loadAppDrawable}
     * 解码并缓存。目的：rebuildList 每次回前台都会重建列表，未变化的包不必重复
     * getApplicationIcon（IPC + 位图解码），消除 onResume 的同步卡顿；同时返回独立实例，
     * 避免同一 Drawable 被「默认快捷应用行」与某条 app 行同时绑定带来状态串扰。
     */
    private Drawable loadAppDrawableCached(String pkg) {
        Drawable cached = iconCache.get(pkg);
        if (cached != null) {
            Drawable.ConstantState cs = cached.getConstantState();
            return cs != null ? cs.newDrawable() : cached;
        }
        Drawable d = loadAppDrawable(pkg);
        iconCache.put(pkg, d);
        return d;
    }

    /**
     * 为某个联动应用创建「原生桌面快捷方式」：在桌面（主屏幕）生成一个真实图标，点击直接关调试并联动启动该应用。
     * 与 ShortcutManager 的「Shortcut」（长按菜单项）不同，这里用的是
     * Android 自 1.6/2.3 起就有的「添加到主屏幕」机制（见 requestPinShortcut）。
     *
     * @param pkg 要创建快捷方式的联动应用包名（每个 app 的桌面图标各不相同，故意图里携带包名）
     */
    void createDesktopShortcutFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return; // 行点击传入的包名恒非空（缺包名场景已废弃），此守卫只防编程错误
        }
        CharSequence label = getApplicationLabel(pkg);
        // 关键：快捷方式必须先打开「本 App」，由 App 关掉调试再联动启动目标应用，
        // 而不是直接拉起目标 app（那样会绕过关调试的联动逻辑，等于什么都不做）。
        // 用显式 Intent 指向 MainActivity + 自定义 action，并把包名带进去，
        // 图标仍用「目标应用 + 本 App 角标」以便一眼认出这是哪个联动应用的快捷方式。
        Intent launch = new Intent(activity, MainActivity.class);
        launch.setAction(MainActivity.ACTION_DESKTOP_LINKAGE);
        launch.putExtra(MainActivity.EXTRA_LINKAGE_PKG, pkg);
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
            activity.sendBroadcast(intent);
            // 两条路径都拿不到「是否真的落到桌面」的真实结果，只能提示已发起
            Toast.makeText(activity, R.string.create_shortcut_done, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // 旧广播路径基本不会抛异常；真抛了只记日志（创建失败与否无从得知）
            android.util.Log.w(TAG, "INSTALL_SHORTCUT broadcast failed", t);
        }
    }

    /** Android 8+：用 ShortcutManager.requestPinShortcut 把联动应用钉选为桌面图标
     *  （区别于 shortcuts.xml 里的长按菜单「Shortcut」，这里生成的是真实桌面图标），
     *  返回是否成功发出请求。 */
    private boolean requestPinShortcut(CharSequence label, Intent launch, Bitmap icon) {
        ShortcutManager sm = (ShortcutManager) activity.getSystemService(Context.SHORTCUT_SERVICE);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            return false;
        }
        ShortcutInfo.Builder b = new ShortcutInfo.Builder(activity,
                "desktop_linkage_" + System.currentTimeMillis());
        b.setIntent(launch);
        b.setShortLabel(label != null ? label : activity.getString(R.string.app_name));
        if (icon != null) {
            b.setIcon(Icon.createWithBitmap(icon));
        }
        try {
            // 结果回调：按官方契约，只有真正钉上时才会收到（PendingIntent 按官方样例用
            // FLAG_IMMUTABLE）；取消/被拒不回调，故 arm() 挂超时兜底提示。
            Intent cb = new Intent(activity, PinShortcutReceiver.class);
            PendingIntent result = PendingIntent.getBroadcast(activity, 0, cb,
                    PendingIntent.FLAG_IMMUTABLE);
            // 先登记再请求：桌面回调可能同步到达，晚登记会 FIFO 空消费 → 5s 后误报失败
            int token = PinShortcutReceiver.arm(activity);
            boolean accepted = false;
            try {
                accepted = sm.requestPinShortcut(b.build(), result.getIntentSender());
            } finally {
                if (!accepted) {
                    PinShortcutReceiver.disarm(token);
                }
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
        int size = Math.max(1, (int) (48 * activity.getResources().getDisplayMetrics().density));
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // 底层：联动应用图标
        Drawable base;
        try {
            base = activity.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            base = ContextCompat.getDrawable(activity, R.mipmap.ic_launcher);
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

        Drawable mine = ContextCompat.getDrawable(activity, R.drawable.ic_shortcut_badge);
        mine.setBounds(left, top, left + badge, top + badge);
        mine.draw(c);

        return out;
    }
}