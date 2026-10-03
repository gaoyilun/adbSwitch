package com.devhider.lsp;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.io.IOException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 对目标 App 隐藏「开发者选项 / USB 调试 / 无线调试」痕迹。
 *
 * 覆盖 6 条检测路径：
 *  1. Settings.Global / Secure / System 的 getXxx / getXxxForUser
 *  2. ContentResolver.call(content://settings, "GET_xxx", name)   —— Android 12+ 的 Settings 底层通道
 *  3. ContentResolver.query(content://settings/...)                —— 批量把整张表读走的情况
 *  4. android.os.SystemProperties.get*                             —— 系统属性
 *  5. Build.TYPE / TAGS / FINGERPRINT / IS_DEBUGGABLE              —— 编译类型
 *  6. 文件(open/access) 与 端口(5555)                              —— /sys/class/android_usb、adb_keys、无线调试端口
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "DevHider";
    public static final String SELF = "com.devhider.lsp";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        if (pkg == null || SELF.equals(pkg)) return;

        Config.load();
        log("loaded in " + pkg);

        try {
            hookBuild();
            hookSettings();
            hookSystemProperties();
            hookContentResolver();
            hookFiles();
            hookPort();
            hookDebuggerFlags();
        } catch (Throwable t) {
            log("install error: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    static void log(String s) {
        if (Config.debugLog) {
            try {
                XposedBridge.log(TAG + ": " + s);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void tryHook(Class<?> cls, String method, XC_MethodHook cb, Class<?>... params) {
        try {
            Object[] args = new Object[params.length + 1];
            System.arraycopy(params, 0, args, 0, params.length);
            args[params.length] = cb;
            XposedHelpers.findAndHookMethod(cls, method, args);
        } catch (Throwable t) {
            log("miss " + cls.getName() + "." + method);
        }
    }

    private static Class<?> cls(String name) {
        try {
            return XposedHelpers.findClass(name, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object convert(String fake, Class<?> rt) {
        if (rt == String.class) return fake;
        if (rt == int.class || rt == Integer.class) return toInt(fake);
        if (rt == long.class || rt == Long.class) return toLong(fake);
        if (rt == float.class || rt == Float.class) return toFloat(fake);
        if (rt == boolean.class || rt == Boolean.class) return toBool(fake);
        return null;
    }

    private static int toInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            try {
                return (int) Float.parseFloat(v);
            } catch (Throwable t2) {
                return 0;
            }
        }
    }

    private static long toLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static float toFloat(String v) {
        try {
            return Float.parseFloat(v);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static boolean toBool(String v) {
        if (v == null) return false;
        String s = v.trim();
        return !(s.isEmpty() || "0".equals(s) || "false".equalsIgnoreCase(s) || "none".equals(s));
    }

    // ------------------------------------------------------------------
    // 1. Settings.Global / Secure / System
    // ------------------------------------------------------------------
    private final XC_MethodHook settingsHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Config.hookSettings) return;
            String name = null;
            for (Object a : param.args) {
                if (a instanceof String) {
                    name = (String) a;
                    break;
                }
            }
            if (name == null) return;
            String fake = Rules.settingsFake(name, Config.fuzzy);
            if (fake == null) return;
            Class<?> rt = returnType(param.method);
            Object val = convert(fake, rt);
            if (val == null) return;
            log("settings " + name + " -> " + fake);
            param.setResult(val);
        }
    };

    private static Class<?> returnType(Member m) {
        if (m instanceof Method) return ((Method) m).getReturnType();
        return null;
    }

    private void hookSettings() {
        String[] classes = {
                "android.provider.Settings$Global",
                "android.provider.Settings$Secure",
                "android.provider.Settings$System",
        };
        for (String cn : classes) {
            Class<?> c = cls(cn);
            if (c == null) continue;
            tryHook(c, "getString", settingsHook, ContentResolver.class, String.class);
            tryHook(c, "getStringForUser", settingsHook, ContentResolver.class, String.class, int.class);
            tryHook(c, "getInt", settingsHook, ContentResolver.class, String.class);
            tryHook(c, "getInt", settingsHook, ContentResolver.class, String.class, int.class);
            tryHook(c, "getIntForUser", settingsHook, ContentResolver.class, String.class, int.class);
            tryHook(c, "getIntForUser", settingsHook, ContentResolver.class, String.class, int.class, int.class);
            tryHook(c, "getLong", settingsHook, ContentResolver.class, String.class);
            tryHook(c, "getLong", settingsHook, ContentResolver.class, String.class, long.class);
            tryHook(c, "getLongForUser", settingsHook, ContentResolver.class, String.class, int.class);
            tryHook(c, "getLongForUser", settingsHook, ContentResolver.class, String.class, long.class, int.class);
            tryHook(c, "getFloat", settingsHook, ContentResolver.class, String.class);
            tryHook(c, "getFloat", settingsHook, ContentResolver.class, String.class, float.class);
            tryHook(c, "getFloatForUser", settingsHook, ContentResolver.class, String.class, int.class);
            tryHook(c, "getFloatForUser", settingsHook, ContentResolver.class, String.class, float.class, int.class);
        }
    }

    // ------------------------------------------------------------------
    // 4. android.os.SystemProperties
    // ------------------------------------------------------------------
    private final XC_MethodHook propHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Config.hookProps) return;
            String key = null;
            for (Object a : param.args) {
                if (a instanceof String) {
                    key = (String) a;
                    break;
                }
            }
            if (key == null) return;
            String real = null;
            String fake = Rules.PROPS.get(key);
            if (fake == null) {
                // 命中 strip 规则 / 模糊规则时，需要先拿到真值再改造
                try {
                    Object r = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    if (r instanceof String) real = (String) r;
                } catch (Throwable ignored) {
                }
                fake = Rules.propFake(key, real, Config.fuzzy);
            }
            if (fake == null) return;
            Class<?> rt = returnType(param.method);
            Object val = convert(fake, rt);
            if (val == null) return;
            log("prop " + key + " (" + real + ") -> " + fake);
            param.setResult(val);
        }
    };

    private void hookSystemProperties() {
        Class<?> c = cls("android.os.SystemProperties");
        if (c == null) return;
        tryHook(c, "get", propHook, String.class);
        tryHook(c, "get", propHook, String.class, String.class);
        tryHook(c, "getInt", propHook, String.class, int.class);
        tryHook(c, "getLong", propHook, String.class, long.class);
        tryHook(c, "getBoolean", propHook, String.class, boolean.class);
    }

    // ------------------------------------------------------------------
    // 2 + 3. ContentResolver.call / query
    // ------------------------------------------------------------------
    private void hookContentResolver() {
        // --- call(content://settings, "GET_global", name, null) ---
        tryHook(ContentResolver.class, "call", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Config.hookCall) return;
                try {
                    Uri uri = (Uri) param.args[0];
                    String method = (String) param.args[1];
                    String arg = (String) param.args[2];
                    if (uri == null || !"settings".equals(uri.getAuthority())) return;
                    if (method == null || !method.startsWith("GET_")) return;
                    String fake = Rules.settingsFake(arg, Config.fuzzy);
                    if (fake == null) return;
                    Bundle b = new Bundle();
                    b.putString("value", fake);
                    log("call " + arg + " -> " + fake);
                    param.setResult(b);
                } catch (Throwable t) {
                    log("call err " + t);
                }
            }
        }, Uri.class, String.class, String.class, Bundle.class);

        if (!Config.hookCursor) return;

        // --- query(content://settings/...) 批量读取整张表 ---
        XC_MethodHook qh = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Uri uri = (Uri) param.args[0];
                    if (uri == null || !"settings".equals(uri.getAuthority())) return;
                    Object res = param.getResult();
                    if (!(res instanceof Cursor)) return;
                    param.setResult(rewriteCursor((Cursor) res));
                } catch (Throwable t) {
                    log("query err " + t);
                }
            }
        };
        tryHook(ContentResolver.class, "query", qh,
                Uri.class, String[].class, String.class, String[].class, String.class);
        tryHook(ContentResolver.class, "query", qh,
                Uri.class, String[].class, String.class, String[].class, String.class,
                android.os.CancellationSignal.class);
        tryHook(ContentResolver.class, "query", qh,
                Uri.class, String[].class, Bundle.class, android.os.CancellationSignal.class);

        // 不走 ContentResolver、直接拿 ContentProviderClient 的那条路也要堵住
        Class<?> cpc = cls("android.content.ContentProviderClient");
        if (cpc != null) {
            tryHook(cpc, "query", qh,
                    Uri.class, String[].class, String.class, String[].class, String.class);
            tryHook(cpc, "query", qh,
                    Uri.class, String[].class, String.class, String[].class, String.class,
                    android.os.CancellationSignal.class);
            // ContentProviderClient.call(String method, String arg, Bundle extras)
            tryHook(cpc, "call", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Config.hookCall) return;
                    try {
                        String method = (String) param.args[0];
                        String arg = (String) param.args[1];
                        if (method == null || !method.startsWith("GET_")) return;
                        String fake = Rules.settingsFake(arg, Config.fuzzy);
                        if (fake == null) return;
                        Bundle b = new Bundle();
                        b.putString("value", fake);
                        log("cpc call " + arg + " -> " + fake);
                        param.setResult(b);
                    } catch (Throwable t) {
                        log("cpc call err " + t);
                    }
                }
            }, String.class, String.class, Bundle.class);
        }
    }

    private static Cursor rewriteCursor(Cursor src) {
        try {
            int nameIdx = src.getColumnIndex("name");
            int valueIdx = src.getColumnIndex("value");
            if (nameIdx < 0 || valueIdx < 0) return src;
            String[] cols = src.getColumnNames();
            MatrixCursor out = new MatrixCursor(cols);
            int guard = 0;
            while (src.moveToNext() && guard++ < 6000) {
                Object[] row = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    try {
                        row[i] = src.getString(i);
                    } catch (Throwable t) {
                        row[i] = null;
                    }
                }
                String name = src.getString(nameIdx);
                String fake = Rules.settingsFake(name, Config.fuzzy);
                if (fake != null) {
                    row[valueIdx] = fake;
                    log("cursor " + name + " -> " + fake);
                }
                out.addRow(row);
            }
            try {
                src.close();
            } catch (Throwable ignored) {
            }
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    // ------------------------------------------------------------------
    // 5. Build
    // ------------------------------------------------------------------
    private void hookBuild() {
        if (!Config.hookBuild) return;
        try {
            if (!"user".equals(Build.TYPE)) {
                XposedHelpers.setStaticObjectField(Build.class, "TYPE", "user");
            }
            String tags = Build.TAGS;
            if (tags != null && (tags.contains("test-keys") || tags.contains("dev-keys"))) {
                XposedHelpers.setStaticObjectField(Build.class, "TAGS", "release-keys");
            }
            if (Build.FINGERPRINT != null && Build.FINGERPRINT.contains("userdebug")
                    || Build.FINGERPRINT != null && Build.FINGERPRINT.contains("/eng/")) {
                XposedHelpers.setStaticObjectField(Build.class, "FINGERPRINT",
                        Rules.stripDebugWord(Build.FINGERPRINT));
            }
            try {
                XposedHelpers.setStaticBooleanField(Build.class, "IS_DEBUGGABLE", false);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            log("build err " + t);
        }
    }

    // ------------------------------------------------------------------
    // 6a. 文件 open / access
    // ------------------------------------------------------------------
    private void hookFiles() {
        if (!Config.hookFiles) return;
        Class<?> linux = cls("libcore.io.Linux");
        if (linux == null) {
            log("no libcore.io.Linux");
            return;
        }
        XC_MethodHook blocker = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String path = (String) param.args[0];
                    if (Rules.blockPath(path)) {
                        log("block file " + path);
                        Class<?> rt = returnType(param.method);
                        if (rt == int.class) param.setResult(-1);
                        else if (rt == boolean.class) param.setResult(false);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        tryHook(linux, "open", blocker, String.class, int.class, int.class);
        tryHook(linux, "access", blocker, String.class, int.class);
        tryHook(linux, "__open_2", blocker, String.class, int.class, int.class);
    }

    // ------------------------------------------------------------------
    // 6b. 无线调试 / adb 网络端口
    // ------------------------------------------------------------------
    private void hookPort() {
        if (!Config.hookPort) return;
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    SocketAddress addr = (SocketAddress) param.args[0];
                    if (!(addr instanceof InetSocketAddress)) return;
                    InetSocketAddress sa = (InetSocketAddress) addr;
                    if (Rules.blockPort(sa.getPort())) {
                        log("block port " + sa.getPort());
                        param.setThrowable(new IOException("Connection refused"));
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        tryHook(java.net.Socket.class, "connect", h, SocketAddress.class, int.class);
        tryHook(java.net.Socket.class, "connect", h, SocketAddress.class);
    }

    // ------------------------------------------------------------------
    // 调试器 / 测试环境标志
    // ------------------------------------------------------------------
    private void hookDebuggerFlags() {
        if (!Config.hookDebugger) return;
        tryHook(android.os.Debug.class, "isDebuggerConnected", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        });
        tryHook(android.os.Debug.class, "waitingForDebugger", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        });
        Class<?> am = cls("android.app.ActivityManager");
        if (am != null) {
            tryHook(am, "isUserAMonkey", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            tryHook(am, "isRunningInTestHarness", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
        }
    }
}
