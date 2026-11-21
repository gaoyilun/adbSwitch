package com.elleng.adbswitch.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 网络状态读取：给「无线调试」行判定「未激活」用。
 *
 * 背景：无线调试开关的 key（adb_wifi_enabled）在关 Wi-Fi 后仍为 1，
 * 底层 prop（persist.adb.tls_server.enable）也仍为 1，两者都判断不出
 * 「此刻能否无线调试」。唯一准确的信号是「设备当前是否连着 Wi-Fi」——
 * 这是标准 ConnectivityManager / NetworkCapabilities 能力，普通权限，
 * 与 root / Shizuku / WSS 三种授权模式无关，统一生效。
 *
 * 只作显示辅助，不参与任何开关判定 / 写入 / 回读确认。
 */
public final class NetworkUtil {
    private NetworkUtil() {}

    /**
     * 当前默认网络是否为 Wi-Fi（TRANSPORT_WIFI）。
     * 读不到（无网络 / 权限异常 / 老设备）一律返回 false → 显示「未激活」，
     * 这与「没连 Wi-Fi 时无线调试本就不可用」的事实一致，不会误报为已开启。
     */
    public static boolean isWifiConnected(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network n = cm.getActiveNetwork();
            if (n == null) {
                return false;
            }
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            return false;
        }
    }
}
