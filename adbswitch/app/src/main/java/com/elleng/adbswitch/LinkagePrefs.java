package com.elleng.adbswitch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 联动应用配置的集中读写。
 *
 * 数据模型（与旧的单一 {@code linkage_pkg} 不同）：
 *  - {@code linkage_pkgs}：JSONArray 字符串，保存「联动应用」列表（有序、去重）；
 *  - {@code linkage_default}：字符串，保存「开启时自动联动跳转」要跳去的默认应用包名。
 *
 * 升级兼容：旧版本只存一个 {@code linkage_pkg}。首次进入时（{@link #migrate}）
 * 把它并入列表，并顺势设为默认跳转应用，使旧的自动跳转行为不丢。
 *
 * 之所以用 JSONArray 字符串而不是 SharedPreferences 的 getStringSet：
 * 后者的顺序不保证、且要求每次都 new 一个 Set 传入（就地修改会被系统忽略），
 * 这里需要稳定顺序（列表展示顺序即存储顺序），故自管 JSON。
 */
final class LinkagePrefs {

    static final String PREFS = "cfg";
    static final String KEY_APPS = "linkage_pkgs";
    static final String KEY_DEFAULT = "linkage_default";
    static final String KEY_LEGACY_PKG = "linkage_pkg";

    private LinkagePrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读取联动应用列表（有序、去重）。首次调用会触发旧数据迁移。 */
    static List<String> getApps(Context c) {
        migrate(c);
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_APPS, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String p = a.optString(i);
                if (p != null && !p.isEmpty()) {
                    out.add(p);
                }
            }
        } catch (JSONException ignored) {
            // 损坏的数据：返回空列表，不崩溃
        }
        return out;
    }

    /** 整体写入联动应用列表（保序、去重；默认 app 若被移出列表则一并清空）。 */
    static void setApps(Context c, List<String> apps) {
        Set<String> set = new LinkedHashSet<>(apps);
        JSONArray a = new JSONArray();
        for (String p : set) {
            a.put(p);
        }
        sp(c).edit().putString(KEY_APPS, a.toString()).apply();
        // 默认 app 必须仍存在于列表中，否则联动自动跳转会找不到目标
        String def = getDefault(c);
        if (!def.isEmpty() && !set.contains(def)) {
            setDefault(c, "");
        }
    }

    /** 追加若干应用（去重，保留已有顺序在前）。 */
    static void addApps(Context c, List<String> toAdd) {
        List<String> cur = getApps(c);
        cur.addAll(toAdd);
        setApps(c, cur);
    }

    static String getDefault(Context c) {
        return sp(c).getString(KEY_DEFAULT, "");
    }

    static void setDefault(Context c, String pkg) {
        sp(c).edit().putString(KEY_DEFAULT, pkg == null ? "" : pkg).apply();
    }

    /**
     * 旧数据迁移：只做一次。
     *  - 若已有新字段 linkage_pkgs → 直接返回；
     *  - 否则若旧 linkage_pkg 非空 → 并入列表并设为默认；
     *  - 否则仅写入空列表标记，避免下次再进这里。
     */
    static void migrate(Context c) {
        SharedPreferences s = sp(c);
        if (s.contains(KEY_APPS)) {
            return;
        }
        String legacy = s.getString(KEY_LEGACY_PKG, "");
        if (legacy != null && !legacy.isEmpty()) {
            List<String> l = new ArrayList<>();
            l.add(legacy);
            setApps(c, l);
            setDefault(c, legacy);
        } else {
            s.edit().putString(KEY_APPS, "[]").apply();
        }
    }
}
