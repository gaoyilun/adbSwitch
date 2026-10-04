package com.elleng.usbdebug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        String action = intent.getAction();
        if (UsbToggleService.ACTION_OPEN.equals(action)) {
            DebugController.setMaster(ctx, true, (ok, s) -> {
                UsbToggleService.stop(ctx);
                pr.finish();
            });
        } else if (UsbToggleService.ACTION_CLOSE.equals(action)) {
            DebugController.setMaster(ctx, false, (ok, s) -> {
                UsbToggleService.syncCloseNotification(ctx);
                pr.finish();
            });
        } else if (UsbToggleService.ACTION_DISMISS.equals(action)) {
            // 只取消置顶/常驻，不动开关真值
            UsbToggleService.stop(ctx);
            pr.finish();
        } else {
            pr.finish();
        }
    }
}
