#include <jni.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <unistd.h>

#include <android/log.h>

#include "zygisk.hpp"
#include "rules.h"

#define LOG_TAG "DevHider"

namespace {

bool g_enabled = false;
bool g_debug = false;
bool g_whitelist = false;
std::vector<std::string> g_packages;
std::vector<std::string> g_excludes;

#define DHLOG(...)                                                              \
    do {                                                                        \
        if (g_debug) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); \
    } while (0)

// ------------------------------------------------------------------
// 规则计算
// ------------------------------------------------------------------
const PropRule *findRule(const char *name) {
    for (int i = 0; i < kPropRuleCount; i++) {
        if (strcmp(kPropRules[i].name, name) == 0) return &kPropRules[i];
    }
    return nullptr;
}

bool startsWith(const char *s, const char *p) {
    return s != nullptr && p != nullptr && strncmp(s, p, strlen(p)) == 0;
}

bool containsLower(const char *haystack, const char *needle) {
    if (haystack == nullptr) return false;
    std::string h(haystack);
    for (char &c : h) {
        if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
    }
    return h.find(needle) != std::string::npos;
}

void stripToken(const char *in, const char *token, char *out, size_t n) {
    if (in == nullptr) {
        out[0] = 0;
        return;
    }
    std::string src(in);
    std::string result;
    size_t pos = 0;
    while (pos <= src.size()) {
        size_t comma = src.find(',', pos);
        std::string item = src.substr(pos, comma == std::string::npos
                                                 ? std::string::npos : comma - pos);
        // trim
        size_t a = item.find_first_not_of(" \t");
        size_t b = item.find_last_not_of(" \t");
        std::string t = (a == std::string::npos) ? "" : item.substr(a, b - a + 1);
        if (!t.empty() && t != token) {
            if (!result.empty()) result += ',';
            result += t;
        }
        if (comma == std::string::npos) break;
        pos = comma + 1;
    }
    if (result.empty()) result = "none";
    snprintf(out, n, "%s", result.c_str());
}

void replaceDebugWords(const char *in, char *out, size_t n) {
    if (in == nullptr) {
        out[0] = 0;
        return;
    }
    std::string s(in);
    size_t p;
    while ((p = s.find("userdebug")) != std::string::npos) s.replace(p, 9, "user");
    while ((p = s.find("test-keys")) != std::string::npos) s.replace(p, 9, "release-keys");
    while ((p = s.find("/eng/")) != std::string::npos) s.replace(p, 5, "/user/");
    while ((p = s.find("-eng ")) != std::string::npos) s.replace(p, 5, "-user ");
    snprintf(out, n, "%s", s.c_str());
}

/** 计算伪造值；返回 true 表示需要伪造 */
bool computeFake(const char *name, const char *real, char *out, size_t n) {
    out[0] = 0;
    if (name == nullptr) return false;

    const PropRule *r = findRule(name);
    if (r != nullptr) {
        switch (r->kind) {
            case 0:
                snprintf(out, n, "%s", r->value);
                return true;
            case 1:
                stripToken(real, "adb", out, n);
                return true;
            case 2:
                replaceDebugWords(real, out, n);
                return true;
            default:
                break;
        }
    }

    // 模糊兜底：名字里带 adb / userdebug 的属性
    if (containsLower(name, "adb")) {
        if (startsWith(name, "sys.") || startsWith(name, "persist.")
            || startsWith(name, "init.svc") || startsWith(name, "ro.")
            || startsWith(name, "vendor.")) {
            snprintf(out, n, "0");
            return true;
        }
    }
    if (containsLower(name, "userdebug") || containsLower(name, "debug_word")) {
        replaceDebugWords(real, out, n);
        return true;
    }
    return false;
}

