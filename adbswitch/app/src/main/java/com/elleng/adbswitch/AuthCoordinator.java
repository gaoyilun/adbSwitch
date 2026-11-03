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

    /** 本启动周期是否已成功确认过授权（确认成功才记，失败不缓存）。 */
    private static boolean sCheckedThisSession = false;
    /** 上次探测时间（节流：失败也不反复 fork su 干等）。 */
    private static long sLastProbeAt = 0;

    private AuthCoordinator() {
        // 工具类，不可实例化
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
        sLastProbeAt = now;
        // 探测线程只用 application context：probe 最坏阻塞 10s（su 等授权），匿名线程持有
        // Activity 强引用期间实例无法回收；回主线程的回调由调用方做 isDestroyed 守卫。
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            final AuthManager.Mode mode = AuthManager.getMode(app);
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
                postResult(cb, new AuthResult(mode, granted, shizukuRunning, RootUtil.RootStatus.DENIED));
                return;
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
            postResult(cb, new AuthResult(mode, ok, shizukuRunning, rootStatus));
        }, "auth-probe").start();
    }

    /** 探测结果切回主线程（Activity 销毁守卫由调用方在回调里做）。 */
    private static void postResult(AuthCallback cb, AuthResult result) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (cb != null) {
                cb.onResult(result);
            }
        });
    }
}
