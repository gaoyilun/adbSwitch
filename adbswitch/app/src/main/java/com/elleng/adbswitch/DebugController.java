package com.elleng.adbswitch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.elleng.adbswitch.util.RootUtil;

import java.util.List;
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
    public static final String ACTION_STATE = "com.elleng.adbswitch.ACTION_STATE";
    public static final String EXTRA_USB = "extra_usb";
    public static final String EXTRA_WIRELESS = "extra_wireless";
    /** 聚合状态：usb || wireless，即 adbd 是否在跑 */
    public static final String EXTRA_ON = "extra_on";

    /** 两个被管理的 Settings.Global 键（监听 URI 与读写都复用，避免散落字符串字面量） */
    public static final String KEY_USB = "adb_enabled";
    public static final String KEY_WIRELESS = "adb_wifi_enabled";

    /** 合并读取时两个键的输出标记（一次 shell 取回两个值，按标记行定位，不靠行号） */
    private static final String TAG_USB_OUT = "__ASW_USB__";
    private static final String TAG_WIRELESS_OUT = "__ASW_WIFI__";

    private static final String PREFS = "cfg";
    private static final String SAVED_USB = "saved_usb";
    private static final String SAVED_WIRELESS = "saved_wireless";
    private static final String SAVED_VALID = "saved_valid";
    /** 「开启调试自动关闭快捷应用」开关（与 MainActivity 共用同一份 cfg） */
    private static final String PREF_KILL_BEFORE_OPEN = "kill_before_open";

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

    /** 缓存的真值（由后台经授权通道校准） */
    private static volatile State cachedState = null;
    private static volatile long cachedAt = 0;
    private static volatile long lastRefreshReqAt = 0;
    /** 缓存有效期：超过则后台重新校准 */
    private static final long STALE_MS = 5000;
    /** 后台校准的最小间隔（read() 被高频调用，必须节流） */
    private static final long REFRESH_MIN_INTERVAL = 1500;

    /**
     * 读取状态：返回缓存的真值；缓存为空时先用 ContentResolver 快速读一次并触发后台校准。
     *
     * 关键背景（曾经踩的坑）：本 App 的写入走 su / Shizuku shell 通道，而部分 ROM
     * （如 HyperOS）会向第三方 App 隐藏 adb_enabled / adb_wifi_enabled ——
     * 实测 shell 读到 1，App 用 ContentResolver 却读到 0，导致总开关状态一直是错的。
     * 因此真值必须与「写入」走同一个通道（{@link #readViaPrivileged}），读写同源才不会打架。
     *
     * 但 read() 会被主线程高频调用（onResume / 每次布局完成 / ContentObserver 去抖），
     * 这里绝不能 fork 进程，否则慢设备或授权挂起时会卡 UI 甚至 ANR（这是历史教训）。
     * 故：同步只返回缓存，另由 {@link #triggerRefresh} 在后台校准并广播刷新 UI。
     */
    public static State read(Context ctx) {
        State c = cachedState;
        if (c == null) {
            // 首次：ContentResolver 快读兜底（该来源会被 ROM 隐藏而失真），
            // 故把 cachedAt 置 0 标记为「未校准」，让下面的 triggerRefresh 立刻用授权通道纠正，
            // 否则会被误判为缓存新鲜而永远不校准（实测踩过：总开关一直停在错误状态）。
            c = new State(readUsb(ctx), readWireless(ctx));
            cachedState = c;
            cachedAt = 0;
        }
        triggerRefresh(ctx);
        return c;
    }

    /** 后台用授权通道校准真值并广播（主线程安全，内部已节流） */
    public static void triggerRefresh(Context ctx) {
        final Context app = ctx.getApplicationContext();
        long now = android.os.SystemClock.uptimeMillis();
        // cachedAt==0 表示缓存尚未经授权通道校准（ContentResolver 兜底值），必须立即校准
        if (cachedAt != 0 && now - cachedAt < STALE_MS) {
            return; // 已校准且仍新鲜
        }
        if (now - lastRefreshReqAt < REFRESH_MIN_INTERVAL) {
            return; // 节流
        }
        lastRefreshReqAt = now;
        IO.execute(() -> {
            State s = readViaPrivileged(app);
            if (s != null) {
                cachedState = s;
                cachedAt = android.os.SystemClock.uptimeMillis();
                broadcast(app);
            }
        });
    }

    /**
     * 外部改动感知：把缓存标记为「未校准」并立刻后台重新校准。
     * 用于 ContentObserver 回调 —— 此时回读拿到的仍是缓存（且本 ROM 向 App 隐藏真值），
     * 不作废就永远刷不出外部改动。校准完成后会广播，UI 由各自的 stateReceiver 更新。
     */
    public static void invalidateAndRefresh(Context ctx) {
        cachedAt = 0;
        lastRefreshReqAt = 0; // 外部改动优先级最高，不受节流挡住
        triggerRefresh(ctx);
    }

    /**
     * 写完后用授权通道回读真值并刷新缓存（仅可在后台线程调用）。
     *
     * 必须显式调用：broadcast() 内部走的是 read()，而 read() 只返回缓存；
     * 若写完不刷新，广播出去的就是写入前的旧状态 —— 表现为「实际已关闭，界面仍显示开启」。
     */
    private static void refreshCacheFromPrivileged(Context app) {
        State s = readViaPrivileged(app);
        if (s != null) {
            cachedState = s;
            cachedAt = android.os.SystemClock.uptimeMillis();
        }
    }

    /** 真值是否已由授权通道确认过（即缓存可信）。UI 据此决定能不能给出安全结论。 */
    public static boolean isCalibrated() {
        return cachedAt != 0;
    }

    /**
     * 用与写入相同的通道（su / Shizuku shell）读取真值。
     * 阻塞调用，只允许在后台线程使用（写入流程与后台校准都在 IO 线程）。
     *
     * 两个键合并成**一条** shell 命令读取：原来要 fork 两次 su，而每次 fork 在 root 模式下
     * 是 100~500ms 的真实等待，一次开关要读写三回就是 6 次。带标记行输出，即使 ROM 的 shell
     * 掺入额外噪声行也能准确定位，不靠行号硬取。
     *
     * @return 读不到有效值时返回 null（调用方保留旧缓存，不覆盖为真假难辨的状态）
     */
    private static State readViaPrivileged(Context app) {
        RootUtil.Result r = runPrivileged(app,
                "echo " + TAG_USB_OUT + "=$(settings get global " + KEY_USB + ");"
                        + " echo " + TAG_WIRELESS_OUT + "=$(settings get global " + KEY_WIRELESS + ")");
        String out = (r.out == null ? "" : r.out);
        String u = null;
        String w = null;
        for (String line : out.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.startsWith(TAG_USB_OUT + "=")) {
                u = s.substring(TAG_USB_OUT.length() + 1).trim();
            } else if (s.startsWith(TAG_WIRELESS_OUT + "=")) {
                w = s.substring(TAG_WIRELESS_OUT.length() + 1).trim();
            }
        }
        if (!("0".equals(u) || "1".equals(u)) || !("0".equals(w) || "1".equals(w))) {
            android.util.Log.w(TAG, "privileged read got invalid values: usb=[" + u
                    + "] wireless=[" + w + "] (code=" + r.code + ", raw=[" + out.trim() + "])");
            return null;
        }
        return new State("1".equals(u), "1".equals(w));
    }

    static boolean readUsb(Context ctx) {
        return getInt(ctx, KEY_USB) == 1;
    }

    static boolean readWireless(Context ctx) {
        return getInt(ctx, KEY_WIRELESS) == 1;
    }

    private static int getInt(Context ctx, String key) {
        try {
            int v = Settings.Global.getInt(ctx.getContentResolver(), key);
            // 诊断：记录 app 视角读到的值，用于区分「读不到」与「写不进」
            android.util.Log.i(TAG, "read " + key + " = " + v);
            return v;
        } catch (Throwable t) {
            // 读不到（键不存在/Provider 异常）按「未开启」处理；真值会在下一次刷新回读纠正
            android.util.Log.w(TAG, "read " + key + " failed", t);
            return -1;
        }
    }

    /**
     * 「开启调试自动关闭快捷应用」。
     *
     * 关键在顺序：**先杀 app，再开调试**。
     * 反过来的话，快捷应用在调试刚开启、自身还活着的瞬间就能读到 adb 状态并弹检测提示；
     * 先 force-stop 掉它，等下次冷启动时调试虽已开启，但少了「运行中感知到状态翻转」这条路径。
     *
     * 只在 opening==true 时执行，关闭调试不需要杀。
     */
    private static void killQuickAppsIfNeeded(Context ctx, boolean opening) {
        if (!opening) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(PREF_KILL_BEFORE_OPEN, false)) {
            return;
        }
        List<String> apps = LinkagePrefs.getApps(ctx);
        if (apps.isEmpty()) {
            return;
        }
        // am force-stop 走系统正常的停止流程（比 kill 更干净，不会留下半死的进程状态）。
        // 双模式：按当前授权模式选执行通道（Shizuku 走 shell uid 2000，否则走 su）。
        // 多个快捷应用：逐一停止，避免任一在调试开启瞬间读到状态翻转。
        // 但合并成**一条**命令执行 —— 每个 app 单独 fork 一次 su 会让「开启调试」慢上好几倍。
        StringBuilder cmd = new StringBuilder();
        for (String pkg : apps) {
            if (cmd.length() > 0) {
                cmd.append("; ");
            }
            cmd.append("am force-stop ").append(pkg);
        }
        android.util.Log.i(TAG, "kill before open: " + cmd);
        runPrivileged(ctx, cmd.toString());
    }

    // —— 写（底层，仅本类内部调用）——

    private static boolean writeUsb(Context ctx, boolean on) {
        return runPrivileged(ctx, "settings put global " + KEY_USB + " " + (on ? "1" : "0")).ok();
    }

    private static boolean writeWireless(Context ctx, boolean on) {
        return runPrivileged(ctx, "settings put global " + KEY_WIRELESS + " " + (on ? "1" : "0")).ok();
    }

    /**
     * 一次 shell 同时写两个键（主开关专用）。
     *
     * 分开写要 fork 两次 su，而 root 模式下每次 fork 都是 100~500ms 的真实等待；
     * 一次开关原本要 fork 六次（读2+写2+回读2），合并后降到三次，响应快一倍以上。
     * 用 ";" 而非 "&&"：即使第一个键写失败也要把第二个写完，最终真值由回读统一确认。
     */
    private static boolean writeBoth(Context ctx, boolean usb, boolean wireless) {
        return runPrivileged(ctx,
                "settings put global " + KEY_USB + " " + (usb ? "1" : "0")
                        + "; settings put global " + KEY_WIRELESS + " " + (wireless ? "1" : "0"))
                .ok();
    }

    /** 按当前授权模式选执行通道：Shizuku 走 shell(uid 2000)，否则走 su。
     *  失败时打完整结果日志（通道/命令/退出码/输出），便于远程定位 Shizuku 写入问题。 */
    private static RootUtil.Result runPrivileged(Context ctx, String cmd) {
        boolean shizuku = AuthManager.getMode(ctx) == AuthManager.Mode.SHIZUKU;
        RootUtil.Result r = shizuku ? ShizukuUtil.run(cmd) : RootUtil.su(cmd);
        android.util.Log.i(TAG, "exec via " + (shizuku ? "shizuku" : "su") + ": cmd=" + cmd
                + " => code=" + r.code + " ok=" + r.ok()
                + " out=" + (r.out == null ? "" : r.out.trim())
                + " err=" + (r.err == null ? "" : r.err.trim()));
        return r;
    }

    // —— 对外写入口（全部异步：su 可能阻塞数百毫秒甚至因授权弹窗挂住，禁止在主线程执行）——

    public interface ResultCallback {
        /** ok=写入是否全部成功；state=写入后回读到的最新真实状态（主线程回调） */
        void onResult(boolean ok, State state);
    }

    private static final Executor IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 一键开关（主开关），按调用方给定目标状态写入。
     *
     * @param on true=打开（还原记忆的组合）；false=关闭（记忆当前组合并把两个都关掉）
     */
    public static void setMaster(Context ctx, final boolean on, final ResultCallback cb) {
        applyMasterOn(ctx, on, cb);
    }

    /**
     * 一键翻转（磁贴 / 主开关整行点击 / 桌面快捷方式用）。
     *
     * 一定要在 IO 线程里**先经授权通道读回真值、再决定翻向**，不能依赖 UI 显示的缓存：
     * 刚启动时缓存来自会被 ROM 隐藏的 ContentResolver，若按它翻转，可能把实际开着的状态
     * 又「打开」一遍，或把实际关着的状态「关」一遍，用户感知就是「点了没反应 / 点反了」。
     * 这里在写之前先拿真值，再取反写入，语义才是「相对真实状态一键翻转」。
     */
    public static void toggleMaster(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            refreshCacheFromPrivileged(app);
            State cur = read(app);
            applyMasterOn(app, !cur.anyOn(), cb);
        });
    }

    /** 主开关写入核心（setMaster / toggleMaster 共用）：写前先取真值用于记忆组合，写后回读校准 */
    private static void applyMasterOn(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            // 写入前先取真值：off 分支要用它保存「记忆组合」，缓存若失真会记错
            refreshCacheFromPrivileged(app);
            State cur = read(app);
            boolean ok;
            if (on) {
                killQuickAppsIfNeeded(app, true); // 顺序：先杀联动应用，再开调试
                State target = loadSaved(app);
                ok = writeBoth(app, target.usb, target.wireless);
            } else {
                // 只在确实开着时记忆：避免把「两个都关」这种无效组合写进 saved
                if (cur.anyOn()) {
                    save(app, cur);
                }
                ok = writeBoth(app, false, false);
            }
            // 写完后回读真值刷新缓存，保证广播出的就是最新状态
            refreshCacheFromPrivileged(app);
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
            killQuickAppsIfNeeded(app, on);
            boolean ok = writeUsb(app, on);
            refreshCacheFromPrivileged(app);
            State after = broadcast(app);
            if (cb != null) {
                MAIN.post(() -> cb.onResult(ok, after));
            }
        });
    }

    /** 单独翻转 USB 调试：同样先读真值再翻，避免按失真缓存翻转 */
    public static void toggleUsb(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            refreshCacheFromPrivileged(app);
            State cur = read(app);
            boolean ok = writeUsb(app, !cur.usb);
            refreshCacheFromPrivileged(app);
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
            killQuickAppsIfNeeded(app, on);
            boolean ok = writeWireless(app, on);
            refreshCacheFromPrivileged(app);
            State after = broadcast(app);
            if (cb != null) {
                MAIN.post(() -> cb.onResult(ok, after));
            }
        });
    }

    /** 单独翻转无线调试：同样先读真值再翻 */
    public static void toggleWireless(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            refreshCacheFromPrivileged(app);
            State cur = read(app);
            boolean ok = writeWireless(app, !cur.wireless);
            refreshCacheFromPrivileged(app);
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
        // 诊断：主界面 UI 完全由这几个 extra 驱动，记下来就等于记下了界面将要显示的值
        android.util.Log.i(TAG, "broadcast usb=" + s.usb + " wireless=" + s.wireless
                + " on=" + s.anyOn());
        ctx.sendBroadcast(i);
        return s;
    }
}
