#pragma once

/*
 * 伪造规则表（与 LSPosed 侧的 com.devhider.lsp.Rules 保持一致）。
 *
 * kind:
 *   0 = 直接替换成 value
 *   1 = 从逗号列表里剔除 "adb"
 *   2 = 把 userdebug / eng / test-keys 替换成 user / release-keys
 */

struct PropRule {
    const char *name;
    const char *value;
    int kind;
};

static const PropRule kPropRules[] = {
        // ---- 编译类型 / 调试能力 ----
        {"ro.debuggable",             "0",            0},
        {"ro.secure",                 "1",            0},
        {"ro.build.type",             "user",         0},
        {"ro.build.tags",             "release-keys", 0},
        {"ro.adb.secure",             "1",            0},
        {"ro.debugable",              "0",            0},
        {"ro.boot.debug",             "0",            0},
        {"ro.boot.adb",               "0",            0},
        {"ro.bootmode",               "normal",       0},
        {"ro.boot.mode",              "normal",       0},
        {"ro.usb.adb",                "0",            0},

        // ---- adbd 运行状态 ----
        {"init.svc.adbd",             "stopped",      0},
        {"init.svc.adbd_root",        "stopped",      0},
        {"init.svc_debug_pid.adbd",   "0",            0},
        {"service.adb.tcp.port",      "-1",           0},
        {"persist.adb.tcp.port",      "-1",           0},
        {"persist.adb.wifi.enabled",  "0",            0},
        {"persist.sys.adb.engineering", "0",          0},
        {"vendor.usb.adb.enabled",    "0",            0},
        {"persist.sys.usb.adb.enabled", "0",          0},
        {"sys.usb.configfs",          "0",            0},
        {"sys.usb.ffs.aio_compat",    "0",            0},

        // ---- USB 功能列表：把 adb 剔掉 ----
        {"sys.usb.state",             nullptr,        1},
        {"sys.usb.config",            nullptr,        1},
        {"persist.sys.usb.config",    nullptr,        1},
        {"vendor.sys.usb.config",     nullptr,        1},
        {"sys.usb.configfs.mode",     nullptr,        1},
        {"ro.sys.usb.config",         nullptr,        1},
        {"persist.sys.usb.qmmi.config", nullptr,      1},
        {"persist.vendor.usb.config", nullptr,        1},

        // ---- 指纹里的 userdebug / eng ----
        {"ro.build.fingerprint",            nullptr,  2},
        {"ro.build.display.id",             nullptr,  2},
        {"ro.vendor.build.fingerprint",     nullptr,  2},
        {"ro.product.build.fingerprint",    nullptr,  2},
        {"ro.system.build.fingerprint",     nullptr,  2},
        {"ro.odm.build.fingerprint",        nullptr,  2},
        {"ro.bootimage.build.fingerprint",  nullptr,  2},
        {"ro.build.description",            nullptr,  2},
        {"ro.build.flavor",                 nullptr,  2},
        {"ro.system.build.type",            nullptr,  2},
        {"ro.vendor.build.type",            nullptr,  2},
        {"ro.product.build.type",           nullptr,  2},
};

static const int kPropRuleCount = (int) (sizeof(kPropRules) / sizeof(kPropRules[0]));

/* 打开 / access 这些路径一律伪装成不存在 */
static const char *const kBlockPaths[] = {
        "/sys/class/android_usb",
        "/sys/devices/virtual/android_usb",
        "/sys/kernel/config/usb_gadget",
        "/config/usb_gadget",
        "/sys/class/udc",
        "/dev/usb-ffs/adb",
        "/dev/usb-ffs",
        "/data/misc/adb",
        "/data/adb/adb_keys",
        "/data/misc/adb_keys",
        "/apex/com.android.adbd",
        "/system/bin/adb",
        "/system/xbin/adb",
        "/sbin/adb",
        "/system/bin/start-adbd",
};

static const int kBlockPathCount = (int) (sizeof(kBlockPaths) / sizeof(kBlockPaths[0]));

/* 无线调试 / adb 网络调试常见端口 */
static const int kBlockPorts[] = {5555, 5556, 5554};

/* 永远不做任何伪造的进程（伪造会让「设置」里开发者选项开关的显示错乱） */
static const char *const kAlwaysExclude[] = {
        "com.android.settings",
        "com.android.systemui",
        "com.android.shell",
        "com.android.providers.settings",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.devhider.lsp",
};

static const int kAlwaysExcludeCount = (int) (sizeof(kAlwaysExclude) / sizeof(kAlwaysExclude[0]));
