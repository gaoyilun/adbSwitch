package com.elleng.usbdebug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 处理通知栏按钮动作。
 *
 * 一律走 {@link DebugController#setMaster}：
 *  - 恢复打开：还原记忆的组合（USB / 无线各自回到关闭前的状态）并撤掉常驻通知
 *  - 临时关闭：记住当前组合，把两个都关掉
 *  - 取消置顶：仅撤掉常驻通知（Live Update 的 Unpin），不改任何开关真值
 */
public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (UsbToggleService.ACTION_OPEN.equals(action)) {
            DebugController.setMaster(ctx, true);
            UsbToggleService.stop(ctx);
        } else if (UsbToggleService.ACTION_CLOSE.equals(action)) {
            DebugController.setMaster(ctx, false);
            UsbToggleService.start(ctx);
        } else if (UsbToggleService.ACTION_DISMISS.equals(action)) {
            // 只取消置顶/常驻，不动开关真值
            UsbToggleService.stop(ctx);
        }
    }
}
