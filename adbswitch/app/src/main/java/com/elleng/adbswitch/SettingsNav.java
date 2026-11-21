package com.elleng.adbswitch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import dalvik.system.PathClassLoader;

/**
 * 系统设置深链导航：点击主界面调试三行的「›」箭头时跳转对应设置项。
 *
 * 真机验证结论（Redmi K90 / HyperOS、荣耀 Magic9 Pro Max / MagicOS 11 各测过）：
 *  - 开发者选项 / 关于本机：系统通用 Action，全 ROM 可用。
 *  - USB 调试高亮键：HyperOS 与荣耀都用 {@code enable_adb}（AOSP 的 {@code adb_preference} 在这两家均无命中）；
 *    该 extra 仅负责滚动定位，AOSP 的键在 OEM ROM 上静默失效（只打开开发者选项、不滚动）。
 *  - 无线调试片段：HyperOS 用 {@code AdbWirelessDebuggingFragment}、AOSP 用 {@code WirelessDebuggingFragment}；
 *    荣耀虽存在前者，但外部 {@code :settings:show_fragment} 被 Settings 进程内片段白名单拦截
 *    （root 上下文实测仍回退到开发者选项主页），故荣耀无线调试无法深链到独立页，只能回退开发者选项 + 引导。
 *
 * 兼容策略（分两种判定，别混用）：
 *  1. **能探事实就探事实**（无线调试片段类）：按 Settings 包里实际存在的类名选，
 *     两个已知命名都不存在（ColorOS / OriginOS 等自研命名）就回退开发者选项 + 引导，
 *     绝不把 {@code :settings:show_fragment} 指向不存在的类（那会落到空白页）。
 *  2. **探不到才按厂商映射**（USB 高亮的偏好键）：偏好键是数据不是类，无法探测；
 *     且猜错的代价仅是「不滚动高亮」，页面照常打开不会崩，故按厂商名映射，
 *     未知厂商统一给 AOSP 的 {@code adb_preference}。
 *  落空时统一退到「开发者选项」+ 引导 Toast。开发者选项入口未开启时，
 *  若本 App 有可用授权通道（WSS/root/Shizuku）则自行开启入口再跳转；仅当确实开不了
 *  （无任何通道 / 写入被拒）才回退「关于本机」+ Toast（提示连点版本号开启）。
 */
public final class SettingsNav {

    private SettingsNav() {}

    /** 跳转目标 */
    public enum Target { USB, WIRELESS, DEV_OPTIONS, A11Y }

    private static final String ACTION_DEV = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;
    private static final String ACTION_ABOUT = Settings.ACTION_DEVICE_INFO_SETTINGS;

    private static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
    private static final String EXTRA_SHOW_FRAGMENT = ":settings:show_fragment";

    // USB 调试偏好键
    private static final String KEY_USB_OEM = "enable_adb";   // HyperOS / 荣耀
    private static final String KEY_USB_AOSP = "adb_preference"; // AOSP / 其它

    // 无线调试片段类：小米 / 部分 HyperOS 用 AdbWirelessDebuggingFragment，
    // 其余（AOSP / 老 HyperOS，如小米 14）用 WirelessDebuggingFragment。
    private static final String FRAG_WIRELESS_OEM = "com.android.settings.development.AdbWirelessDebuggingFragment"; // 小米命名
    private static final String FRAG_WIRELESS_AOSP = "com.android.settings.development.WirelessDebuggingFragment";  // AOSP 命名

    // 承载 :settings:show_fragment 的宿主 Activity。必须用全类名
    // （com.android.settings.SubSettings），相对写法 ".SubSettings" 在部分 ROM 上会被其它
    // App 的 intent-filter 截胡，导致跳转到一个空页面（已在 K90 实测：相对名落到 openwith，
    // 全类名才正常进入无线调试页）。
    private static final String PKG_SETTINGS = "com.android.settings";
    private static final String HOST_SUBSETTINGS = "com.android.settings.SubSettings";

    /** 进程内缓存：已解析出的无线调试片段类名（按设备只解析一次） */
    private static String sWirelessFragment;

    private static final int FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;

    /** 是否小米 / HyperOS：USB 用 enable_adb、无线可直接深链 */
    private static boolean isXiaomi() {
        String m = safeLower(Build.MANUFACTURER);
        String b = safeLower(Build.BRAND);
        return m.contains("xiaomi") || b.contains("xiaomi");
    }

