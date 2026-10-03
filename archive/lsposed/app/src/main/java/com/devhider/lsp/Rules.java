package com.devhider.lsp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 集中定义「开发者选项 / USB 调试 / 无线调试」相关的所有检测点伪造规则。
 * 这份表同时被 LSPosed 模块(Java 层) 与 Zygisk 模块(native 层) 使用，
 * 修改时请同步 zygisk/jni/rules.h。
 */
public final class Rules {

    // ------------------------------------------------------------------
    // Settings 键值伪造表 (Settings.Global / Secure / System 共用同一张表，
    // 因为不同 ROM / 不同 RASP 会到不同 namespace 里去查)
    // ------------------------------------------------------------------
    public static final Map<String, String> SETTINGS = new HashMap<>();

    // ------------------------------------------------------------------
    // 系统属性伪造表
    // ------------------------------------------------------------------
    public static final Map<String, String> PROPS = new HashMap<>();

    /** 这些属性的值是一个以逗号分隔的 USB 功能列表，需要把 "adb" 从列表里剔掉 */
    public static final Set<String> PROPS_STRIP_ADB = new HashSet<>();

    /** 这些属性里可能含有 userdebug / eng 字样，替换成 user */
    public static final Set<String> PROPS_STRIP_DEBUG_WORD = new HashSet<>();

    /** 打开 / access 这些路径一律伪装成「不存在」 */
    public static final String[] BLOCK_PATHS = {
            "/sys/class/android_usb",
            "/sys/kernel/config/usb_gadget",
            "/config/usb_gadget",
            "/sys/class/udc",
            "/dev/usb-ffs/adb",
            "/data/misc/adb",
            "/data/adb/adb_keys",
            "/data/misc/adb_keys",
            "/apex/com.android.adbd",
    };

    /** 无线调试 / adb over network 常见端口 */
    public static final int[] BLOCK_PORTS = {5555, 5556, 5554};

    /**
     * 模糊匹配：命中正则且不在精确表里的 key，按返回类型返回 0 / "" / false。
     * 用于覆盖各 ROM 私有 key 和 RASP 自己拼出来的 key。
     */
    private static final Pattern FUZZY = Pattern.compile(
            "(?i).*(adb|debug|developer|development|mock_location|show_touches|" +
            "pointer_location|gpu_debug|test_harness|userdebug).*");

    /** 模糊匹配也不应该误伤的 key（放行） */
    private static final Set<String> FUZZY_WHITELIST = new HashSet<>();

