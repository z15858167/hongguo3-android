import com.hongguo.client.cenc.Boxes;
import com.hongguo.client.cenc.Decryptor;
import com.hongguo.client.cenc.Moov;
import com.hongguo.client.cenc.Ranger;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Random;

/**
 * 本地单测（纯 JDK，不需要 Android / 真机）：
 * 拿一份真实密文 mp4，用同一把 key 分别做
 *   A. 整文件解密（Decryptor）
 *   B. 随机区间解密（Ranger，流式播放走的就是这条路）
 * 然后逐字节比对：只要区间解密的 IV / rel / 块号算法正确，两条路必须完全一致。
 *
 * 用法: java RangerTest <密文文件> [keyHex]
 *   keyHex 省略时用固定测试 key —— 校验的是"两条路是否一致"，与 key 对不对无关。
 */
public class RangerTest {

    private static final int HEAD = 256 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: java RangerTest <enc.mp4> [keyHex]");
            System.exit(2);
        }
        File src = new File(args[0]);
        String keyHex = args.length > 1 ? args[1] : "00112233445566778899aabbccddeeff";
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) Integer.parseInt(keyHex.substring(i * 2, i * 2 + 2), 16);
        }

        // ---- A. 整文件解密 ----
        File out = File.createTempFile("plain", ".mp4");
        Decryptor.Stats st;
        try {
            st = Decryptor.decrypt(src, out, keyHex, "full");
        } catch (Exception e) {
            System.out.println("✗ 整文件解密失败: " + e);
            System.exit(1);
            return;
        }
        byte[] plain = readAll(out);
        System.out.println("整文件解密: " + plain.length + " 字节, " + st);

        // ---- B. 只从头 256KB 解析 moov（流式播放就是这么干的）----
        byte[] head = new byte[(int) Math.min(HEAD, src.length())];
        try (RandomAccessFile rf = new RandomAccessFile(src, "r")) {
            rf.readFully(head);
        }
        Moov.Info info = Moov.parse(head, "full");
        System.out.println("头部 256KB 解析 moov: tracks=" + info.tracks.size()
                + " patches=" + info.patches.size() + " moovEnd=" + info.moovEnd);
        Ranger ranger = new Ranger(info, key);

        // ---- 逐区间比对 ----
        Random rnd = new Random(12345);
        long total = src.length();
        long[][] ranges = new long[][]{
                {0, 4096},
                {info.moovEnd - 64, 4096},
                {total / 3, 65536},
                {total / 2, 1},
                {total - 8192, 8192},
        };
        int n = ranges.length + 40;
        long[][] all = new long[n][];
        System.arraycopy(ranges, 0, all, 0, ranges.length);
        for (int i = ranges.length; i < n; i++) {
            long start = (long) (rnd.nextDouble() * (total - 200000));
            int len = 1 + rnd.nextInt(200000);
            all[i] = new long[]{start, Math.min(len, (int) (total - start))};
        }

        int bad = 0;
        try (RandomAccessFile rf = new RandomAccessFile(src, "r")) {
            for (long[] rg : all) {
                long start = rg[0];
                int len = (int) rg[1];
                if (start < 0 || len <= 0 || start + len > total) continue;
                byte[] buf = new byte[len];
                rf.seek(start);
                rf.readFully(buf);
                ranger.applyPatches(buf, 0, start, len);
                ranger.decryptRange(buf, 0, start, len);
                int diff = -1;
                for (int i = 0; i < len; i++) {
                    if (buf[i] != plain[(int) start + i]) {
                        diff = i;
                        break;
                    }
                }
                if (diff >= 0) {
                    bad++;
                    System.out.println("✗ 区间 [" + start + ", +" + len + ") 第 " + diff
                            + " 字节不一致: 流式=0x"
                            + Integer.toHexString(buf[diff] & 0xff)
                            + " 整解=0x" + Integer.toHexString(plain[(int) start + diff] & 0xff));
                }
            }
        }
        System.out.println(bad == 0
                ? "✓ 全部 " + all.length + " 个区间与整文件解密逐字节一致"
                : "✗ " + bad + "/" + all.length + " 个区间不一致");
        out.delete();
        System.exit(bad == 0 ? 0 : 1);
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (RandomAccessFile rf = new RandomAccessFile(f, "r")) {
            rf.readFully(b);
        }
        return b;
    }
}
