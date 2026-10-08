package com.elleng.devswitch;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.elleng.devswitch.util.RootUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 后端：经 Shizuku 以 shell(uid 2000) 身份执行命令，等价于 adb shell，
 * 因此 settings / am 这类 shell 可写操作都能做，无需 root。
 *
 * 与 RootUtil 不同，这里走 Shizuku 服务器暴露的 IShizukuService.newProcess：
 * 由 Shizuku 进程（已获 shell 权限）代 fork 子进程执行命令，再把输出流与退出码回传。
 * 返回结构与 RootUtil.su 完全一致，便于 DebugController 直接复用。
 */
public final class ShizukuUtil {

    private ShizukuUtil() {
    }

    /** Shizuku 服务是否在跑（不关心本应用是否已获授权）——用于区分「没启动」与「没授权」两种未授权 */
    public static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本应用是否已被 Shizuku 授权 */
    public static boolean permissionGranted() {
        try {
            return Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 是否已就绪且本应用已被授权 */
    public static boolean available() {
        return running() && permissionGranted();
    }

    /** 以 shell 身份执行一条命令，返回与 RootUtil.su 同构的结果 */
    public static RootUtil.Result run(String cmd) {
        RootUtil.Result res = new RootUtil.Result();
        IRemoteProcess proc = null;
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                res.code = -1;
                res.err = "shizuku binder 为空";
                return res;
            }
            IShizukuService svc = IShizukuService.Stub.asInterface(binder);
            if (svc == null) {
                res.code = -1;
                res.err = "shizuku 服务为空";
                return res;
            }
            proc = svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            // 先并发读流、再等待退出（与 RootUtil.su 的 drain-then-wait 顺序一致）：
            // 若先 waitFor，命令输出一旦撑满管道缓冲，子进程会阻塞在写、waitFor 永不返回，
            // 而 DebugController 的 IO 是单线程执行器 —— 卡死一次，之后所有开关操作全部排队失效。
            final IRemoteProcess p = proc;
            final StringBuilder outB = new StringBuilder();
            final StringBuilder errB = new StringBuilder();
            Thread tOut = new Thread(() -> {
                try {
                    outB.append(drain(p.getInputStream()));
                } catch (Throwable ignored) {
                }
            });
            Thread tErr = new Thread(() -> {
                try {
                    errB.append(drain(p.getErrorStream()));
                } catch (Throwable ignored) {
                }
            });
            tOut.start();
            tErr.start();
            res.code = proc.waitFor();
            tOut.join(2000);
            tErr.join(2000);
            res.out = outB.toString();
            res.err = errB.toString();
        } catch (Throwable t) {
            res.code = -1;
            res.err = String.valueOf(t);
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        return res;
    }

    private static String drain(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return "";
        }
        try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[1024];
            int n;
            StringBuilder sb = new StringBuilder();
            while ((n = fis.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