bool isBlockedPath(const char *path) {
    if (path == nullptr || path[0] != '/') return false;
    if (!startsWith(path, "/sys/") && !startsWith(path, "/data/")
        && !startsWith(path, "/dev/") && !startsWith(path, "/config/")
        && !startsWith(path, "/apex/")) {
        return false;
    }
    for (int i = 0; i < kBlockPathCount; i++) {
        if (startsWith(path, kBlockPaths[i])) return true;
    }
    return false;
}

bool isBlockedPort(int port) {
    for (int p : kBlockPorts) {
        if (p == port) return true;
    }
    return false;
}

// ------------------------------------------------------------------
// 配置
// ------------------------------------------------------------------
void readLines(const char *path, std::vector<std::string> &out) {
    FILE *f = fopen(path, "re");
    if (f == nullptr) return;
    char buf[512];
    while (fgets(buf, sizeof(buf), f) != nullptr) {
        size_t len = strlen(buf);
        while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r'
                           || buf[len - 1] == ' ')) {
            buf[--len] = 0;
        }
        if (len == 0 || buf[0] == '#') continue;
        out.emplace_back(buf);
    }
    fclose(f);
}

bool fileExists(const char *path) {
    struct stat st{};
    return stat(path, &st) == 0;
}

void loadConfig() {
    g_packages.clear();
    g_excludes.clear();
    readLines("/data/adb/devhider/packages.txt", g_packages);
    readLines("/data/adb/devhider/exclude.txt", g_excludes);

    FILE *f = fopen("/data/adb/devhider/mode.txt", "re");
    if (f != nullptr) {
        char buf[64] = {0};
        if (fgets(buf, sizeof(buf), f) != nullptr) {
            g_whitelist = (strncmp(buf, "whitelist", 9) == 0);
        }
        fclose(f);
    } else {
        g_whitelist = false;
    }
    g_debug = fileExists("/data/adb/devhider/debug");
}

bool shouldApply(const std::string &pkg) {
    if (pkg.empty()) return false;
    for (int i = 0; i < kAlwaysExcludeCount; i++) {
        if (pkg == kAlwaysExclude[i]) return false;
    }
    for (const auto &e : g_excludes) {
        if (pkg == e) return false;
    }
    if (g_whitelist) {
        for (const auto &p : g_packages) {
            if (pkg == p) return true;
        }
        return false;
    }
    return true;  // 黑名单模式：除排除项外一律生效
}

