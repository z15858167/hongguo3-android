package com.hongguo.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 签名：直接调用 libmetasec_ml.so 内部的净函数（见 cpp/signer.c）。
 *
 * 和桌面版的区别只在"怎么执行 ARM64 代码"：
 *   桌面（x86）→ unidbg 用 unicorn 模拟一颗 ARM64 CPU
 *   手机（arm64）→ 直接 dlopen，跑原生机器码，零模拟开销
 */
public final class Signer {

    private static boolean libLoaded = false;
    private static boolean bridgeLoaded = false;
    private static String libErr = "";
    private static String bridgeInfo = "";

    static {
        try {
            System.loadLibrary("metasec_ml");
            libLoaded = true;
        } catch (Throwable t) {
            libErr = "loadLibrary(metasec_ml): " + t;
        }
        if (libLoaded) {
            try {
                System.loadLibrary("hgsigner");
                bridgeLoaded = true;
                try {
                    bridgeInfo = nativeInfo();
                } catch (Throwable t) {
                    bridgeInfo = "nativeInfo: " + t;
                }
            } catch (Throwable t) {
                libErr = "loadLibrary(hgsigner): " + t;
            }
        }
    }

    private static native boolean nativeReady();

    private static native String nativeInfo();

    private static native String nativeSign(String url, String headers);

    private Signer() {
    }

    public static boolean ready() {
        if (!bridgeLoaded) return false;
        try {
            return nativeReady();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("metasec_ml: ").append(libLoaded ? "已加载" : "失败").append('\n');
        if (!libErr.isEmpty()) sb.append("  原因: ").append(libErr).append('\n');
        sb.append("hgsigner 桥: ").append(bridgeLoaded ? "已加载" : "失败").append('\n');
        if (!bridgeInfo.isEmpty()) sb.append("  ").append(bridgeInfo);
        return sb.toString();
    }

    /** headers 拼成 so 要求的格式：k\r\nv\r\nk\r\nv\r\n */
    private static String joinHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            sb.append(e.getKey()).append("\r\n").append(e.getValue()).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * 返回形如 {"X-Argus": "...", "X-Gorgon": "..."}。
     * 失败抛异常（调用方据此降级）。
     */
    public static Map<String, String> sign(String url, Map<String, String> headers) throws Exception {
        if (!bridgeLoaded) throw new IllegalStateException("签名桥未加载: " + libErr);
        String raw = nativeSign(url, joinHeaders(headers));
        if (raw == null || raw.isEmpty()) throw new IllegalStateException("签名返回空");
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("X-")) continue;
            int i = s.indexOf(':');
            if (i <= 0) i = s.indexOf(' ');
            if (i <= 0) continue;
            String k = s.substring(0, i).trim();
            String v = s.substring(i + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        if (out.isEmpty()) throw new IllegalStateException("签名结果无法解析: "
                + (raw.length() > 120 ? raw.substring(0, 120) : raw));
        return out;
    }
}
