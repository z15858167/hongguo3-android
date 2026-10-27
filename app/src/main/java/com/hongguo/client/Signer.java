package com.hongguo.client;

import android.content.Context;

import com.bytedance.mobsec.metasec.ml.MS;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 签名：直接调用 libmetasec_ml.so 内部的净函数（见 cpp/signer.c）。
 *
 * 和桌面版的区别只在"怎么执行 ARM64 代码"：
 *   桌面（x86）→ unidbg 用 unicorn 模拟一颗 ARM64 CPU
 *   手机（arm64）→ 直接 dlopen，跑原生机器码，零模拟开销
 *
 * 注意加载顺序：必须先把 Context 交给 MS（so 的 JNI_OnLoad 就可能回调 MS.b 问环境），
 * 再 loadLibrary("metasec_ml")。
 */
public final class Signer {

    private static boolean inited;
    private static boolean libLoaded;
    private static boolean bridgeLoaded;
    private static String err = "";
    private static String info = "";

    private Signer() {
    }

    /** 建议在每次冷启动尽早调用（Api.init 里已经带了）。 */
    public static synchronized void init(Context c) {
        MS.init(c);
        ensure();
    }

    private static synchronized void ensure() {
        if (inited) return;
        inited = true;
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable ignored) {
            // so 是静态链接 C++ 运行时，这里加载失败不影响
        }
        try {
            System.loadLibrary("metasec_ml");
            libLoaded = true;
        } catch (Throwable t) {
            err = "loadLibrary(metasec_ml): " + t;
            return;
        }
        try {
            System.loadLibrary("hgsigner");
            bridgeLoaded = true;
            try {
                info = nativeInfo();
            } catch (Throwable t) {
                info = "nativeInfo: " + t;
            }
        } catch (Throwable t) {
            err = "loadLibrary(hgsigner): " + t;
        }
    }

    private static native boolean nativeReady();

    private static native String nativeInfo();

    private static native String nativeSign(String url, String headers);

    public static boolean ready() {
        ensure();
        if (!bridgeLoaded) return false;
        try {
            return nativeReady();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String status() {
        ensure();
        StringBuilder sb = new StringBuilder();
        sb.append("metasec_ml: ").append(libLoaded ? "已加载" : "失败").append('\n');
        if (!err.isEmpty()) sb.append("  原因: ").append(err).append('\n');
        sb.append("hgsigner 桥: ").append(bridgeLoaded ? "已加载" : "失败").append('\n');
        if (!info.isEmpty()) sb.append("  ").append(info);
        return sb.toString();
    }

    /** so 要的格式：k\r\nv\r\nk\r\nv（末尾不带换行，与桌面签名服务一致）。 */
    private static String joinHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (sb.length() > 0) sb.append("\r\n");
            sb.append(e.getKey()).append("\r\n").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 返回 {"X-Argus": "...", "X-Gorgon": "..."}。
     * so 返回的是交替行（键一行、值一行），也兼容 "键: 值" 形式。
     * 失败抛异常（调用方据此降级或重试免签）。
     */
    public static Map<String, String> sign(String url, Map<String, String> headers) throws Exception {
        ensure();
        if (!bridgeLoaded) throw new IllegalStateException("签名桥未加载: " + err);
        String raw = nativeSign(url, joinHeaders(headers));
        if (raw == null || raw.trim().isEmpty()) throw new IllegalStateException("签名返回空");

        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = raw.split("\n");
        // 形式一：X-Argus\n<val>\nX-Gorgon\n<val>
        for (int i = 0; i + 1 < lines.length; i += 2) {
            String k = lines[i].trim();
            if (!k.startsWith("X-")) continue;
            String v = lines[i + 1].trim();
            if (!v.isEmpty()) out.put(k, v);
        }
        // 形式二：X-Argus: <val>
        if (out.isEmpty()) {
            for (String line : lines) {
                String s = line.trim();
                if (!s.startsWith("X-")) continue;
                int c = s.indexOf(':');
                if (c <= 0) continue;
                String k = s.substring(0, c).trim();
                String v = s.substring(c + 1).trim();
                if (!v.isEmpty()) out.put(k, v);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("签名结果无法解析: "
                    + (raw.length() > 160 ? raw.substring(0, 160) : raw));
        }
        return out;
    }
}
