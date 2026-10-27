package com.hongguo.client.cenc;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128-CTR 分段解密（CENC 语义）。对应 Python core/ctr.py。
 *
 * IV 是 128 位：高 64 位 = base_iv + 样本序号（由调用方算好传 ivHi），低 64 位 = 块号。
 * CTR 支持随机访问，所以可以只解样本中间的一段（rel>0）。
 */
public final class Ctr {

    private final Cipher ecb;
    private final byte[] ctr = new byte[16];
    private final byte[] ks = new byte[16];

    public Ctr(byte[] key16) throws GeneralSecurityException {
        ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));
    }

    /**
     * 原地解密 buf[off, off+len)。
     *
     * @param ivHi 该样本 IV 的高 64 位（base_iv + 样本序号）
     * @param rel  这一段在样本内的偏移
     */
    public void decrypt(long ivHi, byte[] buf, int off, int len, int rel) throws GeneralSecurityException {
        if (len <= 0) return;
        /*
         * 注意：块号必须用 long 参与移位。
         * Java 对 int 的移位距离会取模 32，`blk >>> 32`（int）等于 `blk >>> 0`，
         * 也就是 blk 本身 —— 那样 high 会被多加一个 blk，IV 直接错。
         * 整文件解密（rel=0，blk=0）恰好躲过这个坑，只有按区间解密（rel>=16）才会暴露。
         */
        long blk = (long) rel / 16;
        int skip = rel % 16;
        long low = blk & 0xffffffffL;
        long high = (ivHi + (blk >>> 32)) & 0xffffffffffffffffL;

        int p = off;
        int remaining = len;
        while (remaining > 0) {
            putLongBE(ctr, 0, high);
            putLongBE(ctr, 8, low);
            ecb.doFinal(ctr, 0, 16, ks, 0);
            int n = Math.min(16 - skip, remaining);
            for (int i = 0; i < n; i++) buf[p + i] ^= ks[skip + i];
            p += n;
            remaining -= n;
            skip = 0;
            low = (low + 1) & 0xffffffffffffffffL;
            if (low == 0L) high = (high + 1) & 0xffffffffffffffffL;
        }
    }

    private static void putLongBE(byte[] b, int o, long v) {
        b[o] = (byte) (v >>> 56);
        b[o + 1] = (byte) (v >>> 48);
        b[o + 2] = (byte) (v >>> 40);
        b[o + 3] = (byte) (v >>> 32);
        b[o + 4] = (byte) (v >>> 24);
        b[o + 5] = (byte) (v >>> 16);
        b[o + 6] = (byte) (v >>> 8);
        b[o + 7] = (byte) v;
    }

    /** 十六进制 32 字符 -> 16 字节。 */
    public static byte[] keyFromHex(String hex) {
        if (hex == null || hex.length() != 32) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
