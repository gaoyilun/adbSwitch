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
    /** 无障碍服务列表（settings get secure）：第三方应用读该键可能被 ROM 脱敏，shell 读才可信 */
    private static final String TAG_A11Y_B = "__ASW_A11Y_B__";
    /**
     * 无线调试的「底层意图」属性：adb_wifi_enabled（key）会被系统在无 Wi-Fi / 未授权网络时
     * 自行写回 0，而 persist.adb.tls_server.enable（prop）仍为 1；仅作显示辅助细分「未激活」态，
     * 绝不参与开关判定、回读确认、saved 记忆或主开关聚合（底层逻辑保持上一版不变）。
     * 只有 shell 通道（root / Shizuku）能读；纯 WSS 读不到 → 记 -1 → 维持两态显示。
     */
    private static final String TAG_WIFI_PROP = "__ASW_WIFIPROP__";
    private static final String PROP_WIFI_INTENT = "persist.adb.tls_server.enable";

    private static final String PREFS = "cfg";
    private static final String SAVED_USB = "saved_usb";
    private static final String SAVED_WIRELESS = "saved_wireless";
    private static final String SAVED_DEV = "saved_dev";
    /** 记忆的第三方无障碍列表（master 关时存原始 enabled_accessibility_services 串） */
    private static final String SAVED_A11Y = "saved_a11y_list";
    private static final String SAVED_VALID = "saved_valid";
    /** 「开启调试自动关闭快捷应用」开关（与 MainActivity 共用同一份 cfg） */
    private static final String PREF_KILL_BEFORE_OPEN = "kill_before_open";
    /** 上次经授权通道确认过的状态（跨启动保留，供未校准期间界面显示用） */
    private static final String LAST_USB = "last_usb";
    private static final String LAST_WIRELESS = "last_wireless";
    private static final String LAST_DEV = "last_dev";
    private static final String LAST_A11Y = "last_a11y_count";
    /** 无障碍恢复未成功的第三方服务数（持久化摘要「仍有 N 个需手动开启」的数据源） */
    private static final String A11Y_RESTORE_PENDING = "a11y_restore_pending";
    /** 恢复未成功的具体条目（懒复核自愈用；旧版本残留只有计数没有条目） */
    private static final String A11Y_RESTORE_PENDING_ENTRIES = "a11y_restore_pending_entries";
    /** 无障碍恢复后的一次性提示（UI 侧消费后清掉） */
    private static final String A11Y_RESTORE_TOAST = "a11y_restore_toast";
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

    /**
     * 开关项的组合状态（通用项集合：usb / wireless / dev / a11y 各自独立，主开关按配置聚合）。
     *
     * anyOn()/allOff() 保留 adbd 语义（USB 或无线任一开 = adbd 在跑）—— 通知栏、
     * RASP 检测文案仍按此判定；主开关的聚合走 {@link #anyOn(java.util.Collection)} /
     * {@link #allOff(java.util.Collection)}（按 SwitchConfig.followedItems 聚合）。
     */
    public static final class State {
        public final boolean usb;
        public final boolean wireless;
        /** 开发者选项入口是否开启：独立开关，不影响 adbd 是否运行 */
        public final boolean dev;
        /** 已启用的第三方无障碍服务条目数（0 = 无）；-1 = 未知（parseState 纯函数的占位，提交前会被补真值） */
        public final int a11yCount;
        /**
         * 无线调试底层意图属性 persist.adb.tls_server.enable：-1 = 未读到（纯 WSS 或读失败，
         * 显示退化为两态）/ 0 = 底层关 / 1 = 底层开。**只用于 UI 细分「未激活」态**，
         * 不参与 anyOn/allOff/isOn 等任何开关判定（那些只看 key，保持上一版语义）。
         */
        public final int wifiProp;

        State(boolean usb, boolean wireless, boolean dev) {
            this(usb, wireless, dev, -1, -1);
        }

        State(boolean usb, boolean wireless, boolean dev, int a11yCount) {
            this(usb, wireless, dev, a11yCount, -1);
        }

        State(boolean usb, boolean wireless, boolean dev, int a11yCount, int wifiProp) {
            this.usb = usb;
            this.wireless = wireless;
            this.dev = dev;
            this.a11yCount = a11yCount;
            this.wifiProp = wifiProp;
        }

        /** 只读附加字段：把 shell 读到的底层意图态附加到既有 State（不可变，返回新实例） */
        State withWifiProp(int p) {
            if (this.wifiProp == p) {
                return this;
            }
            return new State(usb, wireless, dev, a11yCount, p);
        }

        /** 无线调试是否处于「key 关但底层意图仍开」（系统因无 Wi-Fi / 未授权网络写回 key=0） */
        public boolean wirelessInactive() {
            return !wireless && wifiProp == 1;
        }

        /** adbd 是否在跑（USB 或 无线任一开着）→ 会被检测为调试态（不含 dev / a11y） */
        public boolean anyOn() {
            return usb || wireless;
        }

        /** adbd 已停止（USB 与 无线都关）→ 通过检测（不含 dev / a11y） */
        public boolean allOff() {
            return !usb && !wireless;
        }

        /** 该项当前是否开启（无障碍以「有第三方服务启用」为开） */
        public boolean isOn(SwitchItem item) {
            switch (item) {
                case USB:
                    return usb;
                case WIRELESS:
                    return wireless;
                case DEV:
                    return dev;
                case A11Y:
                    return a11yCount > 0;
                default:
                    return false;
            }
        }

        /** 在给定项集合内任一开启（主开关聚合语义：跟随主开关的项中任一开 = 主开关开） */
        public boolean anyOn(java.util.Collection<SwitchItem> items) {
            for (SwitchItem i : items) {
                if (isOn(i)) {
                    return true;
                }
            }
            return false;
        }

        /** 在给定项集合内全部关闭（主开关聚合语义） */
        public boolean allOff(java.util.Collection<SwitchItem> items) {
            for (SwitchItem i : items) {
                if (isOn(i)) {
                    return false;
                }
            }
            return true;
        }

        /** §2.4：状态比较用于「无变化不重复广播」，避免 UI 重绘 / 快捷方式重发 / 通知重绘 */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State s = (State) o;
            return usb == s.usb && wireless == s.wireless && dev == s.dev
                    && a11yCount == s.a11yCount && wifiProp == s.wifiProp;
        }

        @Override
        public int hashCode() {
            int h = 17;
            h = 31 * h + (usb ? 1 : 0);
            h = 31 * h + (wireless ? 1 : 0);
            h = 31 * h + (dev ? 1 : 0);
            h = 31 * h + a11yCount;
            h = 31 * h + wifiProp;
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
            // 三个 GLOBAL 键的 ContentResolver 回读被 ROM 隐藏/欺骗，不能跑校准（见上）；
            // 但无障碍列表键（Settings.Secure.enabled_accessibility_services）的
            // ContentResolver 读取是可信的（A11yHelper 类注释）——外部在系统设置里增删
            // 无障碍服务后，必须单独把第三方服务数刷回来，否则缓存与 LAST_A11Y 会一直
            // 停在上次写入值（实测 bug：改系统设置返回 App 数量不刷新，只有 App 内
            // 关了再开才变 —— 因为此前只有写路径 commitAfter 会重数，本分支直接 return）。
            // GLOBAL 三键仍维持「上次确认状态 + 自身写入」，绝不用假回读污染。
            CALIBRATE.execute(() -> {
                int n = A11yHelper.countThirdPartyNow(app);
                Snapshot c2 = cache;
                State base = c2 != null ? c2.state : lastKnownState(app);
                if (base.a11yCount == n) {
                    return; // 数量没变：缓存/LAST_A11Y 本就正确
                }
                android.util.Log.d(TAG, "a11y count partial refresh: " + base.a11yCount + " -> " + n);
                // countThirdPartyNow 含 PM IPC，耗时期间 IO 线程可能已由 commitAfter 提交新全局态。
                // 写回前重读一次 cache，只把 a11yCount 合并到「最新」快照，全局三键取最新值——
                // 绝不用 IPC 前读到的 base.usb/wireless/dev 覆盖已提交状态（否则 UI 倒退且
                // persistLast 落盘成跨启动假状态）。
                Snapshot current = cache;
                State latest = current != null ? current.state : base;
                State s = new State(latest.usb, latest.wireless, latest.dev, n);
                boolean confirmedBefore = hasConfirmedBefore(app);
                // 只有存在「上次确认状态」（GLOBAL 三键落盘值可信）才整体落盘；
                // 首次使用时全局值来自 ContentResolver 快读（可能被 ROM 伪造），
                // 绝不能写进 LAST_* 冒充确认过 —— 只更新内存并广播即可。
                cache = new Snapshot(s, confirmedBefore
                        ? android.os.SystemClock.uptimeMillis()
                        : (current != null ? current.at : 0));
                if (confirmedBefore) {
                    persistLast(app, s);
                }
                broadcast(app);
            });
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
            return new State(readUsb(ctx), readWireless(ctx), readDev(ctx),
                    A11yHelper.countThirdPartyNow(ctx));
        }
        // 老版本升级上来没有 LAST_DEV / LAST_A11Y：缺哪个就退回快读，不硬塞 false
        return new State(sp.getBoolean(LAST_USB, false),
                sp.getBoolean(LAST_WIRELESS, false),
                sp.contains(LAST_DEV) ? sp.getBoolean(LAST_DEV, false) : readDev(ctx),
                sp.contains(LAST_A11Y) ? sp.getInt(LAST_A11Y, 0)
                        : A11yHelper.countThirdPartyNow(ctx));
    }

    /** 记下经授权通道确认过的状态（后台线程调用；apply 异步落盘即可） */
    private static void persistLast(Context app, State s) {
        confirmedThisSession = true;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(LAST_USB, s.usb)
                .putBoolean(LAST_WIRELESS, s.wireless)
                .putBoolean(LAST_DEV, s.dev)
                .putInt(LAST_A11Y, s.a11yCount)
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
        // a11y 已由 readViaShellOrWss 内部的 shell 读顺带取回（ROM 脱敏时唯一可信来源）
        return enrichA11y(app, readViaShellOrWss(app), null);
    }

    /**
     * parseState 是纯函数（不便做 PM 查询），产出的 State 带 a11yCount=-1 占位；
     * 此处补上第三方无障碍服务真值。
     *
     * @param a11yRaw 同一通道顺带读到的列表真值；null = 没读到（回退 ContentResolver 直读）
     */
    private static State enrichA11y(Context ctx, State s, String a11yRaw) {
        if (s == null || s.a11yCount >= 0) {
            return s;
        }
        int count = a11yRaw != null
                ? A11yHelper.countThirdParty(ctx, a11yRaw)
                : A11yHelper.countThirdPartyNow(ctx);
        return new State(s.usb, s.wireless, s.dev, count, s.wifiProp);
    }

    /** 从合并 shell 输出里按标记行取出某个值；标记行缺失返回 null（= 通道没读到，不是空值） */
    private static String tagValue(String out, String tag) {
        if (out == null) {
            return null;
        }
        for (String line : out.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.startsWith(tag + "=")) {
                return s.substring(tag.length() + 1).trim();
            }
        }
        return null;
    }

    /**
     * 取合并读输出里的无障碍列表真值，并把「ContentResolver vs shell」的差异交给 A11yHelper 记账
     * （荣耀等 ROM 会把该键对第三方脱敏成空串，只有 shell 读得到真值）。
     *
     * @return null = 标记行缺失（通道异常），调用方回退 ContentResolver 读
     */
    private static String a11yRawFromOut(Context ctx, String out) {
        String v = tagValue(out, TAG_A11Y_B);
        if (v == null) {
            return null;
        }
        // settings get 对「从未设置过的键」返回字面量 null，语义上是「列表为空」
        String raw = "null".equals(v) ? "" : v;
        A11yHelper.noteTrustedRead(A11yHelper.readRawList(ctx), raw);
        return raw;
    }

    /**
     * 写路径用的无障碍列表（写前态 / 关闭时过滤用）。
     *
     * 与界面读的区别：这里**宁可多 fork 一次 shell**。ROM 脱敏时 ContentResolver 读到空串，
     * 若把它当真值，「关闭」算出的目标列表就是空表 —— 写下去会把系统预装的无障碍服务
     * （TalkBack 之类）一起抹掉。写路径本来就在 IO 线程，多一次读的代价可忽略。
     *
     * 无 shell 通道（纯 WSS）时不做额外读，仍返回 ContentResolver 值；
     * 「读到空但服务其实已绑定」的矛盾由 {@link #hasBoundA11y} 交叉验证，此时拒绝写入
     * （见 {@link #writeA11yBlocking}），绝不把空表当写前态。
     */
    private static String readA11yRawForWrite(Context ctx) {
        String cr = A11yHelper.readRawList(ctx);
        if (!android.text.TextUtils.isEmpty(cr)) {
            return cr;
        }
        // WSS 尚未拿到一次性 pm grant 时绝不静默 fork su（与 readViaShellOrWss 同一立场）
        if (AuthManager.getMode(ctx) == AuthManager.Mode.SECURE_SETTINGS
                && !AuthManager.hasWriteSecureSettings(ctx)) {
            return cr;
        }
        RootUtil.Result r;
        if (AuthManager.getEffectiveMode(ctx) == AuthManager.Mode.SECURE_SETTINGS) {
            if (!ShizukuUtil.available()) {
                return cr;
            }
            r = ShizukuUtil.run(SHELL_READ_A11Y_CMD);
        } else {
            r = runPrivileged(ctx, SHELL_READ_A11Y_CMD);
        }
        if (!r.ok()) {
            return cr;
        }
        String v = tagValue(r.out, TAG_A11Y_B);
        if (v == null) {
            return cr;
        }
        String raw = "null".equals(v) ? "" : v;
        A11yHelper.noteTrustedRead(cr, raw);
        return raw;
    }

    /** 主开关聚合态：跟随主开关的项中任一开启（UI / 磁贴的「主开关开/关」显示） */
    public static boolean masterAnyOn(Context ctx, State s) {
        return s != null && s.anyOn(SwitchConfig.followedItems(ctx));
    }

    /** 主开关聚合态：跟随主开关的项全部关闭 */
    public static boolean masterAllOff(Context ctx, State s) {
        return s == null || s.allOff(SwitchConfig.followedItems(ctx));
    }

    /** WSS 模式下的合并读命令（与 readViaPrivileged 原内联命令一致，抽成常量复用） */
    private static final String SHELL_READ_CMD =
            "echo " + TAG_USB_B + "=$(settings get global " + KEY_USB + ");"
                    + " echo " + TAG_WIFI_B + "=$(settings get global " + KEY_WIRELESS + ");"
                    + " echo " + TAG_DEV_B + "=$(settings get global " + KEY_DEV + ");"
                    + " echo " + TAG_WIFI_PROP + "=$(getprop " + PROP_WIFI_INTENT + ");"
                    + " echo " + TAG_A11Y_B + "=$(settings get secure " + A11yHelper.KEY + ")";

    /**
     * 从 shell 输出里解析无线底层意图属性，返回 1 / 0 / -1（未读到）：
     * getprop 对不存在的属性返回空行，非 0/1 一律按「未读到」处理 → UI 维持两态，绝不据此改判定。
     */
    static int parseWifiProp(String out, String p) {
        String v = tagValue(out, p);
        return "1".equals(v) ? 1 : "0".equals(v) ? 0 : -1;
    }

    /** 把 shell 顺带读到的底层意图态附加到 State（仅 shell 通道调用；读不到保持 -1） */
    private static State attachWifiProp(State s, String out) {
        return s == null ? null : s.withWifiProp(parseWifiProp(out, TAG_WIFI_PROP));
    }

    /** 单读无障碍列表（写路径在 ContentResolver 读空时现场补一次 shell 读） */
    private static final String SHELL_READ_A11Y_CMD =
            "echo " + TAG_A11Y_B + "=$(settings get secure " + A11yHelper.KEY + ")";

    /** 实际写通道是否为 WSS（默认推荐）：WSS 已授权时走应用进程内直写，无需 root / Shizuku */
    private static boolean wssWriteActive(Context ctx) {
        return AuthManager.getEffectiveMode(ctx) == AuthManager.Mode.SECURE_SETTINGS;
    }

    /**
     * 纯 WSS（已授权 WRITE_SECURE_SETTINGS）但无任何 shell 兜底（Shizuku 不可用）时，
     * ContentResolver 读这三个键会被 ROM 隐藏/欺骗（HyperOS 上尤甚），回读不可信 ——
     * 写后只能信任自身写入 + 持久化的「上次确认状态」（lastKnownState），不再把被隐藏的
     * ContentResolver 读当真值。这正是 HyperOS 上「免 root 模式读不到状态、也拨不动开关」
     * 的根因与对策；标准 WSS API 下纯 WSS 路径也只能是 best-effort。
     */
    private static boolean wssTrustWriteOnly(Context ctx) {
        return AuthManager.getEffectiveMode(ctx) == AuthManager.Mode.SECURE_SETTINGS
                && !ShizukuUtil.available();
    }

    /**
     * WSS 直写单个键（无需 root / Shizuku）。
     * 这里用 {@code Settings.Global.putString}（字符串 API）而非 putInt，
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
                getInt(ctx, KEY_DEV) == 1,
                A11yHelper.countThirdPartyNow(ctx));
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
            return enrichA11y(ctx,
                    attachWifiProp(parseState(r.out, TAG_USB_B, TAG_WIFI_B, TAG_DEV_B), r.out),
                    a11yRawFromOut(ctx, r.out));
        }
        if (ShizukuUtil.available()) {
            RootUtil.Result r = ShizukuUtil.run(SHELL_READ_CMD);
            return enrichA11y(ctx,
                    attachWifiProp(parseState(r.out, TAG_USB_B, TAG_WIFI_B, TAG_DEV_B), r.out),
                    a11yRawFromOut(ctx, r.out));
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
                    KEY_DEV.equals(key) ? on : before.dev,
                    before.a11yCount) : null;
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
     *     退化为都写；
     *  2. 写后态直接用「期望值」提交，不再同步走一次 shell 回读 —— WSS+Shizuku 设备上那是
     *     一次 100~500ms 的 newProcess，全压在用户的点击等待上。ROM 静默屏蔽写入（putInt
     *     不抛错但值没变）的情况由 setMasterBlocking 末尾的 {@link #verifyWssWrite} 复核兜底；
     *  3. API<30 没有无线键，直接跳过（不写无人读取的无效键）。
     *
     * 只写 {@code followed} 里的全局键（usb / wireless / dev）；a11y 由 setMasterBlocking 单独走
     * {@link #writeA11yBlocking}（列表值不是 0/1，无法与全局键合并）。
     *
     * @param knownBefore 调用方刚读到的真值（toggleMaster 已读过一次，不必再读）；null=现读
     */
    private static MasterMerge masterMergeWriteWss(Context ctx, boolean on, State knownBefore,
                                                   java.util.EnumSet<SwitchItem> followed) {
        boolean trustOnly = wssTrustWriteOnly(ctx);
        // 纯 WSS 无 shell 兜底：before 用本地持久化的「上次确认状态」（回读被隐藏不可信）；
        // 否则优先用调用方刚读到的真值，避免重复读。
        State before = trustOnly ? lastKnownState(ctx)
                : (knownBefore != null ? knownBefore : readViaShellOrWss(ctx));
        // 「开启调试自动关闭快捷应用」只在真的要开调试键（usb / wireless 跟随）时才有意义：
        // 它的目的是防快捷应用在 adbd 状态翻转瞬间读到调试态；只动 dev / a11y 时没这个翻转
        if (on && (followed.contains(SwitchItem.USB) || followed.contains(SwitchItem.WIRELESS))) {
            killLinkageIfNeeded(ctx, on);
        }
        boolean tUsb = on && savedItemOn(ctx, SwitchItem.USB, true);
        boolean tWifi = on && savedItemOn(ctx, SwitchItem.WIRELESS, false);
        boolean tDev = on && savedItemOn(ctx, SwitchItem.DEV, false);
        boolean wroteAny = false;
        // 任一键失败即整体失败（与 shell 路径同一条不变式）：保留第一个非 NONE 的失败原因，
        // 绝不让后一次成功覆盖前一次失败——否则 USB 被 ROM 丢弃而无线写成功时会提交假成功，
        // 纯 WSS 设备没有 verifyWssWrite 兜底，假状态会直接进缓存
        int reason = RootUtil.REASON_NONE;
        // 关闭（!on）时**强制写目标值为 0**，不参与「与 before 相同就跳过」的 diff 优化：
        // 纯 WSS 无 shell 兜底时 before 来自持久化的「上次确认状态」，可能已过期（例如调试在别处
        // 被打开、或上次写入被 ROM 静默丢弃后又记为成功）。若按过期 before 判定「已是目标态」而
        // 跳过写入，就会出现「关调试成功」（回调 ok=true、界面/快捷方式照常跳转）但系统里调试其实
        // 还开着——正是用户反馈的「桌面快捷方式只跳转、不关调试」。关闭不是高频操作，多写一次
        // Binder 的代价可忽略，换来的是「关就必须真关」的确定性。
        if (followed.contains(SwitchItem.USB)) {
            if (!on || before == null || before.usb != tUsb) {
                int r = putWss(ctx, KEY_USB, tUsb ? 1 : 0);
                if (reason == RootUtil.REASON_NONE) reason = r;
                wroteAny = true;
            }
        }
        if (followed.contains(SwitchItem.WIRELESS) && wirelessSupported()) {
            if (!on || before == null || before.wireless != tWifi) {
                int r = putWss(ctx, KEY_WIRELESS, tWifi ? 1 : 0);
                if (reason == RootUtil.REASON_NONE) reason = r;
                wroteAny = true;
            }
        }
        if (followed.contains(SwitchItem.DEV)) {
            if (!on || before == null || before.dev != tDev) {
                int r = putWss(ctx, KEY_DEV, tDev ? 1 : 0);
                if (reason == RootUtil.REASON_NONE) reason = r;
                wroteAny = true;
            }
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
            // 写后态 = before 把跟随项替换成目标值（非跟随项保持 before 值不动）
            m.after = new State(
                    followed.contains(SwitchItem.USB) ? tUsb : before.usb,
                    followed.contains(SwitchItem.WIRELESS) ? tWifi : before.wireless,
                    followed.contains(SwitchItem.DEV) ? tDev : before.dev,
                    before.a11yCount);
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
        // 用 getString 读再解析，而非 getInt。
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
            case RootUtil.REASON_READ_HIDDEN:
                return ctx.getString(R.string.toast_a11y_read_hidden);
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
     * 现改为：提交侧 synchronized 内先查 INFLIGHT，命中就把最新任务记进 PENDING 并直接
     * return（不提交新任务，被覆盖者的回调并入补发列表）；未命中才加锁占有 INFLIGHT 并提交一次
     * IO 任务。真正执行只在持有 INFLIGHT 的那一条 IO 任务里发生，收尾时拾取 PENDING 的最新参数接着跑。
     */
    private static final java.util.Set<String> INFLIGHT =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** 待办任务 = 最新一次操作 + 被它覆盖掉的历次回调（收尾时以最终结果统一补发，绝不静默丢） */
    private static final class Pending {
        final WriteTask task;
        final ResultCallback cb;
        final java.util.List<ResultCallback> merged = new java.util.ArrayList<>();
        Pending(WriteTask task, ResultCallback cb) {
            this.task = task;
            this.cb = cb;
        }
    }
    private static final java.util.Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Object ENQUEUE_LOCK = new Object();

    /** 写任务体：回调由 enqueue 统一包装传入（任务自身不直接持有调用方回调） */
    private interface WriteTask {
        void run(ResultCallback cb);
    }

    /** 主开关一键写入的队列键（与子项 "usb"/"wireless"/"dev"/"a11y" 区分开） */
    private static final String KEY_MASTER_WRITE = "master";

    /**
     * §4.7 写入在途冻结：**只跟随主开关自己的写入**，主开关行的 Switch 在
     * {@code setMaster/toggleMaster} 排队期间临时置灰，给出「正在执行」的明确反馈。
     *
     * 为什么不能看「INFLIGHT 非空」：任何子项写入都会让 INFLIGHT 非空，
     * 于是拨一次 USB / 无线 / 开发者选项 / 无障碍，主开关也会被置灰再恢复 ——
     * 表现就是「点子开关时主开关的按钮闪一下」，连未跟随主开关的项也会。
     * 主开关聚合态本来就要等这些写入的回读结果，冻结它并无意义，只是视觉噪声。
     */
    private static volatile boolean sMasterBusy = false;
    private static final java.util.List<Runnable> BUSY_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 主开关自身的写入是否在途（不含子项写入） */
    public static boolean isMasterBusy() {
        return sMasterBusy;
    }

    public static void addBusyListener(Runnable r) {
        BUSY_LISTENERS.add(r);
    }

    public static void removeBusyListener(Runnable r) {
        BUSY_LISTENERS.remove(r);
    }

    /** 按当前在途键重算主开关冻结态；只在状态真正翻转时通知（避免每次提交都刷 UI） */
    private static void updateMasterBusy() {
        boolean b = INFLIGHT.contains(KEY_MASTER_WRITE);
        if (sMasterBusy != b) {
            sMasterBusy = b;
            for (Runnable r : BUSY_LISTENERS) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                    // 单个监听抛错不影响其它消费者
                }
            }
        }
    }

    private static void enqueue(String key, WriteTask task, ResultCallback cb) {
        synchronized (ENQUEUE_LOCK) {
            if (INFLIGHT.contains(key)) {
                // 同键已有任务在途：只执行最新一次（覆盖中间态），由在途任务收尾时拾起。
                // 被覆盖者的回调不丢：并入 merged，最终写完后以同一结果各补发一次。
                Pending old = PENDING.get(key);
                Pending p = new Pending(task, cb);
                if (old != null) {
                    p.merged.addAll(old.merged);
                    if (old.cb != null) {
                        p.merged.add(old.cb);
                    }
                }
                PENDING.put(key, p);
                return;
            }
            INFLIGHT.add(key);
        }
        // §4.7：主开关写入在途，立刻冻结主开关 Switch（子项写入不冻结，见 sMasterBusy 注释）
        updateMasterBusy();
        IO.execute(() -> {
            WriteTask current = task;
            ResultCallback currentCb = cb;
            java.util.List<ResultCallback> merged = java.util.Collections.emptyList();
            while (true) {
                final ResultCallback fcb = currentCb;
                final java.util.List<ResultCallback> fmerged = merged;
                try {
                    current.run((ok, s, reason) -> {
                        if (fcb != null) {
                            fcb.onResult(ok, s, reason);
                        }
                        for (ResultCallback m : fmerged) {
                            try {
                                m.onResult(ok, s, reason);
                            } catch (Throwable ignored) {
                                // 单个补发回调抛错不影响其它消费者
                            }
                        }
                    });
                } catch (Throwable t) {
                    // 任务自身的 bug 绝不能把执行器线程搞死、把待办任务永久吞掉：
                    // 记日志后照常走收尾（清在途标记、拾起最新待办）
                    android.util.Log.e(TAG, "enqueued task crashed", t);
                } finally {
                    synchronized (ENQUEUE_LOCK) {
                        Pending next = PENDING.remove(key);
                        if (next == null) {
                            // 没有待办：释放在途标记，当前任务线结束
                            INFLIGHT.remove(key);
                            current = null;
                        } else {
                            // 有最新待办：用最新参数接着跑（不重新入队，避免再多一次调度）
                            current = next.task;
                            currentCb = next.cb;
                            merged = next.merged;
                        }
                    }
                    // 主开关写完即解除冻结；放在锁外通知，避免持锁时跑 UI 回调
                    updateMasterBusy();
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
        enqueue(KEY_MASTER_WRITE, c -> setMasterBlocking(app, on, c), cb);
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
        enqueue(KEY_MASTER_WRITE, c -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, c, lastFailReason());
                return;
            }
            // 把刚读到的真值传下去：WSS 路径直接复用，不必再走一次 100~500ms 的 shell 读。
            // 翻转方向按主开关聚合态（跟随项任一开 = 开），而不是 adbd 态（usb||wireless）——
            // a11y / dev 跟随主开关时二者会分叉。
            setMasterBlocking(app, !masterAnyOn(app, cur), c, cur);
        }, cb);
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
        java.util.EnumSet<SwitchItem> followed = SwitchConfig.followedItems(app);
        boolean wantA11y = followed.contains(SwitchItem.A11Y);
        // a11y 写前列表：优先 ContentResolver，读空时补一次 shell 读（ROM 脱敏时才是真值；
        // 拿脱敏后的空表当写前态，关闭会把系统无障碍服务一起抹掉）
        String a11yRawBefore = wantA11y ? readA11yRawForWrite(app) : null;

        // 全局键合并写（只写跟随主开关的 usb / wireless / dev；a11y 是列表值，单独走一步）
        java.util.EnumSet<SwitchItem> globalFollowed = followed.clone();
        globalFollowed.remove(SwitchItem.A11Y);
        MasterMerge m = masterMergeWrite(app, on, knownBefore, globalFollowed);
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
        // 补 before 的 a11y 计数（parseState 占位 -1），供「关闭时记忆组合」的聚合判定
        State before = m.before;
        if (before != null && before.a11yCount < 0) {
            before = new State(before.usb, before.wireless, before.dev,
                    wantA11y ? A11yHelper.countThirdParty(app, a11yRawBefore) : 0);
        }
        // 关闭时记忆「写前组合」，并只在跟随项真有开着时才记（避免把「全关」这种无效组合写进 saved）
        if (!on && before != null && before.anyOn(followed)) {
            save(app, before, a11yRawBefore, followed);
        }
        // a11y 写入（列表值，无法与全局键合并成一条 settings put）；
        // 恢复待办计数由 writeA11yBlocking 内部异步核对后持久化，这里不再经手
        int reason = m.reason;
        boolean wroteAll = m.wrote;
        if (wantA11y) {
            A11yWriteResult aw = writeA11yBlocking(app, on, a11yRawBefore, false);
            if (!aw.ok) {
                wroteAll = false;
                reason = aw.reason;
            }
        }
        // 组装最终 after：全局键取 m.after，a11y 计数在写后重读（ContentResolver）
        State after = new State(m.after.usb, m.after.wireless, m.after.dev,
                A11yHelper.countThirdPartyNow(app));
        State s = commitAfter(app, after);
        if (cb != null) {
            final int r = reason;
            final boolean ok = wroteAll;
            MAIN.post(() -> cb.onResult(ok, s, r));
        }
        // WSS 路径的「写后态」是信任写结果算出的期望值（没走 shell 回读）：shell 兜底可用时
        // 补一次异步复核。放在回调之后执行——UI 已即时更新，复核耗时不再占用户等待。
        if (m.trusted) {
            verifyWssWrite(app, s);
        }
    }

    /**
     * 主开关写入的通道分发点（WSS 直写 ↔ su / Shizuku shell），与 {@link #writeKey} 对称：
     * 所有主开关的全局键写入都经这里，调用点不再写三目。
     *
     * @param knownBefore 调用方刚读到的真值（toggleMaster 传，省掉 WSS 路径重复一次 shell 读）；
     *                    shell 路径忽略它（单条命令自带读前态标记行）
     * @param followed    跟随主开关的项集合（只写这些全局键；a11y 由 setMasterBlocking 单独处理）
     */
    private static MasterMerge masterMergeWrite(Context ctx, boolean on, State knownBefore,
                                                java.util.EnumSet<SwitchItem> followed) {
        if (wssWriteActive(ctx)) {
            return masterMergeWriteWss(ctx, on, knownBefore, followed);
        }
        return masterMergeWriteShell(ctx, on, followed);
    }

    /** 单 shell 完成主开关的「读前态 →（打开时杀联动应用）→ 写入 → 回读后态」；只写 followed 里的全局键 */
    private static MasterMerge masterMergeWriteShell(Context ctx, boolean on,
                                                     java.util.EnumSet<SwitchItem> followed) {
        StringBuilder cmd = new StringBuilder();
        // 1) 写前真值（全读，供 State / 记忆组合用；a11y 另走 ContentResolver）
        cmd.append("echo ").append(TAG_USB_B).append("=$(settings get global ").append(KEY_USB).append(");");
        cmd.append(" echo ").append(TAG_WIFI_B).append("=$(settings get global ").append(KEY_WIRELESS).append(");");
        cmd.append(" echo ").append(TAG_DEV_B).append("=$(settings get global ").append(KEY_DEV).append(");");
        // 2) 写入（打开时先杀联动应用、再还原记忆组合；关闭时直接全关；只写跟随主开关的项）
        // 杀联动应用仅在真要开调试键时执行（与 masterMergeWriteWss 同条件）
        if (on && (followed.contains(SwitchItem.USB) || followed.contains(SwitchItem.WIRELESS))) {
            String kill = killScriptFor(ctx, true);
            if (kill != null && !kill.isEmpty()) {
                cmd.append(' ').append(kill).append(';');
            }
        }
        if (followed.contains(SwitchItem.USB)) {
            cmd.append(" settings put global ").append(KEY_USB).append(' ')
                    .append((on && savedItemOn(ctx, SwitchItem.USB, true)) ? '1' : '0').append(';');
        }
        // API<30 没有无线调试键：不写（写了也是个无人读取的无效键），读侧按 "null" 放行
        if (followed.contains(SwitchItem.WIRELESS) && wirelessSupported()) {
            cmd.append(" settings put global ").append(KEY_WIRELESS).append(' ')
                    .append((on && savedItemOn(ctx, SwitchItem.WIRELESS, false)) ? '1' : '0').append(';');
        }
        if (followed.contains(SwitchItem.DEV)) {
            cmd.append(" settings put global ").append(KEY_DEV).append(' ')
                    .append((on && savedItemOn(ctx, SwitchItem.DEV, false)) ? '1' : '0').append(';');
        }
        // 3) 写后真值回读（prop 仅作显示辅助：写后 key 归零但底层意图仍开时 UI 仍需显示未激活）
        cmd.append(" echo ").append(TAG_USB_A).append("=$(settings get global ").append(KEY_USB).append(");");
        cmd.append(" echo ").append(TAG_WIFI_A).append("=$(settings get global ").append(KEY_WIRELESS).append(");");
        cmd.append(" echo ").append(TAG_DEV_A).append("=$(settings get global ").append(KEY_DEV).append(");");
        cmd.append(" echo ").append(TAG_WIFI_PROP).append("=$(getprop ").append(PROP_WIFI_INTENT).append(");");
        RootUtil.Result r = runPrivileged(ctx, cmd.toString());
        lastOwnWriteAt = android.os.SystemClock.uptimeMillis();
        MasterMerge m = new MasterMerge();
        m.before = parseState(r.out, TAG_USB_B, TAG_WIFI_B, TAG_DEV_B);
        m.after = attachWifiProp(parseState(r.out, TAG_USB_A, TAG_WIFI_A, TAG_DEV_A), r.out);
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
        cmd.append(" echo ").append(TAG_WIFI_PROP).append("=$(getprop ").append(PROP_WIFI_INTENT).append(");");
        RootUtil.Result r = runPrivileged(ctx, cmd.toString());
        lastOwnWriteAt = android.os.SystemClock.uptimeMillis();
        WriteResult w = new WriteResult();
        w.ok = r.ok();
        w.reason = r.reason;
        w.after = attachWifiProp(parseState(r.out, TAG_USB_A, TAG_WIFI_A, TAG_DEV_A), r.out);
        return w;
    }

    /** 写完后用回读真值刷新内存缓存并广播（仅可在后台线程调用）；返回广播出去的状态 */
    private static State commitAfter(Context app, State after) {
        if (after != null) {
            // parseState 是纯函数产出的 State 带 a11yCount=-1 占位；提交缓存前必须补真值，
            // 否则 -1 会进缓存/persistLast，污染后续一切聚合判定与启动兜底显示
            after = enrichA11y(app, after, null);
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
        enqueue("usb", c -> {
            WriteResult w = writeKey(app, KEY_USB, on, true);
            finishSubWrite(app, w, c);
        }, cb);
    }

    /** 单独翻转 USB 调试：先经授权通道读真值再翻，读不到真值则拒绝 */
    public static void toggleUsb(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("usb", c -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, c, lastFailReason());
                return;
            }
            boolean on = !cur.usb;
            // kill 传 on：只有「正在打开」才需要杀联动应用
            WriteResult w = writeKey(app, KEY_USB, on, on);
            finishSubWrite(app, w, c);
        }, cb);
    }

    /** 单独改无线调试（不动 saved） */
    public static void setWireless(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("wireless", c -> {
            WriteResult w = writeKey(app, KEY_WIRELESS, on, true);
            finishSubWrite(app, w, c);
        }, cb);
    }

    /** 单独翻转无线调试：先读真值再翻；读不到真值则拒绝 */
    public static void toggleWireless(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("wireless", c -> {
            State cur = readViaPrivileged(app);
            if (cur == null) {
                failWithoutWrite(app, c, lastFailReason());
                return;
            }
            boolean on = !cur.wireless;
            WriteResult w = writeKey(app, KEY_WIRELESS, on, on);
            finishSubWrite(app, w, c);
        }, cb);
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
        enqueue("dev", c -> {
            WriteResult w = writeKey(app, KEY_DEV, on, false);
            finishSubWrite(app, w, c);
        }, cb);
    }

    /** 是否有可还原的第三方无障碍记忆（UI 在「开」前预检，无记忆时给明确提示而非「写入未生效」） */
    public static boolean hasA11ySave(Context ctx) {
        String s = savedA11yList(ctx);
        return s != null && !s.isEmpty();
    }

    /**
     * 单独开关第三方无障碍服务。
     * 关 = 当前列表去掉第三方条目（写前把原始列表存进记忆，供「开」还原）；
     * 开 = 把记忆的原始列表写回（无记忆时不写入，调用方应先用 {@link #hasA11ySave} 预检）。
     */
    public static void setA11y(Context ctx, final boolean on, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("a11y", c -> setA11yBlocking(app, on, c, false), cb);
    }

    /** 单独翻转第三方无障碍：以「有无第三方服务启用」为当前态翻转 */
    public static void toggleA11y(Context ctx, final ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("a11y", c -> {
            boolean on = !(A11yHelper.countThirdParty(app, readA11yRawForWrite(app)) > 0);
            setA11yBlocking(app, on, c, false);
        }, cb);
    }

    // —— 「列表读不到」时的二次确认 ——

    /**
     * 上次因「需用户二次确认」而未执行的 a11y 写入方向：-1 无 / 0 关 / 1 开。
     *
     * 与 {@link RootUtil#REASON_READ_HIDDEN} 配套：写入被拦下时记下方向，UI 据此弹确认框，
     * 用户点「继续」后再用 {@link #setA11yConfirmed} 原方向重试——**不是替用户做决定**，
     * 而是把"要不要承担清空系统服务的风险"交回用户。
     */
    private static volatile int a11yConfirmPendingOn = -1;

    /** 上次被拦下的 a11y 写入方向（-1 = 当前没有被拦下的写入） */
    public static int a11yConfirmPendingDirection() {
        return a11yConfirmPendingOn;
    }

    /**
     * 用户在二次确认框里选了「继续」：只重做 a11y 这一步（主开关路径的全局键在第一次尝试里
     * 已经写下去了），方向由 {@link #a11yConfirmPendingDirection()} 决定。
     *
     * 「已确认」不能做成字段：{@code enqueue} 是串行队列，UI 线程先置标志、IO 任务再消费时，
     * 若队列里已经压着别的 a11y 写入（磁贴/快捷方式/用户又拨了开关），那次未确认的写入会
     * 抢先吃掉标志 —— 结果就是「未经同意就按空表口径写」。故把确认结果作为**任务参数**
     * 一路传到 {@link #writeA11yBlocking}，让它和这次写入绑定，队列里谁先跑都不影响。
     */
    public static void setA11yConfirmed(Context ctx, boolean on, ResultCallback cb) {
        final Context app = ctx.getApplicationContext();
        int grantReason = blockedByMissingGrant(app);
        if (grantReason != RootUtil.REASON_NONE) {
            failWithoutWrite(app, cb, grantReason);
            return;
        }
        enqueue("a11y", c -> setA11yBlocking(app, on, c, true), cb);
    }

    private static void setA11yBlocking(Context app, boolean on, ResultCallback cb,
                                        boolean allowUnverified) {
        String before = readA11yRawForWrite(app);
        if (!on) {
            // 关闭：记忆当前列表（开时靠它还原）。与全局项的「子开关不动 saved」不同——
            // a11y 的「开」语义就是「还原被关掉的服务」，不存就永远还原不了。
            if (A11yHelper.countThirdParty(app, before) > 0) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(SAVED_A11Y, before == null ? "" : before)
                        .putBoolean(SAVED_VALID, true).apply();
            }
        }
        A11yWriteResult w = writeA11yBlocking(app, on, before, allowUnverified);
        // 恢复待办计数由 writeA11yBlocking 内部异步核对后持久化（含一次性 Toast 标记）
        if (!w.ok) {
            // 写失败：绝不提交假状态（与主开关/全局子开关同一不变式）
            State last = read(app);
            android.util.Log.w(TAG, "a11y write refused to commit (reason=" + w.reason + ")");
            broadcast(app);
            if (cb != null) {
                final int reason = w.reason;
                MAIN.post(() -> cb.onResult(false, last, reason));
            }
            return;
        }
        // 全局键未动，直接取缓存 + 重读 a11y 计数组装 after（省一次 shell fork）
        State cached = read(app);
        State after = new State(cached.usb, cached.wireless, cached.dev,
                A11yHelper.countThirdPartyNow(app));
        State s = commitAfter(app, after);
        if (cb != null) {
            final int reason = w.reason;
            MAIN.post(() -> cb.onResult(true, s, reason));
        }
    }

    // —— 记忆 ——

    /**
     * 关闭时记忆「写前组合」：只存当前跟随主开关的项（不跟随的项不该被主开关的记忆覆盖）。
     * a11y 存原始 enabled_accessibility_services 列表串（恢复时原样写回）。
     */
    private static void save(Context ctx, State s, String a11yRawList,
                             java.util.EnumSet<SwitchItem> followed) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (followed.contains(SwitchItem.USB)) {
            e.putBoolean(SAVED_USB, s.usb);
        }
        if (followed.contains(SwitchItem.WIRELESS)) {
            e.putBoolean(SAVED_WIRELESS, s.wireless);
        }
        if (followed.contains(SwitchItem.DEV)) {
            e.putBoolean(SAVED_DEV, s.dev);
        }
        if (followed.contains(SwitchItem.A11Y)) {
            // 列表读成空、而系统里确有已绑定的服务 = 该 ROM 脱敏，空表是假值：
            // 别拿它覆盖上一次真正可恢复的记忆（否则「开」永远还原不回任何服务）
            if (!android.text.TextUtils.isEmpty(a11yRawList) || !hasBoundA11y(ctx)) {
                e.putString(SAVED_A11Y, a11yRawList == null ? "" : a11yRawList);
            }
        }
        e.putBoolean(SAVED_VALID, true);
        e.apply();
    }

    /**
     * 取记忆里某个全局项的取值；没有有效记忆时回退默认（USB 开、无线关、dev 关）。
     *
     * 记忆只用于「一键打开还原」，不跟随的项不会被读到 —— 独立开关的取值来自当前真值，
     * 否则一键打开会把用户自己关掉的项又悄悄打开（违背「不跟随 = 互不联动」）。
     */
    private static boolean savedItemOn(Context ctx, SwitchItem item, boolean dflt) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(SAVED_VALID, false)) {
            return dflt;
        }
        switch (item) {
            case USB:
                return sp.getBoolean(SAVED_USB, true);
            case WIRELESS:
                return sp.getBoolean(SAVED_WIRELESS, false);
            case DEV:
                return sp.getBoolean(SAVED_DEV, false);
            default:
                return dflt;
        }
    }

    /** 记忆的第三方无障碍列表（null = 无可恢复记录） */
    private static String savedA11yList(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(SAVED_VALID, false)) {
            return null;
        }
        return sp.getString(SAVED_A11Y, null);
    }

    // —— 无障碍列表写入 ——

    /** a11y 写结果：是否成功 + 失败原因（恢复待办计数由 writeA11yBlocking 异步核对后自行持久化） */
    private static final class A11yWriteResult {
        boolean ok = true;
        int reason = RootUtil.REASON_NONE;
    }

    /**
     * a11y 写入代号：每次写入自增。绑定核对挪到 CALIBRATE 异步跑后，
     * 旧一次恢复的核对结果可能晚于新一次写入（如开→秒关）到达，
     * 代号不匹配即丢弃，防止陈旧 pending 覆盖新状态。
     */
    private static final java.util.concurrent.atomic.AtomicInteger A11Y_VERIFY_GEN =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 无障碍列表写入（主开关与子开关共用）：
     *  - 关闭：当前列表去掉第三方条目（保留系统预装服务）后写回；
     *  - 恢复：写回记忆的原始列表，并尽力拉起第三方进程（避免卡 Binding services）。
     * 读列表优先 ContentResolver；读空但本机有 shell 通道时补一次 shell 读（部分 ROM 脱敏，
     * 见 {@link #readA11yRawForWrite}）；写按授权通道收口（WSS 直写 / shell put）。
     *
     * <p>恢复待办（pending）统一由本方法持久化：
     * **先判定这次是否真的会写**（会被二次确认拦下 / ON 无记忆直接返回都不算），
     * 确认要写之后才清旧待办、作废旧核对代号，写成功后把绑定核对（最长 2.5s 轮询）
     * 排到 CALIBRATE 异步执行，核完写回真实计数并 broadcast 通知 UI。
     * 顺序不能颠倒：被拦下时什么都没写，旧结论若被清掉就再也不会落盘 ——
     * 服务其实还没绑上，提示却凭空消失了。
     * 核对绝不占 IO 单线程——否则期间所有写操作排队。
     */
    private static A11yWriteResult writeA11yBlocking(Context app, boolean on, String rawBefore,
                                                     boolean allowUnverified) {
        A11yWriteResult out = new A11yWriteResult();
        if (rawBefore == null) {
            rawBefore = readA11yRawForWrite(app);
        }
        String target;
        List<String> startPkgs = null;
        if (!on) {
            // 关闭会写回「过滤后的列表」。若列表读成空、而系统里明明有已绑定服务，
            // 说明这个 ROM 把该键对第三方脱敏了 —— 此时按空表写会把系统预装无障碍服务
            // （TalkBack 等）一起抹掉。这是不可逆操作，不替用户决定：先拦下来让 UI 弹二次确认，
            // 用户点「继续」后经 setA11yConfirmed 原方向重试。
            if (android.text.TextUtils.isEmpty(rawBefore) && hasBoundA11y(app) && !allowUnverified) {
                android.util.Log.w(TAG, "a11y read is empty but services ARE bound: "
                        + "key is redacted by ROM, ask user before writing an empty list");
                a11yConfirmPendingOn = on ? 1 : 0;
                out.ok = false;
                out.reason = RootUtil.REASON_READ_HIDDEN;
                return out;
            }
            // 关闭：清掉第三方条目。rawBefore 空 = 本来就没有服务，过滤后仍是空（无副作用）
            target = A11yHelper.filterOutThirdParty(app, rawBefore);
        } else {
            String saved = savedA11yList(app);
            if (saved == null || saved.isEmpty()) {
                // 无可恢复记录：不写入（保持现状），不算失败，也不动旧待办
                return out;
            }
            target = saved;
            startPkgs = A11yHelper.thirdPartyPackages(app, saved);
        }

        // —— 到这里才确定真的要写 ——
        final int gen = A11Y_VERIFY_GEN.incrementAndGet();
        // 清旧待办：这次写入会给出新结论，旧的「需手动」作废，核对完再写回真实值
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(A11Y_RESTORE_PENDING, 0)
                .putString(A11Y_RESTORE_PENDING_ENTRIES, "")
                .apply();
        // 放行展示闸门，避免写入后界面还挂着上一次的「需手动」；上一次被拦下的方向也作废
        a11yPendingChecked = true;
        a11yConfirmPendingOn = -1;

        // 写入目标留痕：排查「开关说开了但列表里少了一个服务」这类问题全靠这一行
        android.util.Log.i(TAG, "a11y write " + (on ? "ON" : "OFF")
                + " target=[" + target + "] before=[" + rawBefore + "]");
        // 写入回读（仅 shell/root 通道有；WSS 直写无回读值 → 走绑定列表兜底）
        final String[] readBack = new String[1];
        int reason = wssWriteActive(app)
                ? putWssSecure(app, A11yHelper.KEY, target)
                : writeA11yShell(app, target, readBack);
        out.reason = reason;
        out.ok = (reason == RootUtil.REASON_NONE);
        if (out.ok) {
            // 脱敏 ROM 上回读恒为空，把刚写下的目标列表记成可信值，
            // 否则界面会一直显示写前的条数（关掉了还显示「已启用 2 个」）
            A11yHelper.noteOwnWrite(target);
        }
        if (!out.ok || !on || startPkgs == null) {
            return out;
        }
        // 恢复：先尽力拉起第三方进程（HyperOS 自启动管控会挡住系统绑定时的自动建进程，
        // 服务永久卡 Binding services；shell 侧 am start 可绕过。见 a11y-toggle-research），
        // 然后**以绑定结果为准**核对哪些服务真的没生效——不能按「有无 shell 通道」猜测：
        // 实测小米 14 无 root 纯 WSS 没有 shell 也绑定成功，旧口径把全部计成「需手动」是误判。
        int launchFailed = startA11yPackages(app, startPkgs);
        final String targetF = target;
        final int launchFailedF = launchFailed;
        final String readBackF = readBack[0];
        scheduleA11yVerify(app, gen, targetF, launchFailedF, readBackF, out.ok);
        return out;
    }

    /**
     * 写后核对「恢复是否真的生效」——两层，与显示解耦（消除「实际已开却卡在需手动十几秒」）：
     *
     * <p>显示层：写入成功即清待办并广播，UI 秒级显示「已开启 N 个」，绝不因系统绑定慢而卡在
     * 「仍有 N 个需手动开启」。开关语义 = key 里有该服务，绑定是系统异步的后续动作。
     *
     * <p>判据优先级：
     * <ol>
     *   <li>有 shell 回读值（root/Shizuku）：回读列表明确不含目标条目 = 写入被 ROM 丢弃，
     *       真失败，带提示写回。荣耀只对本进程 ContentResolver 脱敏，shell 读仍可信。</li>
     *   <li>延迟一次保守的绑定核验：仅当系统绑定列表「非空但明确不含」目标时才静默纠正摘要
     *       （不弹 Toast）——覆盖极少数「key 写进去了但服务最终没绑上」。空绑定列表不作证据
     *       （可见性过滤 / ROM 缓存延迟），沿用 {@link #verifyA11yBound} 的保守口径。</li>
     * </ol>
     * 旧实现两轮绑定核对各等 2.5s + 轮间 5s，荣耀上把「已开」误报成「需手动」十几秒；
     * 现在显示不依赖绑定，核验只做静默兜底。
     */
    private static void scheduleA11yVerify(Context app, int gen, String target,
                                           int launchFailed, String readBack, boolean writeOk) {
        CALIBRATE.execute(() -> {
            if (A11Y_VERIFY_GEN.get() != gen) {
                return; // 已有更新一次写入：本次核对结果作废
            }
            // 显示层：写入成功立即清待办并广播（不等绑定），UI 秒级显示「已开启」
            if (writeOk) {
                persistA11yPendingEx(app, 0, null, true);
            }
            // 回读判据：shell 读回的列表明确不含目标第三方条目 = 真写入失败
            if (readBack != null) {
                List<String> targets = A11yHelper.thirdPartyEntries(app, target);
                List<String> rb = A11yHelper.splitList("null".equals(readBack) ? "" : readBack);
                List<String> missing = new java.util.ArrayList<>();
                for (String e : targets) {
                    if (!rb.contains(e)) {
                        missing.add(e);
                    }
                }
                if (!missing.isEmpty()) {
                    android.util.Log.w(TAG, "a11y write readback missing entries=" + missing);
                    if (A11Y_VERIFY_GEN.get() == gen) {
                        persistA11yPendingEx(app, missing.size(), missing, true);
                    }
                    return;
                }
            }
            // 校验层：延迟做一次保守绑定核验，仅明确没绑上时静默纠正（不弹 Toast）
            MAIN.postDelayed(() -> CALIBRATE.execute(() -> {
                if (A11Y_VERIFY_GEN.get() != gen) {
                    return;
                }
                List<String> pending = new java.util.ArrayList<>();
                int n = verifyA11yBound(app, target, launchFailed, pending);
                if (A11Y_VERIFY_GEN.get() != gen) {
                    return;
                }
                if (n > 0) {
                    android.util.Log.i(TAG, "a11y bind silent-correct pending=" + n
                            + ", bound=" + A11yHelper.boundServiceComponents(app));
                    persistA11yPendingEx(app, n, pending, false);
                }
            }), A11Y_VERIFY_SECOND_ROUND_DELAY_MS);
        });
    }

    /**
     * 落盘恢复待办并广播。
     *
     * @param toast 是否置一次性 Toast 标记；静默纠正传 false（只改摘要、不弹提示）
     */
    private static void persistA11yPendingEx(Context app, int pending, List<String> entries,
                                             boolean toast) {
        SharedPreferences.Editor pe = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        pe.putInt(A11Y_RESTORE_PENDING, pending);
        pe.putString(A11Y_RESTORE_PENDING_ENTRIES, pending > 0 && entries != null
                ? android.text.TextUtils.join(":", entries) : "");
        if (pending > 0 && toast) {
            pe.putBoolean(A11Y_RESTORE_TOAST, true);
        } else if (pending == 0) {
            pe.putBoolean(A11Y_RESTORE_TOAST, false);
        }
        pe.apply();
        a11yPendingChecked = true; // 本次会话已给出核对结论（含 0），UI 可以据此展示
        broadcast(app); // UI 按最新 pending 刷新摘要 / 消费一次性 Toast
    }

    /**
     * 系统里是否真的绑定着无障碍服务。
     * 用来交叉验证「列表读到空」到底是真没有，还是 ROM 把该键对第三方脱敏：
     * 读到空 + 服务却已绑定 = 读不可信，绝不能把空表当作写前态。
     */
    private static boolean hasBoundA11y(Context app) {
        java.util.Set<String> bound = A11yHelper.boundServiceComponents(app);
        return bound != null && !bound.isEmpty();
    }

    /** 恢复后绑定核对的总等待窗口 / 轮询间隔（绑定是异步的，通常 <500ms 全绑上，等满即按未绑定计） */
    private static final long A11Y_BIND_VERIFY_TIMEOUT_MS = 2500;
    private static final long A11Y_BIND_VERIFY_POLL_MS = 250;

    /**
     * 写入后延迟多久做「静默绑定核验」（见 {@link #scheduleA11yVerify}）。
     * 显示层不等它（写入成功即显示已开启），这一步只兜底极少数「key 有但服务没绑上」。
     * 延迟给足绑定时间，避免把慢绑定的服务误判成没绑上。
     */
    private static final long A11Y_VERIFY_SECOND_ROUND_DELAY_MS = 5000;

    /**
     * 恢复后核对「真的没生效」的第三方服务数（与写入通道无关）：
     * 用 {@link A11yHelper#unboundEntries}（AccessibilityManager 已绑定列表）短轮询等
     * 绑定完成，全绑上提前退出；轮询窗口内仍没绑上的才计「需手动开启」。
     *
     * <p>关键口径：**「绑定列表为空」不是失败的正证据**。Android 11+ 包可见性会把
     * getEnabledAccessibilityServiceList 过滤成空列表，MIUI/HyperOS 也存在绑定状态
     * 缓存延迟（数秒到数十秒）——这两种情况下旧口径把「查不到」当成「全没绑上」，
     * 造成系统性误报（实测小米 14 恢复明明成功却永远提示仍需手动）。故只有
     * 「绑定列表非空（查询可信）但某个条目不在其中」或「shell 侧拉起进程确有失败」
     * 才算正证据；查不到 + 无 shell 旁证时宁可漏报不误报。
     *
     * @param target          写回的完整原始列表（含系统服务，核对时只看第三方条目）
     * @param launchFailed    shell 侧拉起进程的失败数（无 shell 通道为 -1，不可作证据）
     * @param outPending      出参：未生效的具体条目（供懒复核自愈）
     */
    private static int verifyA11yBound(Context app, String target, int launchFailed,
                                       List<String> outPending) {
        List<String> entries = A11yHelper.thirdPartyEntries(app, target);
        if (entries.isEmpty()) {
            return 0;
        }
        long deadline = android.os.SystemClock.uptimeMillis() + A11Y_BIND_VERIFY_TIMEOUT_MS;
        List<String> unbound = A11yHelper.unboundEntries(app, entries);
        while (unbound != null && !unbound.isEmpty()
                && android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(A11Y_BIND_VERIFY_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            unbound = A11yHelper.unboundEntries(app, entries);
        }
        if (unbound == null) {
            android.util.Log.w(TAG, "a11y bind verify unavailable, launchFailed=" + launchFailed);
            return launchFailed > 0 ? launchFailed : 0;
        }
        if (unbound.size() == entries.size()) {
            // 全部未绑定：可能是真全卡住，也可能是绑定列表本身查出来就空（可见性过滤 /
            // ROM 缓存延迟）。空列表不作失败证据，无 shell 旁证时不报。
            java.util.Set<String> bound = A11yHelper.boundServiceComponents(app);
            if (bound == null || bound.isEmpty()) {
                android.util.Log.w(TAG, "a11y bind verify saw empty bound list, not reporting");
                return launchFailed > 0 ? launchFailed : 0;
            }
        }
        outPending.clear();
        outPending.addAll(unbound);
        android.util.Log.d(TAG, "a11y bind verify: unbound=" + unbound.size()
                + "/" + entries.size());
        return unbound.size();
    }

    /** WSS 直写 Settings.Secure 键（无障碍列表）；putString 布尔回值识别 ROM 静默丢弃 */
    private static int putWssSecure(Context ctx, String key, String val) {
        int reason;
        try {
            boolean written = Settings.Secure.putString(
                    ctx.getContentResolver(), key, val == null ? "" : val);
            if (!written) {
                android.util.Log.w(TAG, "wss put secure " + key + " not written (dropped by ROM)");
                reason = RootUtil.REASON_WRITE_DROPPED;
            } else {
                reason = RootUtil.REASON_NONE;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "wss put secure " + key + " failed", t);
            reason = RootUtil.REASON_EXEC;
        }
        lastFailReason = reason;
        return reason;
    }

    /**
     * shell 通道写无障碍列表（settings put secure）；列表内容仅 pkg/component 字符，单引号包裹。
     * 同一次 shell 顺带回读写入后的 key（标记行），作为写后「是否真的生效」的可靠判据——
     * 荣耀等 ROM 只对本进程 ContentResolver 脱敏，shell 视角的 settings get 仍是真值。
     *
     * @param outReadBack 出参[0]：回读到的列表串（"null" 表示空）；通道异常时为 null
     */
    private static int writeA11yShell(Context ctx, String list, String[] outReadBack) {
        String safe = list == null ? "" : list.replace("'", "");
        String cmd = "settings put secure " + A11yHelper.KEY + " '" + safe
                + "'; echo __ASW_A11Y_W__=\"$(settings get secure " + A11yHelper.KEY + ")\"";
        RootUtil.Result r = runPrivileged(ctx, cmd);
        if (outReadBack != null) {
            outReadBack[0] = tagValue(r.out, "__ASW_A11Y_W__");
        }
        return r.ok() ? RootUtil.REASON_NONE : r.reason;
    }

    /**
     * 恢复无障碍时尽力拉起第三方进程：进程不在跑则 am start 启动其默认 activity
     * （HyperOS 自启动管控会挡住系统绑定时的自动建进程，进程一跑起来待绑定的服务即可完成绑定）；
     * 随后立即回桌面，减少对用户的打扰。进程已在跑则跳过（绑定本就会成功）。
     *
     * 无 shell 通道（纯 WSS 且 root/Shizuku 皆不可用）时拉不起来，全部计「待手动开启」。
     *
     * @return 未能拉起进程的包数（0 = 全部可恢复）
     */
    private static int startA11yPackages(Context app, List<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            return 0;
        }
        StringBuilder cmd = new StringBuilder();
        int attempted = 0;
        for (String pkg : pkgs) {
            if (!LinkagePrefs.isValidPackageName(pkg)) {
                continue;
            }
            android.content.Intent launch =
                    app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null || launch.getComponent() == null) {
                // 无启动 activity（纯后台服务型应用）：am start 无从下手，只能计待手动
                continue;
            }
            // 组件名单引号包裹：类名可能含 $（内部类），防 shell 变量展开
            String comp = launch.getComponent().flattenToString().replace("'", "");
            if (cmd.length() > 0) {
                cmd.append("; ");
            }
            // pidof 有则跳过 am start（进程在跑绑定就会成功），没有才拉起 + 回桌面；
            // 逐包 echo 退出码标记行，回传后按行统计失败数（一次 shell，不逐包 fork）
            cmd.append("echo __ASW_P").append(attempted)
                    .append("=$(pidof ").append(pkg)
                    .append(" >/dev/null 2>&1 || (am start -n '").append(comp)
                    .append("' >/dev/null 2>&1 && input keyevent 3 >/dev/null 2>&1); echo $?)");
            attempted++;
        }
        if (attempted == 0) {
            return pkgs.size(); // 全都无法解析启动 activity
        }
        RootUtil.Result r;
        if (ShizukuUtil.available()) {
            r = ShizukuUtil.run(cmd.toString());
        } else if (sRootCapable) {
            r = RootUtil.su(cmd.toString());
        } else {
            // 纯 WSS 无 shell 兜底：拉不起进程，但这不是「失败」的证据——很多设备
            // 不拉进程也能正常绑定（实测小米 14）。返回 -1 表示「无旁证」，由
            // verifyA11yBound 按绑定核对定夺，绝不凭空全计失败。
            return -1;
        }
        if (!r.ok()) {
            return attempted + (pkgs.size() - attempted);
        }
        // 按标记行统计失败（非 0 = pidof 没有进程且 am start 也没成功）
        int failed = 0;
        for (int i = 0; i < attempted; i++) {
            if (!markerOk(r.out, "__ASW_P" + i + "=")) {
                failed++;
            }
        }
        return failed + (pkgs.size() - attempted);
    }

    /** 标记行形如 "__ASW_P0__=0"：取值 0 = 成功 */
    private static boolean markerOk(String out, String prefix) {
        if (out == null) {
            return false;
        }
        for (String line : out.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.startsWith(prefix)) {
                String v = s.substring(prefix.length()).trim();
                return "0".equals(v);
            }
        }
        return false;
    }

    // —— 无障碍恢复失败反馈（UI 消费）——

    /**
     * 本会话是否已复核过持久化的「恢复待办」结论。
     * 上次会话写下的 pending 很可能早已自愈（服务后来绑上了），启动就把旧结论摆到界面上，
     * 用户看到的就是「明明开着却短暂显示需手动开启」——所以一律先复核、再展示。
     */
    private static volatile boolean a11yPendingChecked = false;

    /**
     * 上次无障碍恢复未成功的服务数（0 = 无待办）。
     * 本会话尚未复核过时恒返回 0（见 {@link #a11yPendingChecked}），
     * 调用方需在启动时用 {@link #hasPersistedA11yPending} + {@link #recheckA11yRestore} 先复核。
     */
    public static int a11yRestorePending(Context ctx) {
        if (!a11yPendingChecked) {
            return 0;
        }
        return rawA11yRestorePending(ctx);
    }

    /** 持久化的恢复待办原始值（不经本会话复核闸门，供启动时判断是否需要复核） */
    public static boolean hasPersistedA11yPending(Context ctx) {
        return rawA11yRestorePending(ctx) > 0;
    }

    private static int rawA11yRestorePending(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(A11Y_RESTORE_PENDING, 0);
    }

    /**
     * 恢复失败摘要的自愈复核（后台线程，CALIBRATE 串行）：写入时的绑定核对窗口只有 2.5s，
     * 慢绑定 / ROM 绑定状态缓存延迟（MIUI 有数秒到数十秒滞后的报道）会让「仍需手动」误报；
     * 这里稍后按同一口径重核，绑上即清掉。也顺带清掉旧版本误报残留在 cfg 里的 stale pending
     * （旧数据只有计数没存条目时，按记忆列表的第三方条目复核）。
     *
     * @param onDone 复核结束后在主线程回调（UI 刷新用）；可为 null
     */
    public static void recheckA11yRestore(Context ctx, final Runnable onDone) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getInt(A11Y_RESTORE_PENDING, 0) <= 0) {
            // 没有待办：标记本会话已「复核」，让 UI 正常显示条数（而不是一直卡在 0 的闸门后）
            a11yPendingChecked = true;
            if (onDone != null) {
                MAIN.post(onDone);
            }
            return;
        }
        List<String> entries = A11yHelper.splitList(
                sp.getString(A11Y_RESTORE_PENDING_ENTRIES, ""));
        if (entries.isEmpty()) {
            // 旧版本残留（只存了计数没存条目）：按记忆列表的第三方条目复核
            entries = A11yHelper.thirdPartyEntries(app, savedA11yList(app));
        }
        if (entries.isEmpty()) {
            a11yPendingChecked = true;
            if (onDone != null) {
                MAIN.post(onDone);
            }
            return;
        }
        final List<String> toCheck = entries;
        CALIBRATE.execute(() -> {
            List<String> unbound = A11yHelper.unboundEntries(app, toCheck);
            int pending = 0;
            String raw = "";
            // 与 verifyA11yBound 同口径：绑定列表为空不是失败正证据，不报
            if (unbound != null && !unbound.isEmpty()) {
                java.util.Set<String> bound = A11yHelper.boundServiceComponents(app);
                if (bound != null && !bound.isEmpty()) {
                    pending = unbound.size();
                    raw = android.text.TextUtils.join(":", unbound);
                }
            }
            SharedPreferences sp2 = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (sp2.getInt(A11Y_RESTORE_PENDING, 0) != pending) {
                sp2.edit().putInt(A11Y_RESTORE_PENDING, pending)
                        .putString(A11Y_RESTORE_PENDING_ENTRIES, raw).apply();
                android.util.Log.d(TAG, "a11y restore recheck: pending -> " + pending);
            }
            // 无论结论有没有变都要放行闸门并回调：
            // 启动时 a11yRestorePending() 恒为 0，界面正等着这次复核的结果来渲染摘要
            a11yPendingChecked = true;
            if (onDone != null) {
                MAIN.post(onDone);
            }
        });
    }

    /** 消费一次性恢复提示（有未成功的恢复记录时 UI 弹一次） */
    public static boolean consumeA11yRestoreToast(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean v = sp.getBoolean(A11Y_RESTORE_TOAST, false);
        if (v) {
            sp.edit().putBoolean(A11Y_RESTORE_TOAST, false).apply();
        }
        return v;
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
