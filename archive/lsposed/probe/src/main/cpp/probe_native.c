#include <jni.h>
#include <string.h>
#include <sys/system_properties.h>

/*
 * 直接走 libc 的 __system_property_get（不经 android.os.SystemProperties Java 层），
 * 用来验证 Zygisk 的 native PLT hook 是否真的生效。
 *
 * 若 Zygisk 模块生效：这里读到的 ro.debuggable / ro.build.type / sys.usb.state 等
 * 会是「伪造值」。
 * 若只有 LSPosed（ART hook）：这里读到的仍是「真值」——因为 ART hook 摸不到 native 层。
 * 两者对照即可定位 HSBC 到底是哪一层在检测。
 */
jstring Java_com_devhider_probe_MainActivity_nativePropGet(JNIEnv *env,
                                                          jobject thiz,
                                                          jstring key) {
    (void) thiz;
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    if (k == NULL) return (*env)->NewStringUTF(env, "");
    char buf[PROP_VALUE_MAX];
    memset(buf, 0, sizeof(buf));
    __system_property_get(k, buf);
    (*env)->ReleaseStringUTFChars(env, key, k);
    return (*env)->NewStringUTF(env, buf);
}
