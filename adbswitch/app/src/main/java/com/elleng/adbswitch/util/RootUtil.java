package com.elleng.adbswitch.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 通过 su 执行 root 命令。本 App 不依赖 XPosed / 不 hook，
 * 只是用 root 权限改写 settings global adb_enabled 来真实开关 USB 调试。
 */
public final class RootUtil {

    /**
     * 单条命令的执行上限。
     *
     * 为什么必须有超时：DebugController 的写入走的是**单线程** IO 执行器，
     * 一次 su 挂住（授权弹窗没人点、su 二进制异常、系统卡顿）就会把这条线程永久占死，
     * 之后 App 内所有开关操作全部静默排队失效，只能杀进程才能恢复——这是最恶劣的失效形态。
     * 宁可这次返回失败（UI 会提示「执行失败」），也不能把通道堵死。
     *
     * 10s 足够覆盖「用户看到 Magisk 授权弹窗并点允许」的正常往返。
     */
    public static final long TIMEOUT_MS = 10_000;

    /** 超时专属退出码：与真实的 shell 退出码区分开，便于定位 */
    public static final int CODE_TIMEOUT = -2;

    /**
     * 失败原因（ShizukuUtil 复同一套取值，便于 UI 一条链路选文案）。
     *
     * 为什么需要：之前所有失败都归成一句「执行失败，请检查授权模式」，
     * 而「设备根本没有 su」「root 授权被拒」「su 挂住超时」「Shizuku 没启动」「Shizuku 没授权」
     * 五种情况的处理方式完全不同（换模式 / 去 Magisk 授权 / 手动确认 / 去启动 Shizuku / 在管理器里授权），
     * 一句笼统文案等于让用户自己猜。
     */
    public static final int REASON_NONE = 0;
    /** 通道可用，但命令本身返回非 0（被 ROM 拦截、命令不存在等） */
    public static final int REASON_EXEC = 1;
    /** 等子进程等到超时（授权弹窗没人点、系统卡住） */
    public static final int REASON_TIMEOUT = 2;
    /** 设备上没有可用的 su 二进制（等于没有 root 方案） */
    public static final int REASON_NO_SU = 3;
    /** su 存在但没拿到 root（授权被拒 / 未授权） */
    public static final int REASON_DENIED = 4;
    /** Shizuku 服务没在跑（binder 拿不到） */
    public static final int REASON_NO_BINDER = 5;
    /** Shizuku 在跑，但本应用没被授权 */
    public static final int REASON_NO_PERMISSION = 6;
    /**
     * 免root模式（WSS）尚未拿到那一次性的 pm grant。
     * 单独一个原因码：此时缺的既不是 su 也不是 Shizuku，而是「去完成一次性授权」——
     * 若落进 root 兜底通道，无 root 设备上只会报一句「没有 su」，把用户往 Magisk 带偏。
     */
    public static final int REASON_NEED_GRANT = 7;
    /**
     * 免root模式（WSS）下，ContentResolver 写入这些键被系统静默丢弃：
     * {@code Settings.Global.putString} 返回 false（不是异常，命令层面"成功"了）。
     * 某些 ROM（如 HyperOS）对第三方应用写 {@code adb_enabled} / {@code adb_wifi_enabled}
     * 直接丢弃且不抛错，putInt 的 void 返回会让调用方误以为写成功了——
     * 必须显式把这个假成功标记成失败，UI 才能如实提示「写入未生效」。
     */
    public static final int REASON_WRITE_DROPPED = 8;

    public static class Result {
        public int code;
        public String out = "";
        public String err = "";
        /** 失败原因，见 REASON_*；成功时为 REASON_NONE */
        public int reason = REASON_NONE;

        public boolean ok() {
            return code == 0;
        }
    }

    /** root 探测结论：给 UI 区分「没有 root 方案」与「有 su 但没授权」 */
    public enum RootStatus {
        /** su 后拿到 uid 0 */
        GRANTED,
        /** 设备里根本没有可用的 su（未 root / su 被隐藏） */
        NO_SU,
        /** su 存在但没拿到 root（未授权、被拒、超时） */
        DENIED
    }

    private RootUtil() {
    }

    /** 是否拥有 root（su 后能拿到 uid 0） */
    public static boolean hasRoot() {
        return probe() == RootStatus.GRANTED;
    }

