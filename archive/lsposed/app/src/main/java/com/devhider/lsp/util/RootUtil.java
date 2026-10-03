package com.devhider.lsp.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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

    /**
     * 把一段文本写到 /data/adb 下面：先落在 app 私有目录，再用 su 复制过去并放开权限，
     * 这样 Zygisk 模块在 preAppSpecialize(仍有 root 权限) 时才能读到。
     */
    public static boolean pushText(String content, String destPath, File stagingDir) {
        try {
            File tmp = new File(stagingDir, "staging.txt");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            String dir = destPath.substring(0, destPath.lastIndexOf('/'));
            String cmd = "mkdir -p " + dir + "; "
                    + "cat '" + tmp.getAbsolutePath() + "' > '" + destPath + "'; "
                    + "chmod 0755 " + dir + "; "
                    + "chmod 0644 '" + destPath + "'";
            Result r = su(cmd);
            return r.ok();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String readText(String path) {
        Result r = su("cat '" + path + "' 2>/dev/null");
        return r.out;
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
