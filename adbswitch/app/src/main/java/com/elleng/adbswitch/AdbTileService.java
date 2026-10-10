package com.elleng.adbswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 通知控制中心（下拉快捷磁贴）：点击即一键开关调试（主开关语义），
 * 关 = 记住组合并把 USB / 无线都关掉，开 = 还原记忆的组合。
 * 状态一律从 {@link DebugController} 读，不自己判断无线调试。
 */
public class AdbTileService extends TileService {

    /** 状态变化 listener（替代原进程内广播）：磁贴随真值刷新 */
    private final DebugController.StateListener stateListener = s -> updateTile(s);

    @Override
    public void onStartListening() {
        super.onStartListening();
        // 状态变化改走进程内 listener 注册表（App 单进程，广播纯开销）
        DebugController.addStateListener(stateListener);
        updateTile(DebugController.read(this));
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        try {
            DebugController.removeStateListener(stateListener);
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
        DebugController.toggleMaster(this, (ok, s, reason) -> {
            if (ok) {
                if (s.anyOn()) {
                    AdbToggleService.stop(this);
                } else {
                    AdbToggleService.syncCloseNotification(this);
                }
            } else {
                // 读不到真值 / 写失败：明确提示，别让磁贴「点了没反应」。
                // 文案按原因细分（Shizuku 没启动 / 没授权 / root 没授权 / 超时…），
                // 用户才知道下一步该做什么，而不是只看到一句笼统的「执行失败」。
                Toast.makeText(this, DebugController.failureMessage(this, reason),
                        Toast.LENGTH_SHORT).show();
            }
            updateTile(s);
        });
    }

    private void updateTile(DebugController.State s) {
        // 磁贴刷新时顺带同步长按快捷方式的状态灯（下拉面板展开即读真值，灯能保持较新）
        ToggleShortcuts.markDirty(this);
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