    /**
     * 探测 root 的实际状态。
     *
     * 与旧实现（只看 `id -u` 的输出）的两点差异：
     *  1. 同时校验退出码：su 存在但被拒时，某些 su 实现仍会往 stdout 吐东西，
     *     只看输出会把「明明没授权」误判成已授权 —— 退出码是唯一可信的判据。
     *  2. 区分「没有 su」与「有 su 没授权」：前者换 root 模式没意义（该去装 root 或换 Shizuku），
     *     后者才该去 Magisk 里点允许。UI 靠这个结论给不同的引导文案。
     */
    public static RootStatus probe() {
        Result r = su("id -u");
        if (r.ok() && "0".equals(r.out.trim())) {
            return RootStatus.GRANTED;
        }
        if (r.reason == REASON_NO_SU) {
            return RootStatus.NO_SU;
        }
        return RootStatus.DENIED;
    }

    /** 通过 su 执行一条 shell 命令 */
    public static Result su(String cmd) {
        Result res = new Result();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // 必须先并发抽干 stdout / stderr，再 waitFor：
            // 顺序读的话，子进程一旦把另一条管道写满而阻塞，读当前这条永远等不到 EOF，
            // 于是 waitFor 永远不返回。su 还在等授权弹窗时尤其容易卡死。
            // （ShizukuUtil 是同一个坑，两边保持一致。）
            final Process proc = p;
            // StringBuffer：见 drain() 注释——读取线程与 waitFor 超时后的主读取方并发写/读
            final StringBuffer outB = new StringBuffer();
            final StringBuffer errB = new StringBuffer();
            Thread tOut = new Thread(() -> outB.append(drain(proc.getInputStream())));
            Thread tErr = new Thread(() -> errB.append(drain(proc.getErrorStream())));
            tOut.start();
            tErr.start();
            boolean timedOut = false;
            boolean nonZeroExit = false;
            if (proc.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                res.code = proc.exitValue();
                nonZeroExit = res.code != 0;
            } else {
                // 超时：先杀进程再收尾，绝不让 IO 线程卡在这里
                timedOut = true;
                res.code = CODE_TIMEOUT;
                res.reason = REASON_TIMEOUT;
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            tOut.join(2000);
            tErr.join(2000);
            res.out = outB.toString();
            res.err = errB.toString();
            // 退出码非 0：区分「su 没给权限」与「命令本身失败」。
            // 旧实现一律归 REASON_DENIED，导致 settings 写入被 ROM 拦截这类真实失败
            // 也对用户显示「root 授权被拒」，把排查方向带偏（与 ShizukuUtil 的
            // REASON_EXEC 分类对齐）。判据见 looksDenied()。
            if (nonZeroExit) {
                res.reason = looksDenied(res) ? REASON_DENIED : REASON_EXEC;
            }
            if (timedOut) {
                res.err = (res.err == null || res.err.isEmpty() ? "" : res.err + "\n")
                        + "su 执行超时(" + TIMEOUT_MS + "ms)";
            }
        } catch (Throwable t) {
            res.code = -1;
            res.err = String.valueOf(t);
            // exec 直接失败 = 系统里找不到 su（未 root / Magisk 对本站隐藏了 su），
            // 与「su 存在但被拒绝」是两回事，UI 要分别引导。
            res.reason = isSuMissing(t) ? REASON_NO_SU : REASON_EXEC;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        return res;
    }

    /** exec 抛错是否代表「没有 su」：只看异常文本，不额外 fork 进程去 which su */
    private static boolean isSuMissing(Throwable t) {
        if (t == null) {
            return false;
        }
        String m = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("no such file or directory") || m.contains("not found")
                || m.contains("permission denied") && m.contains("su");
    }

    /**
     * 非零退出是否像「授权被拒」：输出里带拒绝字样，或完全没有任何输出
     * （su 被 Magisk / 用户拒绝时典型表现：命令根本没跑，stderr 也是空的）。
     */
    private static boolean looksDenied(Result r) {
        String text = (String.valueOf(r.err) + "\n" + String.valueOf(r.out))
                .toLowerCase(java.util.Locale.ROOT);
        if (text.contains("denied") || text.contains("not permitted")
                || text.contains("permission denied") || text.contains("not allowed")) {
            return true;
        }
        return r.err.trim().isEmpty() && r.out.trim().isEmpty();
    }

    private static String drain(java.io.InputStream is) {
        // StringBuffer：drain 线程与 waitFor 超时后的读取方并发访问（join(2000) 超时后
        // 仍可能读到还在写的线程），StringBuilder 非线程安全，内部数组可能被写越界。
        StringBuffer sb = new StringBuffer();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
