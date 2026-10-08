package com.elleng.devswitch;

import android.content.Context;

/**
 * 双模式授权的持久化与判定：
 *  - ROOT：沿用 su（RootUtil），需超级用户授权。
 *  - SHIZUKU：走 Shizuku（shell uid 2000），无需 root，但需 Shizuku 管理器已启动且本应用被授权。
 *
 * 当前选中的模式存于 cfg/auth_mode；DebugController 据此选 RootUtil.su 或
 * ShizukuUtil.run 作为真实读写通道（读写必须同源，见 DebugController 注释）。
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

}
