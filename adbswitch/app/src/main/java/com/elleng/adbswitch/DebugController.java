package com.elleng.adbswitch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.elleng.adbswitch.util.RootUtil;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
 * 此外还管理第三个键：
 *   - Settings.Global.development_settings_enabled（开发者选项入口）
 *     它是**独立开关**，与主开关互不联动：关它只隐藏设置里的开发者选项入口，
 *     不会动 adb_enabled / adb_wifi_enabled；反过来开/关调试也不会动它。
 *     （AOSP 的 DevelopmentSettingsEnabler 也是只写这一个键，不联动 adb。）
 *
 * 设计要点（这是整个 App 唯一允许改写这三个键的地方）：
 *  1. 真值只有一个来源：每次都回读 Settings，不缓存、不猜。
 *  2. 主开关语义：「开」= 任一开着（adbd 在跑），「关」= 两个都关（真正安全）。
 *     开发者选项键不参与这个判定（见 State.anyOn / allOff）。
 *  3. 一键关闭：先把当前组合写进 saved，再把两个都写 0；
 *     一键打开：从 saved 还原原组合（这就是「记住两个开关状态」）。
 *  4. 子开关单独拨：只改对应那一个键，不动 saved —— 用户手动调的不该被"记忆"覆盖。
 *     开发者选项键同样不进 saved（它不属于「调试组合」）。
 *  5. 通知 / LiveUpdate / 磁贴 / 联动 / 主界面全部只调用本类，不再各自判断要不要管无线。
 *
 * 性能与结构（本轮重构）：
 *  - 所有写操作合并为**单次** shell 执行（读前态 → 必要时杀联动应用 → 写入 → 回读后态），
 *    把原来分散的 2~4 次 su / Shizuku fork 合并为 1 次：root 模式每次 fork 100~500ms，
 *    合并后响应快数倍，也顺带把通知/磁贴 goAsync 的 8s 窗口压力降到最低。
 *  - 单线程 IO 执行器按操作键去重合并：同一键在途时，新请求只记「最新一次」，
 *    在途任务完成后用最新参数再跑一次，避免快速连点把队列塞满或在途重复写。
 *  - 状态变化改用进程内 listener 注册表（App 单进程，隐式广播纯开销），不再发 ACTION_STATE。
 */
public final class DebugController {

    private static final String TAG = "DebugController";

    /** 状态变化 listener（替代原进程内广播；App 单进程，广播纯开销） */
    public interface StateListener {
        void onStateChanged(State s);
    }

    /** 状态变化广播的遗留 action（仅供外部兼容参考，App 内已改用 {@link #addStateListener}） */
    public static final String ACTION_STATE = "com.elleng.adbswitch.ACTION_STATE";
    public static final String EXTRA_USB = "extra_usb";
    public static final String EXTRA_WIRELESS = "extra_wireless";
    /** 开发者选项入口是否开启（独立开关，不参与 EXTRA_ON 的判定） */
    public static final String EXTRA_DEV = "extra_dev";
    /** 聚合状态：usb || wireless，即 adbd 是否在跑 */
    public static final String EXTRA_ON = "extra_on";

    /** 三个被管理的 Settings.Global 键（监听 URI 与读写都复用，避免散落字符串字面量） */
    public static final String KEY_USB = "adb_enabled";
    public static final String KEY_WIRELESS = "adb_wifi_enabled";
    /** 开发者选项入口开关（Settings.Global.DEVELOPMENT_SETTINGS_ENABLED） */
    public static final String KEY_DEV = "development_settings_enabled";

    /** 合并读取/写入时各键的输出标记（一次 shell 取回全部值，按标记行定位，不靠行号） */
    private static final String TAG_USB_B = "__ASW_USB_B__";
    private static final String TAG_WIFI_B = "__ASW_WIFI_B__";
    private static final String TAG_DEV_B = "__ASW_DEV_B__";
    private static final String TAG_USB_A = "__ASW_USB_A__";
    private static final String TAG_WIFI_A = "__ASW_WIFI_A__";
    private static final String TAG_DEV_A = "__ASW_DEV_A__";

