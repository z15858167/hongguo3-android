package com.hongguo.client.cenc;

import android.util.Base64;

/**
 * spade_a -> content key 的纯算法解包。对应 Python frida/unwrap_spade.py 的 unwrap_v1。
 *
 * 已确认是**纯字节变换**（XOR + POPCOUNT + 位置相关），没有 KEK、没有 AES，
 * 所以真机上不需要任何 native/模拟器，几行 Java 就够。
 */
public final class Spade {

    private Spade() {
    }

    private static int popcount(int x) {
        return Integer.bitCount(x & 0xffffffff);
    }

    private static int s8(int v) {
        v &= 0xff;
        return v >= 128 ? v - 256 : v;
    }

    /** 37 字节密文 -> 32 字符 hex key；解不开返回 null（可能是明文源或 v2 算法）。 */
    public static String unwrapV1(byte[] spade, int flag) {
        int L = spade.length;
        if (L < 3) return null;
        int bVar5 = (spade[0] & 0xff) ^ (spade[1] & 0xff) ^ (spade[2] & 0xff);
        int iVar9 = bVar5 - 0x30;
        if (iVar9 < 1) return null;
        int uVar1 = (L - bVar5) + 0x2f;
        if (uVar1 < 1 || 1 + uVar1 > L) return null;

        byte[] dest = new byte[uVar1];
        System.arraycopy(spade, 1, dest, 0, uVar1);

        int b16 = spade[L - iVar9 - 2] & 0xff;
        int b14 = spade[L - iVar9 - 1] & 0xff;
        byte[] s1 = new byte[iVar9];
        for (int i = 0; i < iVar9; i++) {
            s1[i] = (byte) (b14 ^ b16 ^ (spade[i + (L - iVar9)] & 0xff));
        }
        String kind = new String(s1, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (kind.startsWith("app_v2") || kind.startsWith("web_v2")) return null;

        b14 = 0x55;
        b16 = 0xfa;
        for (int i = 0; i < uVar1; i++) {
            int b6 = dest[i] & 0xff;
            int u18 = popcount(i);
            int b3 = b6;
            int b7 = b14;
            int nb16 = b16;
            if ((i & 1) != 0) {
                b3 = b16;
                b7 = b6;
                nb16 = b14;
            }
            int cVar4 = flag != 0 ? (u18 + 0x15) : s8(-0x15 - u18);
            dest[i] = (byte) ((cVar4 + (nb16 ^ b6)) & 0xff);
            b14 = b7;
            b16 = b3;
        }

        int b0 = dest[0] & 0xff;
        int u11;
        if (b0 >= 0x30 && b0 <= 0x39) {
            u11 = b0 - 0x30;
        } else if (b0 >= 0x61 && b0 <= 0x7a) {
            u11 = b0 - 0x57;
        } else {
            return null;
        }
        int iv9 = uVar1 - (u11 & 0xff);
        if (iv9 < 2) return null;
        byte[] keyBytes = new byte[iv9 - 1];
        System.arraycopy(dest, 1, keyBytes, 0, iv9 - 1);
        return new String(keyBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** video_model 里的 spadeA（base64）-> 32 字符 hex key。 */
    public static String spadeToKey(String spadeB64, int flag) {
        if (spadeB64 == null || spadeB64.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(spadeB64, Base64.DEFAULT);
            String k = unwrapV1(raw, flag);
            return (k != null && k.length() == 32) ? k : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 依次试 flag=0/1，两个都失败返回 null。 */
    public static String spadeToKey(String spadeB64) {
        String k = spadeToKey(spadeB64, 0);
        if (k == null) k = spadeToKey(spadeB64, 1);
        return k;
    }
}
