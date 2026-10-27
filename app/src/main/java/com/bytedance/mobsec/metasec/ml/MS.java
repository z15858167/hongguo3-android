package com.bytedance.mobsec.metasec.ml;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * libmetasec_ml.so **回调**的 Java 类（反调试 + 环境查询），不是 native 宿主。
 *
 * native 入口注册在 ms.bd.c.m 上；so 通过 JNI 调这个类的 b(op,...) 问环境信息，
 * op 取值与返回类型是桌面版 unidbg 实测出来的（FqTrace.handleMS / MetasecSign.handleMS）：
 *
 *   65539     → "/data/.../files/.msdata"   状态文件路径
 *   33554433  → true                         (两个布尔开关)
 *   33554434  → true
 *   16777232  → 版本号 int
 *   16777233  → 版本名 String
 *   16777218  → 证书字节（ms_16777218.bin）
 *   268435470 → 当前毫秒时间戳 Long
 *
 * 返回 null 时 so 会退回默认值/签出空串，所以这里必须照着实现。
 */
public final class MS {

    /** so 会 GetStaticIntField 读这个静态标志（unidbg 里恒为 0x40）。 */
    public static int a = 0x40;

    /** 版本号/名，默认对齐 config.json 里 UA 的 com.phoenix.read/72232。 */
    public static int versionCode = 72232;
    public static String versionName = "7.2.2.32";

    private static Context ctx;
    private static String filesDir = "";
    private static byte[] cert;
    private static boolean certLoaded;

    private MS() {
    }

    /** 必须在 loadLibrary("metasec_ml") **之前**调用：so 的 JNI_OnLoad 就可能回调过来。 */
    public static synchronized void init(Context c) {
        if (c == null) return;
        ctx = c.getApplicationContext();
        if (ctx != null) {
            filesDir = ctx.getFilesDir().getAbsolutePath();
        }
    }

    private static synchronized byte[] cert() {
        if (certLoaded) return cert;
        certLoaded = true;
        if (ctx == null) return null;
        try (InputStream in = ctx.getAssets().open("ms_16777218.bin")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            cert = bo.toByteArray();
        } catch (Throwable t) {
            cert = null;
        }
        return cert;
    }

    public static Object b(int op, int op2, long lv, String s, Object obj) {
        switch (op) {
            case 65539:
                return (filesDir.isEmpty() ? "/data/data/com.hongguo.client/files" : filesDir)
                        + "/.msdata";
            case 33554433:
            case 33554434:
                return Boolean.TRUE;
            case 16777232:
                return versionCode;
            case 16777233:
                return versionName;
            case 16777218:
                return cert();
            case 268435470:
                return System.currentTimeMillis();
            default:
                return null;
        }
    }

    public static Object a(int op, int op2, long lv, String s, Object obj) {
        return null;
    }

    /** so 会调一次 MS.a()V 做无害回调。 */
    public static void a() {
    }
}
