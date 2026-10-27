package ms.bd.c;

/**
 * libmetasec_ml.so 在 JNI_OnLoad 里把真正的签名入口注册到这个类：
 *   RegisterNatives(ms/bd/c/m, a(IIJLjava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;)
 *
 * 也就是说：System.loadLibrary("metasec_ml") 之后，直接调 m.a(...) 就能进入 so 的 native 代码。
 */
public final class m {

    private m() {
    }

    public static native Object a(int op1, int op2, long lv, String s, Object obj);
}
