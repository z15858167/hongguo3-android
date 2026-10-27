/*
 * 直接调用 libmetasec_ml.so 里的签名函数（so 里没导出，只能"基址 + 偏移"）。
 *
 * 两个候选入口，都来自桌面版的实测结论：
 *
 *   0x168c80  sign(url, header)                                  ← FqTrace.SIGN，2 参数，已验证出真签名
 *   0x27d288  sign(url, header, 0x18, tick, 0xffffffffffffff, n)  ← MetasecSign.SIGN_OFFSET，6 参数
 *
 * 注意 0x27d288 落在本 so 的 .rodata 区（.text 只到 0x268d64），
 * 直接跳过去会 SIGSEGV，所以调用前先查 /proc/self/maps 确认目标地址可执行。
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "HgSigner"
#define SO_NAME "libmetasec_ml.so"

#define OFF_TWO_ARG  0x168c80UL
#define OFF_SIX_ARG  0x27d288UL

typedef const char *(*sign2_fn)(const char *, const char *);
typedef const char *(*sign6_fn)(const char *, const char *, long, long, long, long);

static uintptr_t g_base = 0;
static char g_note[192];

static int in_list(const char *list, const char *x) {
    size_t n = strlen(x);
    const char *p = list;
    while ((p = strstr(p, x)) != NULL) {
        char nxt = p[n];
        if (p == list || p[-1] == ',' || p[-1] == ' ') {
            if (nxt == '\0' || nxt == ',' || nxt == ' ' || nxt == '\n') return 1;
        }
        p += n;
    }
    return 0;
}

/* 从 /proc/self/maps 里找 libmetasec_ml.so：最低映射地址 = 加载基址；同时记录可执行区间数 */
static uintptr_t find_base(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) {
        snprintf(g_note, sizeof g_note, "打不开 /proc/self/maps");
        return 0;
    }
    char line[512];
    uintptr_t lo = 0;
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, SO_NAME)) continue;
        unsigned long a = 0, b = 0;
        if (sscanf(line, "%lx-%lx", &a, &b) != 2) continue;
        if (lo == 0 || (uintptr_t) a < lo) lo = (uintptr_t) a;
    }
    fclose(f);
    if (lo == 0) snprintf(g_note, sizeof g_note, "maps 里没有 " SO_NAME);
    return lo;
}

/* 目标地址是否可执行（在某个 r-x 映射内） */
static int is_exec(uintptr_t addr) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    int ok = 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long a = 0, b = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &a, &b, perms) != 3) continue;
        if (addr >= (uintptr_t) a && addr < (uintptr_t) b) {
            ok = (perms[2] == 'x');
            break;
        }
    }
    fclose(f);
    return ok;
}

static uintptr_t base_of(void) {
    if (g_base == 0) g_base = find_base();
    return g_base;
}

JNIEXPORT jboolean JNICALL
Java_com_hongguo_client_Signer_nativeReady(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    uintptr_t base = base_of();
    return (base && is_exec(base + OFF_TWO_ARG)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_hongguo_client_Signer_nativeInfo(JNIEnv *env, jclass clazz) {
    (void) clazz;
    uintptr_t base = base_of();
    char buf[320];
    snprintf(buf, sizeof buf,
             "base=0x%lx 2arg=0x%lx(%s) 6arg=0x%lx(%s) %s",
             (unsigned long) base,
             (unsigned long) (base ? base + OFF_TWO_ARG : 0),
             base && is_exec(base + OFF_TWO_ARG) ? "x" : "-",
             (unsigned long) (base ? base + OFF_SIX_ARG : 0),
             base && is_exec(base + OFF_SIX_ARG) ? "x" : "-",
             g_note);
    return (*env)->NewStringUTF(env, buf);
}

/*
 * 返回多行 "X-Argus\n<val>\nX-Gorgon\n<val>"，失败返回 null。
 * headers 由 Java 侧拼成 k\r\nv\r\nk\r\nv（与桌面签名服务的协议一致）。
 */
JNIEXPORT jstring JNICALL
Java_com_hongguo_client_Signer_nativeSign(JNIEnv *env, jclass clazz,
                                          jstring jurl, jstring jheaders) {
    (void) clazz;
    uintptr_t base = base_of();
    if (base == 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "no base: %s", g_note);
        return NULL;
    }

    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    const char *hdr = (*env)->GetStringUTFChars(env, jheaders, NULL);
    if (!url || !hdr) {
        if (url) (*env)->ReleaseStringUTFChars(env, jurl, url);
        if (hdr) (*env)->ReleaseStringUTFChars(env, jheaders, hdr);
        return NULL;
    }

    const char *out = NULL;

    /* 首选 2 参数入口（已验证） */
    uintptr_t a2 = base + OFF_TWO_ARG;
    if (is_exec(a2)) {
        out = ((sign2_fn) a2)(url, hdr);
        __android_log_print(ANDROID_LOG_INFO, TAG, "2arg -> %s",
                            out ? (out[0] ? "ok" : "empty") : "null");
    }

    /* 兜底 6 参数入口（只在目标可执行时才敢跳，否则必崩） */
    uintptr_t a6 = base + OFF_SIX_ARG;
    if ((out == NULL || out[0] == '\0') && is_exec(a6)) {
        static long tick = 0x2d641eee02cfL;
        static long counter = 0x3a75b16;
        out = ((sign6_fn) a6)(url, hdr, 0x18L, tick, 0xffffffffffffffL, counter);
        tick += 0x100000L;
        counter++;
        __android_log_print(ANDROID_LOG_INFO, TAG, "6arg -> %s",
                            out ? (out[0] ? "ok" : "empty") : "null");
    }

    (*env)->ReleaseStringUTFChars(env, jurl, url);
    (*env)->ReleaseStringUTFChars(env, jheaders, hdr);

    if (!out || out[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, out);
}