    static {
        // ============ 核心：开发者选项 总开关 ============
        SETTINGS.put("development_settings_enabled", "0");
        SETTINGS.put("developer_options_enabled", "0");
        SETTINGS.put("development_options_enabled", "0");
        SETTINGS.put("dev_settings_enabled", "0");
        SETTINGS.put("settings_development", "0");

        // ============ 核心：USB 调试 ============
        SETTINGS.put("adb_enabled", "0");
        SETTINGS.put("usb_debug", "0");
        SETTINGS.put("usb_debugging", "0");
        SETTINGS.put("usb_debugging_enabled", "0");
        SETTINGS.put("debug_usb", "0");
        SETTINGS.put("adb_notify", "0");
        SETTINGS.put("adb_allowed_connection_time", "0");
        SETTINGS.put("adb_always_allow", "0");
        SETTINGS.put("adb_wifi_connected_since", "0");

        // ============ 核心：无线调试 (Android 11+) ============
        SETTINGS.put("adb_wifi_enabled", "0");
        SETTINGS.put("adb_wifi_new_pairing_enabled", "0");
        SETTINGS.put("adb_wifi_pairing_code", "");
        SETTINGS.put("adb_wifi_pairing_service", "");
        SETTINGS.put("adb_wifi_port", "-1");
        SETTINGS.put("wireless_debugging_enabled", "0");
        SETTINGS.put("wireless_debug_enabled", "0");

        // ============ 选择调试应用 / 等待调试器 ============
        SETTINGS.put("debug_app", "");
        SETTINGS.put("wait_for_debugger", "0");

        // ============ 模拟位置 ============
        SETTINGS.put("mock_location", "0");
        SETTINGS.put("mock_location_app", "");
        SETTINGS.put("allow_mock_location", "0");

        // ============ 输入 / 绘图 可视化 ============
        SETTINGS.put("show_touches", "0");
        SETTINGS.put("pointer_location", "0");
        SETTINGS.put("show_hw_screen_updates", "0");
        SETTINGS.put("show_screen_updates", "0");
        SETTINGS.put("show_cpu_usage", "0");
        SETTINGS.put("show_hw_layers_updates", "0");
        SETTINGS.put("debug_layout", "0");
        SETTINGS.put("debug_view_attributes", "0");
        SETTINGS.put("force_msaa", "0");
        SETTINGS.put("disable_overlays", "0");
        SETTINGS.put("simulate_color_space", "0");

        // ============ 动画时长缩放（保持默认 1.0） ============
        SETTINGS.put("window_animation_scale", "1.0");
        SETTINGS.put("transition_animation_scale", "1.0");
        SETTINGS.put("animator_duration_scale", "1.0");

        // ============ GPU / 图形 ============
        SETTINGS.put("enable_gpu_debug_layers", "0");
        SETTINGS.put("gpu_debug_app", "");
        SETTINGS.put("gpu_debug_layers", "");
        SETTINGS.put("gpu_debug_layer_app", "");
        SETTINGS.put("enable_opengl_traces", "");
        SETTINGS.put("angle_debug_package", "");
        SETTINGS.put("angle_gl_driver_selection_pkgs", "");
        SETTINGS.put("angle_gl_driver_selection_values", "");

        // ============ 其它开发者选项条目 ============
        SETTINGS.put("bugreport_in_power_menu", "0");
        SETTINGS.put("hide_error_dialogs", "0");
        SETTINGS.put("stay_on_while_plugged_in", "0");
        SETTINGS.put("always_finish_activities", "0");
        SETTINGS.put("strict_mode", "0");
        SETTINGS.put("wifi_verbose_logging_enabled", "0");
        SETTINGS.put("wifi_display_certification_on", "0");
        SETTINGS.put("bluetooth_hci_log", "0");
        SETTINGS.put("bluetooth_show_devices_without_names", "0");
        SETTINGS.put("usb_audio_automatic_routing_disabled", "0");
        SETTINGS.put("force_allow_on_external", "0");
        SETTINGS.put("force_resizable_activities", "0");
        SETTINGS.put("enable_freeform_support", "0");
        SETTINGS.put("display_density_forced", "");
        SETTINGS.put("accessibility_display_daltonizer_enabled", "0");
        SETTINGS.put("accessibility_display_daltonizer", "-1");
        SETTINGS.put("oem_unlock_allowed", "0");
        SETTINGS.put("oem_unlock_enabled", "0");
        SETTINGS.put("hidden_api_policy", "0");
        SETTINGS.put("hidden_api_blacklist_exemptions", "");
        SETTINGS.put("select_usb_configuration", "");
        SETTINGS.put("usb_configuration", "");
        SETTINGS.put("app_process_limit", "-1");
        SETTINGS.put("background_process_limit", "-1");
        SETTINGS.put("verify_apps_over_usb", "1");
        SETTINGS.put("enable_16k_pages", "0");

        // ============ 放行白名单（避免误伤） ============
        FUZZY_WHITELIST.add("adb_install_need_wifi");
        FUZZY_WHITELIST.add("debug_wifi_allow_scan_with_traffic");

        // ============ 系统属性 ============
        PROPS.put("ro.debuggable", "0");
        PROPS.put("ro.secure", "1");
        PROPS.put("ro.build.type", "user");
        PROPS.put("ro.build.tags", "release-keys");
        PROPS.put("ro.adb.secure", "1");
        PROPS.put("init.svc.adbd", "stopped");
        PROPS.put("init.svc.adbd_root", "stopped");
        PROPS.put("init.svc_debug_pid.adbd", "0");
        PROPS.put("service.adb.tcp.port", "-1");
        PROPS.put("persist.adb.tcp.port", "-1");
        PROPS.put("persist.adb.wifi.enabled", "0");
        PROPS.put("ro.boot.debug", "0");
        PROPS.put("ro.debugable", "0");
        PROPS.put("ro.boot.adb", "0");
        PROPS.put("persist.sys.adb.engineering", "0");
        PROPS.put("sys.usb.configfs", "0");
        PROPS.put("sys.usb.ffs.aio_compat", "0");
        PROPS.put("vendor.usb.adb.enabled", "0");
        PROPS.put("persist.sys.usb.adb.enabled", "0");
        PROPS.put("ro.usb.adb", "0");
        PROPS.put("persist.sys.usb.qmmi.config", "none");
        PROPS.put("persist.vendor.usb.config", "none");
        PROPS.put("ro.bootmode", "normal");
        PROPS.put("ro.boot.mode", "normal");

        PROPS_STRIP_ADB.add("sys.usb.state");
        PROPS_STRIP_ADB.add("sys.usb.config");
        PROPS_STRIP_ADB.add("persist.sys.usb.config");
        PROPS_STRIP_ADB.add("vendor.sys.usb.config");
        PROPS_STRIP_ADB.add("sys.usb.configfs.mode");
        PROPS_STRIP_ADB.add("ro.sys.usb.config");
        PROPS_STRIP_ADB.add("persist.sys.usb.qmmi.config");

        PROPS_STRIP_DEBUG_WORD.add("ro.build.fingerprint");
        PROPS_STRIP_DEBUG_WORD.add("ro.build.display.id");
        PROPS_STRIP_DEBUG_WORD.add("ro.vendor.build.fingerprint");
        PROPS_STRIP_DEBUG_WORD.add("ro.product.build.fingerprint");
        PROPS_STRIP_DEBUG_WORD.add("ro.system.build.fingerprint");
        PROPS_STRIP_DEBUG_WORD.add("ro.odm.build.fingerprint");
        PROPS_STRIP_DEBUG_WORD.add("ro.bootimage.build.fingerprint");
        PROPS_STRIP_DEBUG_WORD.add("ro.build.description");
        PROPS_STRIP_DEBUG_WORD.add("ro.build.flavor");
        PROPS_STRIP_DEBUG_WORD.add("ro.system.build.type");
        PROPS_STRIP_DEBUG_WORD.add("ro.vendor.build.type");
        PROPS_STRIP_DEBUG_WORD.add("ro.product.build.type");
    }

