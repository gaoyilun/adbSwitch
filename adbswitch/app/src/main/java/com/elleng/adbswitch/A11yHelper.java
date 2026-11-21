package com.elleng.adbswitch;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无障碍服务列表的读取、第三方过滤与目标列表构造。
 *
 * 唯一有效开关：{@code Settings.Secure.enabled_accessibility_services}
 * （冒号分隔的 pkg/component 列表）。实测（docs/a11y-toggle-research.md）：
 * 只切 {@code accessibility_enabled=0} 不会解绑已运行服务，必须改写这个列表才真正生效。
 *
 * 本类只做「列表级」纯逻辑（读、拆分、第三方判定、过滤、构造目标列表）；
 * 实际写入由 {@link DebugController} 按授权通道收口（WSS 直写 / shell put）。
 * 读一律走 ContentResolver —— 该键不像 adb 键会被 HyperOS 向第三方隐藏，直读即可信；
 * 读写同源（WSS 模式下写也走 ContentResolver）。
 */
public final class A11yHelper {

    /** Settings.Secure 键（字符串字面量，不引用隐藏 API 常量） */
    public static final String KEY = "enabled_accessibility_services";

    private A11yHelper() {
    }

    /** 读当前启用服务原始列表（"pkg/comp:pkg2/comp2"；无服务时 null / 空串） */
    public static String readRawList(Context ctx) {
        try {
            String raw = Settings.Secure.getString(ctx.getContentResolver(), KEY);
            // ROM 脱敏（实测荣耀 MagicOS）：应用内读到空串，shell 却能读到真值。
            // 一旦确认过这种脱敏，就改用以 shell 读为基准的缓存值，否则界面永远显示「无第三方服务」。
            if (TextUtils.isEmpty(raw) && crRedacted && trustedRaw != null) {
                logRawIfChanged(trustedRaw);
                return trustedRaw;
            }
            // 低噪音自检：值变了才打一行。部分 ROM 可能对第三方隐藏/脱敏该键，
            // 表现为这里恒为 null —— 界面就会显示「无第三方服务」而系统里其实开着服务，
            // 有这行日志才能一眼分清是「没读权限」还是「过滤逻辑判错」。
            logRawIfChanged(raw);
            return raw;
        } catch (Throwable t) {
            android.util.Log.w("A11yHelper", "read enabled list failed", t);
            return null;
        }
    }

    // —— 读通道兜底：ROM 脱敏时以 shell 读为准 ——

    /**
     * 上次经 shell（su / Shizuku，root / uid 2000）读到的列表。
     * 荣耀 MagicOS 实测：{@code settings get secure enabled_accessibility_services} 能读到 2 条，
     * 同一台设备上应用内 {@code Settings.Secure.getString} 却返回空串 —— 系统侧其实绑定了服务，
     * 界面却显示「无第三方服务」。这类 ROM 只能以 shell 值为准。
     */
    private static volatile String trustedRaw;

    /** ContentResolver 读是否已被判定为 ROM 脱敏（读到空、shell 读到非空） */
    private static volatile boolean crRedacted;

    /**
     * 记录一次「ContentResolver + shell」双通道对照读的结果（由 DebugController 在后台线程调用；
     * 本类不自己 fork shell —— 它会被 UI 线程上的读调用，阻塞在那里就是卡顿/ANR）。
     *
     * shell 读到空值时什么都不记：那既可能是「确实没有服务」，也可能是 shell 通道异常，
     * 都不该覆盖已有的可信值。
     */
    public static void noteTrustedRead(String crRaw, String shellRaw) {
        if (TextUtils.isEmpty(shellRaw)) {
            return;
        }
        trustedRaw = shellRaw;
        if (TextUtils.isEmpty(crRaw) && !crRedacted) {
            crRedacted = true;
            android.util.Log.i("A11yHelper", "ContentResolver read is EMPTY while shell read has "
                    + "value(s) — ROM redacts this key; trusting shell read");
        }
    }

