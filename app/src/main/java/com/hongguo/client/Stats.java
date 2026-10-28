package com.hongguo.client;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * 自检打点：冷启动/搜索到底慢在哪一步、缓存目录到底占了多少。
 * 数据只在内存里，给「诊断」弹窗展示，方便真机上直接看数（不用 adb）。
 */
public final class Stats {

    private static final long T0 = SystemClock.elapsedRealtime();

    /** 榜单每一轮 HTTP 的耗时（首屏是串行 6~8 轮，这里能看出每轮是不是都在重建连接）。 */
    private static final List<Long> ROUNDS = new ArrayList<>();
    /** 搜索每一页的耗时与重试次数。 */
    private static final List<String> PAGES = new ArrayList<>();
    /** 首屏从进程启动到内容可见的总耗时，-1 表示还没出来。 */
    private static long firstPaintMs = -1;
    /** 上一次搜索从发起到首屏结果可见的耗时。 */
    private static long searchFirstMs = -1;
    private static long searchStartMs = 0;
    private static String lastNote = "";

    private Stats() {
    }

    public static long bootMs() {
        return SystemClock.elapsedRealtime() - T0;
    }

    public static void round(long ms) {
        synchronized (ROUNDS) {
            ROUNDS.add(ms);
        }
    }

    public static void clearRounds() {
        synchronized (ROUNDS) {
            ROUNDS.clear();
        }
    }

    public static void firstPaint() {
        if (firstPaintMs < 0) firstPaintMs = SystemClock.elapsedRealtime() - T0;
    }

    public static void searchStart() {
        searchStartMs = SystemClock.elapsedRealtime();
        synchronized (PAGES) {
            PAGES.clear();
        }
    }

    public static void page(String desc, long ms) {
        synchronized (PAGES) {
            PAGES.add(desc + " " + ms + "ms");
        }
    }

    public static void searchFirstResult() {
        if (searchStartMs > 0 && searchFirstMs < 0) {
            searchFirstMs = SystemClock.elapsedRealtime() - searchStartMs;
        }
    }

    public static void note(String s) {
        lastNote = s;
    }

    /** 诊断弹窗用的整段文本。 */
    public static String report(android.content.Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("首屏\n");
        sb.append("  进程启动至今 ").append(bootMs()).append("ms\n");
        sb.append("  内容首次可见 ").append(firstPaintMs < 0 ? "（还没出来）" : firstPaintMs + "ms").append('\n');
        synchronized (ROUNDS) {
            if (!ROUNDS.isEmpty()) {
                sb.append("  榜单 ").append(ROUNDS.size()).append(" 轮，每轮耗时：");
                for (int i = 0; i < ROUNDS.size(); i++) {
                    sb.append(ROUNDS.get(i)).append(i == ROUNDS.size() - 1 ? "ms" : " / ");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("搜索\n");
        sb.append("  首次出结果 ").append(searchFirstMs < 0 ? "（本次会话还没搜）" : searchFirstMs + "ms").append('\n');
        synchronized (PAGES) {
            for (String p : PAGES) sb.append("  ").append(p).append('\n');
        }
        sb.append('\n');

        java.io.File dir = ctx.getCacheDir();
        long total = Cache.size(dir);
        long cap = Cache.limit(dir);
        sb.append("缓存（内部 cache 目录）\n");
        sb.append("  占用 ").append(Cache.fmt(total)).append("，上限 ").append(Cache.fmt(cap)).append('\n');
        sb.append("  文件数 ").append(Cache.list(dir).size()).append('\n');
        for (java.io.File f : Cache.top(dir, 3)) {
            sb.append("  最大：").append(f.getName()).append(" ").append(Cache.fmt(f.length())).append('\n');
        }
        sb.append("  可用空间 ").append(Cache.fmt(dir.getUsableSpace())).append('\n');
        if (!lastNote.isEmpty()) sb.append('\n').append(lastNote).append('\n');
        return sb.toString();
    }
}
