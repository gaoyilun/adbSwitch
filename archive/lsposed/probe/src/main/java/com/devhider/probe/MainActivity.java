package com.devhider.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 探针 App：用和 RASP 一模一样的方式去读「开发者选项 / USB 调试 / 无线调试」痕迹，
 * 然后把读到的值直接列出来。
 *
 * 用法：把这个 App 加入 DevOpts Hider 的 LSPosed 作用域 -> 强制停止 -> 重新打开。
 * 全绿说明目标 App（如香港汇丰）读到的就是「没开开发者选项」的状态。
 */
public class MainActivity extends Activity {

    static {
        try {
            System.loadLibrary("probe_native");
        } catch (Throwable t) {
            // 没有原生 so 也能跑（只是少了 native 通道那一栏）
        }
    }

    /** 直接走 libc __system_property_get，验证 Zygisk native 层 */
    private static native String nativePropGet(String key);

    private final List<Check> items = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView tvSummary;

    public static final class Check {
        public String name;
        public String value;
        public boolean ok;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvSummary = findViewById(R.id.tvSummary);
        ListView lv = findViewById(R.id.lvCheck);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = LayoutInflater.from(MainActivity.this)
                            .inflate(R.layout.item_check, parent, false);
                }
                Check c = items.get(position);
                ((TextView) v.findViewById(R.id.tvName)).setText(c.name);
                ((TextView) v.findViewById(R.id.tvValue)).setText("读到: " + c.value);
                TextView tv = v.findViewById(R.id.tvVerdict);
                tv.setText(c.ok ? "已隐藏" : "仍暴露");
                tv.setTextColor(c.ok ? 0xFF2E7D32 : 0xFFC62828);
                return v;
            }
        };
        lv.setAdapter(adapter);

        findViewById(R.id.btnRescan).setOnClickListener(v -> scan());
        Button btnCopy = findViewById(R.id.btnCopyFail);
        if (btnCopy != null) btnCopy.setOnClickListener(v -> copyFail());
        scan();
    }

    private void add(String name, String value, boolean ok) {
        Check c = new Check();
        c.name = name;
        c.value = value == null ? "(null)" : value;
        c.ok = ok;
        items.add(c);
    }

    private static boolean clean(String v) {
        return v == null || v.isEmpty() || "0".equals(v) || "false".equals(v)
                || "-1".equals(v) || "none".equals(v) || "stopped".equals(v);
    }

    /** 整表查询的结果：查不到（key 不在该 namespace）等于没泄漏，算通过 */
    private static boolean queryOk(String v) {
        if (v == null) return true;
        if (v.startsWith("(not found)") || v.startsWith("(null")
                || v.startsWith("(no name") || v.startsWith("(ERR")) {
            return true;
        }
        return clean(v);
    }

    private String setting(String key) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            if (v == null) v = Settings.Secure.getString(getContentResolver(), key);
            if (v == null) v = Settings.System.getString(getContentResolver(), key);
            return v;
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    /** 模拟「把整张 settings 表读走」的检测方式 */
    private String queryTable(String which, String want) {
        Cursor c = null;
        try {
            c = getContentResolver().query(Uri.parse("content://settings/" + which),
                    null, null, null, null);
            if (c == null) return "(null cursor)";
            int ni = c.getColumnIndex("name");
            int vi = c.getColumnIndex("value");
            if (ni < 0 || vi < 0) return "(no name/value)";
            while (c.moveToNext()) {
                if (want.equals(c.getString(ni))) return c.getString(vi);
            }
            return "(not found)";
        } catch (Throwable t) {
            return "ERR " + t;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getDeclaredMethod("get", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, key);
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    private void scan() {
        items.clear();

        // ---- Settings ----
        String[] keys = {"adb_enabled", "development_settings_enabled", "adb_wifi_enabled",
                "adb_allowed_connection_time", "debug_app", "wait_for_debugger",
                "mock_location", "show_touches", "pointer_location"};
        for (String k : keys) {
            String v = setting(k);
            add("Settings: " + k, v, clean(v));
        }

        // ---- 整表读取 ----
        String q1 = queryTable("global", "adb_enabled");
        add("query 全表(global): adb_enabled", q1, queryOk(q1));
        String q2 = queryTable("global", "development_settings_enabled");
        add("query 全表(global): development_settings_enabled", q2, queryOk(q2));
        String q3 = queryTable("secure", "development_settings_enabled");
        add("query 全表(secure): development_settings_enabled", q3, queryOk(q3));

        // ---- 系统属性 ----
        String[] props = {"ro.debuggable", "ro.secure", "ro.build.type", "ro.build.tags",
                "sys.usb.state", "sys.usb.config", "persist.sys.usb.config",
                "init.svc.adbd", "service.adb.tcp.port", "persist.adb.tcp.port", "ro.adb.secure"};
        for (String p : props) {
            String v = prop(p);
            boolean ok;
            if ("ro.secure".equals(p) || "ro.adb.secure".equals(p)) {
                ok = "1".equals(v) || v == null || v.isEmpty();
            } else if ("ro.build.type".equals(p)) {
                ok = "user".equals(v);
            } else if ("ro.build.tags".equals(p)) {
                ok = "release-keys".equals(v);
            } else if ("sys.usb.state".equals(p) || "sys.usb.config".equals(p)
                    || "persist.sys.usb.config".equals(p)) {
                ok = v == null || !v.toLowerCase().contains("adb");
            } else {
                ok = clean(v);
            }
            add("prop: " + p, v, ok);
        }

        // ---- Build ----
        add("Build.TYPE", Build.TYPE, "user".equals(Build.TYPE));
        add("Build.TAGS", Build.TAGS, Build.TAGS != null && Build.TAGS.contains("release-keys"));
        add("Build.FINGERPRINT", Build.FINGERPRINT,
                Build.FINGERPRINT != null && !Build.FINGERPRINT.contains("userdebug")
                        && !Build.FINGERPRINT.contains("/eng/"));

        // ---- 原生 __system_property_get（直接走 libc，验证 Zygisk native 层）----
        // 若这一栏显示「已隐藏」而上面「prop:」栏显示「仍暴露」，说明只有 native 层被处理，
        // 进一步证明 HSBC 很可能走的就是 native 通道（HMA-OSS 摸不到的那一层）。
        String[] nprops = {"ro.debuggable", "ro.build.type", "sys.usb.state",
                "init.svc.adbd", "service.adb.tcp.port"};
        for (String p : nprops) {
            String v;
            try {
                v = nativePropGet(p);
            } catch (Throwable t) {
                v = "ERR " + t;
            }
            boolean ok;
            if ("ro.build.type".equals(p)) ok = "user".equals(v);
            else if ("sys.usb.state".equals(p)) ok = v == null || !v.toLowerCase().contains("adb");
            else ok = clean(v);
            add("native prop: " + p, v, ok);
        }

        // ---- 文件 ----
        String[] paths = {"/sys/class/android_usb/android0/functions",
                "/sys/class/android_usb/android0/state", "/data/misc/adb/adb_keys"};
        for (String path : paths) {
            boolean e;
            try {
                e = new File(path).exists();
            } catch (Throwable t) {
                e = false;
            }
            add("File.exists " + path, String.valueOf(e), !e);
        }

        // ---- 无线调试端口 ----
        String r;
        boolean okConn;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 300);
            r = "connected";
            okConn = false;
        } catch (Throwable t) {
            r = t.getClass().getSimpleName();
            okConn = true;
        }
        add("Socket connect 127.0.0.1:5555", r, okConn);

        // ---- 调试器 ----
        boolean dbg = android.os.Debug.isDebuggerConnected();
        add("Debug.isDebuggerConnected", String.valueOf(dbg), !dbg);

        int ok = 0;
        for (Check c : items) {
            if (c.ok) ok++;
        }
        int leak = items.size() - ok;
        tvSummary.setText("共 " + items.size() + " 项：已隐藏 " + ok + "，仍暴露 " + leak);
        tvSummary.setTextColor(leak == 0 ? 0xFF2E7D32 : 0xFFC62828);
        adapter.notifyDataSetChanged();
    }

    /** 把「仍暴露」的异常项一键复制到剪贴板，方便贴给排查 */
    private void copyFail() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Check c : items) {
            if (c.ok) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(c.name).append(" = ").append(c.value);
            n++;
        }
        if (n == 0) {
            Toast.makeText(this, R.string.toast_no_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = "DevHider 探针异常项（共 " + n + " 条）：\n" + sb;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("DevHiderProbe", text));
        }
        Toast.makeText(this, getString(R.string.toast_copied, n), Toast.LENGTH_SHORT).show();
    }
}
