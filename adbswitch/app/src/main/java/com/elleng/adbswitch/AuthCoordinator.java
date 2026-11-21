package com.elleng.adbswitch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.elleng.adbswitch.util.RootUtil;
import com.elleng.adbswitch.util.ShizukuUtil;

/**
 * 授权检测协调器（§3.1 拆分自 MainActivity 的 performAuthCheck 体系）。
 *
 * <p>收拢：模式探测（WSS / Shizuku / root 各自只探当前模式对应的后端）+ 30s 节流
 * + 本会话确认闸门 + 结果回调。把「探测什么、多久探一次、结果是什么」与 Activity 的 UI
 * 渲染解耦——Activity 只负责拿到 {@link AuthResult} 后，在主线程刷新偏好项 / 弹引导 /
 * 请求 Shizuku 授权。</p>
 *
 * <p>状态为进程级（static）：Activity 因配置变更重建后，本会话确认结论仍有效，
 * 避免旋转屏幕就重 fork su / 重探 Shizuku。切模式 / 写入失败 / binder 死亡时由调用方
 * 调 {@link #resetSession()} 作废。</p>
 */
public final class AuthCoordinator {

    private static final long AUTH_PROBE_MIN_INTERVAL_MS = 30_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 本启动周期是否已成功确认过授权（确认成功才记，失败不缓存）。 */
    private static volatile boolean sCheckedThisSession = false;
    /** 上次探测时间（节流：失败也不反复 fork su 干等）。 */
    private static volatile long sLastProbeAt = 0;

    /** 是否有探测在途：冷启动 Shizuku sticky 与 runPrefsInit 会各调一次 check()，去重避免两次 fork su / 两次 Magisk 弹窗。 */
    private static volatile boolean sProbing = false;
    /** 在途探测对应的模式：切模式后必须另起一轮，不能把旧通道结论派给新通道的调用方。 */
    private static volatile AuthManager.Mode sProbingMode = null;
    /** 探测轮次：模式切换后旧轮结果按序号作废。 */
    private static volatile long sProbeGeneration = 0;
    /** 本轮所有等待者：在途期间进来的调用方（含用户点「重试」）都会收到同一份结果，绝不静默吞掉。 */
    private static final java.util.List<AuthCallback> sPendingCallbacks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private AuthCoordinator() {
        // 工具类，不可实例化
    }

    /**
     * 本进程是否已确认过授权可用。只有 {@code available=true} 才标记，
     * 故可用于 Activity 重建后按已确认结论初始化 UI：既不闪假授权，也不闪假未授权。
     */
    public static boolean hasSessionChecked() {
        return sCheckedThisSession;
    }

    /** 重置本会话确认状态：切模式 / 写入失败 / binder 死亡时调用，迫使下次重测。 */
    public static void resetSession() {
        sCheckedThisSession = false;
    }

    /** 主线程回调里、结果已应用到存活的 Activity 后，标记本会话已确认。 */
    public static void markSessionChecked() {
        sCheckedThisSession = true;
    }

    /** 授权检测结果（由 {@link #check} 在后台探测后通过 {@link AuthCallback} 回传）。 */
    public static final class AuthResult {
        public final AuthManager.Mode mode;
        public final boolean available;
        public final boolean shizukuRunning;
        public final RootUtil.RootStatus rootStatus;

        public AuthResult(AuthManager.Mode mode, boolean available,
                         boolean shizukuRunning, RootUtil.RootStatus rootStatus) {
            this.mode = mode;
            this.available = available;
            this.shizukuRunning = shizukuRunning;
            this.rootStatus = rootStatus;
        }
    }

    /** 后台探测完成后的主线程回调。 */
    public interface AuthCallback {
        void onResult(AuthResult result);
    }

