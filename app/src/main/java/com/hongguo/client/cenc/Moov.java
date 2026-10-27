package com.hongguo.client.cenc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从明文 moov 解析：轨道、样本表、senc base_iv、需要打的补丁。
 * 对应 Python core/moov.py。
 *
 * 前提（已实测）：源片是非分片 MP4、moov 在头；加密只作用于 mdat 里的样本；
 * senc 记录整样本 IV（无 subsample），IV = (base_iv + 样本序号) << 64。
 */
public final class Moov {

    public static class NotSupported extends Exception {
        public NotSupported(String m) {
            super(m);
        }
    }

    public static final class Track {
        public String kind;
        public int[] sizes;
        public int[] offs;
        public long baseIv;
        public boolean encrypted;
    }

    public static final class Patch {
        public final int pos;
        public final String fourcc;

        public Patch(int pos, String fourcc) {
            this.pos = pos;
            this.fourcc = fourcc;
        }
    }

    public static final class Info {
        public List<Track> tracks = new ArrayList<>();
        public List<Patch> patches = new ArrayList<>();
        public int moovEnd;
    }

    private static final Map<String, String> PATCH_FULL = new HashMap<>();
    private static final Map<String, String> PATCH_MID = new HashMap<>();
    private static final Map<String, String> PATCH_MIN = new HashMap<>();

    static {
        PATCH_FULL.put("encv", "hvc1");
        PATCH_FULL.put("enca", "mp4a");
        PATCH_FULL.put("sinf", "free");
        PATCH_FULL.put("senc", "free");
        PATCH_FULL.put("saiz", "free");
        PATCH_FULL.put("saio", "free");

        PATCH_MID.put("encv", "hvc1");
        PATCH_MID.put("enca", "mp4a");
        PATCH_MID.put("sinf", "free");

        PATCH_MIN.put("encv", "hvc1");
        PATCH_MIN.put("enca", "mp4a");
    }

    private static final Map<String, String> HANDLERS = new HashMap<>();

    static {
        HANDLERS.put("vide", "video");
        HANDLERS.put("soun", "audio");
        HANDLERS.put("text", "text");
    }

    private Moov() {
    }

    private static Map<String, String> modeMap(String mode) {
        if ("min".equals(mode)) return PATCH_MIN;
        if ("mid".equals(mode)) return PATCH_MID;
        return PATCH_FULL;
    }

    /** senc -> {baseIvHex, count, hasSubsample} */
    private static Object[] senc(byte[] buf, int stblS, int stblE) {
        for (Boxes.Box bx : Boxes.iter(buf, stblS, stblE, false)) {
            if (!"senc".equals(bx.type)) continue;
            int c = bx.contentStart();
            if (c + 12 > bx.end()) return new Object[]{null, 0, false};
            int flags = ((buf[c + 1] & 0xff) << 16) | ((buf[c + 2] & 0xff) << 8) | (buf[c + 3] & 0xff);
            int cnt = Boxes.u32(buf, c + 4);
            if (cnt <= 0) return new Object[]{null, 0, false};
            if ((flags & 2) != 0 || bx.size != 16 + cnt * 8) return new Object[]{null, cnt, true};
            long iv = 0;
            for (int i = 0; i < 8; i++) iv = (iv << 8) | (buf[c + 8 + i] & 0xff);
            return new Object[]{iv, cnt, false};
        }
        return new Object[]{null, 0, false};
    }

