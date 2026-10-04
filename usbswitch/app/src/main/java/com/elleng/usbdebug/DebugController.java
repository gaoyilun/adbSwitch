package com.elleng.usbdebug;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.elleng.usbdebug.util.RootUtil;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 调试开关的唯一状态层与唯一写入口。
 *
 * 背景：Android 17 起，RASP（如 HSBC）不只看 adb_enabled，还会看 init.svc.adbd ——
 * 只要 adbd 在跑就会被判定为调试态。而 adbd 在「USB 调试」或「无线调试」任一开启时都会运行，
 * 所以本应用必须同时管理两个 Settings 键：
 *   - Settings.Global.adb_enabled        （USB 调试）
 *   - Settings.Global.adb_wifi_enabled   （无线调试）
 *
 * 设计要点（这是整个 App 唯一允许改写这两个键的地方）：
 *  1. 真值只有一个来源：每次都回读 Settings，不缓存、不猜。
 *  2. 主开关语义：「开」= 任一开着（adbd 在跑），「关」= 两个都关（真正安全）。
 *  3. 一键关闭：先把当前组合写进 saved，再把两个都写 0；
 *     一键打开：从 saved 还原原组合（这就是「记住两个开关状态」）。
 *  4. 子开关单独拨：只改对应那一个键，不动 saved —— 用户手动调的不该被"记忆"覆盖。
 *  5. 通知 / LiveUpdate / 磁贴 / 联动 / 主界面全部只调用本类，不再各自判断要不要管无线。
 */
public final class DebugController {

    private static final String TAG = "DebugController";

    /** 状态变化广播（setPackage 限定本应用） */
    public static final String ACTION_STATE = "com.elleng.usbdebug.ACTION_STATE";
    public static final String EXTRA_USB = "extra_usb";
    public static final String EXTRA_WIRELESS = "extra_wireless";
    /** 聚合状态：usb || wireless，即 adbd 是否在跑 */
    public static final String EXTRA_ON = "extra_on";

    private static final String PREFS = "cfg";
    private static final String SAVED_USB = "saved_usb";
    private static final String SAVED_WIRELESS = "saved_wireless";
    private static final String SAVED_VALID = "saved_valid";
    /** 「打开调试前先杀掉联动应用」开关（与 MainActivity 共用同一份 cfg） */
    private static final String PREF_KILL_BEFORE_OPEN = "kill_before_open";
    private static final String PREF_PKG = "linkage_pkg";

    private DebugController() {
    }

    /** 两个开关的组合状态 */
    public static final class State {
        public final boolean usb;
        public final boolean wireless;

        State(boolean usb, boolean wireless) {
            this.usb = usb;
            this.wireless = wireless;
        }

        /** 任一开着 → adbd 在跑 → 会被检测为调试态 */
        public boolean anyOn() {
            return usb || wireless;
        }

        /** 两个都关 → adbd stopped → 通过检测 */
        public boolean allOff() {
            return !usb && !wireless;
        }
    }

    // —— 读 ——

    /**
     * 读取不需要 root：Settings.Global 是全局可读表，我们用 su 写的就是这张表，
     * ContentResolver 直读与 su 读取得到同一个值。之前每次 read() fork 两个 su 进程，
     * 又被主线程高频调用（onResume / 每次布局完成 / ContentObserver 去抖），
     * 慢设备或 root 授权挂起时会卡 UI 甚至 ANR。写仍是唯一需要 su 的地方。
     */
    public static State read(Context ctx) {
        return new State(readUsb(ctx), readWireless(ctx));
    }

    static boolean readUsb(Context ctx) {
        return getInt(ctx, "adb_enabled") == 1;
    }

    static boolean readWireless(Context ctx) {
        return getInt(ctx, "adb_wifi_enabled") == 1;
    }

