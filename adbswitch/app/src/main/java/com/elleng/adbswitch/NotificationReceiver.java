package com.elleng.adbswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 处理通知栏按钮动作。
 *
 * 一律走 {@link DebugController#setMaster}（写入在后台 IO 执行器，回调回主线程）：
 *  - 恢复打开：还原记忆的组合（USB / 无线各自回到关闭前的状态）并撤掉常驻通知
 *  - 临时关闭：记住当前组合，把两个都关掉，并按「临时关闭时弹通知」开关决定常驻通知
 *
 * 广播立即结束、不用 goAsync 挂住：写入本就不依赖广播存活，「写 + Toast + 刷新快捷方式」
 * 全留在回调里。旧实现 goAsync + 8s 兜底短于通道最坏耗时（10~12s），su 挂起时兜底先跑、
 * 失败提示反而被掐掉——正是最需要反馈的时刻用户零反馈。进程保活由常驻通知的前台服务承担
 * （通知按钮存在即服务在跑）。
 */
public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        String action = intent.getAction();
        if (AdbToggleService.ACTION_OPEN.equals(action)) {
            DebugController.setMaster(ctx, true, (ok, s, reason) -> {
                // 写入失败必须让用户知道：只改通知会留下「点了没反应」的错觉
                if (!ok) {
                    Toast.makeText(ctx, DebugController.failureMessage(ctx, reason),
                            Toast.LENGTH_SHORT).show();
                } else {
                    AdbToggleService.stop(ctx);
                }
                ToggleShortcuts.markDirty(ctx);
            });
        } else if (AdbToggleService.ACTION_CLOSE.equals(action)) {
            DebugController.setMaster(ctx, false, (ok, s, reason) -> {
                if (!ok) {
                    Toast.makeText(ctx, DebugController.failureMessage(ctx, reason),
                            Toast.LENGTH_SHORT).show();
                } else {
                    AdbToggleService.syncCloseNotification(ctx);
                }
                ToggleShortcuts.markDirty(ctx);
            });
        }
    }
}