    /**
     * 执行一次授权检测（带节流 + 会话闸门）。
     *
     * @param ctx 任意 context（内部取 application context，避免探测线程持有 Activity 强引用）。
     * @param force true=忽略会话闸门与节流，立即重测（切模式 / 点重试 / 授权结果回调）。
     * @param cb 主线程回调，携带探测结果；被闸门 / 节流挡住时不回调（与旧 performAuthCheck 一致）。
     */
    public static void check(Context ctx, boolean force, AuthCallback cb) {
        long now = android.os.SystemClock.uptimeMillis();
        if (sCheckedThisSession && !force) {
            return;
        }
        if (!force && now - sLastProbeAt < AUTH_PROBE_MIN_INTERVAL_MS) {
            return; // 节流：失败也不反复 fork su 干等
        }
        // 模式必须在发起时取：切模式后 sProbingMode 变化会强制另起一轮，旧轮结论按序号作废。
        final AuthManager.Mode mode = AuthManager.getMode(ctx);
        synchronized (AuthCoordinator.class) {
            if (sProbing && mode == sProbingMode) {
                // 同模式已有探测在途：只挂载回调复用本轮结果，不重复 fork su / 弹 Magisk 授权框。
                // 用户点「重试」也走这里——在途结果一定会回到他手上，不会静默吞掉；
                // 若探测失败，sProbing 会在 finally 清掉，下次重试照常重新探测。
                if (cb != null) {
                    sPendingCallbacks.add(cb);
                }
                return;
            }
            // 无在途 / 模式已切换：开启新一轮，旧轮结果按 generation 作废
            sProbeGeneration++;
            sProbing = true;
            sProbingMode = mode;
            sPendingCallbacks.clear();
            if (cb != null) {
                sPendingCallbacks.add(cb);
            }
        }
        sLastProbeAt = now;
        // 探测线程只用 application context：probe 最坏阻塞 10s（su 等授权），匿名线程持有
        // Activity 强引用期间实例无法回收；回主线程的回调由调用方做 isDestroyed 守卫。
        final Context app = ctx.getApplicationContext();
        final long generation = sProbeGeneration;
        new Thread(() -> {
            AuthResult result = null;
            try {
                result = probe(app, mode);
            } catch (Throwable t) {
                android.util.Log.w("AuthCoordinator", "auth probe failed", t);
            } finally {
                // 成功 / 失败 / 超时都必须清在途标志，否则后续「重试」会被永久挡住
                finishProbe(generation, result);
            }
        }, "auth-probe").start();
    }

    /** 实际探测（后台线程）：只探当前模式对应的后端，不碰任何 UI。 */
    private static AuthResult probe(Context app, AuthManager.Mode mode) {
        if (mode == AuthManager.Mode.SECURE_SETTINGS) {
            // 免 root 模式：只查本应用是否已持有 WRITE_SECURE_SETTINGS（纯 checkSelfPermission，
            // 零成本、不 fork su、不惊动 Magisk）——遵循「只探当前模式对应的后端」。
            final boolean granted = AuthManager.hasWriteSecureSettings(app);
            // 杀联动应用需要 Shizuku 或 root：Shizuku 可用时无需探 root；否则用零成本判据
            // （su 二进制是否存在）替代真实 fork 探测（见 §1.1）。真正的授权判定推迟到用户实际
            // 「开启调试」时由 killLinkageIfNeeded 惰性执行，启动期完全无需提前知道 root 是否可用。
            final boolean shizukuAvail = ShizukuUtil.available();
            DebugController.setRootCapable(shizukuAvail
                    ? false : RootUtil.suBinaryExists());
            final boolean shizukuRunning = ShizukuUtil.running();
            return new AuthResult(mode, granted, shizukuRunning, RootUtil.RootStatus.DENIED);
        }
        // Shizuku 模式不 fork su（代价高且惊动 Magisk），root 模式才探；否则按 DENIED 处理
        final RootUtil.RootStatus rootStatus = mode == AuthManager.Mode.ROOT
                ? RootUtil.probe() : RootUtil.RootStatus.DENIED;
        final boolean rootOk = rootStatus == RootUtil.RootStatus.GRANTED;
        final boolean shizukuRunning = ShizukuUtil.running();
        final boolean shizukuOk = shizukuRunning && ShizukuUtil.permissionGranted();
        final boolean ok = (mode == AuthManager.Mode.SHIZUKU) ? shizukuOk : rootOk;
        DebugController.setRootCapable(rootOk);
        if (mode == AuthManager.Mode.ROOT) {
            // root 模式下 probe 是真实 fork，结论可直接落成「杀应用能力」缓存：
            // 之后切到免 root 模式时仍能准确判定，而不必再 fork 一次 su。
            DebugController.setKillCapable(app, rootOk);
        }
        return new AuthResult(mode, ok, shizukuRunning, rootStatus);
    }

    /**
     * 探测收尾：作废旧轮结果、清在途标志、把本轮结果派发给所有等待者。
     * 探测异常（result==null）不派发假结论——调用方仍停在「未确认」态，可再次重试。
     */
    private static void finishProbe(long generation, AuthResult result) {
        final java.util.List<AuthCallback> callbacks;
        synchronized (AuthCoordinator.class) {
            if (generation != sProbeGeneration) {
                return; // 已被新模式的探测取代，旧结论不派发；新轮自己负责清标志与派发
            }
            sProbing = false;
            sProbingMode = null;
            callbacks = new java.util.ArrayList<>(sPendingCallbacks);
            sPendingCallbacks.clear();
        }
        if (result == null || callbacks.isEmpty()) {
            return;
        }
        MAIN.post(() -> {
            for (AuthCallback cb : callbacks) {
                try {
                    cb.onResult(result);
                } catch (Throwable t) {
                    android.util.Log.w("AuthCoordinator", "auth callback failed", t);
                }
            }
        });
    }
}
