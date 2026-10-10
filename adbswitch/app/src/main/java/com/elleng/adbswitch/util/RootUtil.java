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

    public static class Result {
        public int code;
        public String out = "";
        public String err = "";

        public boolean ok() {
            return code == 0;
        }
    }

    private RootUtil() {
    }

    /** 是否拥有 root（su 后能拿到 uid 0） */
    public static boolean hasRoot() {
        Result r = su("id -u");
        return r.out.trim().equals("0");
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
            final StringBuilder outB = new StringBuilder();
            final StringBuilder errB = new StringBuilder();
            Thread tOut = new Thread(() -> outB.append(drain(proc.getInputStream())));
            Thread tErr = new Thread(() -> errB.append(drain(proc.getErrorStream())));
            tOut.start();
            tErr.start();
            boolean timedOut = false;
            if (proc.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                res.code = proc.exitValue();
            } else {
                // 超时：先杀进程再收尾，绝不让 IO 线程卡在这里
                timedOut = true;
                res.code = CODE_TIMEOUT;
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            tOut.join(2000);
            tErr.join(2000);
            res.out = outB.toString();
            res.err = errB.toString();
            if (timedOut) {
                res.err = (res.err == null || res.err.isEmpty() ? "" : res.err + "\n")
                        + "su 执行超时(" + TIMEOUT_MS + "ms)";
            }
        } catch (Throwable t) {
            res.code = -1;
            res.err = String.valueOf(t);
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

    private static String drain(java.io.InputStream is) {
        StringBuilder sb = new StringBuilder();
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
