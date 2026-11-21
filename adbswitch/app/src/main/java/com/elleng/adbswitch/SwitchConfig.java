package com.elleng.adbswitch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 开关项配置（是否显示 / 是否跟随主开关）的持久化与查询。
 *
 * 语义（与用户确认的设计）：
 *  - shown = 该开关功能是否启用。隐藏 = 本 App 完全不管理（不显示、不写入、不跟随）。
 *  - followMaster = 是否跟随主开关一键开/关，仅在 shown=true 时有意义。
 *  - 合法状态三种：不显示 / 显示+跟随 / 显示+不跟随（不存在「不显示+跟随」）。
 *
 * 默认值（对齐旧版行为，升级无感）：
 *  - USB / 无线：显示 + 跟随（旧主开关管理的就是这两项）
 *  - 开发者选项：显示 + 不跟随（旧版独立开关）
 *  - 无障碍：显示 + 不跟随（新功能，默认不动它——一键关会停掉读屏等服务，须用户显式纳入）
 */
public final class SwitchConfig {

    private static final String PREFS = "cfg";
    private static final String PREFIX_SHOWN = "item_shown_";
    private static final String PREFIX_FOLLOW = "item_follow_";
    /** 显示顺序（SwitchItem.name 逗号串，全量排列；缺省/残缺按枚举顺序补全） */
    private static final String KEY_ORDER = "item_order";

    private SwitchConfig() {
    }

    /** 该开关项是否显示（= 功能是否启用） */
    public static boolean isShown(Context ctx, SwitchItem item) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(PREFIX_SHOWN + item.id, true);
    }

    /** 该开关项是否跟随主开关（隐藏项恒为 false） */
    public static boolean followsMaster(Context ctx, SwitchItem item) {
        return isShown(ctx, item) && rawFollow(ctx, item);
    }

    /** 原始跟随值（不带隐藏守卫）：供管理弹窗初始化，重新显示时恢复原设置 */
    static boolean rawFollow(Context ctx, SwitchItem item) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(PREFIX_FOLLOW + item.id, defaultFollow(item));
    }

    /** 设置是否显示。不动跟随值：「不显示+跟随」的非法态由 followsMaster 的读守卫独自维持，
     *  隐藏期间跟随值原样保留，重新显示时自然恢复（避免 USB 隐藏再显示后悄悄不再跟随） */
    public static void setShown(Context ctx, SwitchItem item, boolean shown) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREFIX_SHOWN + item.id, shown).apply();
    }

    /** 设置是否跟随主开关（隐藏项忽略写入） */
    public static void setFollowMaster(Context ctx, SwitchItem item, boolean follow) {
        if (!isShown(ctx, item)) {
            return;
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREFIX_FOLLOW + item.id, follow).apply();
    }

    /** 跟随主开关的项集合（主开关一键操作的目标） */
    public static EnumSet<SwitchItem> followedItems(Context ctx) {
        EnumSet<SwitchItem> out = EnumSet.noneOf(SwitchItem.class);
        for (SwitchItem item : SwitchItem.values()) {
            if (item.supported() && followsMaster(ctx, item)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 显示顺序（用户在「管理开关项」里拖动排出来的；残缺/未知项按枚举顺序补全） */
    public static List<SwitchItem> getOrder(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_ORDER, "");
        List<SwitchItem> out = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) {
                try {
                    SwitchItem it = SwitchItem.valueOf(s.trim());
                    if (!out.contains(it)) {
                        out.add(it);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 未知 id（版本升级后枚举变了）：跳过，由下面补全
                }
            }
        }
        for (SwitchItem it : SwitchItem.values()) {
            if (!out.contains(it)) {
                out.add(it);
            }
        }
        return out;
    }

    /** 保存显示顺序（传入完整排列；调用方来自管理弹窗的拖动结果） */
    public static void setOrder(Context ctx, List<SwitchItem> order) {
        StringBuilder sb = new StringBuilder();
        for (SwitchItem it : order) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(it.name());
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    private static boolean defaultFollow(SwitchItem item) {
        switch (item) {
            case USB:
            case WIRELESS:
                return true;
            default:
                return false;
        }
    }
}
