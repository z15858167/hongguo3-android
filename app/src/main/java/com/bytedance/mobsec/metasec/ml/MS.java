package com.bytedance.mobsec.metasec.ml;

/**
 * 注意：这个类**不是** native 宿主。
 *
 * libmetasec_ml.so 在 JNI_OnLoad 里只做了一件事：
 *   RegisterNatives(ms/bd/c/m, a(IIJLjava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;)
 * 然后通过 GetStaticMethodID 找到 MS.b(...) 用于**回调 Java**（反调试探测：查调用栈）。
 *
 * 所以这里的方法必须是普通 Java 方法（返回 null 兜底），
 * 声明成 native 会导致 so 回调时 UnsatisfiedLinkError / 直接崩。
 */
public final class MS {

    private MS() {
    }

    public static Object b(int op1, int op2, long lv, String s, Object obj) {
        return null;
    }

    public static Object a(int op1, int op2, long lv, String s, Object obj) {
        return null;
    }

    public static void a2() {
    }
}
