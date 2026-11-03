package com.elleng.adbswitch;

import com.elleng.adbswitch.util.ShizukuUtil;

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
 *  - 状态变化改用进程内 listener 注册表（App 单进程，隐式广播纯开销）。
 */
public final class DebugController {

    private static final String TAG = "DebugController";

    /** 状态变化 listener（替代原进程内广播；App 单进程，广播纯开销） */
    public interface StateListener {
        void onStateChanged(State s);
    }

    /** 三个被管理的 Settings.Global 键（监听 URI 与读写都复用，避免散落字符串字面量） */
    public static final String KEY_USB = "adb_enabled";
    public static final String KEY_WIRELESS = "adb_wifi_enabled";
    /** 开发者选项入口开关（Settings.Global.DEVELOPMENT_SETTINGS_ENABLED） */
    public static final String KEY_DEV = "development_settings_enabled";

    /**
     * 无线调试设置键（adb_wifi_enabled）自 Android 11（API 30）才引入。
     * 低版本没有这个键：{@code Settings.Global.getInt} 会抛 SettingNotFoundException，
     * {@code settings get global} 返回字符串 "null" —— 读写两侧都必须按「无线不存在」处理，
     * 否则一次读不到就把整个状态读取拖垮（旧实现：一个键抛异常、整读返回 null，
     * 导致低版本设备永远读不回真值、UI 卡在「正在读取」且主开关全程置灰）。
     */
    static boolean wirelessSupported() {
        return android.os.Build.VERSION.SDK_INT >= 30;
    }

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
    /**
     * 「开启调试自动关闭快捷应用」的**真实执行结果**（三态持久化）：
     * 无此键 = 从未执行过（未知）；true = 上次执行成功；false = 上次因授权问题失败。
     *
     * <p>为什么缓存执行结果、而不是缓存「su 是否已授权」这个探测结论：探测结论必须 fork 一次
     * su 才知道，而免 root 是默认模式、绝大多数用户根本没有 root —— 为了这个开关的置灰状态
     * 就在启动 / 回前台 fork su，会在装了 Magisk 但没给本应用授权的设备上弹授权框、最坏阻塞
     * 10s（这是 1.0.4 明确修掉的打扰）。而「自动关闭快捷应用」本来就要真跑一次 su / Shizuku，
     * 顺手把这个结果记下来零额外成本，且它就是用户实际关心的那个结论。
     */
    private static final String KILL_CAPABLE = "kill_capable";
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

