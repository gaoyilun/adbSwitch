package com.elleng.adbswitch;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 桌面 shortcut 开关类条目的无界面中转页（参考 NoAutoBTConnect 的做法）：
 * 静态 shortcut 从机制上只能指向 Activity（ShortcutService 不接受 Receiver/Service intent），
 * 故借 Theme.NoDisplay + onCreate 里立即 finish() 伪装成「后台完成」，不出现任何界面。
 *
 * 按 intent action 分发：TOGGLE_MASTER 走主开关语义；TOGGLE_USB / TOGGLE_WIRELESS
 * 单独拨对应键（与主界面子开关同一入口 DebugController）。主/子灯的联动刷新不需要
 * 额外判断——回调里现读真值重新发布（ToggleShortcuts.publish）。
 *
 * 写入是异步 su，回调到达时 Activity 早已结束，因此一切用 application context；
 * 常驻通知走 syncCloseNotification：此刻若还能起前台服务就用服务，
 * 被后台限制拒绝则自动降级为直发通知（Live Update 提升同样生效）。
 */
public class ShortcutToggleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context app = getApplicationContext();
        final String action = getIntent() == null ? null : getIntent().getAction();
        // NoDisplay 硬约束：必须在 onResume 之前 finish()，否则系统抛异常
        finish();

        if (ToggleShortcuts.ACTION_TOGGLE_USB.equals(action)
                || ToggleShortcuts.ACTION_TOGGLE_WIRELESS.equals(action)) {
            final boolean wireless = ToggleShortcuts.ACTION_TOGGLE_WIRELESS.equals(action);
            // 先读真值再翻转（toggleUsb/toggleWireless 内部处理），不依赖屏幕缓存
            DebugController.ResultCallback cb = (ok, s) -> {
                if (!ok) {
                    Toast.makeText(app, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
                    return;
                }
                ToggleShortcuts.markDirty(app);
                boolean on = wireless ? s.wireless : s.usb;
                Toast.makeText(app,
                        app.getString(wireless ? R.string.wireless_title : R.string.usb_title)
                                + " " + app.getString(on ? R.string.usb_on : R.string.usb_off),
                        Toast.LENGTH_SHORT).show();
            };
            if (wireless) {
                DebugController.toggleWireless(app, cb);
            } else {
                DebugController.toggleUsb(app, cb);
            }
            return;
        }

        // 一键开关：先读真值再翻转（toggleMaster 内部处理）
        DebugController.toggleMaster(app, (ok, s) -> {
            if (!ok) {
                Toast.makeText(app, R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
                return;
            }
            if (s.anyOn()) {
                AdbToggleService.stop(app);
            } else {
                AdbToggleService.syncCloseNotification(app);
            }
            ToggleShortcuts.markDirty(app);
            Toast.makeText(app, s.anyOn() ? R.string.master_on : R.string.master_off,
                    Toast.LENGTH_SHORT).show();
        });
    }
}