    private Rules() {
    }

    /** 返回 settings key 的伪造值；null 表示不处理 */
    public static String settingsFake(String key, boolean fuzzy) {
        if (key == null) return null;
        String v = SETTINGS.get(key);
        if (v != null) return v;
        if (fuzzy && !FUZZY_WHITELIST.contains(key)) {
            String lower = key.toLowerCase();
            if (lower.contains("adb") || lower.contains("debug")
                    || lower.contains("developer") || lower.contains("development")
                    || lower.contains("mock_location") || lower.contains("show_touches")
                    || lower.contains("pointer_location") || lower.contains("userdebug")) {
                return "0";
            }
        }
        return null;
    }

    /** 返回属性的伪造值；null 表示保持原值 */
    public static String propFake(String key, String real, boolean fuzzy) {
        if (key == null) return null;
        String v = PROPS.get(key);
        if (v != null) return v;
        if (PROPS_STRIP_ADB.contains(key)) return stripToken(real, "adb");
        if (PROPS_STRIP_DEBUG_WORD.contains(key)) return stripDebugWord(real);
        if (fuzzy) {
            String lower = key.toLowerCase();
            if (lower.contains("adb") && (lower.startsWith("sys.") || lower.startsWith("persist.")
                    || lower.startsWith("init.svc") || lower.startsWith("ro."))) {
                return "0";
            }
            if (lower.contains("userdebug") || lower.contains(".debug")) {
                return stripDebugWord(real);
            }
        }
        return null;
    }

    public static String stripToken(String value, String token) {
        if (value == null) return null;
        String[] parts = value.split(",");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty() || t.equals(token)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        String out = sb.toString();
        return out.isEmpty() ? "none" : out;
    }

    public static String stripDebugWord(String value) {
        if (value == null) return null;
        String out = value.replace("userdebug", "user")
                .replace("eng", "user")
                .replace("test-keys", "release-keys");
        return out;
    }

    public static boolean blockPath(String path) {
        if (path == null) return false;
        for (String p : BLOCK_PATHS) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    public static boolean blockPort(int port) {
        for (int p : BLOCK_PORTS) {
            if (p == port) return true;
        }
        return false;
    }
}
