package com.elleng.adbswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 处理通知栏按钮动作。
 *
 * 一律走 {@link DebugController#setMaster}（su 写入在后台线程，回调回主线程）：
 *  - 恢复打开：还原记忆的组合（USB / 无线各自回到关闭前的状态）并撤掉常驻通知
 *  - 临时关闭：记住当前组合，把两个都关掉，并按「临时关闭时弹通知」开关决定常驻通知
 *  - 取消置顶：仅撤掉常驻通知（Live Update 的 Unpin），不改任何开关真值
 *
 * 写入是异步的，用 goAsync() 挂住广播结果，防止 su 还没跑完进程就先被回收。
 */
public class NotificationReceiver extends BroadcastReceiver {

    /** goAsync 只能把广播时限撑到约 10s；回调里 finish 必须在此之前，超时兜底强制结束防 ANR */
    private static final long FINISH_TIMEOUT_MS = 8000;
    // 用静态 Looper 建 Handler：实例字段初始化里拿 this/getMainLooper 在早期阶段会踩坑
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        // 兜底：su 挂起 / IO 队列拥堵时回调迟迟不来，到点强制 finish。
        // 回调正常到达时先取消兜底再 finish；重复 finish 容忍（见 finishQuietly）。
        final Runnable forceFinish = () -> finishQuietly(pr);
        MAIN.postDelayed(forceFinish, FINISH_TIMEOUT_MS);
        String action = intent.getAction();
        if (AdbToggleService.ACTION_OPEN.equals(action)) {
            DebugController.setMaster(ctx, true, (ok, s) -> {
                // 写入失败必须让用户知道：只改通知会留下「点了没反应」的错觉
                if (!ok) {
                    Toast.makeText(ctx, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
                } else {
                    AdbToggleService.stop(ctx);
                }
                ToggleShortcuts.publish(ctx);
                MAIN.removeCallbacks(forceFinish);
                finishQuietly(pr);
            });
        } else if (AdbToggleService.ACTION_CLOSE.equals(action)) {
            DebugController.setMaster(ctx, false, (ok, s) -> {
                if (!ok) {
                    Toast.makeText(ctx, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
                } else {
                    AdbToggleService.syncCloseNotification(ctx);
                }
                ToggleShortcuts.publish(ctx);
                MAIN.removeCallbacks(forceFinish);
                finishQuietly(pr);
            });
        } else if (AdbToggleService.ACTION_DISMISS.equals(action)) {
            // 只取消置顶/常驻，不动开关真值
            AdbToggleService.stop(ctx);
            MAIN.removeCallbacks(forceFinish);
            finishQuietly(pr);
        } else {
            MAIN.removeCallbacks(forceFinish);
            finishQuietly(pr);
        }
    }

    private static void finishQuietly(PendingResult pr) {
        try {
            pr.finish();
        } catch (Throwable ignored) {
            // 重复 finish / 已超时被系统回收：都不值得再折腾
        }
    }
}
