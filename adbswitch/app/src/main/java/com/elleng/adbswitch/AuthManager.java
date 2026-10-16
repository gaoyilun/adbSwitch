package com.elleng.adbswitch;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.elleng.adbswitch.util.RootUtil;

/**
 * 授权模式的持久化与判定。
 *
 * 三种「写通道」：
 *  - ROOT     ：沿用 su（RootUtil），需超级用户授权。
 *  - SHIZUKU  ：走 Shizuku（shell uid 2000），无需 root，但需 Shizuku 管理器已启动且本应用被授权。
 *  - SECURE_SETTINGS（默认推荐）：应用进程内直接写 Settings.Global（WRITE_SECURE_SETTINGS），
 *              无需 root、无需 Shizuku 常驻。前提是本应用已被一次性授予该权限
 *              （adb shell pm grant / Shizuku 代跑 pm grant / root 代跑 pm grant 三者任一）。
 *
 * 设计要点（与用户确认的最终架构）：
 *  - WSS 是默认推荐通道；root / Shizuku 完整保留为「兜底写通道 + 首次授权的引导手段」。
 *  - WSS 的读取在部分 ROM（如 HyperOS）上可能被 ContentResolver 隐藏，故 DebugController 在 WSS 模式下
 *    仍优先 WSS 直读、shell 可用时回退 shell 读，保证状态显示不退化。
 *  - 授权（pm grant）是粘滞的：跨重启/软重启保留，仅卸载/重装/备份恢复会撤销 → 故 onResume 需复检。
 *
 * 当前选中的「偏好模式」存于 cfg/auth_mode；{@link #getEffectiveMode} 再结合「WSS 是否真已授权」
 * 算出真正用于读写的实际通道（WSS 未授权时自动回退到 root / Shizuku 兜底）。
 */
public final class AuthManager {

    public enum Mode {
        ROOT,
        SHIZUKU,
        SECURE_SETTINGS
    }

    public static final String PREF_MODE = "auth_mode";
    public static final int SHIZUKU_PERM_REQUEST = 7700;

    /** cfg 中偏好模式取值 */
    private static final String VAL_ROOT = "root";
    private static final String VAL_SHIZUKU = "shizuku";
    private static final String VAL_WSS = "secure_settings";

    private AuthManager() {
    }

    /** 用户偏好的模式（仅记录选择，不保证该通道当前可用） */
    public static Mode getMode(Context ctx) {
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        // 全新安装（从未显式选过模式）：默认落到推荐通道 WRITE_SECURE_SETTINGS。
        // 老用户已持久化过自己的选择（会被 contains 命中），行为完全不受影响。
        if (!sp.contains(PREF_MODE)) {
            return Mode.SECURE_SETTINGS;
        }
        String m = sp.getString(PREF_MODE, VAL_ROOT);
        if (VAL_SHIZUKU.equals(m)) {
            return Mode.SHIZUKU;
        }
        if (VAL_WSS.equals(m)) {
            return Mode.SECURE_SETTINGS;
        }
        return Mode.ROOT;
    }

    public static void setMode(Context ctx, Mode mode) {
        String v = mode == Mode.SHIZUKU ? VAL_SHIZUKU
                : mode == Mode.SECURE_SETTINGS ? VAL_WSS : VAL_ROOT;
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString(PREF_MODE, v)
                .apply();
    }

    /** 本应用是否已持有 WRITE_SECURE_SETTINGS（一次性 pm grant 之后即为 true） */
    public static boolean hasWriteSecureSettings(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 实际用于读写的有效通道。
     * WSS 仅在「用户偏好 WSS 且确实已授权」时生效；否则回退到 root / Shizuku 兜底
     * （WSS 授权被撤销后自动退回，避免静默失效）。
     */
    public static Mode getEffectiveMode(Context ctx) {
        if (getMode(ctx) == Mode.SECURE_SETTINGS && hasWriteSecureSettings(ctx)) {
            return Mode.SECURE_SETTINGS;
        }
        return getMode(ctx) == Mode.SHIZUKU ? Mode.SHIZUKU : Mode.ROOT;
    }

    /** 是否为默认推荐通道（WSS）可用状态 */
    public static boolean isWssRecommended(Context ctx) {
        return hasWriteSecureSettings(ctx);
    }

    /** 一次性授权命令（手动 adb 场景给用户复制执行） */
    public static String grantCommand(Context ctx) {
        return "adb shell pm grant " + ctx.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
    }

    /** 用 root 代跑一次 pm grant（授权成功返回 true） */
    public static boolean grantViaRoot(Context ctx) {
        RootUtil.Result r = RootUtil.su("pm grant " + ctx.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS");
        return r.ok();
    }

    /**
     * 用 Shizuku 代跑一次 pm grant。
     *
     * 只要求 Shizuku 服务在跑，**不要求本应用已被 Shizuku 授权** —— 调用方
     * （MainActivity.grantWssViaShizuku）会先弹授权框，用户授权回来后才调到这里。
     * 若在此处再要求「已授权」，则未授权时恒为 false，退化成静默无反应：
     * 这正是「点 Shizuku 没反应」的根因（用户点它就是因为还没授权，构成循环依赖）。
     * ShizukuUtil.run 内部仍会校验并给出具体失败原因，不会因此失去错误反馈。
     */
    public static boolean grantViaShizuku(Context ctx) {
        if (!ShizukuUtil.running()) {
            return false;
        }
        return ShizukuUtil.run("pm grant " + ctx.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS").ok();
    }
}
