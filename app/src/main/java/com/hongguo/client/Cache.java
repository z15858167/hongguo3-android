package com.hongguo.client;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 播放缓存管理。所有缓存都在内部 getCacheDir()：
 *   <vid>.<清晰度>.enc   流式播放的密文（StreamSource 边下边解，会预分配到整集大小）
 *   <vid>.<清晰度>.mp4   不支持边下边播时整集解密出的明文（Decryptor）
 *
 * 之前这两个文件只写不删（全项目唯一 delete 只清兜底路径的 .enc），
 * 结果就是"看几集后应用占用空间明显变大"。这里补上：
 *   - trim()：按最后修改时间从旧到新淘汰，直到总量低于水位（先删 .enc，再删 .mp4）
 *   - clear()：一键清空
 * 上限默认 512MB，同时用分区可用空间兜底（可用 < 5% 时按 5% 算，避免写满磁盘）。
 */
public final class Cache {

    /** 缓存总上限：512MB。 */
    public static final long MAX_BYTES = 512L * 1024 * 1024;

    private Cache() {
    }

    public static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile()) out.add(f);
        }
        return out;
    }

    public static long size(File dir) {
        long n = 0;
        for (File f : list(dir)) n += f.length();
        return n;
    }

    /** 按分区可用空间修正后的实际上限（可用空间紧张时收紧）。 */
    public static long limit(File dir) {
        long usable = dir == null ? 0 : dir.getUsableSpace();
        if (usable <= 0) return MAX_BYTES;
        long cap = Math.max(64L * 1024 * 1024, usable / 20);   // 不超过可用空间的 5%
        return Math.min(MAX_BYTES, cap);
    }

    /**
     * 淘汰到总量低于 maxBytes。返回释放的字节数。
     * 顺序：最旧的先删；同年龄时先删 .enc（重下即可），.mp4 后删。
     */
    public static long trim(File dir, long maxBytes) {
        List<File> fs = list(dir);
        if (fs.isEmpty()) return 0;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= maxBytes) return 0;

        Collections.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int c = Long.compare(a.lastModified(), b.lastModified());
                if (c != 0) return c;
                return rank(a) - rank(b);
            }

            private int rank(File f) {
                return f.getName().endsWith(".mp4") ? 1 : 0;   // .enc 优先被淘汰
            }
        });

        long freed = 0;
        for (File f : fs) {
            if (total <= maxBytes) break;
            long n = f.length();
            if (f.delete()) {
                freed += n;
                total -= n;
            }
        }
        return freed;
    }

    /** 清空整个缓存目录，返回释放的字节数。 */
    public static long clear(File dir) {
        long freed = 0;
        for (File f : list(dir)) {
            long n = f.length();
            if (f.delete()) freed += n;
        }
        return freed;
    }

    /** 命中缓存时刷新修改时间，让 LRU 知道这集刚用过。 */
    public static void touch(File f) {
        if (f == null || !f.exists()) return;
        f.setLastModified(System.currentTimeMillis());
    }

    public static String fmt(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.2f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    /** 占用最大的前 n 个文件，诊断页展示用。 */
    public static List<File> top(File dir, int n) {
        List<File> fs = list(dir);
        Collections.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.length(), a.length());
            }
        });
        return new ArrayList<>(fs.subList(0, Math.min(n, fs.size())));
    }

    /** trim 时的文件清单（调试用）。 */
    static String dump(File dir) {
        StringBuilder sb = new StringBuilder();
        List<File> fs = list(dir);
        Collections.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : fs) sb.append(f.getName()).append(" ").append(fmt(f.length())).append('\n');
        return sb.toString();
    }

    /** 保持所有文件都在数组里（避免误删目录本身）。 */
    static boolean isCacheName(File f) {
        String n = f.getName();
        return n.endsWith(".enc") || n.endsWith(".mp4") || n.endsWith(".part");
    }

    /** 只清理本 App 认识的缓存文件名，避免误删同目录下的其它东西。 */
    public static long trimKnown(File dir, long maxBytes) {
        List<File> all = list(dir);
        List<File> known = new ArrayList<>();
        long total = 0;
        for (File f : all) {
            total += f.length();
            if (isCacheName(f)) known.add(f);
        }
        if (total <= maxBytes) return 0;
        return trimList(known, total, maxBytes);
    }

    private static long trimList(List<File> fs, long total, long maxBytes) {
        Collections.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int c = Long.compare(a.lastModified(), b.lastModified());
                if (c != 0) return c;
                return (a.getName().endsWith(".mp4") ? 1 : 0)
                        - (b.getName().endsWith(".mp4") ? 1 : 0);
            }
        });
        long freed = 0;
        for (File f : fs) {
            if (total <= maxBytes) break;
            long n = f.length();
            if (f.delete()) {
                freed += n;
                total -= n;
            }
        }
        return freed;
    }

}