/** 用原始 syscall 读，避免被自己 hook 的 open 影响 */
std::string readCmdline() {
    int fd = (int) syscall(SYS_openat, AT_FDCWD, "/proc/self/cmdline", O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return "";
    char buf[512] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return "";
    return std::string(buf);
}

// ------------------------------------------------------------------
// 原始函数指针
// ------------------------------------------------------------------
int (*orig_prop_get)(const char *name, char *value) = nullptr;
int (*orig_prop_get_bool)(const char *name) = nullptr;
int (*orig_prop_read)(const prop_info *pi, char *name, char *value) = nullptr;
void (*orig_prop_read_cb)(const prop_info *pi,
                          void (*cb)(void *, const char *, const char *, uint32_t),
                          void *cookie) = nullptr;

int (*orig_open)(const char *path, int flags, int mode) = nullptr;
int (*orig_openat)(int dirfd, const char *path, int flags, int mode) = nullptr;
int (*orig_access)(const char *path, int mode) = nullptr;
int (*orig_faccessat)(int dirfd, const char *path, int mode, int flags) = nullptr;
int (*orig_connect)(int fd, const struct sockaddr *addr, socklen_t len) = nullptr;

jstring (*orig_get_1)(JNIEnv *, jclass, jstring) = nullptr;
jstring (*orig_get_2)(JNIEnv *, jclass, jstring, jstring) = nullptr;
jint (*orig_get_int)(JNIEnv *, jclass, jstring, jint) = nullptr;
jlong (*orig_get_long)(JNIEnv *, jclass, jstring, jlong) = nullptr;
jboolean (*orig_get_boolean)(JNIEnv *, jclass, jstring, jboolean) = nullptr;

// ------------------------------------------------------------------
// native 属性 hook
// ------------------------------------------------------------------
int my_prop_get(const char *name, char *value) {
    if (orig_prop_get == nullptr) {
        if (value != nullptr) value[0] = 0;
        return 0;
    }
    int ret = orig_prop_get(name, value);
    if (!g_enabled || name == nullptr || value == nullptr) return ret;
    char fake[PROP_VALUE_MAX];
    if (computeFake(name, value, fake, sizeof(fake))) {
        DHLOG("prop_get %s: %s -> %s", name, value, fake);
        snprintf(value, PROP_VALUE_MAX, "%s", fake);
    }
    return ret;
}

int my_prop_read(const prop_info *pi, char *name, char *value) {
    if (orig_prop_read == nullptr) return 0;
    int ret = orig_prop_read(pi, name, value);
    if (!g_enabled || name == nullptr || value == nullptr) return ret;
    char fake[PROP_VALUE_MAX];
    if (computeFake(name, value, fake, sizeof(fake))) {
        DHLOG("prop_read %s: %s -> %s", name, value, fake);
        snprintf(value, PROP_VALUE_MAX, "%s", fake);
    }
    return ret;
}

void my_prop_read_cb(const prop_info *pi,
                     void (*cb)(void *, const char *, const char *, uint32_t),
                     void *cookie) {
    if (orig_prop_read_cb == nullptr) return;
    if (!g_enabled || cb == nullptr || orig_prop_read == nullptr) {
        orig_prop_read_cb(pi, cb, cookie);
        return;
    }
    char name[PROP_NAME_MAX];
    char value[PROP_VALUE_MAX];
    name[0] = 0;
    value[0] = 0;
    orig_prop_read(pi, name, value);
    if (name[0] == 0) {
        orig_prop_read_cb(pi, cb, cookie);
        return;
    }
    char fake[PROP_VALUE_MAX];
    if (computeFake(name, value, fake, sizeof(fake))) {
        DHLOG("prop_read_cb %s: %s -> %s", name, value, fake);
        cb(cookie, name, fake, 0);
        return;
    }
    orig_prop_read_cb(pi, cb, cookie);
}

bool isPropTrue(const char *v) {
    if (v == nullptr) return false;
    std::string s(v);
    for (char &c : s) {
        if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
    }
    return s == "1" || s == "true" || s == "y" || s == "yes" || s == "on";
}

int my_prop_get_bool(const char *name) {
    if (orig_prop_get_bool == nullptr) return 0;
    if (!g_enabled || name == nullptr) return orig_prop_get_bool(name);
    // 取真实字符串（部分规则需要 real 才能改写，例如剔除 adb / 替换 userdebug）
    char real[PROP_VALUE_MAX] = {0};
    if (orig_prop_get != nullptr) orig_prop_get(name, real);
    char fake[PROP_VALUE_MAX];
    if (computeFake(name, real, fake, sizeof(fake))) {
        DHLOG("prop_get_bool %s: -> %s", name, fake);
        return isPropTrue(fake) ? 1 : 0;
    }
    return orig_prop_get_bool(name);
}

// ------------------------------------------------------------------
// 文件 / 端口 hook
// ------------------------------------------------------------------
int my_open(const char *path, int flags, int mode) {
    if (orig_open == nullptr) return -1;
    if (g_enabled && isBlockedPath(path)) {
        DHLOG("block open %s", path);
        errno = ENOENT;
        return -1;
    }
    return orig_open(path, flags, mode);
}

int my_openat(int dirfd, const char *path, int flags, int mode) {
    if (orig_openat == nullptr) return -1;
    if (g_enabled && path != nullptr && path[0] == '/' && isBlockedPath(path)) {
        DHLOG("block openat %s", path);
        errno = ENOENT;
        return -1;
    }
    return orig_openat(dirfd, path, flags, mode);
}

int my_access(const char *path, int mode) {
    if (orig_access == nullptr) return -1;
    if (g_enabled && isBlockedPath(path)) {
        DHLOG("block access %s", path);
        errno = ENOENT;
        return -1;
    }
    return orig_access(path, mode);
}

int my_faccessat(int dirfd, const char *path, int mode, int flags) {
    if (orig_faccessat == nullptr) return -1;
    if (g_enabled && path != nullptr && path[0] == '/' && isBlockedPath(path)) {
        DHLOG("block faccessat %s", path);
        errno = ENOENT;
        return -1;
    }
    return orig_faccessat(dirfd, path, mode, flags);
}

int my_connect(int fd, const struct sockaddr *addr, socklen_t len) {
    if (orig_connect == nullptr) return -1;
    if (g_enabled && addr != nullptr) {
        if (addr->sa_family == AF_INET && (size_t) len >= sizeof(struct sockaddr_in)) {
            const auto *sin = reinterpret_cast<const struct sockaddr_in *>(addr);
            if (isBlockedPort(ntohs(sin->sin_port))) {
                DHLOG("block connect port %d", ntohs(sin->sin_port));
                errno = ECONNREFUSED;
                return -1;
            }
        } else if (addr->sa_family == AF_INET6 && (size_t) len >= sizeof(struct sockaddr_in6)) {
            const auto *sin6 = reinterpret_cast<const struct sockaddr_in6 *>(addr);
            if (isBlockedPort(ntohs(sin6->sin6_port))) {
                DHLOG("block connect port %d", ntohs(sin6->sin6_port));
                errno = ECONNREFUSED;
                return -1;
            }
        }
    }
    return orig_connect(fd, addr, len);
}

// ------------------------------------------------------------------
// JNI: android.os.SystemProperties 的 native 方法
// ------------------------------------------------------------------
jstring fakeString(JNIEnv *env, jstring key, jstring real) {
    if (key == nullptr) return real;
    const char *k = env->GetStringUTFChars(key, nullptr);
    if (k == nullptr) return real;
    const char *r = (real != nullptr) ? env->GetStringUTFChars(real, nullptr) : nullptr;
    char fake[PROP_VALUE_MAX];
    bool hit = computeFake(k, r != nullptr ? r : "", fake, sizeof(fake));
    if (r != nullptr) env->ReleaseStringUTFChars(real, r);
    env->ReleaseStringUTFChars(key, k);
    if (!hit) return real;
    DHLOG("jni_get %s -> %s", k, fake);
    return env->NewStringUTF(fake);
}

jstring my_get_1(JNIEnv *env, jclass cls, jstring key) {
    jstring real = (orig_get_1 != nullptr) ? orig_get_1(env, cls, key) : nullptr;
    if (!g_enabled) return real;
    return fakeString(env, key, real);
}

jstring my_get_2(JNIEnv *env, jclass cls, jstring key, jstring def) {
    jstring real = (orig_get_2 != nullptr) ? orig_get_2(env, cls, key, def) : def;
    if (!g_enabled) return real;
    return fakeString(env, key, real);
}

jint my_get_int(JNIEnv *env, jclass cls, jstring key, jint def) {
    jint real = (orig_get_int != nullptr) ? orig_get_int(env, cls, key, def) : def;
    if (!g_enabled || key == nullptr) return real;
    const char *k = env->GetStringUTFChars(key, nullptr);
    if (k == nullptr) return real;
    const PropRule *r = findRule(k);
    env->ReleaseStringUTFChars(key, k);
    if (r != nullptr && r->kind == 0) {
        DHLOG("jni_get_int %s -> %s", k, r->value);
        return (jint) atoi(r->value);
    }
    return real;
}

jlong my_get_long(JNIEnv *env, jclass cls, jstring key, jlong def) {
    jlong real = (orig_get_long != nullptr) ? orig_get_long(env, cls, key, def) : def;
    if (!g_enabled || key == nullptr) return real;
    const char *k = env->GetStringUTFChars(key, nullptr);
    if (k == nullptr) return real;
    const PropRule *r = findRule(k);
    env->ReleaseStringUTFChars(key, k);
    if (r != nullptr && r->kind == 0) {
        DHLOG("jni_get_long %s -> %s", k, r->value);
        return (jlong) atoll(r->value);
    }
    return real;
}

jboolean my_get_boolean(JNIEnv *env, jclass cls, jstring key, jboolean def) {
    jboolean real = (orig_get_boolean != nullptr) ? orig_get_boolean(env, cls, key, def) : def;
    if (!g_enabled || key == nullptr) return real;
    const char *k = env->GetStringUTFChars(key, nullptr);
    if (k == nullptr) return real;
    const PropRule *r = findRule(k);
    env->ReleaseStringUTFChars(key, k);
    if (r != nullptr && r->kind == 0) {
        jboolean v = (strcmp(r->value, "0") != 0 && strcmp(r->value, "false") != 0) ? 1 : 0;
        DHLOG("jni_get_boolean %s -> %d", k, (int) v);
        return v;
    }
    return real;
}

} // namespace

