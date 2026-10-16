package com.elleng.adbswitch;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.elleng.adbswitch.util.RootUtil;

import java.io.FileInputStream;
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
 *
 * ⚠️ 官方已预告移除本通道（Shizuku-API 13.1.1 changelog 原文）：
 *   "Prepare to remove `Shizuku#newProcess`, developers should have to use UserService instead"
 * 即 Shizuku 官方建议改用 UserService（在 root/shell 进程里跑自己的代码），
 * newProcess 属于「文本通信、无 tty、不可靠」的旧通道。迁移需新增 AIDL + UserService 类，
 * 且 UserService 进程里 Context#getContentResolver 不可用（官方明示），仍只能 exec settings。
 * 目前 newProcess 仍可用，故保留；动手迁移前请先确认 Shizuku 当前版本是否仍提供它。
 */
public final class ShizukuUtil {

    /**
     * 无 binder 时的就绪等待上限。
     *
     * 为什么需要：ShizukuProvider 取 binder 是**异步**的（ContentProvider 握手），而磁贴
     * （AdbTileService）、桌面快捷方式（ShortcutToggleActivity）这类入口不经过 Activity，
     * 冷启动时可能 binder 还没到就直接 newProcess，白失败一次。
     * 等待必须有界：DebugController 的 IO 是单线程执行器，只有这一条通道，
     * 卡住一次后面所有操作都排队失效（这条硬规则同 RootUtil.TIMEOUT_MS）。
     * 2s 足够覆盖 provider 的握手，不会让「压根没装 Shizuku」的用户等太久。
     */
    public static final long READY_WAIT_MS = 2000;

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

    /**
     * 等到 Shizuku 的 binder 到达（或超时）。**只能在后台线程调用**——它会阻塞当前线程，
     * 主线程调用等于 ANR。已就绪时立即返回，不做任何等待。
     *
     * 用 sticky 监听：binder 已经在了会立刻回调一次，不会漏掉「等待开始前就就绪」的情况。
     *
     * @return 等待结束时 binder 是否可用
     */
    public static boolean awaitBinder(long timeoutMs) {
        if (running()) {
            return true;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Shizuku.OnBinderReceivedListener l = new Shizuku.OnBinderReceivedListener() {
            @Override
            public void onBinderReceived() {
                latch.countDown();
            }
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(l);
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            android.util.Log.w("AdbSwitch", "await Shizuku binder failed", t);
        } finally {
            try {
                Shizuku.removeBinderReceivedListener(l);
            } catch (Throwable ignored) {
                // 移除失败不影响结果：监听表里残留一个一次性 lambda 的代价可接受，
                // 但正常路径必须移除，否则每次执行都泄漏一个监听（静态表持有引用）。
            }
        }
        return running();
    }

    /** 以 shell 身份执行一条命令，返回与 RootUtil.su 同构的结果 */
    public static RootUtil.Result run(String cmd) {
        RootUtil.Result res = new RootUtil.Result();
        IRemoteProcess proc = null;
        try {
            // 前置校验：把「服务没启动」「没授权」与「命令执行失败」分开。
            // 旧实现三者都落进同一个 catch，UI 只能给一句笼统的「执行失败」，
            // 用户无从判断该去启动 Shizuku、还是去管理器里授权、还是换 root 模式。
            if (!running()) {
                awaitBinder(READY_WAIT_MS);
                if (!running()) {
                    res.code = -1;
                    res.reason = RootUtil.REASON_NO_BINDER;
                    res.err = "shizuku 服务未启动";
                    return res;
                }
            }
            if (!permissionGranted()) {
                res.code = -1;
                res.reason = RootUtil.REASON_NO_PERMISSION;
                res.err = "shizuku 未授权本应用";
                return res;
            }
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
            // StringBuffer：tOut.join(2000) 超时后读取方与 drain 线程仍可能并发访问，
            // StringBuilder 非线程安全（见 RootUtil.su 同款修复）。
            final StringBuffer outB = new StringBuffer();
            final StringBuffer errB = new StringBuffer();
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
            // 与 RootUtil 同一条硬规则：等待必须有上限。
            // DebugController 的 IO 是单线程执行器，一次挂起 = 之后所有开关操作永久排队失效。
            // Shizuku 的 IRemoteProcess 只暴露了无参 waitFor()，故把它挪到独立线程再 join 限时；
            // 超时则 destroy 子进程（让它带着退出码返回），IO 线程继续往下走。
            final int[] code = {RootUtil.CODE_TIMEOUT};
            Thread tWait = new Thread(() -> {
                try {
                    code[0] = p.waitFor();
                } catch (Throwable ignored) {
                }
            });
            tWait.start();
            tWait.join(RootUtil.TIMEOUT_MS);
            boolean timedOut = tWait.isAlive();
            if (timedOut) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                try {
                    tWait.join(500);
                } catch (Throwable ignored) {
                }
            }
            res.code = code[0];
            tOut.join(2000);
            tErr.join(2000);
            res.out = outB.toString();
            res.err = errB.toString();
            if (timedOut) {
                res.reason = RootUtil.REASON_TIMEOUT;
                res.err = (res.err == null || res.err.isEmpty() ? "" : res.err + "\n")
                        + "shizuku 执行超时(" + RootUtil.TIMEOUT_MS + "ms)";
            } else if (res.code != 0) {
                res.reason = RootUtil.REASON_EXEC;
            }
        } catch (Throwable t) {
            res.code = -1;
            res.err = String.valueOf(t);
            res.reason = RootUtil.REASON_EXEC;
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

    /**
     * 抽干一条进程输出流并关闭它。
     *
     * 两个坑都在这个方法里修过：
     *  1. ParcelFileDescriptor 本身必须关闭 —— 旧实现只关了内层 FileInputStream，
     *     每次 run() 泄漏一个 Linux fd，长期使用后 Too many open files。
     *  2. 不能按 1024 字节分块 new String(buf, 0, n, UTF_8)：多字节序列（中文/emoji）
     *     跨块边界会被解码成 U+FFFD 乱码。先收完整字节再一次性解码。
     */
    private static String drain(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return "";
        }
        // 先读到底再关 pfd：关闭动作在 try-with-resources 退出时发生，
        // 此时流已 EOF，不会截断读取。
        try (ParcelFileDescriptor p = pfd;
             FileInputStream fis = new FileInputStream(p.getFileDescriptor())) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            // 含「重复关闭」等无害异常：读到哪儿算哪儿，绝不让读取失败炸掉执行器
            return "";
        }
    }
}