    private static final String PREFS = "cfg";
    private static final String SAVED_USB = "saved_usb";
    private static final String SAVED_WIRELESS = "saved_wireless";
    private static final String SAVED_VALID = "saved_valid";
    /** 「开启调试自动关闭快捷应用」开关（与 MainActivity 共用同一份 cfg） */
    private static final String PREF_KILL_BEFORE_OPEN = "kill_before_open";
    /** 上次经授权通道确认过的状态（跨启动保留，供未校准期间界面显示用） */
    private static final String LAST_USB = "last_usb";
    private static final String LAST_WIRELESS = "last_wireless";
    private static final String LAST_DEV = "last_dev";
    /** 本会话是否已确认过真值（缓存失效不清它）：用于「启动窗口期屏蔽操作」 */
    private static volatile boolean confirmedThisSession = false;

    private DebugController() {
    }

    /** 三个开关的组合状态（dev 只是随行携带，不参与调试态判定） */
    public static final class State {
        public final boolean usb;
        public final boolean wireless;
        /** 开发者选项入口是否开启：独立开关，不影响 adbd 是否运行 */
        public final boolean dev;

        State(boolean usb, boolean wireless, boolean dev) {
            this.usb = usb;
            this.wireless = wireless;
            this.dev = dev;
        }

        /** 任一开着 → adbd 在跑 → 会被检测为调试态（不含 dev） */
        public boolean anyOn() {
            return usb || wireless;
        }

        /** 两个都关 → adbd stopped → 通过检测（不含 dev） */
        public boolean allOff() {
            return !usb && !wireless;
        }
    }

    // —— 读 ——

    /** 缓存的真值（由后台经授权通道校准）：值与校准时间合成单一快照原子更新，避免 UI 看到撕裂 */
    private static volatile Snapshot cache = null;
    private static volatile long lastRefreshReqAt = 0;
    /** 缓存有效期：超过则后台重新校准 */
    private static final long STALE_MS = 5000;
    /** 后台校准的最小间隔（read() 被高频调用，必须节流） */
    private static final long REFRESH_MIN_INTERVAL = 1500;
    /** 自身写入后短窗内忽略 observer 的「外部改动」：写入路径已自带回读校准，别再 fork */
    private static final long OWN_WRITE_SUPPRESS_MS = 1500;
    private static volatile long lastOwnWriteAt = 0;

    /** 状态快照：状态值与校准时间必须成对更新（at==0 表示尚未经授权通道校准） */
    private static final class Snapshot {
        final State state;
        final long at;

        Snapshot(State state, long at) {
            this.state = state;
            this.at = at;
        }
    }

    /**
     * 读取状态：返回缓存的真值；缓存为空时先用「上次确认状态」兜底（跨启动保存在 cfg，
     * 不依赖会被 ROM 隐藏的 ContentResolver，启动首帧即正确、不闪变），
     * 仅在全新安装（从没确认过、无上次状态）才退回 ContentResolver 快读。
     * 之后立刻触发后台用授权通道校准，校准完成会刷新缓存并通知各 listener。
     *
     * 关键背景（曾经踩的坑）：本 App 的写入走 su / Shizuku shell 通道，而部分 ROM
     * （如 HyperOS）会向第三方 App 隐藏 adb_enabled / adb_wifi_enabled ——
     * 实测 shell 读到 1，App 用 ContentResolver 却读到 0，导致总开关状态一直是错的。
     * 因此真值必须与「写入」走同一个通道（{@link #readViaPrivileged}），读写同源才不会打架。
     *
     * 但 read() 会被主线程高频调用（onResume / 每次布局完成 / listener 回调去抖），
     * 这里绝不能 fork 进程，否则慢设备或授权挂起时会卡 UI 甚至 ANR（这是历史教训）。
     * 故：同步只返回缓存（或上次确认状态兜底），另由 {@link #triggerRefresh} 在后台校准。
     */
    public static State read(Context ctx) {
        Snapshot c = cache;
        if (c == null) {
            // 主线程兜底：优先用「上次经授权通道确认过」的持久化状态（跨启动保存在 cfg）；
            // 只在全新安装（从没确认过、无上次状态）才退回 ContentResolver 快读（与旧行为一致）。
            c = new Snapshot(lastKnownState(ctx), 0);
            cache = c;
        }
        triggerRefresh(ctx);
        return c.state;
    }

