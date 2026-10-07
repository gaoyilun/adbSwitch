package com.elleng.devswitch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import com.elleng.devswitch.LinkagePrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 本应用动态快捷方式的唯一发布器，一次全量发布（长按菜单条目全部由这里控制）：
 *  1) toggle_master 一键开关（恒排最前）：图标是状态灯——开启绿点、
 *     关闭橙点，颜色与主界面状态圆点同源（stateOn / stateOff）；
 *  2) 联动「开启时自动联动跳转」打开时：open_home 打开主页（房子图标；必须用独立
 *     action，MainActivity 见到它跳过联动自动跳转）；
 *  3) 联动关闭时：改为发布 USB 调试 / 无线调试两个子开关，各自带状态灯、
 *     单独拨只改对应键（与主界面子开关同一语义）；联动开着时条目放不下，不发布；
 *  4) launch_linkage_app 启动联动应用：图标/名称取用户选定的目标应用，
 *     未选择联动应用时不发布。
 * 主/子开关的状态联动不需要额外逻辑：所有写入都经由 DebugController，
 * 主开关关=两个都关、子开关全关=anyOn 变假，发布时现读真值所以灯永远一致。
 *
 * 全部走动态而非 manifest 静态项的原因：静态项在长按菜单里恒排在动态项前面，
 * 且图标无法随状态变色、顺序无法控制（本 ROM 实测不允许同 id 动态覆盖静态）。
 *
 * 为什么全量重建、而不是 getDynamicShortcuts 拿旧条目合并复用：
 * 旧 ShortcutInfo 经 binder 往返后图标字段会被剥空（实测两种图标都丢），
 * setDynamicShortcuts 又必须整列表替换（漏发即删除），所以每条都必须当场新鲜构造。
 *
 * 指示灯只在本 App 触及状态时刷新（界面刷新、磁贴展开、开关写完）；
 * App 没跑过、调试在别处被改时，灯保持上次状态，属可接受的信息滞后。
 */
final class ToggleShortcuts {

    static final String ID = "toggle_master";
    static final String ACTION_TOGGLE = "com.elleng.devswitch.action.TOGGLE_MASTER";
    static final String ID_USB = "toggle_usb";
    static final String ACTION_TOGGLE_USB = "com.elleng.devswitch.action.TOGGLE_USB";
    static final String ID_WIRELESS = "toggle_wireless";
    static final String ACTION_TOGGLE_WIRELESS = "com.elleng.devswitch.action.TOGGLE_WIRELESS";
    static final String ID_HOME = "open_home";
    static final String ACTION_OPEN_HOME = "com.elleng.devswitch.action.OPEN_HOME";
    static final String ID_LAUNCH = "launch_linkage_app";

    private ToggleShortcuts() {
    }

    /** 上次发布的内容指纹：内容没变就不重建（发布要走 IPC 且构造图标，不该随每次状态广播重做） */
    private static volatile String lastSignature = null;

    static void publish(Context ctx) {
        try {
            ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
            if (sm == null) {
                return;
            }
            DebugController.State s = DebugController.read(ctx);
            SharedPreferences sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
            boolean linkage = sp.getBoolean("linkage_enabled", false);
            // 动态「启动联动应用」快捷方式 = 启动「默认跳转应用」（自动跳转也用同一个目标）
            String pkg = LinkagePrefs.getDefault(ctx);

            // 指纹覆盖一切会影响发布结果的因素；进程重启后 lastSignature 归零，必定重发一次
            String sig = (s.usb ? "1" : "0") + (s.wireless ? "1" : "0") + '|' + linkage + '|' + pkg;
            if (sig.equals(lastSignature)) {
                return;
            }
            lastSignature = sig;

            List<ShortcutInfo> list = new ArrayList<>();
            list.add(buildToggle(ctx, s));
            int rank = 1;
            if (linkage) {
                // 联动开着：给出「打开主页」绕开自动跳转，子开关放不下、不发布
                list.add(buildHome(ctx, rank++));
            } else {
                list.add(buildSub(ctx, ID_USB, ACTION_TOGGLE_USB,
                        R.string.sc_toggle_usb_short, R.string.sc_toggle_usb_long,
                        s.usb, rank++));
                list.add(buildSub(ctx, ID_WIRELESS, ACTION_TOGGLE_WIRELESS,
                        R.string.sc_toggle_wireless_short, R.string.sc_toggle_wireless_long,
                        s.wireless, rank++));
            }
            if (!pkg.isEmpty()) {
                list.add(buildLaunch(ctx, pkg, rank++));
            }
            sm.setDynamicShortcuts(list);
        } catch (Throwable t) {
            android.util.Log.w("UsbSwitch", "shortcut publish failed", t);
        }
    }

    private static ShortcutInfo buildToggle(Context ctx, DebugController.State s) {
        return new ShortcutInfo.Builder(ctx, ID)
                .setRank(0)
                .setShortLabel(ctx.getString(R.string.sc_toggle_short))
                .setLongLabel(ctx.getString(R.string.sc_toggle_long))
                .setIcon(Icon.createWithResource(ctx,
                        s.anyOn() ? R.drawable.ic_dot_on : R.drawable.ic_dot_off))
                .setIntent(new Intent(ctx, ShortcutToggleActivity.class).setAction(ACTION_TOGGLE))
                .build();
    }

    /** 子开关（USB / 无线）：灯反映各自的真值，点击单独拨那一个键 */
    private static ShortcutInfo buildSub(Context ctx, String id, String action,
                                         int shortRes, int longRes, boolean on, int rank) {
        return new ShortcutInfo.Builder(ctx, id)
                .setRank(rank)
                .setShortLabel(ctx.getString(shortRes))
                .setLongLabel(ctx.getString(longRes))
                .setIcon(Icon.createWithResource(ctx,
                        on ? R.drawable.ic_dot_on : R.drawable.ic_dot_off))
                .setIntent(new Intent(ctx, ShortcutToggleActivity.class).setAction(action))
                .build();
    }

    private static ShortcutInfo buildHome(Context ctx, int rank) {
        return new ShortcutInfo.Builder(ctx, ID_HOME)
                .setRank(rank)
                .setShortLabel(ctx.getString(R.string.sc_open_home_short))
                .setLongLabel(ctx.getString(R.string.sc_open_home_long))
                .setIcon(Icon.createWithResource(ctx, R.drawable.ic_home))
                .setIntent(new Intent(ctx, MainActivity.class).setAction(ACTION_OPEN_HOME))
                .build();
    }

    private static ShortcutInfo buildLaunch(Context ctx, String pkg, int rank) {
        CharSequence label;
        try {
            label = ctx.getPackageManager().getApplicationLabel(
                    ctx.getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            label = pkg;
        }
        Intent target = new Intent(ctx, MainActivity.class)
                .setAction(ID_LAUNCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return new ShortcutInfo.Builder(ctx, ID_LAUNCH)
                .setRank(rank)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(appIcon(ctx, pkg))
                .setIntent(target)
                .build();
    }

    /** 目标应用图标转位图（ShortcutInfo 只认 Icon；非位图 drawable 先画成位图） */
    private static Icon appIcon(Context ctx, String pkg) {
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                return Icon.createWithBitmap(
                        ((android.graphics.drawable.BitmapDrawable) d).getBitmap());
            }
            int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96);
            int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return Icon.createWithBitmap(bmp);
        } catch (Throwable t) {
            return Icon.createWithResource(ctx, R.mipmap.ic_launcher);
        }
    }
}
