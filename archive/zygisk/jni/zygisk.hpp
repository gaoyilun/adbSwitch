#pragma once

/*
 * Zygisk 模块公开 API（精简版）。
 *
 * 这里刻意声明为 API v2 的 ABI：
 *   - api_version <= 3 的模块，Magisk 会提供「regex 版」pltHookRegister + pltHookExclude 的函数表；
 *   - Magisk 内部对高版本模块用的是 dev/inode 版，但对低版本模块仍保留 regex 兼容 shim
 *     (见 native/src/core/zygisk/module.hpp 里的 plt_hook_register compatibility shim)。
 *   因此声明 v2 在 Magisk 24 ~ 最新版上都能拿到同一套函数表，兼容性最好。
 *
 * 另外：本模块完全不使用 AppSpecializeArgs 里的字段（各版本布局不同，容易错位），
 * 包名一律从 /proc/self/cmdline 读取。
 */

#include <jni.h>

#define ZYGISK_API_VERSION 2

namespace zygisk {

struct Api;
struct AppSpecializeArgs;
struct ServerSpecializeArgs;

class ModuleBase {
public:
    virtual void onLoad(Api *api, JNIEnv *env) {}
    virtual void preAppSpecialize(AppSpecializeArgs *args) {}
    virtual void postAppSpecialize(const AppSpecializeArgs *args) {}
    virtual void preServerSpecialize(ServerSpecializeArgs *args) {}
    virtual void postServerSpecialize(const ServerSpecializeArgs *args) {}
    virtual ~ModuleBase() {}
};

// 只是占位：本模块不解析任何字段
struct AppSpecializeArgs {
    void *_opaque[16];
};

struct ServerSpecializeArgs {
    void *_opaque[8];
};

enum Option : int {
    FORCE_DENYLIST_UNMOUNT = 0,
    DLCLOSE_MODULE_LIBRARY = 1,
};

struct Api {
    void *impl;
    bool (*registerModule)(Api *tbl, void *module_abi);

    void (*hookJniNativeMethods)(JNIEnv *env, const char *clz, JNINativeMethod *methods, int numMethods);
    void (*pltHookRegister)(const char *regex, const char *symbol, void *newFunc, void **oldFunc);
    void (*pltHookExclude)(const char *regex, const char *symbol);
    bool (*pltHookCommit)();
    int (*connectCompanion)(void *impl);
    void (*setOption)(void *impl, Option);
    int (*getModuleDir)(void *impl);
    uint32_t (*getFlags)(void *impl);
};

struct ModuleAbi {
    long api_version;
    ModuleBase *impl;
    void (*preAppSpecialize)(ModuleBase *, AppSpecializeArgs *);
    void (*postAppSpecialize)(ModuleBase *, const AppSpecializeArgs *);
    void (*preServerSpecialize)(ModuleBase *, ServerSpecializeArgs *);
    void (*postServerSpecialize)(ModuleBase *, const ServerSpecializeArgs *);
};

} // namespace zygisk

#define REGISTER_ZYGISK_MODULE(clazz)                                          \
static void zy_pre(zygisk::ModuleBase *m, zygisk::AppSpecializeArgs *a) {       \
    static_cast<clazz *>(m)->preAppSpecialize(a);                               \
}                                                                              \
static void zy_post(zygisk::ModuleBase *m, const zygisk::AppSpecializeArgs *a) { \
    static_cast<clazz *>(m)->postAppSpecialize(a);                              \
}                                                                              \
static void zy_pre_s(zygisk::ModuleBase *m, zygisk::ServerSpecializeArgs *a) {  \
    static_cast<clazz *>(m)->preServerSpecialize(a);                            \
}                                                                              \
static void zy_post_s(zygisk::ModuleBase *m, const zygisk::ServerSpecializeArgs *a) { \
    static_cast<clazz *>(m)->postServerSpecialize(a);                           \
}                                                                              \
static clazz zy_instance;                                                      \
static zygisk::ModuleAbi zy_abi = {                                            \
    ZYGISK_API_VERSION, &zy_instance, &zy_pre, &zy_post, &zy_pre_s, &zy_post_s \
};                                                                             \
extern "C" [[gnu::visibility("default")]]                                      \
void zygisk_module_entry(zygisk::Api *table, JNIEnv *env) {                     \
    if (table != nullptr && table->registerModule != nullptr &&                 \
        table->registerModule(table, &zy_abi)) {                                \
        zy_instance.onLoad(table, env);                                         \
    }                                                                          \
}
