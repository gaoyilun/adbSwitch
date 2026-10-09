package com.elleng.adbswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 通知控制中心（下拉快捷磁贴）：点击即一键开关调试（主开关语义），
 * 关 = 记住组合并把 USB / 无线都关掉，开 = 还原记忆的组合。
 * 状态一律从 {@link DebugController} 读，不自己判断无线调试。
 */
public class AdbTileService extends TileService {

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (DebugController.ACTION_STATE.equals(i.getAction())) {
                updateTile(new DebugController.State(
                        i.getBooleanExtra(DebugController.EXTRA_USB, false),
                        i.getBooleanExtra(DebugController.EXTRA_WIRELESS, false)));
            }
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        IntentFilter f = new IntentFilter(DebugController.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        updateTile(DebugController.read(this));
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 点击磁贴：主开关语义。写入走 DebugController 后台线程（su 不能卡控制面板主线程），
     * 完成后在回调里刷新磁贴；常驻通知按「临时关闭时弹通知」开关决定，与主界面一致。
     */
    @Override
    public void onClick() {
        super.onClick();
        // 先经授权通道读真值、再相对真值翻转（DebugController.toggleMaster 内部处理），
        // 不依赖屏幕显示的缓存（刚展开面板那瞬间缓存可能还没校准）。
        DebugController.toggleMaster(this, (ok, s) -> {
            if (ok) {
                if (s.anyOn()) {
                    AdbToggleService.stop(this);
                } else {
                    AdbToggleService.syncCloseNotification(this);
                }
            }
            updateTile(s);
        });
    }

    private void updateTile(DebugController.State s) {
        // 磁贴刷新时顺带同步长按快捷方式的状态灯（下拉面板展开即读真值，灯能保持较新）
        ToggleShortcuts.publish(this);
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        t.setState(s.anyOn() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(s.allOff() ? R.string.tile_off
                : (s.usb && s.wireless ? R.string.tile_all_on : R.string.tile_partial)));
        t.updateTile();
    }
}