        /** §2.4：状态比较用于「无变化不重复广播」，避免 UI 重绘 / 快捷方式重发 / 通知重绘 */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State s = (State) o;
            return usb == s.usb && wireless == s.wireless && dev == s.dev;
        }

        @Override
        public int hashCode() {
            int h = 17;
            h = 31 * h + (usb ? 1 : 0);
            h = 31 * h + (wireless ? 1 : 0);
            h = 31 * h + (dev ? 1 : 0);
            return h;
        }
    }

    // —— 读 ——

    /** 缓存的真值（由后台经授权通道校准）：值与校准时间合成单一快照原子更新，避免 UI 看到撕裂 */
    private static volatile Snapshot cache = null;
    private static volatile long lastRefreshReqAt = 0;
    /**
     * 缓存有效期：超过则后台重新校准。
     * 按通道分开取值：WSS 直读是亚毫秒级 Binder，shell 读一次 100~500ms ——
     * 两类通道共用一个阈值，要么让 shell 通道白跑校准，要么让 WSS 通道反应迟钝。
     */
    private static final long STALE_MS_SHELL = 5000;
    private static final long STALE_MS_WSS = 1500;
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

    /**
     * 冷启动预热（§2.5b）：把「上次经授权通道确认过的状态」先读进内存缓存，
     * 使首帧 {@link #read} 直接命中缓存、不再做 SharedPreferences 磁盘 I/O。
     * 只做本地读（{@link #lastKnownState}），不触发后台 fork 校准，避免 Application.onCreate
     * 早期副作用；首屏仍会由 triggerRefresh 正常校准。
     */
    public static void warmUp(Context ctx) {
        if (cache == null) {
            cache = new Snapshot(lastKnownState(ctx.getApplicationContext()), 0);
        }
    }

    /**
     * 当前通道的缓存有效期：按「本次读是否会 fork 进程」取阈值，而不是按配置的模式名（§2.2）。
     * 非 WSS 模式必然走 su / Shizuku shell（fork）；WSS 模式下只有 Shizuku 可用时才走 shell 读，
     * 否则走 ContentResolver 直读（亚毫秒、不 fork）。旧实现只按 getEffectiveMode==SECURE_SETTINGS
     * 给短阈值，让 WSS+Shizuku 设备用 STALE_MS_WSS 短阈值却付 shell 代价，校准频率虚高 3 倍多。
     */
    private static long staleMs(Context ctx) {
        boolean willFork = AuthManager.getEffectiveMode(ctx) != AuthManager.Mode.SECURE_SETTINGS
                || ShizukuUtil.available();
        return willFork ? STALE_MS_SHELL : STALE_MS_WSS;
    }

    /** 后台用授权通道校准真值并通知 listener（主线程安全，内部已节流） */
    public static void triggerRefresh(Context ctx) {
        final Context app = ctx.getApplicationContext();
        long now = android.os.SystemClock.uptimeMillis();
        // at==0 表示缓存尚未经授权通道校准（兜底值），必须立即校准
        Snapshot c = cache;
        if (c != null && c.at != 0 && now - c.at < staleMs(app)) {
            return; // 已校准且仍新鲜
        }
        if (now - lastRefreshReqAt < REFRESH_MIN_INTERVAL) {
            return; // 节流
        }
        lastRefreshReqAt = now;
        // §2.1：校准读走独立执行器 CALIBRATE，不再与写入共用单线程 IO（见 CALIBRATE 注释）。
        // 纯 WSS 无 shell 兜底：ContentResolver 回读被 ROM 隐藏/欺骗（HyperOS 上尤甚），
        // 若跑授权通道校准，会用假值污染本地持久化状态（lastKnownState）与缓存，导致 UI
        // 与写入自相矛盾（读显示错、写后又被假回读打回）。这种模式下信任「上次确认状态」
        // + 自身写入：标记本会话已确认以放行点击，不再做会被欺骗的校准。
        if (wssTrustWriteOnly(app)) {
            confirmedThisSession = true;
            return;
        }
        CALIBRATE.execute(() -> {
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
        // 短窗内不再重复 fork；外部改动若落在窗内，最迟缓存有效期（staleMs）到了也会校准到。
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
        return readViaShellOrWss(app);
    }

    /** WSS 模式下的合并读命令（与 readViaPrivileged 原内联命令一致，抽成常量复用） */
    private static final String SHELL_READ_CMD =
            "echo " + TAG_USB_B + "=$(settings get global " + KEY_USB + ");"
                    + " echo " + TAG_WIFI_B + "=$(settings get global " + KEY_WIRELESS + ");"
                    + " echo " + TAG_DEV_B + "=$(settings get global " + KEY_DEV + ")";

    /** 实际写通道是否为 WSS（默认推荐）：WSS 已授权时走应用进程内直写，无需 root / Shizuku */
    private static boolean wssWriteActive(Context ctx) {
        return AuthManager.getEffectiveMode(ctx) == AuthManager.Mode.SECURE_SETTINGS;
    }

    /**
     * 纯 WSS（已授权 WRITE_SECURE_SETTINGS）但无任何 shell 兜底（Shizuku 不可用）时，
     * ContentResolver 读这三个键会被 ROM 隐藏/欺骗（HyperOS 上尤甚），回读不可信 ——
     * 写后只能信任自身写入 + 持久化的「上次确认状态」（lastKnownState），不再把被隐藏的
     * ContentResolver 读当真值。这正是 HyperOS 上「免 root 模式读不到状态、也拨不动开关」
     * 的根因与对策；Geto/IMD 同样走标准 WSS API，纯 WSS 时也只能是 best-effort。
     */
    private static boolean wssTrustWriteOnly(Context ctx) {
        return AuthManager.getEffectiveMode(ctx) == AuthManager.Mode.SECURE_SETTINGS
                && !ShizukuUtil.available();
    }

    /**
     * WSS 直写单个键（无需 root / Shizuku）。
     * 对齐 Geto/IMD：用 {@code Settings.Global.putString}（字符串 API）而非 putInt，
     * 并**用其布尔返回值识别「这一行到底写没写进去」**——部分 ROM（如 HyperOS）对第三方
     * 应用写 adb_enabled / adb_wifi_enabled 直接静默丢弃且不抛错，putInt 的 void 返回会
     * 让调用方误以为写成功了。返回 false → 标记 REASON_WRITE_DROPPED（如实告知而非静默弹回）。
     */
    /**
     * WSS 直写单个键（无需 root / Shizuku）。
     * 返回本次写入的失败原因（RootUtil.REASON_*，成功为 REASON_NONE）；§3.2 起 reason
     * 随返回值走，不再经全局 lastFailReason 传递。为兼容诊断接口 lastFailReason() 仍同步写一次全局。
     */
    private static int putWss(Context ctx, String key, int val) {
        int reason;
        try {
            boolean written = Settings.Global.putString(
                    ctx.getContentResolver(), key, Integer.toString(val));
            if (!written) {
                android.util.Log.w(TAG, "wss put " + key + "=" + val
                        + " not written (dropped by ROM)");
                reason = RootUtil.REASON_WRITE_DROPPED;
            } else {
                reason = RootUtil.REASON_NONE;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "wss put " + key + " failed", t);
            reason = RootUtil.REASON_EXEC;
        }
        lastFailReason = reason; // 诊断保留：lastFailReason() 仍可反映最近一次
        return reason;
    }

    /**
     * WSS 直读三个键（ContentResolver）。部分 ROM（如 HyperOS）可能隐藏这些键（读到 0），
     * 此时读到的值与真值不符——纯 WSS 设备没有更可信的读通道，只能如此（有 Shizuku 时
     * {@link #readViaShellOrWss} 会优先走 shell 读）。
     *
     * 逐键独立读：任一键读不到（不存在 / Provider 异常）按「未开启」处理，
     * 绝不再让单键缺失把整次读取拖垮 —— API<30 没有 adb_wifi_enabled，旧实现一个
     * getInt 抛 SettingNotFoundException 就整体返回 null，真值永远校准不回来。
     */
    private static State readStateWss(Context ctx) {
        return new State(getInt(ctx, KEY_USB) == 1,
                wirelessSupported() && getInt(ctx, KEY_WIRELESS) == 1,
                getInt(ctx, KEY_DEV) == 1);
    }

    /**
     * 经授权通道读真值（readViaPrivileged 的统一入口）：
     *  - 非 WSS 模式：原 shell 读（su / Shizuku）。
     *  - WSS 模式且 Shizuku 可用：走 Shizuku shell 读（HyperOS 上可靠，避免 ContentResolver 被隐藏）。
     *  - WSS 模式且无任何 shell 兜底：走 WSS ContentResolver 直读（部分 ROM 可能受隐藏影响，留作真机验证项）。
     *
     * WSS 尚未拿到一次性 pm grant 时（getEffectiveMode 会回退到 ROOT），绝不静默 fork su：
     * 无 root 设备上那是一次 10s 挂起或 Magisk 授权弹窗，且后台校准会自动反复触发——
     * 与写入口 blockedByMissingGrant 同一立场，直接用 ContentResolver 读。
     */
    private static State readViaShellOrWss(Context ctx) {
        if (AuthManager.getMode(ctx) == AuthManager.Mode.SECURE_SETTINGS
                && !AuthManager.hasWriteSecureSettings(ctx)) {
            return readStateWss(ctx);
        }
        AuthManager.Mode mode = AuthManager.getEffectiveMode(ctx);
        if (mode != AuthManager.Mode.SECURE_SETTINGS) {
            RootUtil.Result r = runPrivileged(ctx, SHELL_READ_CMD);
            return parseState(r.out, TAG_USB_B, TAG_WIFI_B, TAG_DEV_B);
        }
        if (ShizukuUtil.available()) {
            RootUtil.Result r = ShizukuUtil.run(SHELL_READ_CMD);
            return parseState(r.out, TAG_USB_B, TAG_WIFI_B, TAG_DEV_B);
        }
        // 纯 WSS 无 shell 兜底：ContentResolver 读被 ROM 隐藏/欺骗（HyperOS 上尤甚），回读不可信——
        // 直接返回本地持久化的「上次确认状态」（lastKnownState），不再把被隐藏的真值当真。
        return wssTrustWriteOnly(ctx) ? lastKnownState(ctx) : readStateWss(ctx);
    }

    /** WSS 模式写单个键（含写后回读）；opening 且 Shizuku 可用时先经 Shizuku 杀联动应用 */
    private static WriteResult writeKeyWss(Context ctx, String key, boolean on, boolean kill) {
        if (kill) {
            killLinkageIfNeeded(ctx, on);
        }
        boolean trustOnly = wssTrustWriteOnly(ctx);
        // 纯 WSS 无 shell 兜底时，before 也用本地持久化的「上次确认状态」（回读被隐藏不可信）
        State before = trustOnly ? lastKnownState(ctx) : readViaShellOrWss(ctx);
        int reason = putWss(ctx, key, on ? 1 : 0);
        lastOwnWriteAt = android.os.SystemClock.uptimeMillis();
        WriteResult w = new WriteResult();
        w.ok = (reason == RootUtil.REASON_NONE);
        w.reason = reason;
        if (trustOnly) {
            // 纯 WSS 无 shell 兜底：ContentResolver 回读被 ROM 隐藏/欺骗，写后不回读，直接信任写入。
            // putString 返回 true（且未抛错）→ 按期望值提交；返回 false（静默丢弃）/ 抛错 → 拒绝提交，
            // 由 UI 如实提示「写入未生效」，而不是让开关静默弹回、骗用户以为关了。
            w.after = w.ok ? new State(
                    KEY_USB.equals(key) ? on : before.usb,
                    KEY_WIRELESS.equals(key) ? on : before.wireless,
                    KEY_DEV.equals(key) ? on : before.dev) : null;
        } else {
            // 有 Shizuku 兜底的设备仍回读真值（一次 newProcess，但可靠，能纠正 ROM 屏蔽写入）
            w.after = readViaShellOrWss(ctx);
        }
        return w;
    }

    /**
     * WSS 模式主开关合并写。与 shell 版的三点差异：
     *  1. 只写与 before 不同的键：putInt 即使值不变也是一次完整 Binder 往返（还触发 observer /
     *     落盘），磁贴 / 快捷方式高频点击时全是无谓开销。before 读失败（null）时不敢 diff，
     *     退化为两个键都写；
     *  2. 写后态直接用「期望值」提交，不再同步走一次 shell 回读 —— WSS+Shizuku 设备上那是
     *     一次 100~500ms 的 newProcess，全压在用户的点击等待上。ROM 静默屏蔽写入（putInt
     *     不抛错但值没变）的情况由 setMasterBlocking 末尾的 {@link #verifyWssWrite} 复核兜底；
     *  3. API<30 没有无线键，直接跳过（不写无人读取的无效键）。
     *
     * @param knownBefore 调用方刚读到的真值（toggleMaster 已读过一次，不必再读）；null=现读
     */
    private static MasterMerge masterMergeWriteWss(Context ctx, boolean on, State knownBefore) {
        State saved = loadSaved(ctx);
        // 纯 WSS 无 shell 兜底：before 用本地持久化的「上次确认状态」（回读被隐藏不可信）；
        // 否则优先用调用方刚读到的真值，避免重复读。
        boolean trustOnly = wssTrustWriteOnly(ctx);
        State before = trustOnly ? lastKnownState(ctx)
                : (knownBefore != null ? knownBefore : readViaShellOrWss(ctx));
        killLinkageIfNeeded(ctx, on);
        boolean tUsb = on ? saved.usb : false;
        boolean tWifi = on && wirelessSupported() ? saved.wireless : false;
        boolean wroteAny = false;
        int reason = RootUtil.REASON_NONE;
        // 关闭（!on）时**强制写两个键为 0**，不参与「与 before 相同就跳过」的 diff 优化：
        // 纯 WSS 无 shell 兜底时 before 来自持久化的「上次确认状态」，可能已过期（例如调试在别处
        // 被打开、或上次写入被 ROM 静默丢弃后又记为成功）。若按过期 before 判定「已是目标态」而
        // 跳过写入，就会出现「关调试成功」（回调 ok=true、界面/快捷方式照常跳转）但系统里调试其实
        // 还开着——正是用户反馈的「桌面快捷方式只跳转、不关调试」。关闭不是高频操作，多写一次
        // Binder 的代价可忽略，换来的是「关就必须真关」的确定性。
        if (!on || before == null || before.usb != tUsb) {
            reason = putWss(ctx, KEY_USB, tUsb ? 1 : 0);
            wroteAny = true;
        }
        if (!on || before == null || before.wireless != tWifi) {
            reason = putWss(ctx, KEY_WIRELESS, tWifi ? 1 : 0);
            wroteAny = true;
        }
        if (!wroteAny) {
            // 两个键都已是目标态（无需写入 = 成功）：别让上一次操作的失败原因污染本次结论
            reason = RootUtil.REASON_NONE;
        }
        lastOwnWriteAt = android.os.SystemClock.uptimeMillis();
        MasterMerge m = new MasterMerge();
        m.before = before;
        m.reason = reason;
        m.wrote = (reason == RootUtil.REASON_NONE);
        if (!m.wrote) {
            // 写入失败：拒绝提交（与 shell 路径同一条不变式——写失败绝不污染状态缓存）
            m.after = null;
            return m;
        }
        if (before != null) {
            m.after = new State(tUsb, tWifi, before.dev);
            // 真写了才需要异步复核；什么都没写时 after==before（刚读回来的真值，无需验证）
            m.trusted = wroteAny;
        } else {
            m.after = readViaShellOrWss(ctx); // before 都读不到，只能回读（旧行为）
        }
        return m;
    }

    /** 从合并 shell 输出里按标记行解析出三个键的真值；标记行缺失返回 null。包级可见以便纯逻辑单测（§3.5） */
    static State parseState(String out, String pU, String pW, String pD) {
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
        // "null" = settings get 对「从未设置过的键」的返回（不是失败）：adb_enabled /
        // adb_wifi_enabled 在用户从未开关过调试的设备上就是这个值，按「关」放行。
        // 若判为无效，整次读取返回 null → 真值永远校准不回来，UI 卡在「正在读取」。
        // 注意与「标记行整个缺失」（u/w 为 Java null）区分：那才是通道异常，仍返回 null。
        // dev 键缺失按「关」处理（个别 ROM 没有这个键），只记日志不放行失败。
        boolean uValid = "0".equals(u) || "1".equals(u) || "null".equals(u);
        boolean wValid = "0".equals(w) || "1".equals(w) || "null".equals(w);
        if (!uValid || !wValid) {
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
        // 对齐 Geto/IMD：用 getString 读再解析，而非 getInt。
        // getInt 对「键不存在」会抛 SettingNotFoundException，而 getString 返回 null（更稳）；
        // 二者对"已存在的值"解析结果一致。读不到（null / 异常）按「未开启」处理。
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), key);
            if (v == null) {
                return -1;
            }
            return Integer.parseInt(v);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "read " + key + " failed", t);
            return -1;
        }
    }

    // —— 写（底层，仅本类内部调用）——

    /**
     * 最近一次经授权通道执行的失败原因（RootUtil.REASON_*，成功时为 REASON_NONE）。
     *
     * 诊断用途（§3.2 起）：失败原因已改为随 WriteResult / MasterMerge 返回值走，并在回调签名
     * {@link #ResultCallback#onResult} 里显式带出，不再依赖这个跨线程全局静态 —— 否则 §2.1
     * 把校准读拆到独立执行器后会放大线程归属问题，且两次快操作间 UI 可能读到上一次失败的 reason。
     * 本字段仅保留作「最近一次」诊断，由 putWss / runPrivileged 同步写入。
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
            case RootUtil.REASON_NEED_GRANT:
                return ctx.getString(R.string.toast_wss_need_grant);
            case RootUtil.REASON_WRITE_DROPPED:
                return ctx.getString(R.string.toast_wss_write_dropped);
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

    /**
     * 写入执行器：单线程串行，配合同键去重合并（enqueue），保证一条 su / 一次 Shizuku shell
     * 在途期间不会再有第二条同键写插进来把队列塞满；不同键之间虽然各自排队，但单线程下仍串行执行。
     */
    private static final Executor IO = Executors.newSingleThreadExecutor();
    /**
     * §2.1：后台校准读专用执行器，与写入执行器（IO）分离。
     *
     * 旧实现校准读与写入共用同一条单线程 IO：一次 su 挂到 10s 超时期间，所有后台状态校准任务
     * 排在写任务后面，导致磁贴 / 通知 / 设置页的状态显示整体滞后（而校准本身是 UI 反馈的来源）。
     * 现把校准读单独放到 CALIBRATE，写入仍走 IO 单线程串行。两者都是单线程，cache 的 Snapshot
     * 更新是原子赋值（volatile）、无并发撕裂；校准与写入并发读写的内容解析器/进程彼此独立，
     * 没有需要互斥的共享可变状态（lastFailReason 仅诊断用途，见 §3.2）。
     */
    private static final Executor CALIBRATE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 单线程执行器 + 同键去重合并：同一操作键（usb/wireless/dev/master）在途时，
     * 新请求只记「最新一次」，在途任务完成后用最新参数再跑一次，避免快速连点把队列塞满
     * 或在途重复写。不同键之间互不影响（可并行排队，但执行器是单线程，仍串行执行）。
     *
     * 关键修正：去重判定必须在**提交侧（调用线程）**完成，而不是写在 IO 任务体内——
     * 旧实现把 INFLIGHT.add 放在 IO.execute 的任务体里，而执行器是单线程，第二个同键任务
     * 提交后只是排队，等第一个跑完（finally 已 INFLIGHT.remove）才开始执行，此时 add 必然
     * 成功、PENDING 分支永远不可达，结果「连点 N 次 = N 次串行 shell 往返」，去重完全失效。
     * 现改为：提交侧 synchronized 内先查 INFLIGHT，命中就把最新 Runnable 记进 PENDING 并直接
     * return（不提交新任务）；未命中才加锁占有 INFLIGHT 并提交一次 IO 任务。真正执行只在持有
     * INFLIGHT 的那一条 IO 任务里发生，收尾时拾取 PENDING 的最新参数接着跑。
     */
    private static final java.util.Set<String> INFLIGHT =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.Map<String, Runnable> PENDING = new ConcurrentHashMap<>();
    private static final Object ENQUEUE_LOCK = new Object();

    /**
     * §4.7 写入在途冻结：单线程 IO 执行器下，busy = INFLIGHT 非空。
     * UI 订阅 {@link #addBusyListener} 后在写任务排队期间冻结主开关，给出明确反馈
     * （不再停在旧值、连发 Toast 无所适从）。busy 只在「由空变非空 / 由非空变空」时通知，
     * 避免每次提交都刷 UI。
     */
    private static volatile boolean sBusy = false;
    private static final java.util.List<Runnable> BUSY_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static boolean isBusy() {
        return sBusy;
    }

    public static void addBusyListener(Runnable r) {
        BUSY_LISTENERS.add(r);
    }

    public static void removeBusyListener(Runnable r) {
        BUSY_LISTENERS.remove(r);
    }

    private static void setBusy(boolean b) {
        if (sBusy != b) {
            sBusy = b;
            for (Runnable r : BUSY_LISTENERS) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                    // 单个监听抛错不影响其它消费者
                }
            }
        }
    }

    private static void enqueue(String key, Runnable task) {
        boolean submit;
        synchronized (ENQUEUE_LOCK) {
            if (INFLIGHT.contains(key)) {
                // 同键已有任务在途：只记最新一次（覆盖中间态），由在途任务收尾时拾起，不再提交
                PENDING.put(key, task);
                return;
            }
            INFLIGHT.add(key);
            submit = true;
        }
        if (!submit) {
            return;
        }
        // §4.7：有任务在途，立刻冻结 UI 开关（仅在由空变非空时通知）
        setBusy(!INFLIGHT.isEmpty());
        IO.execute(() -> {
            Runnable current = task;
            while (true) {
                try {
                    current.run();
                } catch (Throwable t) {
                    // 任务自身的 bug 绝不能把执行器线程搞死、把待办任务永久吞掉：
                    // 记日志后照常走收尾（清在途标记、拾起最新待办）
                    android.util.Log.e(TAG, "enqueued task crashed", t);
                } finally {
                    synchronized (ENQUEUE_LOCK) {
                        Runnable next = PENDING.remove(key);
                        if (next == null) {
                            // 没有待办：释放在途标记，当前任务线结束
                            INFLIGHT.remove(key);
                            current = null;
                            // 全空则解除 busy 冻结（仅在由非空变空时通知）
                            if (INFLIGHT.isEmpty()) {
                                setBusy(false);
                            }
                        } else {
                            // 有最新待办：用最新参数接着跑（不重新入队，避免再多一次调度）
                            current = next;
                        }
                    }
                }
                if (current == null) {
                    return;
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

    /** 上一次广播出去的状态（用于「无变化不重复通知」，见 §2.4） */
    private static volatile State lastBroadcast = null;

    /**
     * 广播当前完整状态（读真值，不传参，保证各消费者看到的都是最新），并通知各 listener。
     * §2.4：状态没变则不重复通知——UI 重绘、快捷方式重发、通知重绘都省。
     * 首次（lastBroadcast==null）必定通知；写回弹 / 校准等场景若状态未变，跳过无害
     * （UI 本就显示该值）。消费者首帧由各自主线程 read() 渲染，不依赖 broadcast。
     */
    public static State broadcast(Context ctx) {
        State s = read(ctx);
        if (lastBroadcast != null && lastBroadcast.equals(s)) {
            return s;
        }
        lastBroadcast = s;
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
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
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
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("master", () -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, cb, lastFailReason());
                return;
            }
            // 把刚读到的真值传下去：WSS 路径直接复用，不必再走一次 100~500ms 的 shell 读
            setMasterBlocking(app, !cur.anyOn(), cb, cur);
        });
    }

    /** 主开关写入核心：单次 shell 完成「读前态 → 杀联动应用 → 写入 → 回读后态」 */
    private static void setMasterBlocking(Context app, boolean on, ResultCallback cb) {
        setMasterBlocking(app, on, cb, null);
    }

    /**
     * @param knownBefore toggleMaster 刚读到的真值（WSS 路径直接复用，省一次 shell 读）；null=自己读
     */
    private static void setMasterBlocking(Context app, boolean on, ResultCallback cb,
                                          State knownBefore) {
        MasterMerge m = masterMergeWrite(app, on, knownBefore);
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
                final int reason = m.reason;
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
            final int reason = m.reason;
            MAIN.post(() -> cb.onResult(m.wrote, s, reason));
        }
        // WSS 路径的「写后态」是信任写结果算出的期望值（没走 shell 回读）：shell 兜底可用时
        // 补一次异步复核。放在回调之后执行——UI 已即时更新，复核耗时不再占用户等待。
        if (m.trusted) {
            verifyWssWrite(app, s);
        }
    }

    /**
     * 主开关写入的通道分发点（WSS 直写 ↔ su / Shizuku shell），与 {@link #writeKey} 对称：
     * 所有主开关写入都经这里，调用点不再写三目。
     *
     * @param knownBefore 调用方刚读到的真值（toggleMaster 传，省掉 WSS 路径重复一次 shell 读）；
     *                    shell 路径忽略它（单条命令自带读前态标记行）
     */
    private static MasterMerge masterMergeWrite(Context ctx, boolean on, State knownBefore) {
        if (wssWriteActive(ctx)) {
            return masterMergeWriteWss(ctx, on, knownBefore);
        }
        return masterMergeWriteShell(ctx, on);
    }

    /** 单 shell 完成主开关的「读前态 →（打开时杀联动应用）→ 写入 → 回读后态」 */
    private static MasterMerge masterMergeWriteShell(Context ctx, boolean on) {
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
        // API<30 没有无线调试键：不写（写了也是个无人读取的无效键），读侧按 "null" 放行
        if (wirelessSupported()) {
            cmd.append(" settings put global ").append(KEY_WIRELESS).append(' ')
                    .append(on ? (saved.wireless ? '1' : '0') : '0').append(';');
        }
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
        m.reason = r.reason;
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
        w.reason = r.reason;
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

    /**
     * WSS 信任写后的复核（仅在 MasterMerge.trusted 时调用；运行在 IO 线程）。
     *
     * masterMergeWriteWss 为了不把 100~500ms 的 shell 回读压在点击等待上，直接用期望值提交。
     * 这里补一次同源回读兜底：ROM 静默屏蔽写入（putString 返回 true 但值其实没变）时纠正
     * 缓存并广播。（注意：putString 返回 false 的情况已在 masterMergeWriteWss / writeKeyWss
     * 里被标记成 REASON_WRITE_DROPPED 拒绝提交，不会走到这里。）
     * 纯 WSS 设备没有可信回读通道（ContentResolver 可能被 ROM 隐藏），跳过——做了反而误导。
     * 调用方把它放在写回调之后执行：UI 已拿到即时反馈，复核耗时不再占用户等待。
     */
    private static void verifyWssWrite(Context app, State expected) {
        if (!ShizukuUtil.available()) {
            return;
        }
        try {
            State real = readViaShellOrWss(app);
            if (real != null && (real.usb != expected.usb || real.wireless != expected.wireless
                    || real.dev != expected.dev)) {
                android.util.Log.w(TAG, "wss write verify mismatch, corrected: expected usb="
                        + expected.usb + " wifi=" + expected.wireless + " dev=" + expected.dev
                        + " -> real usb=" + real.usb + " wifi=" + real.wireless
                        + " dev=" + real.dev);
                cache = new Snapshot(real, android.os.SystemClock.uptimeMillis());
                persistLast(app, real);
                broadcast(app);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "wss write verify failed", t);
        }
    }

    /**
     * 「开启调试前杀联动应用」的可用通道能力（由 MainActivity 在授权检测时填充）。
     * 免 root 模式下 UI 线程不能 fork su，故 root 可用性由后台线程的 performAuthCheck
     * 探测后写到这里；Shizuku 可用性则运行时现查（廉价、线程安全）。
     */
    private static volatile boolean sRootCapable = false;
    static void setRootCapable(boolean v) { sRootCapable = v; }
    static boolean isRootCapable() { return sRootCapable; }

    /** 杀应用能力缓存的三态取值 */
    static final int KILL_CAP_UNKNOWN = 0;
    static final int KILL_CAP_OK = 1;
    static final int KILL_CAP_DENIED = 2;

    /** 上次「自动关闭快捷应用」的真实结果（未知 / 成功 / 授权失败），跨启动保留。 */
    static int killCapability(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.contains(KILL_CAPABLE)) {
            return KILL_CAP_UNKNOWN;
        }
        return sp.getBoolean(KILL_CAPABLE, true) ? KILL_CAP_OK : KILL_CAP_DENIED;
    }

    /** 记录一次真实执行结果（成功 / 授权失败），供免 root 模式下把开关显示与实际对齐。 */
    static void setKillCapable(Context ctx, boolean ok) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KILL_CAPABLE, ok).apply();
    }

    /** 切换授权模式后旧结论作废（另一条通道的能力与是否授权都不同）。 */
    static void clearKillCapability(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KILL_CAPABLE).apply();
    }

    /** 最近一次「开启调试」是否因 Shizuku/root 皆不可用而未能杀联动应用（供 UI 一次性提示） */
    private static volatile boolean sKillSkipped = false;
    static boolean consumeKillSkipped() {
        boolean v = sKillSkipped;
        sKillSkipped = false;
        return v;
    }

    /**
     * 最近一次杀应用是否「有通道但授权被拒 / 已撤销」（区别于 {@link #sKillSkipped} 的「根本没有通道」）。
     * 两类失败的处理办法不同：前者要回到 root 管理器 / Shizuku 里补授权，后者只能换模式或装 root，
     * 所以提示文案必须分开，否则用户不知道该往哪走。
     */
    private static volatile boolean sKillDenied = false;
    static boolean consumeKillDenied() {
        boolean v = sKillDenied;
        sKillDenied = false;
        return v;
    }

    /**
     * 「开启调试前杀联动应用」：按可用通道代跑 am force-stop。
     *  - Shizuku 可用：借 Shizuku（shell uid 2000）；
     *  - 否则若设备有 root（sRootCapable）：借 su；
     *  - 两者皆无（纯免 root 模式、Shizuku/root 均不可用）：杀不掉，置 sKillSkipped 让 UI 告知，
     *    不再像旧实现那样静默吞掉（用户完全不知情）。
     * shell 模式把杀进程合并进同一条命令（省一次 fork），不走这里。
     */
    private static void killLinkageIfNeeded(Context ctx, boolean opening) {
        if (!opening) {
            return;
        }
        String k = killScriptFor(ctx, true);
        if (k == null || k.isEmpty()) {
            return;
        }
        if (ShizukuUtil.available()) {
            recordKillResult(ctx, ShizukuUtil.run(k));
        } else if (sRootCapable) {
            recordKillResult(ctx, RootUtil.su(k));
        } else {
            android.util.Log.w(TAG, "kill linkage skipped: neither Shizuku nor root available");
            sKillSkipped = true;
        }
    }

    /**
     * 记录「自动关闭快捷应用」的真实执行结果（见 {@link #KILL_CAPABLE}）。
     *
     * <p>旧实现把 {@code su / ShizukuUtil.run} 的返回值直接丢弃，于是免 root 模式下
     * 「su 二进制存在但从未授权」时：开关按 {@link RootUtil#suBinaryExists()} 乐观显示为可用，
     * 真到开调试却静默失败——快捷应用照样读到调试态，用户毫不知情。这里把授权类失败
     * 也接到 {@link #sKillSkipped} 上，让 UI 明确提示；成功则记住，之后显示就是准的。
     *
     * <p>EXEC / TIMEOUT 不是授权问题（ROM 拦截 / 卡住），不动缓存，避免把偶发故障记成「没权限」。
     */
    private static void recordKillResult(Context ctx, RootUtil.Result r) {
        if (r == null) {
            return;
        }
        if (r.ok()) {
            setKillCapable(ctx, true);
            return;
        }
        if (r.reason == RootUtil.REASON_DENIED || r.reason == RootUtil.REASON_NO_SU
                || r.reason == RootUtil.REASON_NO_BINDER
                || r.reason == RootUtil.REASON_NO_PERMISSION) {
            android.util.Log.w(TAG, "kill linkage denied (reason=" + r.reason + ")");
            setKillCapable(ctx, false);
            sKillDenied = true;
        }
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
        // 包名必须过白名单校验：这些字符串来自 SharedPreferences，一旦被写入含 shell
        // 元字符（; $() | & ` 等）的值，就会以 root / uid 2000 权限执行任意命令。
        // 入口（LinkagePrefs.setApps）已挡过一轮，这里作为特权消费侧再兜底一次。
        StringBuilder cmd = new StringBuilder();
        for (String pkg : apps) {
            if (!LinkagePrefs.isValidPackageName(pkg)) {
                android.util.Log.w(TAG, "skip invalid linkage package name in kill script");
                continue;
            }
            if (cmd.length() > 0) {
                cmd.append("; ");
            }
            cmd.append("am force-stop ").append(pkg);
        }
        return cmd.toString();
    }

    /** 读不到真值、拒绝执行时统一收口：回传失败原因 + 当前缓存，让 UI 提示重试/切换模式并保持一致 */
    private static void failWithoutWrite(Context app, ResultCallback cb, int reason) {
        State last = read(app);
        android.util.Log.w(TAG, "privileged read failed, refuse to act (auth lost or channel hung)");
        if (cb != null) {
            MAIN.post(() -> cb.onResult(false, last, reason));
        }
    }

    /**
     * WSS 模式尚未拿到那一次性的 pm grant 时，拒绝把写入静默转去 root / Shizuku 兜底通道。
     *
     * getEffectiveMode 在 WSS 未授权时会回落 root / Shizuku（初衷是避免直接失效），后果却是：
     * 无 root 设备上用户点一下开关，等到一句「未检测到 root」——真正该做的是完成一次性授权，
     * 却被引导去装 root。故所有写入口统一拦下并回传 REASON_NEED_GRANT（§3.2：reason 随返回值走，
     * 不再写全局 lastFailReason）：App 内点击由 UI 弹一次性授权引导；磁贴 / 快捷方式等无界面入口
     * 则给对应 Toast。
     *
     * @return 阻塞原因（REASON_NONE 表示未阻塞）
     */
    private static int blockedByMissingGrant(Context ctx) {
        if (AuthManager.getMode(ctx) == AuthManager.Mode.SECURE_SETTINGS
                && !AuthManager.hasWriteSecureSettings(ctx)) {
            return RootUtil.REASON_NEED_GRANT;
        }
        return RootUtil.REASON_NONE;
    }

    /**
     * 子开关写入的通道分发点（WSS 直写 ↔ su / Shizuku shell），与 {@link #masterMergeWrite} 对称：
     * 六个 set / toggle 入口都走这里，新增写通道只改这一个函数，不必再翻六处三目。
     *
     * @param kill 是否允许「开启前杀联动应用」（dev 开关传 false：它不参与调试态）
     */
    private static WriteResult writeKey(Context ctx, String key, boolean on, boolean kill) {
        if (wssWriteActive(ctx)) {
            return writeKeyWss(ctx, key, on, kill);
        }
        return writeKeyReadback(ctx, kill ? killScriptFor(ctx, on) : null,
                "settings put global " + key + " " + (on ? "1" : "0"));
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
                final int reason = w.reason;
                MAIN.post(() -> cb.onResult(false, last, reason));
            }
            return;
        }
        State s = commitAfter(app, w.after);
        if (cb != null) {
            final int reason = w.reason;
            MAIN.post(() -> cb.onResult(w.ok, s, reason));
        }
    }

    /** 单独改 USB 调试（不动 saved） */
    public static void setUsb(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("usb", () -> {
            WriteResult w = writeKey(app, KEY_USB, on, true);
            finishSubWrite(app, w, cb);
        });
    }

    /** 单独翻转 USB 调试：先经授权通道读真值再翻，读不到真值则拒绝 */
    public static void toggleUsb(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("usb", () -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, cb, lastFailReason());
                return;
            }
            boolean on = !cur.usb;
            // kill 传 on：只有「正在打开」才需要杀联动应用
            WriteResult w = writeKey(app, KEY_USB, on, on);
            finishSubWrite(app, w, cb);
        });
    }

    /** 单独改无线调试（不动 saved） */
    public static void setWireless(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("wireless", () -> {
            WriteResult w = writeKey(app, KEY_WIRELESS, on, true);
            finishSubWrite(app, w, cb);
        });
    }

    /** 单独翻转无线调试：先读真值再翻；读不到真值则拒绝 */
    public static void toggleWireless(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("wireless", () -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, cb, lastFailReason());
                return;
            }
            boolean on = !cur.wireless;
            WriteResult w = writeKey(app, KEY_WIRELESS, on, on);
            finishSubWrite(app, w, cb);
        });
    }

    /**
     * 单独改开发者选项入口（不动 saved、不动 adb / 无线）。
     * 与 setUsb / setWireless 同一套节奏：不杀快捷应用，写完回读真值再广播。
     */
    public static void setDev(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        // 开发者选项不参与调试态、不联动快捷应用，无需杀进程（kill=false）
        enqueue("dev", () -> {
            WriteResult w = writeKey(app, KEY_DEV, on, false);
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

    /** 单键写入结果：是否成功 + 写入后回读真值 + 失败原因（随返回值走，见 §3.2） */
    private static final class WriteResult {
        boolean ok;
        State after;
        int reason = RootUtil.REASON_NONE;
    }

    /** 主开关合并写结果：写前态（关时记忆用）+ 写后态 + 是否成功写过 + 失败原因（见 §3.2） */
    private static final class MasterMerge {
        State before;
        State after;
        boolean wrote;
        /** after 是「信任写结果」算出的期望值（未经回读验证）时为 true，调用方需异步复核 */
        boolean trusted;
        int reason = RootUtil.REASON_NONE;
    }
}
