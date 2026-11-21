package com.elleng.adbswitch;

/**
 * 开关项标识：把「主开关管理哪些内容」从固定的 usb/wireless/dev 三件套
 * 解耦为可配置的项集合（用户可决定每项是否显示、是否跟随主开关）。
 *
 * 每项两个属性（持久化见 {@link SwitchConfig}）：
 *  - shown（是否显示）= 该功能是否启用。隐藏 = 本 App 完全不管理它（不显示、不写入、不跟随）。
 *  - followMaster（是否跟随主开关）仅在 shown=true 时有意义。
 *
 * 合法状态只有三种：不显示 / 显示+跟随 / 显示+不跟随。
 *
 * 当前候选池四项；框架按枚举扩展，新项入池即获得配置/主开关/显示的全套支持。
 */
public enum SwitchItem {

    /** USB 调试（Settings.Global.adb_enabled） */
    USB("usb"),
    /** 无线调试（Settings.Global.adb_wifi_enabled，API 30+） */
    WIRELESS("wireless"),
    /** 开发者选项入口（Settings.Global.development_settings_enabled） */
    DEV("dev"),
    /** 第三方无障碍服务（Settings.Secure.enabled_accessibility_services） */
    A11Y("a11y");

    /** cfg 配置键后缀（item_shown_ / item_follow_ 前缀 + id） */
    public final String id;

    SwitchItem(String id) {
        this.id = id;
    }

    /** 该系统版本是否支持此项（无线调试键 API 30 才引入） */
    public boolean supported() {
        return this != WIRELESS || android.os.Build.VERSION.SDK_INT >= 30;
    }
}
