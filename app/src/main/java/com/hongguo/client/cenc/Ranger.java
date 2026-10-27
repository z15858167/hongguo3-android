package com.hongguo.client.cenc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 区间解密器：给定文件区间 [from, from+len)，把这段密文就地变成明文。
 *
 * 独立成类是为了能在普通 JDK 上直接单测（不依赖 Android / media3），
 * StreamSource 只负责取数据，真正的解密逻辑全在这里。
 *
 * 三件事：
 *   1. 打等长补丁（encv→hvc1 等）——所以明文与密文的**文件偏移一一对应**
 *   2. 找出与该区间重叠的所有样本
 *   3. 每个样本用 AES-CTR 解密**重叠的那一段**（CENC：IV 高 64 位 = base_iv + 样本序号，
 *      低 64 位 = 块号；块号从样本起始位置算起，所以传 rel = 段起点 - 样本起点）
 */
public final class Ranger {

    private final Moov.Info info;
    private final Ctr ctr;

    private int[] pPos;
    private byte[][] pBytes;

    private final List<int[]> sOffs = new ArrayList<>();
    private final List<int[]> sSizes = new ArrayList<>();
    private final List<Long> sIv = new ArrayList<>();

    public Ranger(Moov.Info info, byte[] key16) throws Exception {
        this.info = info;
        this.ctr = key16 == null ? null : new Ctr(key16);
        buildIndex();
    }

    public Moov.Info info() {
        return info;
    }

    private void buildIndex() {
        List<Moov.Patch> ps = info.patches;
        pPos = new int[ps.size()];
        pBytes = new byte[ps.size()][];
        for (int i = 0; i < ps.size(); i++) {
            pPos[i] = ps.get(i).pos;
            pBytes[i] = ps.get(i).fourcc.getBytes(StandardCharsets.US_ASCII);
        }
        // 插入排序（补丁数量很少，够用），保证位置升序便于顺序扫描
        for (int i = 1; i < pPos.length; i++) {
            for (int j = i; j > 0 && pPos[j] < pPos[j - 1]; j--) {
                int tp = pPos[j];
                pPos[j] = pPos[j - 1];
                pPos[j - 1] = tp;
                byte[] tb = pBytes[j];
                pBytes[j] = pBytes[j - 1];
                pBytes[j - 1] = tb;
            }
        }
        for (Moov.Track t : info.tracks) {
            if (!t.encrypted || t.offs == null || t.sizes == null) continue;
            sOffs.add(t.offs);
            sSizes.add(t.sizes);
            sIv.add(t.baseIv);
        }
    }

    /** 把 buf[off, off+len)（对应文件区间 [from, from+len)）里的 fourcc 补丁打上。 */
    public void applyPatches(byte[] buf, int off, long from, int len) {
        if (pPos.length == 0) return;
        long to = from + len;
        for (int i = 0; i < pPos.length; i++) {
            int p = pPos[i];
            if (p + 4 <= from) continue;
            if (p >= to) break;
            for (int k = 0; k < 4; k++) {
                long abs = (long) p + k;
                if (abs < from || abs >= to) continue;
                buf[off + (int) (abs - from)] = pBytes[i][k];
            }
        }
    }

    /** 把 buf[off, off+len)（对应文件区间 [from, from+len)）里的加密样本解出来。 */
    public void decryptRange(byte[] buf, int off, long from, int len) throws Exception {
        if (ctr == null || len <= 0) return;
        long to = from + len;
        for (int t = 0; t < sOffs.size(); t++) {
            int[] offs = sOffs.get(t);
            int[] sizes = sSizes.get(t);
            long baseIv = sIv.get(t);
            if (offs.length == 0) continue;
            int i = floorIndex(offs, from);
            for (; i < offs.length; i++) {
                int s = offs[i];
                if (s >= to) break;
                int e = s + sizes[i];
                if (e <= from) continue;
                int a = (int) Math.max(s, from);
                int b = (int) Math.min(e, to);
                if (a >= b) continue;
                ctr.decrypt(baseIv + i, buf, off + (a - (int) from), b - a, a - s);
            }
        }
    }

    /** 最大的 i 使 offs[i] <= p（样本偏移升序）。 */
    static int floorIndex(int[] offs, long p) {
        int lo = 0, hi = offs.length - 1, idx = 0;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (offs[m] <= p) {
                idx = m;
                lo = m + 1;
            } else {
                hi = m - 1;
            }
        }
        return idx;
    }
}
