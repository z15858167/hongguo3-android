package com.hongguo.client.cenc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MP4 box 遍历。对应 Python core/boxes.py。
 * 只在内存 buffer 上工作，不依赖文件。
 */
public final class Boxes {

    public static final class Box {
        public final String type;
        public final int start;
        public final int size;
        public final int hdr;

        Box(String type, int start, int size, int hdr) {
            this.type = type;
            this.start = start;
            this.size = size;
            this.hdr = hdr;
        }

        public int contentStart() {
            return start + hdr;
        }

        public int end() {
            return start + size;
        }
    }

    private static final Set<String> FULL_CONTAINERS = new HashSet<>(Arrays.asList(
            "moov", "trak", "mdia", "minf", "stbl", "dinf", "edts",
            "mvex", "moof", "traf", "sinf", "schi"));

    private static final Set<String> VISUAL_ENTRIES = new HashSet<>(Arrays.asList(
            "encv", "hvc1", "hev1", "avc1", "avc3"));

    private static final Set<String> AUDIO_ENTRIES = new HashSet<>(Arrays.asList(
            "enca", "mp4a", "ac-3", "ec-3"));

    private static final int VISUAL_EXTRA = 78;
    private static final int AUDIO_EXTRA = 36;

    private Boxes() {
    }

    public static int u32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    public static long u64(byte[] b, int o) {
        return ((u32(b, o) & 0xffffffffL) << 32) | (u32(b, o + 4) & 0xffffffffL);
    }

    public static String fourcc(byte[] b, int o) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) (b[o + i] & 0xff);
        return new String(c);
    }

    private static String hex(byte[] b, int o, int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            int v = b[o + i] & 0xff;
            sb.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 0xf, 16));
        }
        return sb.toString();
    }

    /** 平铺遍历 [start,end)，allowTruncated=true 时末尾越界的 box 也会返回。 */
    public static List<Box> iter(byte[] b, int start, int end, boolean allowTruncated) {
        List<Box> out = new ArrayList<>();
        if (end > b.length) end = b.length;
        int o = start;
        while (o + 8 <= end) {
            int size = u32(b, o);
            String type = fourcc(b, o + 4);
            int hs = 8;
            if (size == 1) {
                if (o + 16 > end) return out;
                long big = u64(b, o + 8);
                if (big > Integer.MAX_VALUE) return out;
                size = (int) big;
                hs = 16;
            } else if (size == 0) {
                size = end - o;
            }
            if (size < 8 || o + size > end) {
                if (allowTruncated && size >= 8) out.add(new Box(type, o, size, hs));
                return out;
            }
            out.add(new Box(type, o, size, hs));
            o += size;
        }
        return out;
    }

    public static List<Box> iter(byte[] b, int start, int end) {
        return iter(b, start, end, false);
    }

    /** 递归遍历（含 stsd / sample entry 内部的非 box 前缀）。 */
    public static void walk(byte[] b, int start, int end, List<Box> out) {
        if (end > b.length) end = b.length;
        for (Box bx : iter(b, start, end, false)) {
            out.add(bx);
            int cend = bx.end();
            if (FULL_CONTAINERS.contains(bx.type)) {
                walk(b, bx.contentStart(), cend, out);
            } else if ("stsd".equals(bx.type)) {
                walk(b, bx.contentStart() + 8, cend, out);
            } else if (VISUAL_ENTRIES.contains(bx.type)) {
                if (bx.start + VISUAL_EXTRA <= cend) walk(b, bx.start + VISUAL_EXTRA, cend, out);
            } else if (AUDIO_ENTRIES.contains(bx.type)) {
                if (bx.start + AUDIO_EXTRA <= cend) walk(b, bx.start + AUDIO_EXTRA, cend, out);
            }
        }
    }

    public static List<Box> walk(byte[] b, int start, int end) {
        List<Box> out = new ArrayList<>();
        walk(b, start, end, out);
        return out;
    }

    /** 按路径找 box，返回 {contentStart, boxEnd}；找不到返回 null。 */
    public static int[] find(byte[] b, String[] path, int start, int end) {
        if (end > b.length) end = b.length;
        for (Box bx : iter(b, start, end, false)) {
            if (!path[0].equals(bx.type)) continue;
            if (path.length == 1) return new int[]{bx.contentStart(), bx.end()};
            String[] rest = Arrays.copyOfRange(path, 1, path.length);
            int[] r = find(b, rest, bx.contentStart(), bx.end());
            if (r != null) return r;
        }
        return null;
    }

    public static int[] find(byte[] b, String[] path) {
        return find(b, path, 0, b.length);
    }

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }
}