    private static int getInt(Context ctx, String key) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            // 读不到（键不存在/Provider 异常）按「未开启」处理；真值会在下一次刷新回读纠正
            android.util.Log.w(TAG, "read " + key + " failed", t);
            return -1;
        }
    }

    /**
     * 「打开调试前先杀掉联动应用」。
     *
     * 关键在顺序：**先杀 app，再开调试**。
     * 反过来的话，联动应用在调试刚开启、自身还活着的瞬间就能读到 adb 状态并弹检测提示；
     * 先 force-stop 掉它，等下次冷启动时调试虽已开启，但少了「运行中感知到状态翻转」这条路径。
     *
     * 只在 opening==true 时执行，关闭调试不需要杀。
     */
    private static void killLinkageAppIfNeeded(Context ctx, boolean opening) {
        if (!opening) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(PREF_KILL_BEFORE_OPEN, false)) {
            return;
        }
        String pkg = sp.getString(PREF_PKG, "");
        if (pkg.isEmpty()) {
            return;
        }
        // am force-stop 走系统正常的停止流程（比 kill 更干净，不会留下半死的进程状态）
        android.util.Log.i(TAG, "kill before open: force-stop " + pkg);
        RootUtil.su("am force-stop " + pkg);
    }

    // —— 写（底层，仅本类内部调用）——

    private static boolean writeUsb(boolean on) {
        return RootUtil.su("settings put global adb_enabled " + (on ? "1" : "0")).ok();
    }

    private static boolean writeWireless(boolean on) {
        return RootUtil.su("settings put global adb_wifi_enabled " + (on ? "1" : "0")).ok();
    }

    // —— 对外写入口（全部异步：su 可能阻塞数百毫秒甚至因授权弹窗挂住，禁止在主线程执行）——

    public interface ResultCallback {
        /** ok=写入是否全部成功；state=写入后回读到的最新真实状态（主线程回调） */
        void onResult(boolean ok, State state);
    }

    private static final Executor IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 一键开关（主开关）。
     *
     * @param on true=打开（还原记忆的组合）；false=关闭（记忆当前组合并把两个都关掉）
     */
    public static void setMaster(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            State cur = read(app);
            boolean ok;
            if (on) {
                killLinkageAppIfNeeded(app, true); // 顺序：先杀联动应用，再开调试
                State target = loadSaved(app);
                boolean a = writeUsb(target.usb);
                boolean b = writeWireless(target.wireless);
                ok = a && b;
            } else {
                // 只在确实开着时记忆：避免把「两个都关」这种无效组合写进 saved
                if (cur.anyOn()) {
                    save(app, cur);
                }
                boolean a = writeUsb(false);
                boolean b = writeWireless(false);
                ok = a && b;
            }
            State after = broadcast(app);
            if (cb != null) {
                MAIN.post(() -> cb.onResult(ok, after));
            }
        });
    }

    /** 单独改 USB 调试（不动 saved） */
    public static void setUsb(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            killLinkageAppIfNeeded(app, on);
            boolean ok = writeUsb(on);
            State after = broadcast(app);
            if (cb != null) {
                MAIN.post(() -> cb.onResult(ok, after));
            }
        });
    }

    /** 单独改无线调试（不动 saved） */
    public static void setWireless(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            killLinkageAppIfNeeded(app, on);
            boolean ok = writeWireless(on);
            State after = broadcast(app);
            if (cb != null) {
                MAIN.post(() -> cb.onResult(ok, after));
            }
        });
    }

    // —— 记忆 ——

    private static void save(Context ctx, State s) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(SAVED_USB, s.usb)
                .putBoolean(SAVED_WIRELESS, s.wireless)
                .putBoolean(SAVED_VALID, true)
                .apply();
    }

    /** 取记忆的组合；没有有效记忆时回退 USB 开、无线关 */
    private static State loadSaved(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(SAVED_VALID, false)) {
            return new State(sp.getBoolean(SAVED_USB, true),
                    sp.getBoolean(SAVED_WIRELESS, false));
        }
        return new State(true, false);
    }

    // —— 广播 ——

    /** 广播当前完整状态（读真值，不传参，保证各消费者看到的都是最新），并把状态回传给调用方 */
    public static State broadcast(Context ctx) {
        State s = read(ctx);
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(ctx.getPackageName());
        i.putExtra(EXTRA_USB, s.usb);
        i.putExtra(EXTRA_WIRELESS, s.wireless);
        i.putExtra(EXTRA_ON, s.anyOn());
        ctx.sendBroadcast(i);
        return s;
    }
}