    private static int[][] samples(byte[] buf, int stblS, int stblE) {
        int[] stsz = Boxes.find(buf, new String[]{"stsz"}, stblS, stblE);
        if (stsz == null) return null;
        int c = stsz[0];
        int fixed = Boxes.u32(buf, c + 4);
        int cnt = Boxes.u32(buf, c + 8);
        if (cnt <= 0 || cnt > 20_000_000) return null;
        int[] sizes = new int[cnt];
        if (fixed != 0) {
            for (int i = 0; i < cnt; i++) sizes[i] = fixed;
        } else {
            for (int i = 0; i < cnt; i++) sizes[i] = Boxes.u32(buf, c + 12 + 4 * i);
        }

        int[] stco = Boxes.find(buf, new String[]{"stco"}, stblS, stblE);
        int[] co64 = Boxes.find(buf, new String[]{"co64"}, stblS, stblE);
        int[] chunks;
        if (stco != null) {
            int n = Boxes.u32(buf, stco[0] + 4);
            chunks = new int[Math.max(0, n)];
            for (int i = 0; i < n; i++) chunks[i] = Boxes.u32(buf, stco[0] + 8 + 4 * i);
        } else if (co64 != null) {
            int n = Boxes.u32(buf, co64[0] + 4);
            chunks = new int[Math.max(0, n)];
            for (int i = 0; i < n; i++) chunks[i] = (int) Boxes.u64(buf, co64[0] + 8 + 8 * i);
        } else {
            return null;
        }

        int[] stsc = Boxes.find(buf, new String[]{"stsc"}, stblS, stblE);
        if (stsc == null) return null;
        int ne = Boxes.u32(buf, stsc[0] + 4);
        int[] first = new int[ne];
        int[] spc = new int[ne];
        for (int i = 0; i < ne; i++) {
            first[i] = Boxes.u32(buf, stsc[0] + 8 + 12 * i);
            spc[i] = Boxes.u32(buf, stsc[0] + 12 + 12 * i);
        }

        int[] perChunk = new int[chunks.length];
        for (int i = 0; i < ne; i++) {
            int last = (i + 1 < ne) ? first[i + 1] - 1 : chunks.length;
            for (int ch = first[i]; ch <= last; ch++) {
                if (ch >= 1 && ch <= chunks.length) perChunk[ch - 1] = spc[i];
            }
        }

        int[] offs = new int[cnt];
        int si = 0;
        int used = 0;
        for (int chI = 0; chI < chunks.length; chI++) {
            int off = chunks[chI];
            for (int k = 0; k < perChunk[chI]; k++) {
                if (si >= cnt) break;
                if (si < offs.length) {
                    offs[si] = off;
                    used = si + 1;
                }
                off += sizes[si];
                si++;
            }
        }
        if (used < cnt) {
            int[] trimmed = new int[used];
            System.arraycopy(offs, 0, trimmed, 0, used);
            offs = trimmed;
        }
        return new int[][]{sizes, offs};
    }

    private static String handler(byte[] buf, int s, int e) {
        int[] r = Boxes.find(buf, new String[]{"mdia", "hdlr"}, s, e);
        if (r == null) return null;
        return Boxes.fourcc(buf, r[0] + 8);
    }

    public static Info parse(byte[] buf, String mode) throws NotSupported {
        int[] moov = Boxes.find(buf, new String[]{"moov"});
        if (moov == null) throw new NotSupported("找不到 moov（可能 moov 在文件尾部）");
        if (Boxes.find(buf, new String[]{"moof"}) != null) {
            throw new NotSupported("分片 MP4（存在 moof），暂不支持");
        }

        Info info = new Info();
        Map<String, String> pmap = modeMap(mode);

        for (Boxes.Box bx : Boxes.walk(buf, moov[0], moov[1])) {
            String rep = pmap.get(bx.type);
            if (rep != null) info.patches.add(new Patch(bx.start + 4, rep));
        }

        List<Boxes.Box> top = Boxes.iter(buf, 0, buf.length, false);
        int moovEnd = buf.length;
        for (Boxes.Box bx : top) {
            if ("moov".equals(bx.type)) {
                moovEnd = bx.end();
                break;
            }
        }
        info.moovEnd = moovEnd;

        for (Boxes.Box bx : Boxes.iter(buf, moov[0], moov[1], false)) {
            if (!"trak".equals(bx.type)) continue;
            int cs = bx.contentStart();
            int ce = bx.end();
            String h = handler(buf, cs, ce);
            String name = h == null ? null : HANDLERS.get(h);
            if (name == null) continue;
            int[] stbl = Boxes.find(buf, new String[]{"mdia", "minf", "stbl"}, cs, ce);
            if (stbl == null) continue;

            Object[] sc = senc(buf, stbl[0], stbl[1]);
            boolean hasSub = (Boolean) sc[2];
            if (hasSub) throw new NotSupported(name + " 轨含 subsample，暂不支持");

            int[][] so = samples(buf, stbl[0], stbl[1]);
            Track t = new Track();
            t.kind = name;
            t.sizes = so == null ? new int[0] : so[0];
            t.offs = so == null ? new int[0] : so[1];
            t.baseIv = sc[0] == null ? 0L : (Long) sc[0];
            t.encrypted = sc[0] != null;
            info.tracks.add(t);
        }

        boolean hasVideo = false;
        for (Track t : info.tracks) if ("video".equals(t.kind)) hasVideo = true;
        if (!hasVideo) throw new NotSupported("没有视频轨");
        return info;
    }
}