    /** 后台用授权通道校准真值并通知 listener（主线程安全，内部已节流） */
    public static void triggerRefresh(Context ctx) {
        final Context app = ctx.getApplicationContext();
        long now = android.os.SystemClock.uptimeMillis();
        // at==0 表示缓存尚未经授权通道校准（兜底值），必须立即校准
        Snapshot c = cache;
        if (c != null && c.at != 0 && now - c.at < STALE_MS) {
            return; // 已校准且仍新鲜
        }
        if (now - lastRefreshReqAt < REFRESH_MIN_INTERVAL) {
            return; // 节流
        }
        lastRefreshReqAt = now;
        IO.execute(() -> {
            State s = readViaPrivileged(app);
            if (s != null) {
                cache = new Snapshot(s, android.os.SystemClock.uptimeMillis());
                persistLast(app, s);
                broadcast(app);
            }
        });
    }

    /**
     * 外部改动感知：把缓存标记为「未校准」并立刻后台重新校准。
     * 用于 ContentObserver 回调 —— 此时回读拿到的仍是缓存（且本 ROM 向 App 隐藏真值），
     * 不作废就永远刷不出外部改动。校准完成后会通知各 listener，UI 由各自的 listener 更新。
     */
    public static void invalidateAndRefresh(Context ctx) {
        // 自身写入也会触发 observer（两个键就是两次）：写入路径已回读校准并通知，
        // 短窗内不再重复 fork；外部改动若落在窗内，最迟 STALE_MS 后也会校准到。
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastOwnWriteAt < OWN_WRITE_SUPPRESS_MS) {
            return;
        }
        Snapshot c = cache;
        if (c != null) {
            cache = new Snapshot(c.state, 0); // 只作废校准标记，保留旧值供 UI 显示
        }
        lastRefreshReqAt = 0; // 外部改动优先级最高，不受节流挡住
        triggerRefresh(ctx);
    }

    /**
     * 上次经授权通道确认过的状态（跨启动保留）。
     *
     * 用途：启动瞬间真值还没校准回来时，界面用它渲染，而不是用 ContentResolver 兜底值——
     * ROM 隐藏这两个键时兜底值恒为 0，会先显示「关闭」、校准完又跳回「开启」，一闪一跳。
     * 全新安装（从没校准过）没有可参考的上次状态，退回 ContentResolver 快读（与旧行为一致）。
     *
     * 这套值持久化在 cfg 的 LAST_USB/LAST_WIRELESS/LAST_DEV，apply() 异步落盘，
     * 因此**会保存本地**、跨进程杀掉/App 重启都还在；而 read() 内部那个内存缓存（Snapshot）
     * 只活在当前进程，每次启动重新校准。两者分工：内存缓存管「当前进程内的新鲜度」，
     * LAST_* 管「跨启动的可靠回退值」。
     */
    public static State lastKnownState(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.contains(LAST_USB)) {
            return new State(readUsb(ctx), readWireless(ctx), readDev(ctx));
        }
        // 老版本升级上来没有 LAST_DEV（只记过两个键）：缺哪个就退回快读，不硬塞 false
        return new State(sp.getBoolean(LAST_USB, false),
                sp.getBoolean(LAST_WIRELESS, false),
                sp.contains(LAST_DEV) ? sp.getBoolean(LAST_DEV, false) : readDev(ctx));
    }

    /** 记下经授权通道确认过的状态（后台线程调用；apply 异步落盘即可） */
    private static void persistLast(Context app, State s) {
        confirmedThisSession = true;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(LAST_USB, s.usb)
                .putBoolean(LAST_WIRELESS, s.wireless)
                .putBoolean(LAST_DEV, s.dev)
                .apply();
    }

    /** 本会话是否已用授权通道确认过真值（启动窗口期 UI 据此屏蔽操作） */
    public static boolean confirmedThisSession() {
        return confirmedThisSession;
    }

    /** 是否曾确认过真值（持久化）：没有它就是首次使用，无「上次状态」可显示 */
    public static boolean hasConfirmedBefore(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(LAST_USB);
    }

    /** 真值是否已由授权通道确认过（即缓存可信）。UI 据此决定能不能给出安全结论。 */
    public static boolean isCalibrated() {
        Snapshot c = cache;
        return c != null && c.at != 0;
    }

    /**
     * 用与写入相同的通道（su / Shizuku shell）读取真值。
     * 阻塞调用，只允许在后台线程使用（写入流程与后台校准都在 IO 线程）。
     *
     * 三个键合并成**一条** shell 命令读取：若分开 fork su 读取，root 模式下每次 fork
     * 是 100~500ms 的真实等待，一次开关要读写三回就是 9 次。带标记行输出，即使 ROM 的 shell
     * 掺入额外噪声行也能准确定位，不靠行号硬取。
     *
     * dev 键单独降级：个别 ROM 可能没有 development_settings_enabled（读到 null），
     * 若因此判定整体读取失败，会把主功能（adb 开关）一起拖死 —— 那是本 App 的根本。
     * 故 dev 读不到时按「关闭」处理并记日志，usb / wireless 仍按原规则严格要求。
     *
     * @return usb / wireless 读不到有效值时返回 null（调用方保留旧缓存，不覆盖为真假难辨的状态）
     */
    private static State readViaPrivileged(Context app) {
        RootUtil.Result r = runPrivileged(app,
                "echo " + TAG_USB_B + "=$(settings get global " + KEY_USB + ");"
                        + " echo " + TAG_WIFI_B + "=$(settings get global " + KEY_WIRELESS + ");"
                        + " echo " + TAG_DEV_B + "=$(settings get global " + KEY_DEV + ")");
        return parseState(r.out, TAG_USB_B, TAG_WIFI_B, TAG_DEV_B);
    }

    /** 从合并 shell 输出里按标记行解析出三个键的真值；usb/wireless 无效返回 null */
    private static State parseState(String out, String pU, String pW, String pD) {
        if (out == null) {
            return null;
        }
        String u = null;
        String w = null;
        String d = null;
        for (String line : out.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.startsWith(pU + "=")) {
                u = s.substring(pU.length() + 1).trim();
            } else if (s.startsWith(pW + "=")) {
                w = s.substring(pW.length() + 1).trim();
            } else if (s.startsWith(pD + "=")) {
                d = s.substring(pD.length() + 1).trim();
            }
        }
        if (!("0".equals(u) || "1".equals(u)) || !("0".equals(w) || "1".equals(w))) {
            android.util.Log.w(TAG, "privileged read got invalid values: usb=[" + u
                    + "] wireless=[" + w + "]");
            return null;
        }
        boolean dev = "1".equals(d);
        if (!("0".equals(d) || "1".equals(d))) {
            android.util.Log.w(TAG, "privileged read dev invalid, treat as off: dev=[" + d + "]");
        }
        return new State("1".equals(u), "1".equals(w), dev);
    }

    static boolean readUsb(Context ctx) {
        return getInt(ctx, KEY_USB) == 1;
    }

    static boolean readWireless(Context ctx) {
        return getInt(ctx, KEY_WIRELESS) == 1;
    }

    /** 开发者选项入口（ContentResolver 快读，仅供兜底：真值靠授权通道回读） */
    static boolean readDev(Context ctx) {
        return getInt(ctx, KEY_DEV) == 1;
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

    // —— 写（底层，仅本类内部调用）——

    /**
     * 最近一次经授权通道执行的失败原因（RootUtil.REASON_*，成功时为 REASON_NONE）。
     *
     * 只在单线程 IO 执行器里写、在紧随其后的回调里读，因此不需要额外同步：
     * 写路径是「runPrivileged → 解析 → 回调」一条直线，中间不会再跑别的通道调用。
     * 用途是把「没装 su / root 未授权 / Shizuku 没启动 / Shizuku 没授权 / 超时 / 命令失败」
     * 区分开传给 UI，而不是让所有失败都显示同一句「执行失败」。
     */
    private static volatile int lastFailReason = RootUtil.REASON_NONE;

    /** 最近一次执行失败的原因（供 UI 在 ok=false 时选文案） */
    public static int lastFailReason() {
        return lastFailReason;
    }

    /**
     * 按失败原因给出可执行的引导文案（主界面 / 磁贴 / 桌面快捷方式共用一份）。
     * 兜底仍用原来的「执行失败，请检查授权模式」。
     */
    public static String failureMessage(Context ctx, int reason) {
        switch (reason) {
            case RootUtil.REASON_NO_SU:
                return ctx.getString(R.string.toast_no_su);
            case RootUtil.REASON_DENIED:
                return ctx.getString(R.string.toast_root_denied);
            case RootUtil.REASON_NO_BINDER:
                return ctx.getString(R.string.toast_shizuku_not_running);
            case RootUtil.REASON_NO_PERMISSION:
                return ctx.getString(R.string.toast_shizuku_no_perm);
            case RootUtil.REASON_TIMEOUT:
                return ctx.getString(R.string.toast_auth_timeout);
            default:
                return ctx.getString(R.string.toast_root_fail);
        }
    }

    /** 按当前授权模式选执行通道：Shizuku 走 shell(uid 2000)，否则走 su。 */
    private static RootUtil.Result runPrivileged(Context ctx, String cmd) {
        boolean shizuku = AuthManager.getMode(ctx) == AuthManager.Mode.SHIZUKU;
        RootUtil.Result r = shizuku ? ShizukuUtil.run(cmd) : RootUtil.su(cmd);
        lastFailReason = r.ok() ? RootUtil.REASON_NONE : r.reason;
        // Log.d：release 默认不可见；排查授权/写入问题时 adb setprop log.tag.DebugController DEBUG 可开
        android.util.Log.d(TAG, "exec via " + (shizuku ? "shizuku" : "su")
                + " => code=" + r.code + " ok=" + r.ok() + " reason=" + r.reason);
        return r;
    }

    // —— 对外写入口（全部异步：su 可能阻塞数百毫秒甚至因授权弹窗挂住，禁止在主线程执行）——

    public interface ResultCallback {
        /**
         * @param ok 写入是否全部成功
         * @param state 写入后回读到的最新真实状态（主线程回调）。
         *              来自授权通道回读，不会因为「命令退出码 0 但被 ROM 屏蔽」而失真。
         * @param reason 失败原因（RootUtil.REASON_*）：成功时为 REASON_NONE。
         *               必须带出来 —— 「没有 su」「root 未授权」「Shizuku 没启动」
         *               「Shizuku 没授权」「超时」五种情况用户要做的事完全不同，
         *               笼统一句「执行失败」等于让用户自己猜。
         */
        void onResult(boolean ok, State state, int reason);
    }

    private static final Executor IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 单线程执行器 + 同键去重合并：同一操作键（usb/wireless/dev/master）在途时，
     * 新请求只记「最新一次」，在途任务完成后用最新参数再跑一次，避免快速连点把队列塞满
     * 或在途重复写。不同键之间互不影响（可并行排队，但执行器是单线程，仍串行执行）。
     */
    private static final java.util.Set<String> INFLIGHT =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.Map<String, Runnable> PENDING = new ConcurrentHashMap<>();

    private static void enqueue(String key, Runnable task) {
        if (!INFLIGHT.add(key)) {
            PENDING.put(key, task); // 覆盖为最新一次请求（丢弃中间态）
            return;
        }
        IO.execute(() -> {
            try {
                task.run();
            } finally {
                INFLIGHT.remove(key);
                Runnable next = PENDING.remove(key);
                if (next != null) {
                    enqueue(key, next); // 用最新参数再跑一次（丢回队列尾部，不抢占刚结束的在途任务）
                }
            }
        });
    }

    // —— listener 注册表（替代进程内广播） ——

    private static final java.util.Set<StateListener> LISTENERS =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void addStateListener(StateListener l) {
        LISTENERS.add(l);
    }

    public static void removeStateListener(StateListener l) {
        LISTENERS.remove(l);
    }

    /** 广播当前完整状态（读真值，不传参，保证各消费者看到的都是最新），并通知各 listener */
    public static State broadcast(Context ctx) {
        State s = read(ctx);
        final State fs = s;
        MAIN.post(() -> {
            for (StateListener l : new java.util.ArrayList<>(LISTENERS)) {
                try {
                    l.onStateChanged(fs);
                } catch (Throwable ignored) {
                    // 单个 listener 抛错不影响其它消费者
                }
            }
        });
        return s;
    }

    /**
     * 一键开关（主开关），按调用方给定目标状态写入。
     * 全程只 fork 一次 su / 一次 Shizuku shell：读前态 →（打开时杀联动应用）→ 写入 → 回读后态。
     *
     * @param on true=打开（还原记忆的组合）；false=关闭（记忆当前组合并把两个都关掉）
     */
    public static void setMaster(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        enqueue("master", () -> setMasterBlocking(app, on, cb));
    }

    /**
     * 一键翻转（磁贴 / 主开关整行点击 / 桌面快捷方式用）。
     * 一定要在 IO 线程里**先经授权通道读回真值、再决定翻向**，不能依赖 UI 显示的缓存：
     * 刚启动时缓存来自会被 ROM 隐藏的 ContentResolver，若按它翻转，可能把实际开着的状态
     * 又「打开」一遍，或把实际关着的状态「关」一遍，用户感知就是「点了没反应 / 点反了」。
     * 真值读不到（授权失效 / 通道挂起）时直接拒绝并回传失败，交调用方提示重试或切换模式。
     */
    public static void toggleMaster(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        enqueue("master", () -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, cb);
                return;
            }
            setMasterBlocking(app, !cur.anyOn(), cb);
        });
    }

    /** 主开关写入核心：单次 shell 完成「读前态 → 杀联动应用 → 写入 → 回读后态」 */
    private static void setMasterBlocking(Context app, boolean on, ResultCallback cb) {
        MasterMerge m = masterMergeWrite(app, on);
        // 写入或回读失败（未授权 / 通道挂起 / ROM 拦截）：**绝不提交**。
        // 曾有的严重缺陷：after 兜底成 new State(false,false,false) 并无条件进 cache + persistLast，
        // 把「假全关」当成真值渲染、还污染跨启动的「上次确认状态」——表现为关了再也开不回来、
        // 切回已授权模式后 UI 卡在关闭态（污染值是「已校准」的新鲜缓存，校准还会被节流挡住）。
        if (m.after == null) {
            State last = read(app); // 返回未污染的当前缓存
            android.util.Log.w(TAG, "master write refused to commit: wrote=" + m.wrote
                    + " (write failed or readback invalid)");
            broadcast(app); // 让各消费者按未污染状态回弹
            if (cb != null) {
                final int reason = lastFailReason;
                MAIN.post(() -> cb.onResult(false, last, reason));
            }
            return;
        }
        // 关闭时记忆「写前组合」，并只在实际开着时才记（避免把「两个都关」这种无效组合写进 saved）
        if (!on && m.before != null && m.before.anyOn()) {
            save(app, m.before);
        }
        State s = commitAfter(app, m.after);
        if (cb != null) {
            final int reason = lastFailReason;
            MAIN.post(() -> cb.onResult(m.wrote, s, reason));
        }
    }

    /** 单 shell 完成主开关的「读前态 →（打开时杀联动应用）→ 写入 → 回读后态」 */
    private static MasterMerge masterMergeWrite(Context ctx, boolean on) {
        State saved = loadSaved(ctx);
        StringBuilder cmd = new StringBuilder();
        // 1) 写前真值（关闭时用于记忆组合）
        cmd.append("echo ").append(TAG_USB_B).append("=$(settings get global ").append(KEY_USB).append(");");
        cmd.append(" echo ").append(TAG_WIFI_B).append("=$(settings get global ").append(KEY_WIRELESS).append(");");
        cmd.append(" echo ").append(TAG_DEV_B).append("=$(settings get global ").append(KEY_DEV).append(");");
        // 2) 写入（打开时先杀联动应用、再还原记忆组合；关闭时直接全关）
        if (on) {
            String kill = killScriptFor(ctx, true);
            if (kill != null && !kill.isEmpty()) {
                cmd.append(' ').append(kill).append(';');
            }
            cmd.append(" settings put global ").append(KEY_USB).append(' ')
                    .append(saved.usb ? '1' : '0').append(';');
        } else {
            cmd.append(" settings put global ").append(KEY_USB).append(" 0;");
        }
        cmd.append(" settings put global ").append(KEY_WIRELESS).append(' ')
                .append(on ? (saved.wireless ? '1' : '0') : '0').append(';');
        // 3) 写后真值回读
        cmd.append(" echo ").append(TAG_USB_A).append("=$(settings get global ").append(KEY_USB).append(");");
        cmd.append(" echo ").append(TAG_WIFI_A).append("=$(settings get global ").append(KEY_WIRELESS).append(");");
        cmd.append(" echo ").append(TAG_DEV_A).append("=$(settings get global ").append(KEY_DEV).append(");");
        RootUtil.Result r = runPrivileged(ctx, cmd.toString());
        lastOwnWriteAt = android.os.SystemClock.uptimeMillis();
        MasterMerge m = new MasterMerge();
        m.before = parseState(r.out, TAG_USB_B, TAG_WIFI_B, TAG_DEV_B);
        m.after = parseState(r.out, TAG_USB_A, TAG_WIFI_A, TAG_DEV_A);
        m.wrote = r.ok();
        return m;
    }

    /**
     * 单次 shell 完成「（可选杀联动应用）→ 写入 → 回读后态」，用于子开关 setX / toggleX。
     * 返回写入是否成功与写入后回读到的真值（可能 null）。
     */
    private static WriteResult writeKeyReadback(Context ctx, String killScript, String writeScript) {
        StringBuilder cmd = new StringBuilder();
        if (killScript != null && !killScript.isEmpty()) {
            cmd.append(killScript).append("; ");
        }
        cmd.append(writeScript).append("; ");
        cmd.append("echo ").append(TAG_USB_A).append("=$(settings get global ").append(KEY_USB).append(");");
        cmd.append(" echo ").append(TAG_WIFI_A).append("=$(settings get global ").append(KEY_WIRELESS).append(");");
        cmd.append(" echo ").append(TAG_DEV_A).append("=$(settings get global ").append(KEY_DEV).append(");");
        RootUtil.Result r = runPrivileged(ctx, cmd.toString());
        lastOwnWriteAt = android.os.SystemClock.uptimeMillis();
        WriteResult w = new WriteResult();
        w.ok = r.ok();
        w.after = parseState(r.out, TAG_USB_A, TAG_WIFI_A, TAG_DEV_A);
        return w;
    }

    /** 写完后用回读真值刷新内存缓存并广播（仅可在后台线程调用）；返回广播出去的状态 */
    private static State commitAfter(Context app, State after) {
        if (after != null) {
            cache = new Snapshot(after, android.os.SystemClock.uptimeMillis());
            persistLast(app, after);
        }
        return broadcast(app);
    }

    /** 「开启调试自动关闭快捷应用」的杀进程脚本（只在 opening 时构造；默认开启） */
    private static String killScriptFor(Context ctx, boolean opening) {
        if (!opening) {
            return null;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(PREF_KILL_BEFORE_OPEN, true)) {
            return null;
        }
        List<String> apps = LinkagePrefs.getApps(ctx);
        if (apps.isEmpty()) {
            return null;
        }
        // am force-stop 走系统正常的停止流程（比 kill 更干净，不会留下半死的进程状态）。
        // 合并进同一条命令执行 —— 避免每个 app 单独 fork 一次 su 拖慢「开启调试」。
        StringBuilder cmd = new StringBuilder();
        for (String pkg : apps) {
            if (cmd.length() > 0) {
                cmd.append("; ");
            }
            cmd.append("am force-stop ").append(pkg);
        }
        return cmd.toString();
    }

    /** 读不到真值、拒绝执行时统一收口：回传失败 + 当前缓存，让 UI 提示重试/切换模式并保持一致 */
    private static void failWithoutWrite(Context app, ResultCallback cb) {
        State last = read(app);
        android.util.Log.w(TAG, "privileged read failed, refuse to act (auth lost or channel hung)");
        if (cb != null) {
            final int reason = lastFailReason;
            MAIN.post(() -> cb.onResult(false, last, reason));
        }
    }

    // —— 统一的 set / toggle 模板（原六个方法同构，现由下面两个底层原语 + enqueue 收口）——

    /** 子开关写完的统一收尾：回读失败拒绝提交（与主开关同规则），成功则提交并广播 */
    private static void finishSubWrite(Context app, WriteResult w, ResultCallback cb) {
        if (w.after == null) {
            // 写入或回读失败（未授权 / 通道挂起 / ROM 拦截）：绝不提交假状态
            State last = read(app);
            android.util.Log.w(TAG, "sub write refused to commit (write failed or readback invalid)");
            broadcast(app);
            if (cb != null) {
                final int reason = lastFailReason;
                MAIN.post(() -> cb.onResult(false, last, reason));
            }
            return;
        }
        State s = commitAfter(app, w.after);
        if (cb != null) {
            final int reason = lastFailReason;
            MAIN.post(() -> cb.onResult(w.ok, s, reason));
        }
    }

    /** 单独改 USB 调试（不动 saved） */
    public static void setUsb(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        enqueue("usb", () -> {
            WriteResult w = writeKeyReadback(app, killScriptFor(app, on),
                    "settings put global " + KEY_USB + " " + (on ? "1" : "0"));
            finishSubWrite(app, w, cb);
        });
    }

    /** 单独翻转 USB 调试：先经授权通道读真值再翻，读不到真值则拒绝 */
    public static void toggleUsb(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        enqueue("usb", () -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, cb);
                return;
            }
            boolean on = !cur.usb;
            WriteResult w = writeKeyReadback(app, killScriptFor(app, on),
                    "settings put global " + KEY_USB + " " + (on ? "1" : "0"));
            finishSubWrite(app, w, cb);
        });
    }

    /** 单独改无线调试（不动 saved） */
    public static void setWireless(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        enqueue("wireless", () -> {
            WriteResult w = writeKeyReadback(app, killScriptFor(app, on),
                    "settings put global " + KEY_WIRELESS + " " + (on ? "1" : "0"));
            finishSubWrite(app, w, cb);
        });
    }

    /** 单独翻转无线调试：先读真值再翻；读不到真值则拒绝 */
    public static void toggleWireless(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        enqueue("wireless", () -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, cb);
                return;
            }
            boolean on = !cur.wireless;
            WriteResult w = writeKeyReadback(app, killScriptFor(app, on),
                    "settings put global " + KEY_WIRELESS + " " + (on ? "1" : "0"));
            finishSubWrite(app, w, cb);
        });
    }

    /**
     * 单独改开发者选项入口（不动 saved、不动 adb / 无线）。
     * 与 setUsb / setWireless 同一套节奏：不杀快捷应用，写完回读真值再广播。
     */
    public static void setDev(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        // 开发者选项不参与调试态、不联动快捷应用，无需杀进程（opening=false）
        enqueue("dev", () -> {
            WriteResult w = writeKeyReadback(app, null,
                    "settings put global " + KEY_DEV + " " + (on ? "1" : "0"));
            finishSubWrite(app, w, cb);
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

    /**
     * 取记忆的组合；没有有效记忆时回退 USB 开、无线关。
     *
     * 这里的组合只用于「一键打开还原调试态」，dev 不属于它 —— 独立开关的取值必须来自当前真值，
     * 否则一键打开会把用户自己关掉的开发者选项又悄悄打开（违背「两者互不联动」）。
     * 故 dev 一律填 false，调用方（主开关路径）也不会读这个字段。
     */
    private static State loadSaved(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(SAVED_VALID, false)) {
            return new State(sp.getBoolean(SAVED_USB, true),
                    sp.getBoolean(SAVED_WIRELESS, false), false);
        }
        return new State(true, false, false);
    }

    // —— 合并写操作的结果载体 ——

    /** 单键写入结果：是否成功 + 写入后回读真值 */
    private static final class WriteResult {
        boolean ok;
        State after;
    }

    /** 主开关合并写结果：写前态（关时记忆用）+ 写后态 + 是否成功写过 */
    private static final class MasterMerge {
        State before;
        State after;
        boolean wrote;
    }
}
