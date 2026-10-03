package com.devhider.lsp;

import de.robv.android.xposed.XSharedPreferences;

/** 模块开关。UI 写入 SharedPreferences("config")，hook 侧通过 XSharedPreferences 读取。 */
public final class Config {

    public static final String PREF = "config";

    public static final String K_HOOK_SETTINGS = "hook_settings";
    public static final String K_HOOK_PROPS = "hook_props";
    public static final String K_HOOK_FILES = "hook_files";
    public static final String K_HOOK_PORT = "hook_port";
    public static final String K_HOOK_BUILD = "hook_build";
    public static final String K_HOOK_CURSOR = "hook_cursor";
    public static final String K_HOOK_CALL = "hook_call";
    public static final String K_HOOK_DEBUG = "hook_debugger";
    public static final String K_FUZZY = "fuzzy";
    public static final String K_LOG = "debug_log";

    public static boolean hookSettings = true;
    public static boolean hookProps = true;
    public static boolean hookFiles = true;
    public static boolean hookPort = true;
    public static boolean hookBuild = true;
    public static boolean hookCursor = true;
    public static boolean hookCall = true;
    public static boolean hookDebugger = true;
    public static boolean fuzzy = true;
    public static boolean debugLog = false;

    private static XSharedPreferences pref;

    public static void load() {
        try {
            if (pref == null) {
                pref = new XSharedPreferences("com.devhider.lsp", PREF);
                pref.makeWorldReadable();
            } else {
                pref.reload();
            }
            hookSettings = pref.getBoolean(K_HOOK_SETTINGS, true);
            hookProps = pref.getBoolean(K_HOOK_PROPS, true);
            hookFiles = pref.getBoolean(K_HOOK_FILES, true);
            hookPort = pref.getBoolean(K_HOOK_PORT, true);
            hookBuild = pref.getBoolean(K_HOOK_BUILD, true);
            hookCursor = pref.getBoolean(K_HOOK_CURSOR, true);
            hookCall = pref.getBoolean(K_HOOK_CALL, true);
            hookDebugger = pref.getBoolean(K_HOOK_DEBUG, true);
            fuzzy = pref.getBoolean(K_FUZZY, true);
            debugLog = pref.getBoolean(K_LOG, false);
        } catch (Throwable t) {
            // 读不到就用默认值（全开）
        }
    }
}
