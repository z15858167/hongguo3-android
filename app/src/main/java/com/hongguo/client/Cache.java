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
 * 水位模型（一集短剧 80~200MB，下面的数值按"1GB 缓存区"设计）：
 *
 *   0                                        824MB        1024MB
 *   |------------------------------------------|------------|
 *                    正常使用区                  触发线        硬上限 CAP
 *
 *   - CAP          = 1GB   缓存区硬上限，任何情况下占用不会超过它
 *   - KEEP_FREE    = 200MB 常态应保持的空闲额度；占用达到 (CAP-KEEP_FREE)=824MB 就触发清理，
 *                          而不是等真正写满 1GB 才动手（写满再删会让"看到一半的集"被误删）
 *   - 清理量       = 当前占用的一半（不是只删一个文件），清完立刻回到 ~400~500MB 空闲，
 *                          避免"每次只删一点点 → 每集都触发一次淘汰"的抖动
 *   - HARD_FREE    = 150MB 设备级硬底线：分区可用空间低于它时继续清（哪怕缓存区未满）
 *
 * 淘汰顺序：最旧的先删；同年龄先删 .enc（密文重下即可），.mp4 后删。
 * 正在播放/正在写的那一个文件会被 pin 住，任何清理都不会删它。
 */
public final class Cache {

    /** 缓存区硬上限：1GB。 */
    public static final long CAP = 1024L * 1024 * 1024;
    /** 常态应保持的空闲额度：200MB（触发线 = CAP - 200MB = 824MB）。 */
    public static final long KEEP_FREE = 200L * 1024 * 1024;
    /** 设备可用空间的硬底线：150MB（低于它就继续清，不管缓存区满没满）。 */
    public static final long HARD_FREE = 150L * 1024 * 1024;
    /** 给系统/其它 App 预留的设备空间：300MB。 */
    public static final long DEVICE_RESERVE = 300L * 1024 * 1024;
    /** 触发后一次性清掉的比例：一半。 */
    public static final double TRIM_RATIO = 0.5;
    /** 设备再挤也保留的最小额度（保证当前这一集能落盘、能拖动）。 */
    public static final long MIN_CAP = 128L * 1024 * 1024;

    /** 正在播放/正在写的文件名，清理时跳过。 */
    private static volatile String pinName;

    private Cache() {
    }

    /** 保护当前文件不被任何清理删掉（换集时重新 pin 新的）。 */
    public static void pin(File f) {
        pinName = (f == null ? null : f.getName());
    }

    public static void unpin() {
        pinName = null;
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

    /**
     * 有效上限：1GB，但设备本身可用空间不足时会收紧（给系统留 DEVICE_RESERVE）。
     * 例：设备剩 5GB → 1GB；剩 800MB → 500MB；剩 200MB → 128MB（只够当前集）。
     */
    public static long limit(File dir) {
        if (dir == null) return CAP;
        long usable = dir.getUsableSpace();
        if (usable <= 0) return CAP;
        return Math.min(CAP, Math.max(MIN_CAP, usable - DEVICE_RESERVE));
    }

    /** 触发线：占用达到这个值就开始清理（= 上限 - 应保持的空闲）。 */
    public static long trigger(File dir) {
        return Math.max(0, limit(dir) - KEEP_FREE);
    }

    /** 缓存区当前的空闲额度（上限 - 已用）。 */
    public static long free(File dir) {
        return Math.max(0, limit(dir) - size(dir));
    }

    /**
     * 主入口：确保还能再写 need 字节，且写完仍留有 KEEP_FREE 空闲。
     * 触发时一次清掉当前占用的一半（设备空间紧张时按 HARD_FREE 追加清理）。
     * 返回实际释放的字节数（没触发则为 0）。
     */
    public static long ensure(File dir, long need) {
        if (dir == null) return 0;
        long total = size(dir);
        if (total <= 0) return 0;
        long cap = limit(dir);
        long usable = dir.getUsableSpace();

        boolean overQuota = total + need > cap - KEEP_FREE;
        boolean deviceTight = usable > 0 && usable - need < HARD_FREE;
        if (!overQuota && !deviceTight) return 0;

        // 常规目标：清掉一半；若这一集本身很大，则清到"放下它之后仍留 200MB"
        long target = Math.min((long) (total * TRIM_RATIO), Math.max(0, cap - KEEP_FREE - need));
        // 设备空间见底：再多清出 (HARD_FREE - 可用 + need)，保证分区不至于被写死
        if (deviceTight) {
            long extra = HARD_FREE - usable + need;
            target = Math.min(target, Math.max(0, total - extra));
        }
        return trimTo(dir, target, total);
    }

    /** 只按上限收一次（启动时 / 离开播放页用），不预留额外空间。 */
    public static long trim(File dir) {
        return ensure(dir, 0);
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

    // ---------------- 内部 ----------------

    private static long trimTo(File dir, long target, long total) {
        List<File> fs = list(dir);
        if (fs.isEmpty() || total <= target) return 0;

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
            if (total <= target) break;
            String n = f.getName();
            if (pinName != null && pinName.equals(n)) continue;      // 正在播的这集不删
            if (!(n.endsWith(".enc") || n.endsWith(".mp4") || n.endsWith(".part"))) continue;
            long sz = f.length();
            if (f.delete()) {
                freed += sz;
                total -= sz;
            }
        }
        return freed;
    }

    /** 供诊断页展示的一句话水位说明。 */
    public static String line(File dir) {
        long cap = limit(dir);
        long used = size(dir);
        return "已用 " + fmt(used) + " / 上限 " + fmt(cap)
                + "（空闲 " + fmt(Math.max(0, cap - used))
                + "，达到 " + fmt(Math.max(0, cap - KEEP_FREE)) + " 自动清一半）";
    }
}
