package com.elleng.devswitch.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 通过 su 执行 root 命令。本 App 不依赖 XPosed / 不 hook，
 * 只是用 root 权限改写 settings global adb_enabled 来真实开关 USB 调试。
 */
public final class RootUtil {

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
            res.out = drain(p.getInputStream());
            res.err = drain(p.getErrorStream());
            res.code = p.waitFor();
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
