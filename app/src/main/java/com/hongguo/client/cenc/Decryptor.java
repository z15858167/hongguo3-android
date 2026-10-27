package com.hongguo.client.cenc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 整集解密：密文 mp4 -> 明文 mp4（本地文件）。
 *
 * 手机端和桌面端的差别就在这里：桌面要"虚拟明文 MP4 + Range 服务"是为了骗过浏览器 <video>，
 * 手机上不需要这一层 —— 直接解密出一个明文文件，交给 ExoPlayer 播就行。
 */
public final class Decryptor {

    public static final class Stats {
        public int patches;
        public int videoSamples;
        public int audioSamples;
        public boolean encrypted;
        public int moovEnd;

        @Override
        public String toString() {
            return "patches=" + patches + " video=" + videoSamples + " audio=" + audioSamples
                    + " enc=" + encrypted;
        }
    }

    private Decryptor() {
    }

    private static byte[] readAll(File f) throws IOException {
        long n = f.length();
        if (n <= 0 || n > Integer.MAX_VALUE) throw new IOException("文件大小异常: " + n);
        byte[] buf = new byte[(int) n];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            if (off != buf.length) throw new IOException("读取不完整 " + off + "/" + buf.length);
        }
        return buf;
    }

    /**
     * @param in       密文（上游原片）
     * @param out      解密后的明文
     * @param keyHex   32 字符 hex content key；为空表示明文源，直接复制
     * @param mode     min / mid / full（补丁强度）
     */
    public static Stats decrypt(File in, File out, String keyHex, String mode) throws Exception {
        byte[] data = readAll(in);
        Stats st = new Stats();

        byte[] key = keyHex == null ? null : Ctr.keyFromHex(keyHex);
        if (key == null) {
            try (FileOutputStream fo = new FileOutputStream(out)) {
                fo.write(data);
            }
            return st;
        }

        Moov.Info info = Moov.parse(data, mode);
        st.moovEnd = info.moovEnd;

        for (Moov.Patch p : info.patches) {
            if (p.pos >= 0 && p.pos + 4 <= data.length) {
                for (int i = 0; i < 4; i++) data[p.pos + i] = (byte) p.fourcc.charAt(i);
                st.patches++;
            }
        }

        Ctr ctr = new Ctr(key);
        for (Moov.Track t : info.tracks) {
            int n = Math.min(t.sizes.length, t.offs.length);
            if ("video".equals(t.kind)) st.videoSamples = n;
            if ("audio".equals(t.kind)) st.audioSamples = n;
            if (!t.encrypted) continue;
            st.encrypted = true;
            for (int i = 0; i < n; i++) {
                int off = t.offs[i];
                int len = t.sizes[i];
                if (off < 0 || len <= 0 || off + len > data.length) continue;
                ctr.decrypt(t.baseIv + i, data, off, len, 0);
            }
        }

        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(data);
        }
        return st;
    }
}
