package com.elleng.devswitch;

import android.content.Context;

import com.elleng.devswitch.util.RootUtil;

/**
 * 双模式授权调度：
 *  - ROOT：沿用 su（RootUtil），需超级用户授权。
 *  - SHIZUKU：走 Shizuku（shell uid 2000），无需 root，但需 Shizuku 管理器已启动且本应用被授权。
 *
 * 当前选中的模式存于 cfg/auth_mode；isAuthorized 只判定「当前模式是否可用」，
 * DebugController 据此选 RootUtil.su 或 ShizukuUtil.run 作为真实写入通道。
 */
public final class AuthManager {

    public enum Mode {
        ROOT,
        SHIZUKU
    }

    public static final String PREF_MODE = "auth_mode";
    public static final int SHIZUKU_PERM_REQUEST = 7700;

    private AuthManager() {
    }

    public static Mode getMode(Context ctx) {
        String m = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getString(PREF_MODE, "root");
        return "shizuku".equals(m) ? Mode.SHIZUKU : Mode.ROOT;
    }

    public static void setMode(Context ctx, Mode mode) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString(PREF_MODE, mode == Mode.SHIZUKU ? "shizuku" : "root")
                .apply();
    }

    public static boolean isRootAvailable() {
        return RootUtil.hasRoot();
    }

    public static boolean isShizukuAvailable() {
        return ShizukuUtil.available();
    }

    /** 当前选中模式是否可用（写开关的前置条件） */
    public static boolean isAuthorized(Context ctx) {
        return getMode(ctx) == Mode.SHIZUKU ? isShizukuAvailable() : isRootAvailable();
    }
}