// ------------------------------------------------------------------
// Zygisk 模块
// ------------------------------------------------------------------
class DevHiderModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        loadConfig();

        JNINativeMethod methods[] = {
                {"native_get",       "(Ljava/lang/String;)Ljava/lang/String;",               (void *) my_get_1},
                {"native_get",       "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", (void *) my_get_2},
                {"native_get_int",   "(Ljava/lang/String;I)I",                               (void *) my_get_int},
                {"native_get_long",  "(Ljava/lang/String;J)J",                               (void *) my_get_long},
                {"native_get_boolean", "(Ljava/lang/String;Z)Z",                             (void *) my_get_boolean},
        };
        api_->hookJniNativeMethods(env_, "android/os/SystemProperties", methods, 5);
        orig_get_1 = reinterpret_cast<decltype(orig_get_1)>(methods[0].fnPtr);
        orig_get_2 = reinterpret_cast<decltype(orig_get_2)>(methods[1].fnPtr);
        orig_get_int = reinterpret_cast<decltype(orig_get_int)>(methods[2].fnPtr);
        orig_get_long = reinterpret_cast<decltype(orig_get_long)>(methods[3].fnPtr);
        orig_get_boolean = reinterpret_cast<decltype(orig_get_boolean)>(methods[4].fnPtr);

        api_->pltHookRegister(".*\\.so$", "__system_property_get",
                              (void *) my_prop_get, (void **) &orig_prop_get);
        api_->pltHookRegister(".*\\.so$", "__system_property_get_bool",
                              (void *) my_prop_get_bool, (void **) &orig_prop_get_bool);
        api_->pltHookRegister(".*\\.so$", "__system_property_read",
                              (void *) my_prop_read, (void **) &orig_prop_read);
        api_->pltHookRegister(".*\\.so$", "__system_property_read_callback",
                              (void *) my_prop_read_cb, (void **) &orig_prop_read_cb);
        api_->pltHookRegister(".*\\.so$", "open", (void *) my_open, (void **) &orig_open);
        api_->pltHookRegister(".*\\.so$", "openat", (void *) my_openat, (void **) &orig_openat);
        api_->pltHookRegister(".*\\.so$", "access", (void *) my_access, (void **) &orig_access);
        api_->pltHookRegister(".*\\.so$", "faccessat", (void *) my_faccessat,
                              (void **) &orig_faccessat);
        api_->pltHookRegister(".*\\.so$", "connect", (void *) my_connect, (void **) &orig_connect);
        api_->pltHookCommit();
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        std::string pkg = readCmdline();
        g_enabled = shouldApply(pkg);
        DHLOG("pkg=%s enabled=%d", pkg.c_str(), (int) g_enabled);
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(DevHiderModule)