    /**
     * 记录本应用刚写下去的目标列表。
     * 脱敏 ROM 上回读永远为空，不刷新这份缓存就会一直显示「写之前」的条数
     * （关掉无障碍后界面仍说「已启用 2 个」）。
     */
    public static void noteOwnWrite(String written) {
        trustedRaw = written == null ? "" : written;
    }

    // UI 与 IO 线程都会读写：仅用于日志去重，加 volatile 保证跨线程可见
    private static volatile String lastLoggedRaw = null;

    private static void logRawIfChanged(String raw) {
        if (raw != null && raw.equals(lastLoggedRaw)) {
            return;
        }
        if (raw == null && lastLoggedRaw == null) {
            return;
        }
        lastLoggedRaw = raw;
        android.util.Log.i("A11yHelper", "enabled_accessibility_services = " + raw);
    }

    /** 把列表串拆成条目（null / 空串 → 空列表） */
    public static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        for (String s : raw.split(":")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 条目 → 所属包名（"pkg/comp" 取斜杠前；容错：无斜杠则整段） */
    public static String packageOf(String entry) {
        int i = entry.indexOf('/');
        return i > 0 ? entry.substring(0, i) : entry;
    }

    /**
     * 该条目是否属于第三方应用（非系统预装）。
     * 口径已定：仅按 {@code ApplicationInfo.FLAG_SYSTEM} 过滤，Shizuku 自身服务也在此列之外。
     * 包已卸载时条目仍可能残留在列表里 —— 保守按第三方处理（关闭时清掉、恢复时原样写回）。
     */
    public static boolean isThirdParty(Context ctx, String entry) {
        String pkg = packageOf(entry);
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 列表里第三方条目数（副标题「已启用 X 个无障碍服务」的 X） */
    public static int countThirdParty(Context ctx, String raw) {
        int n = 0;
        for (String e : splitList(raw)) {
            if (isThirdParty(ctx, e)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 便利方法：直接读当前第三方条目数。
     *
     * 读空时多一步交叉验证：{@code enabled_accessibility_services} 读到空、而系统里却有
     * 已绑定的服务，说明该键被 ROM 脱敏（荣耀 MagicOS 实测），不是「真的没有服务」。
     * 此时按「已绑定的服务集合」计数，界面才不会在服务明明运行时显示「无第三方服务」。
     */
    public static int countThirdPartyNow(Context ctx) {
        String raw = readRawList(ctx);
        if (!TextUtils.isEmpty(raw) || trustedRaw != null) {
            // 有可信列表（含「确实为空」的情况）就按它算，不再猜
            return countThirdParty(ctx, raw);
        }
        Set<String> bound = boundServiceComponents(ctx);
        if (bound == null || bound.isEmpty()) {
            return 0;
        }
        int m = 0;
        for (String id : bound) {
            if (isThirdParty(ctx, id)) {
                m++;
            }
        }
        if (m > 0) {
            android.util.Log.i("A11yHelper", "list read empty but " + m
                    + " third-party service(s) are bound — ROM hides list, counting bound ones");
        }
        return m;
    }

    /** 过滤掉第三方条目（保留系统预装服务）→ 关闭第三方的目标列表 */
    public static String filterOutThirdParty(Context ctx, String raw) {
        List<String> kept = new ArrayList<>();
        for (String e : splitList(raw)) {
            if (!isThirdParty(ctx, e)) {
                kept.add(e);
            }
        }
        return TextUtils.join(":", kept);
    }

    /** 列表里的第三方包名（去重；恢复时用于 am start 拉起进程，避免卡 Binding services） */
    public static List<String> thirdPartyPackages(Context ctx, String raw) {
        List<String> out = new ArrayList<>();
        for (String e : splitList(raw)) {
            if (isThirdParty(ctx, e)) {
                String pkg = packageOf(e);
                if (!out.contains(pkg)) {
                    out.add(pkg);
                }
            }
        }
        return out;
    }

    /** 列表里的第三方条目（不去重；恢复后逐条核对绑定结果用） */
    public static List<String> thirdPartyEntries(Context ctx, String raw) {
        List<String> out = new ArrayList<>();
        for (String e : splitList(raw)) {
            if (isThirdParty(ctx, e)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 当前**已实际绑定**（系统已完成连接、服务可工作）的服务组件集合（"pkg/全类名"）。
     *
     * 数据源 {@code AccessibilityManager.getEnabledAccessibilityServiceList} —— 它只含
     * 已绑定服务（AOSP 侧遍历 mBoundServices），卡在「正在绑定」的不在其中。这正是
     * 「恢复是否真的生效」的判据：无需 root / shell，任何授权通道都可读。
     * （此前只按「有无 shell 通道能拉进程」猜测恢复结果，纯 WSS 无 shell 时全计
     * 「需手动」——实测小米 14 无 root 服务正常绑定，属误判。）
     *
     * @return 组件 id 集合；查询失败返回 null（调用方按「无法核对」回退旧口径）
     */
    public static Set<String> boundServiceComponents(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return null;
            }
            List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) {
                return null;
            }
            Set<String> out = new HashSet<>();
            for (AccessibilityServiceInfo info : list) {
                // 主取证用 getId()（"pkg/全类名"，Parcelable 必带）；ResolveInfo 在部分
                // ROM/版本上可能为 null 或被包可见性裁剪，只作兜底。
                String id = info.getId();
                if (!TextUtils.isEmpty(id)) {
                    out.add(id);
                    continue;
                }
                ResolveInfo ri = info.getResolveInfo();
                if (ri != null && ri.serviceInfo != null) {
                    out.add(ri.serviceInfo.packageName + "/" + ri.serviceInfo.name);
                }
            }
            // Log.d：本方法在「列表读空」的兜底路径上会被频繁调用，别用 I 级刷屏
            android.util.Log.d("A11yHelper", "bound services: " + out);
            return out;
        } catch (Throwable t) {
            android.util.Log.w("A11yHelper", "query bound services failed", t);
            return null;
        }
    }

    /**
     * entries 中尚未绑定的第三方条目（= 真的没生效、需用户手动处理的）。
     * 非第三方条目不核对（系统服务自会绑定）。
     *
     * @return 未绑定条目；无法核对绑定（boundServiceComponents 为 null）时返回 null
     */
    public static List<String> unboundEntries(Context ctx, List<String> entries) {
        Set<String> bound = boundServiceComponents(ctx);
        if (bound == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String e : entries) {
            if (!entryBound(e, bound)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 条目是否出现在已绑定组件集合里（容错 flattenToString / flattenToShortString 两种串法）。
     *
     * **两边都要归一化**：这两个写法没有固定由哪一侧给出——
     * 无障碍列表 key 里存的是全限定 `pkg/pkg.Cls`，而 {@code AccessibilityServiceInfo.getId()}
     * 在 K90 实测返回的是缩写 `pkg/.Cls`。旧实现只补全了「entry 是缩写」的方向，
     * 遇到「bound 是缩写、entry 是全限定」就永远比不相等，服务明明绑着却一直判未绑定，
     * 于是「仍有 N 个需手动开启」永久残留、懒复核每 5 秒空转一次。
     *
     * 故统一把两边的类名都补全成全限定再比对，与写法来源无关。
     */
    static boolean entryBound(String entry, Set<String> bound) {
        if (entry == null || bound == null) {
            return false;
        }
        int j = entry.indexOf('/');
        if (j <= 0) {
            return false;
        }
        final String epkg = entry.substring(0, j);
        final String ecls = fullClassName(epkg, entry.substring(j + 1));
        for (String b : bound) {
            if (b == null) {
                continue;
            }
            int i = b.indexOf('/');
            if (i <= 0) {
                continue;
            }
            String pkg = b.substring(0, i);
            if (!epkg.equals(pkg)) {
                continue;
            }
            if (ecls.equals(fullClassName(pkg, b.substring(i + 1)))) {
                return true;
            }
        }
        return false;
    }

    /** 类名补全：flattenToShortString 的 ".Cls" 展开成 "pkg.Cls"，其余原样返回 */
    static String fullClassName(String pkg, String cls) {
        if (cls == null) {
            return "";
        }
        return cls.startsWith(".") ? pkg + cls : cls;
    }
}
