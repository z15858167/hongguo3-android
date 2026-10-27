/*
 * 直接调用 libmetasec_ml.so 里的签名函数。
 *
 * 依据：桌面版 FqTrace.sign 的实现是
 *     module.callFunction(emulator, 0x27d288, url, headers)
 * 也就是说这个 so 里有一个「净函数」，入参只有两个字符串指针，
 * 返回签名结果字符串。它不在导出符号表里（so 只导出 JNI_OnLoad），
 * 所以只能"加载基址 + 固定偏移"来定位。
 *
 * 偏移 0x27d288 只对当前这份 so 有效；换 so 必须重新定位。
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "HgSigner"
#define SIGN_OFFSET 0x27d288UL

typedef const char *(*sign_fn)(const char *, const char *);

static uintptr_t g_base = 0;
static char g_note[192];

/* 从 /proc/self/maps 里找 libmetasec_ml.so 的最低映射地址 = 加载基址 */
static uintptr_t find_base(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) {
        snprintf(g_note, sizeof g_note, "打不开 /proc/self/maps");
        return 0;
    }
    char line[512];
    uintptr_t lo = 0;
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, "libmetasec_ml.so")) continue;
        unsigned long a = 0, b = 0;
        if (sscanf(line, "%lx-%lx", &a, &b) == 2) {
            if (lo == 0 || (uintptr_t) a < lo) lo = (uintptr_t) a;
        }
    }
    fclose(f);
    if (lo == 0) snprintf(g_note, sizeof g_note, "maps 里没有 libmetasec_ml.so");
    return lo;
}

JNIEXPORT jboolean JNICALL
Java_com_hongguo_client_Signer_nativeReady(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_base == 0) g_base = find_base();
    return g_base != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_hongguo_client_Signer_nativeInfo(JNIEnv *env, jclass clazz) {
    (void) clazz;
    if (g_base == 0) g_base = find_base();
    char buf[256];
    snprintf(buf, sizeof buf, "base=0x%lx target=0x%lx %s",
             (unsigned long) g_base,
             (unsigned long) (g_base ? g_base + SIGN_OFFSET : 0),
             g_note);
    return (*env)->NewStringUTF(env, buf);
}

/*
 * 返回签名结果（多行 "X-Argus: ...\nX-Gorgon: ...\n"），失败返回 null。
 * headers 用 C 版换行拼接：k\r\nv\r\nk\r\nv\r\n
 */
JNIEXPORT jstring JNICALL
Java_com_hongguo_client_Signer_nativeSign(JNIEnv *env, jclass clazz,
                                          jstring jurl, jstring jheaders) {
    (void) clazz;
    if (g_base == 0) g_base = find_base();
    if (g_base == 0) return NULL;

    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    const char *hdr = (*env)->GetStringUTFChars(env, jheaders, NULL);
    if (!url || !hdr) {
        if (url) (*env)->ReleaseStringUTFChars(env, jurl, url);
        if (hdr) (*env)->ReleaseStringUTFChars(env, jheaders, hdr);
        return NULL;
    }

    sign_fn fn = (sign_fn) (g_base + SIGN_OFFSET);
    const char *out = fn(url, hdr);

    (*env)->ReleaseStringUTFChars(env, jurl, url);
    (*env)->ReleaseStringUTFChars(env, jheaders, hdr);

    if (!out) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "sign 返回 null");
        return NULL;
    }
    return (*env)->NewStringUTF(env, out);
}
