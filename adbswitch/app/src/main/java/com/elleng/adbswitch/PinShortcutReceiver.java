package com.elleng.adbswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面快捷方式钉选结果回调（{@code ShortcutManager.requestPinShortcut} 的 resultIntent）。
 *
 * 官方契约（见 ShortcutManager#requestPinShortcut 文档）：
 *  - 只有快捷方式真正钉到桌面时才会回调 —— 这就是「真实成功状态」；
 *  - 用户在确认框点取消、或 ROM 直接吞掉请求（如未授予「创建桌面快捷方式」权限）都不回调。
 *
 * 故失败/取消只能靠超时兜底：{@link #arm} 按请求登记一条限时等待，超时没等到回调就提示未创建
 * （用户取消与被 ROM 吞掉无法区分，统一提示「请确认是否授权」）。
 * 按请求（而非全局单例）登记，5 秒内连建多个快捷方式时各自的成败互不吞并：
 * 每次成功回调消费最早一条待定请求，剩下的各自按自己的超时判定。
 *
 * 注册在 manifest（exported=false）+ 显式 Intent 指向本类：不受隐式广播限制，
 * 进程被杀后回调到来也能拉起本类弹提示。
 */
public class PinShortcutReceiver extends BroadcastReceiver {

    /** 等回调的兜底时限：钉选确认框通常秒级响应，超时即视为没钉上 */
    private static final long TIMEOUT_MS = 5000;

    // 用静态 Looper 建 Handler：实例字段初始化里拿 this/getMainLooper 在早期阶段会踩坑
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 待定请求表：token -> 超时任务（LinkedHashMap 保序，成功回调消费最早一条） */
    private static final Map<Integer, Runnable> PENDING = new LinkedHashMap<>();
    private static int seq = 0;
    private static Context appCtx;

    /** 发起钉选请求后调用：按请求登记等待回调，并挂各自的超时兜底。 */
    static synchronized void arm(Context ctx) {
        appCtx = ctx.getApplicationContext();
        final int token = ++seq;
        Runnable timeout = new Runnable() {
            @Override
            public void run() {
                synchronized (PinShortcutReceiver.class) {
                    if (PENDING.remove(token) == null) {
                        return; // 已被成功回调消费
                    }
                }
                Context app = appCtx;
                if (app != null) {
                    Toast.makeText(app, R.string.create_shortcut_fail, Toast.LENGTH_LONG).show();
                }
            }
        };
        PENDING.put(token, timeout);
        MAIN.postDelayed(timeout, TIMEOUT_MS);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 能走到这里 = 桌面已确认钉上（官方契约：只有成功才回调）：
        // 消费最早一条待定请求（回调不带请求标识，按 FIFO 对应）
        Runnable consumed = null;
        synchronized (PinShortcutReceiver.class) {
            Iterator<Map.Entry<Integer, Runnable>> it = PENDING.entrySet().iterator();
            if (it.hasNext()) {
                consumed = it.next().getValue();
                it.remove();
            }
        }
        if (consumed != null) {
            MAIN.removeCallbacks(consumed);
        }
        Toast.makeText(ctx, R.string.create_shortcut_ok, Toast.LENGTH_SHORT).show();
    }
}
