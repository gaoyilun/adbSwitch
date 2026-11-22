package com.elleng.adbswitch;

import com.elleng.adbswitch.util.DrawableUtils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import com.elleng.adbswitch.LinkagePrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 本应用动态快捷方式的唯一发布器，一次全量发布（长按菜单条目全部由这里控制）：
 *  1) toggle_master 一键开关（恒排最前）：图标是状态灯——开启绿点、
 *     关闭橙点，颜色与主界面状态圆点同源（stateOn / stateOff）；
 *  2) 联动「开启时自动联动跳转」打开时：open_home 打开主页（房子图标；必须用独立
 *     action，MainActivity 见到它跳过联动自动跳转）；
 *  3) 联动关闭时：按用户在「管理开关项」里的勾选发布 USB / 无线 / 开发者选项 /
 *     无障碍四个子开关，顺序跟随用户拖动结果；每个带状态灯、单独拨只改对应键，
 *     与主界面子开关同一语义。联动开着时条目放不下，沿用旧行为不发布子开关；
 *  4) launch_linkage_app 启动联动应用：图标/名称取用户选定的目标应用，
 *     未选择联动应用、或用户关掉「快捷菜单中显示默认快捷应用」时不发布。
 * 超出系统 getMaxShortcutCountPerActivity() 上限时从尾部截断，优先级为
 * 主开关 → 用户勾选的子开关 → 打开主页 → 启动应用。
 * 主/子开关的状态联动不需要额外逻辑：所有写入都经由 DebugController，
 * 主开关关=跟随它的项都关、跟随项全关=anyOn 变假，发布时现读真值所以灯永远一致。
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
    static final String ACTION_TOGGLE = "com.elleng.adbswitch.action.TOGGLE_MASTER";
    static final String ID_USB = "toggle_usb";
    static final String ACTION_TOGGLE_USB = "com.elleng.adbswitch.action.TOGGLE_USB";
    static final String ID_WIRELESS = "toggle_wireless";
    static final String ACTION_TOGGLE_WIRELESS = "com.elleng.adbswitch.action.TOGGLE_WIRELESS";
    static final String ID_DEV = "toggle_dev";
    static final String ACTION_TOGGLE_DEV = "com.elleng.adbswitch.action.TOGGLE_DEV";
    static final String ID_A11Y = "toggle_a11y";
    static final String ACTION_TOGGLE_A11Y = "com.elleng.adbswitch.action.TOGGLE_A11Y";
    static final String ID_HOME = "open_home";
    static final String ACTION_OPEN_HOME = "com.elleng.adbswitch.action.OPEN_HOME";
    static final String ID_LAUNCH = "launch_linkage_app";

    /**
     * 中转页调用方鉴权 token（P0 安全收口）：
     * 五个 toggle action（主开关 + 四个子开关）是公开字符串、ShortcutToggleActivity 又必须
     * exported 供 launcher 跨 UID 拉起，仅靠 action 白名单挡不住三方 App 构造同 action 的
     * 显式 intent 触发特权写。
     * 故发布 shortcut 时写入一个随安装生成、不可猜测的 extra token，中转页校验通过才执行。
     * token 存 MODE_PRIVATE 偏好（三方 App 读不到），反编译也拿不到（运行时随机、非硬编码）。
     */
    private static final String KEY_ACTION_TOKEN = "shortcut_action_token";
    private static final String EXTRA_ACTION_TOKEN = "com.elleng.adbswitch.extra.SHORTCUT_TOKEN";

    /** 取（或首次生成）本安装的 action token；仅本应用私有偏好可见 */
    private static String actionToken(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        String t = sp.getString(KEY_ACTION_TOKEN, "");
        if (t == null || t.isEmpty()) {
            // 两段 UUID 拼接，熵足够；后台线程调用，用 commit 落盘确保发布与校验一致。
            t = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString();
            sp.edit().putString(KEY_ACTION_TOKEN, t).commit();
        }
        return t;
    }

    /** 校验intent是否携带本安装的合法 token（供 ShortcutToggleActivity 在写入前调用） */
    static boolean hasValidActionToken(Context ctx, Intent in) {
        if (in == null) {
            return false;
        }
        SharedPreferences sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        String expected = sp.getString(KEY_ACTION_TOKEN, "");
        return expected != null && !expected.isEmpty()
                && expected.equals(in.getStringExtra(EXTRA_ACTION_TOKEN));
    }

    private ToggleShortcuts() {
    }

    /** 上次发布的内容指纹：内容没变就不重建（发布要走 IPC 且构造图标，不该随每次状态广播重做） */
    private static volatile String lastSignature = null;
    /** 正在后台构建的指纹：构建在途期间同指纹不重复提交（慢构建时 250ms 防抖挡不住） */
    private static volatile String buildingSignature = null;

    static void publish(Context ctx) {
        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        if (sm == null) {
            return;
        }
        DebugController.State s = DebugController.read(ctx);
        SharedPreferences sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        boolean linkage = sp.getBoolean("linkage_enabled", false);
        // 动态「启动联动应用」快捷方式 = 启动「默认跳转应用」（自动跳转也用同一个目标）
        String pkg = LinkagePrefs.getDefault(ctx);
        boolean launchInMenu = SwitchConfig.isLaunchInMenu(ctx);
        // 用户关掉「快捷菜单中显示默认快捷应用」后，这一条不再发布，腾出一个子开关槽位

        // 指纹覆盖一切会影响发布结果的因素；进程重启后 lastSignature 归零，必定重发一次。
        // 指纹比较在主线程（廉价字符串拼接），无变化直接返回，避免高频广播连发重建。
        // a11y 计数进指纹：它参与主开关聚合（a11y 跟随时），变化时主开关点要跟着变。
        // dev 与跟随项集合同理：主开关灯用 masterAnyOn(followed)，漏了它们改跟随后灯不重建。
        // 快捷菜单勾选集合 + 其顺序也须进指纹：改了勾选/排序必须全量重发，否则长按菜单不更新。
        StringBuilder subSig = new StringBuilder();
        if (!linkage) {
            for (SwitchItem it : SwitchConfig.getOrder(ctx)) {
                if (SwitchConfig.usesShortcut(ctx, it)) {
                    subSig.append(it.id).append(',');
                }
            }
        }
        String sig = (s.usb ? "1" : "0") + (s.wireless ? "1" : "0") + (s.dev ? "1" : "0")
                + ':' + s.a11yCount + '|' + linkage + '|' + pkg + '|' + launchInMenu
                + '|' + SwitchConfig.followedItems(ctx) + '|' + subSig;
        if (sig.equals(lastSignature) || sig.equals(buildingSignature)) {
            return;
        }
        buildingSignature = sig;

        // §2.3：构建 ShortcutInfo 列表（含 PM IPC 取标签/图标 + 位图化）移到后台线程，
        // 只把 setDynamicShortcuts 这个发布调用切回主线程（PUBLISH_HANDLER 绑主线程 Looper），
        // 避免主线程做若干次 IPC + 位图拷贝而掉帧。
        final String fsig = sig;
        final Context app = ctx.getApplicationContext();
        BUILD_EXEC.execute(() -> {
            try {
                List<ShortcutInfo> list = new ArrayList<>();
                list.add(buildToggle(app, s));
                int rank = 1;
                if (linkage) {
                    // 联动开着：给出「打开主页」绕开自动跳转，子开关放不下、不发布
                    list.add(buildHome(app, rank++));
                } else {
                    // 联动关闭：按用户的勾选与拖动顺序发布子开关
                    for (SwitchItem it : SwitchConfig.getOrder(app)) {
                        if (SwitchConfig.usesShortcut(app, it)) {
                            rank = addSubShortcut(app, it, s, list, rank);
                        }
                    }
                }
                if (!pkg.isEmpty() && launchInMenu) {
                    list.add(buildLaunch(app, pkg, rank++));
                }
                // 系统对动态快捷方式条数有上限，超出必须从尾部截断（setDynamicShortcuts 会拒绝整个列表）。
                // 主开关在最前、其余按「子开关 → 打开主页 → 启动应用」加入，截尾即保留高优先级项。
                int max = sm.getMaxShortcutCountPerActivity();
                final List<ShortcutInfo> finalList = list.size() > max
                        ? new ArrayList<>(list.subList(0, max)) : list;
                // 只有发布成功才记指纹：setDynamicShortcuts 被系统限流/拒绝时返回 false，
                // 若先记指纹，之后指纹相同就永远不再重试，长按菜单的状态灯会长期失真。
                PUBLISH_HANDLER.post(() -> {
                    if (fsig.equals(buildingSignature)) {
                        buildingSignature = null;
                    }
                    if (!sm.setDynamicShortcuts(finalList)) {
                        android.util.Log.w("AdbSwitch", "setDynamicShortcuts rejected, will retry on next publish");
                        return;
                    }
                    lastSignature = fsig;
                });
            } catch (Throwable t) {
                if (fsig.equals(buildingSignature)) {
                    buildingSignature = null;
                }
                android.util.Log.w("AdbSwitch", "shortcut build failed", t);
            }
        });
    }

    /** 按开关项补一条子开关快捷方式；灯取该项真值，返回更新后的 rank */
    private static int addSubShortcut(Context ctx, SwitchItem item, DebugController.State s,
                                      List<ShortcutInfo> list, int rank) {
        switch (item) {
            case USB:
                list.add(buildSub(ctx, ID_USB, ACTION_TOGGLE_USB,
                        R.string.sc_toggle_usb_short, R.string.sc_toggle_usb_long,
                        s.usb, rank++));
                break;
            case WIRELESS:
                list.add(buildSub(ctx, ID_WIRELESS, ACTION_TOGGLE_WIRELESS,
                        R.string.sc_toggle_wireless_short, R.string.sc_toggle_wireless_long,
                        s.wireless, rank++));
                break;
            case DEV:
                list.add(buildSub(ctx, ID_DEV, ACTION_TOGGLE_DEV,
                        R.string.sc_toggle_dev_short, R.string.sc_toggle_dev_long,
                        s.dev, rank++));
                break;
            case A11Y:
                list.add(buildSub(ctx, ID_A11Y, ACTION_TOGGLE_A11Y,
                        R.string.sc_toggle_a11y_short, R.string.sc_toggle_a11y_long,
                        s.a11yCount > 0, rank++));
                break;
            default:
                break;
        }
        return rank;
    }

    private static ShortcutInfo buildToggle(Context ctx, DebugController.State s) {
        // 主开关点 = 跟随主开关的项的聚合态（与磁贴/通知同一语义）
        boolean on = DebugController.masterAnyOn(ctx, s);
        return new ShortcutInfo.Builder(ctx, ID)
                .setRank(0)
                .setShortLabel(ctx.getString(R.string.sc_toggle_short))
                .setLongLabel(ctx.getString(R.string.sc_toggle_long))
                .setIcon(Icon.createWithResource(ctx,
                        on ? R.drawable.ic_dot_on : R.drawable.ic_dot_off))
                .setIntent(toggleIntent(ctx, ACTION_TOGGLE))
                .build();
    }

    /** 子开关（四项的任一项）：灯反映各自真值，点击单独拨那一个键 */
    private static ShortcutInfo buildSub(Context ctx, String id, String action,
                                         int shortRes, int longRes, boolean on, int rank) {
        return new ShortcutInfo.Builder(ctx, id)
                .setRank(rank)
                .setShortLabel(ctx.getString(shortRes))
                .setLongLabel(ctx.getString(longRes))
                .setIcon(Icon.createWithResource(ctx,
                        on ? R.drawable.ic_dot_on : R.drawable.ic_dot_off))
                .setIntent(toggleIntent(ctx, action))
                .build();
    }

    /** 构造中转页 intent 并附上本安装 token（主/子开关五个 action 统一走这里，不漏发） */
    private static Intent toggleIntent(Context ctx, String action) {
        return new Intent(ctx, ShortcutToggleActivity.class)
                .setAction(action)
                .putExtra(EXTRA_ACTION_TOKEN, actionToken(ctx));
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
            // 固定目标尺寸：自适应图标内在尺寸 108dp（4x 屏 ≈ 432px ⇒ 单张 ≈ 746KB），
            // 而快捷方式实际只按 ~48dp 显示，按内在尺寸栅格化纯属浪费
            Bitmap bmp = DrawableUtils.toBitmap(d, 96);
            if (bmp != null) {
                return Icon.createWithBitmap(bmp);
            }
        } catch (Throwable t) {
        }
        return Icon.createWithResource(ctx, R.mipmap.ic_launcher);
    }

    // —— 合并发布：状态广播高频连发时，只真正发布最后一次（发布走 IPC 且要构造图标） ——

    private static final android.os.Handler PUBLISH_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 后台构建快捷方式列表（PM IPC + 图标位图化）的执行器，避免主线程掉帧（§2.3） */
    private static final Executor BUILD_EXEC = Executors.newSingleThreadExecutor();
    private static volatile Context pendingCtx = null;
    private static final Runnable PUBLISH_RUN = () -> {
        Context ctx = pendingCtx;
        if (ctx != null) {
            publish(ctx);
        }
    };

    /** 标记「需重发」并延迟合并：连发的状态变化只发布最后一次，避免每次广播都重做 IPC。 */
    static void markDirty(Context ctx) {
        pendingCtx = ctx.getApplicationContext();
        PUBLISH_HANDLER.removeCallbacks(PUBLISH_RUN);
        PUBLISH_HANDLER.postDelayed(PUBLISH_RUN, 250);
    }
}