    /** 是否荣耀：USB 用 enable_adb；无线无法深链（白名单拦截），走回退 */
    private static boolean isHonor() {
        String m = safeLower(Build.MANUFACTURER);
        String b = safeLower(Build.BRAND);
        return m.contains("honor") || b.contains("honor");
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    /**
     * 统一入口：开发者选项入口未开启时，不再默认回退「关于本机 + Toast」——
     * 本 App 在 WSS / root / Shizuku 任一通道可用时能自行把开发者选项入口开启，
     * 故先尝试开启再跳转对应设置项；只有确实开不了（无可用授权通道 / 写入被拒）才回退关于本机 + 引导。
     * 调用方需传 Activity 或带 NEW_TASK 能力的 Context。
     *
     * 门控数据源必须用权威校准状态（{@link DebugController#read} 返回的内存缓存，
     * 与界面显示一致），不能用 {@link DebugController#readDev} —— 后者是未授权直读
     * Settings.Global，在 HyperOS / 荣耀等 ROM 上读不到真值（恒返回未开启），
     * 会导致「界面显示已开启、点箭头却跳关于本机」的矛盾。
     */
    public static void navigate(Context ctx, Target target) {
        // 无障碍设置是独立设置页，不依赖开发者选项入口：直接跳，不经 dev 门控
        if (target == Target.A11Y) {
            openAccessibility(ctx);
            return;
        }
        if (DebugController.read(ctx).dev) {
            openTarget(ctx, target);
            return;
        }
        // 入口未开：进该页需要先开启开发者选项入口（写 development_settings_enabled=1，
        // 这是本 App 明确管理的三个状态键之一，属未经用户明确确认的状态变更）。
        // §4.3：先给一次轻确认，确认后才尝试开启并跳转；确认前不做任何状态写入。
        // 仅当调用方是 Activity 时才弹确认框；非 Activity 上下文（理论上不会走到）退化为直接执行。
        if (ctx instanceof Activity) {
            new MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.nav_confirm_title)
                    .setMessage(R.string.nav_confirm_msg)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> enableDevAndOpen(ctx, target))
                    .show();
        } else {
            enableDevAndOpen(ctx, target);
        }
    }

    /** 开启开发者选项入口（写入失败则回退到关于本机 + 引导）后再跳转对应设置项 */
    private static void enableDevAndOpen(Context ctx, Target target) {
        DebugController.setDev(ctx, true, (ok, after, reason) -> {
            if (ok && after != null && after.dev) {
                openTarget(ctx, target);
            } else {
                fallbackAbout(ctx);
            }
        });
    }

    /** 按目标跳转对应设置项（入口已确认开启时调用） */
    private static void openTarget(Context ctx, Target target) {
        switch (target) {
            case USB:
                openUsb(ctx);
                break;
            case WIRELESS:
                openWireless(ctx);
                break;
            case DEV_OPTIONS:
                openDevOptions(ctx);
                break;
            case A11Y:
                openAccessibility(ctx);
                break;
        }
    }

    /** 无障碍设置：通用 Action，全 ROM 可用，不需要开发者选项入口 */
    public static void openAccessibility(Context ctx) {
        Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        it.addFlags(FLAGS);
        startSafely(ctx, it);
    }

    /** 开不了开发者选项入口时的回退：跳关于本机并提示连点版本号开启 */
    private static void fallbackAbout(Context ctx) {
        openAbout(ctx);
        Toast.makeText(ctx, R.string.toast_devopts_off_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * USB 调试：跳转开发者选项并滚动/高亮到「USB 调试」项。
     *
     * 这里保留厂商判断是有意的：偏好键名是数据、无法像片段类那样探测，而猜错的代价
     * 仅是「不滚动/不高亮」，页面仍会正常打开，不会崩也不会落空白页。
     * 故 HyperOS / 荣耀给 OEM 键 {@code enable_adb}，其余（含 OPPO / vivo 等未列出的厂商）
     * 给 AOSP 键 {@code adb_preference}。
     */
    public static void openUsb(Context ctx) {
        String key = (isXiaomi() || isHonor()) ? KEY_USB_OEM : KEY_USB_AOSP;
        Intent it = new Intent(ACTION_DEV);
        it.putExtra(EXTRA_FRAGMENT_ARG_KEY, key);
        it.addFlags(FLAGS);
        startSafely(ctx, it);
    }

    /** 开发者选项：跳转开发者选项主页 */
    public static void openDevOptions(Context ctx) {
        Intent it = new Intent(ACTION_DEV);
        it.addFlags(FLAGS);
        startSafely(ctx, it);
    }

    /** 关于本机：通用 Action，全 ROM 可用 */
    public static void openAbout(Context ctx) {
        Intent it = new Intent(ACTION_ABOUT);
        it.addFlags(FLAGS);
        startSafely(ctx, it);
    }

    /** 无线调试：先探测本机确实存在哪个片段类，存在才深链，否则回退开发者选项 + 引导 */
    public static void openWireless(Context ctx) {
        if (isHonor()) {
            // 荣耀：片段类存在，但外部 :settings:show_fragment 被 Settings 进程内白名单拦截
            // （root 上下文实测只回到开发者选项主页）——这是系统侧限制，不是命名问题，
            // 故直接回退并给引导，省掉一次必然落空的跳转
            openDevOptions(ctx);
            Toast.makeText(ctx, R.string.toast_wireless_fallback, Toast.LENGTH_LONG).show();
            return;
        }
        // 事实优先：Settings 包里存在哪个无线调试片段类，才用哪个名字深链。
        // 两个都不存在（ColorOS / OriginOS 等自研命名）就走回退，绝不把 show_fragment
        // 指向一个不存在的类 —— 那会落到 SubSettings 的空白页。
        String frag = existingWirelessFragment(ctx);
        if (frag == null) {
            android.util.Log.i("AdbSwitch", "no known wireless fragment in Settings; fallback");
            openDevOptions(ctx);
            Toast.makeText(ctx, R.string.toast_wireless_fallback, Toast.LENGTH_LONG).show();
            return;
        }
        // SubSettings 宿主 + 对应片段，全类名避免被其它 App 的 intent-filter 截胡
        Intent it = new Intent();
        it.setClassName(PKG_SETTINGS, HOST_SUBSETTINGS);
        it.putExtra(EXTRA_SHOW_FRAGMENT, frag);
        it.addFlags(FLAGS);
        startSafely(ctx, it);
    }

    /**
     * 统一跳转保护：ROM 移除目标组件 / 权限不足时 startActivity 会抛
     * ActivityNotFoundException / SecurityException，裸调用直接崩在设置页点击上。
     * 异步回调（enableDevAndOpen）到达时宿主可能已销毁，先做存活检查。
     * 失败回退「关于本机」（通用 Action）+ 提示；回退也失败则只提示。
     */
    private static void startSafely(Context ctx, Intent it) {
        if (ctx instanceof Activity) {
            Activity a = (Activity) ctx;
            if (a.isFinishing() || a.isDestroyed()) {
                return;
            }
        }
        try {
            ctx.startActivity(it);
        } catch (Throwable t) {
            android.util.Log.w("AdbSwitch", "settings nav failed: " + it, t);
            try {
                Intent about = new Intent(ACTION_ABOUT);
                about.addFlags(FLAGS);
                ctx.startActivity(about);
            } catch (Throwable ignored) {
            }
            Toast.makeText(ctx, R.string.toast_nav_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 解析本机真实存在的无线调试片段类名；两个已知命名都不存在时返回 null（调用方回退）。
     *
     * 判定规则按**事实**而不是按厂商：Settings 包里若包含小米命名的
     * {@link #FRAG_WIRELESS_OEM}（AdbWirelessDebuggingFragment），则用它（该命名才是真实可达的片段，
     * 如 Redmi K90 / Android 17 HyperOS）；否则看 AOSP 命名 {@link #FRAG_WIRELESS_AOSP} 是否存在
     * （小米 14 / HyperOS OS3、多数 AOSP 派生 ROM）；都不存在（ColorOS / OriginOS 等自研命名）
     * 返回 null，由调用方回退开发者选项 —— 这样新增厂商不会因为「不在白名单里」而落到空白页。
     */
    private static String existingWirelessFragment(Context ctx) {
        if (sWirelessFragment != null) {
            return sWirelessFragment;
        }
        if (settingsHasClass(ctx, FRAG_WIRELESS_OEM)) {
            sWirelessFragment = FRAG_WIRELESS_OEM;
        } else if (settingsHasClass(ctx, FRAG_WIRELESS_AOSP)) {
            sWirelessFragment = FRAG_WIRELESS_AOSP;
        } else {
            return null; // 不缓存：不同 ROM 可能装了两个都没有的 Settings，不写成空串更易读
        }
        return sWirelessFragment;
    }

    /** Settings 包里是否存在指定类（用 PathClassLoader 探测，命中即视为存在） */
    private static boolean settingsHasClass(Context ctx, String className) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(PKG_SETTINGS, 0);
            PathClassLoader cl = new PathClassLoader(ai.publicSourceDir, ClassLoader.getSystemClassLoader());
            cl.loadClass(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false; // 该类确实不存在
        } catch (PackageManager.NameNotFoundException e) {
            return false; // Settings 不存在（理论上不可能）
        } catch (LinkageError e) {
            // 类存在于 dex 但链接失败（依赖缺失等，含 VerifyError 子类）—— 名称存在即视为存在
            return true;
        } catch (Throwable t) {
            return false; // 其它异常统一按缺失处理，回退 AOSP 命名
        }
    }
}
