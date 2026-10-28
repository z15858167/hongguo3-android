package com.hongguo.client.cenc;

import android.util.Log;

import com.hongguo.client.Cache;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 边下边解的数据源：ExoPlayer 要哪个区间，就现场把那一段密文解密成明文给它。
 *
 * 之所以能做到"实时"而不是"整集下载完再解"：
 *   1. 源片是**非分片 MP4，moov 在文件头**（已实测），所以只需先取 256 KB 头就能拿到
 *      样本表（每个样本的偏移/长度）和 base_iv；
 *   2. AES-CTR 支持随机访问（Ctr.decrypt 带 rel 参数），样本中间任意一段都能单独解；
 *   3. 补丁（encv→hvc1 等）是等长替换，所以明文和密文的**文件偏移一一对应**，
 *      请求位置不用做任何换算。
 *
 * 于是：一个后台线程顺序下载密文到缓存文件，前台读多少解多少，等多久只取决于网络速度，
 * 而不是等整集下完。
 *
 * v0.6.0 的四处关键改动（都是"切集慢/卡在黑屏"的直接原因）：
 *   - 不再 setLength(total) 预分配：旧做法让文件长度一上来就等于整集，于是"文件长度==总长"
 *     被当成已下完，读到的是全 0 → 解码失败 → 回退整集下载。现在文件长度 = 已下载字节，
 *     是否下完由 <name>.done 标记判定；
 *   - release() 直接 disconnect() 连接 + notifyAll()，能立刻打断 read()（原来 interrupt()
 *     打不断阻塞中的 socket 读）；
 *   - waitFor() 从 15ms 轮询改成条件变量 wait/notifyAll；
 *   - 新增 softLimit：预载的那一集只下到 N MB 就停，把带宽让给正在播的一集，
 *     激活时 setSoftLimit(-1) 立刻满速。
 */
public final class StreamSource implements DataSource {

    private static final String TAG = "StreamSource";
    private static final int HEAD = 256 * 1024;
    private static final int WAIT_TIMEOUT_MS = 30_000;
    /** 进度回调节流：至少 1MB 或 300ms 才回调一次（原来每 64KB 一次，UI 线程被打满）。 */
    private static final long PROG_BYTES = 1024 * 1024;
    private static final long PROG_MS = 300;

    public interface Progress {
        /** downloaded 已下载的密文字节数，total 总大小（未知为 -1），done 是否下完。 */
        void on(long downloaded, long total, boolean done);
    }

    private final String url;
    private final byte[] key;          // null = 明文源，只打补丁不解密
    private final File cache;
    private final File doneMark;
    private final String mode;
    private final Progress prog;

    private long total = -1;
    private Moov.Info info;
    private Ranger ranger;
    private RandomAccessFile raf;

    private final Object lock = new Object();
    private volatile long downloaded;
    private volatile boolean finished;
    private volatile boolean failed;
    private final AtomicBoolean stop = new AtomicBoolean();
    private Thread worker;
    private volatile HttpURLConnection conn;
    /** 预载限速：下到这么多字节就暂停（-1 = 不限速）。 */
    private volatile long softLimit = -1;
    /** 服务器不支持 Range（返回 200）时必须整段顺序下载。 */
    private boolean noRange;

    private long pos;
    private boolean prepared;

    private long lastProgBytes, lastProgMs;

    public StreamSource(String url, String keyHex, File cache, String mode, Progress prog) {
        this.url = url;
        this.key = keyHex == null ? null : Ctr.keyFromHex(keyHex);
        this.cache = cache;
        this.doneMark = new File(cache.getAbsolutePath() + ".done");
        this.mode = mode == null ? "full" : mode;
        this.prog = prog;
    }

    /** 缓存文件（getCacheDir 下的 .enc），供播放页退出时精确清理。 */
    public File cacheFile() {
        return cache;
    }

    /** 限速：绝对字节偏移，-1 = 不限速；预载用 6MB，activate 时置 -1。 */
    public void setSoftLimit(long bytes) {
        softLimit = bytes;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public boolean isFinished() {
        return finished;
    }

    // ---------------- DataSource ----------------
    @Override
    public void addTransferListener(TransferListener transferListener) {
        // 不需要统计带宽
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        if (!prepared) prepare();
        pos = dataSpec.position;
        if (raf == null) raf = new RandomAccessFile(cache, "r");
        if (total < 0) return C.LENGTH_UNSET;
        return Math.max(0L, total - pos);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (total >= 0 && pos >= total) return C.RESULT_END_OF_INPUT;

        // 等到至少 1 字节可用（下载结束仍不够就是 EOF）
        if (!waitFor(pos + 1)) {
            if (failed && downloaded <= pos) throw new IOException("下载中断");
            return C.RESULT_END_OF_INPUT;
        }
        long ready = downloaded;
        int n = (int) Math.min(length, ready - pos);
        if (n <= 0) return 0;

        RandomAccessFile f = raf;
        if (f == null) return C.RESULT_END_OF_INPUT;
        f.seek(pos);
        f.readFully(buffer, offset, n);

        try {
            ranger.applyPatches(buffer, offset, pos, n);
            ranger.decryptRange(buffer, offset, pos, n);
        } catch (Exception e) {
            throw new IOException("解密失败", e);
        }

        pos += n;
        return n;
    }

    @Override
    public android.net.Uri getUri() {
        return android.net.Uri.parse(url);
    }

    @Override
    public void close() {
        // 缓存与下载线程保留到 release()，seek 时不用重新建连
    }

    /** 停止后台下载、关闭文件句柄（切换剧集 / 退出页面时调）。 */
    public void release() {
        stop.set(true);
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();          // 只有断开连接才能真正打断阻塞的 read()
            } catch (Throwable ignored) {
            }
        }
        synchronized (lock) {
            lock.notifyAll();
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            worker = null;
        }
        try {
            if (raf != null) {
                raf.close();
                raf = null;
            }
        } catch (IOException ignored) {
        }
    }

    public long totalSize() {
        return total;
    }

    public long downloaded() {
        return downloaded;
    }

    // ---------------- 准备 ----------------
    private void prepare() throws IOException {
        prepared = true;

        // 1) 用 Range 取头部，同时拿到文件总长度
        byte[] head = fetchRange(0, HEAD - 1);
        if (head == null || head.length < 4096) throw new IOException("取文件头失败");
        if (total <= 0) throw new IOException("拿不到文件总长度（可能不支持 Range）");

        // 2) moov 在头里，直接解析样本表和补丁
        try {
            info = Moov.parse(head, mode);
        } catch (Moov.NotSupported e) {
            throw new IOException("moov 不在文件头: " + e.getMessage());
        }
        try {
            ranger = new Ranger(info, key);
        } catch (Exception e) {
            throw new IOException("初始化解密器失败", e);
        }

        long have = cache.exists() ? cache.length() : 0;
        boolean done = doneMark.exists();

        if (done && have == total) {
            // 整集已在缓存里（旧版本留下的 .done 也可能来自 fallback 路径）
            downloaded = have;
            finished = true;
            if (prog != null) prog.on(have, total, true);
        } else {
            // 没有 .done 标记 = 上次没下完。旧版本的预分配文件长度可能等于整集，
            // 无法区分"真下完"与"只写了头"，一律删掉重下（每集只会发生一次）。
            if (have > 0) {
                cache.delete();
            }
            have = 0;
            // 只按一段的量预留空间，绝不按整集预分配（预分配正是"假完整"的来源）
            Cache.ensure(cache.getParentFile(), Math.min(total, 32L * 1024 * 1024));
            try (RandomAccessFile out = new RandomAccessFile(cache, "rw")) {
                out.seek(0);
                out.write(head, 0, Math.min(head.length, (int) total));
            }
            downloaded = Math.min(head.length, (int) total);
            lastProgBytes = downloaded;
            lastProgMs = System.currentTimeMillis();
            startWorker(noRange ? 0 : downloaded);
        }
        Log.i(TAG, "prepare ok: total=" + total + " cached=" + downloaded
                + " noRange=" + noRange + " tracks=" + info.tracks.size()
                + " patches=" + info.patches.size());
    }

    private byte[] fetchRange(long start, long end) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
            c.connect();
            int code = c.getResponseCode();
            if (code == 200 && start > 0) {
                // 服务器忽略了 Range 却回了 200：整包从 0 开始，按这个偏移读就是错位数据
                throw new IOException("服务器不支持 Range");
            }
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            if (code == 200) noRange = true;
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) {
                int slash = cr.lastIndexOf('/');
                if (slash > 0) {
                    try {
                        total = Long.parseLong(cr.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (total < 0) {
                long len = c.getContentLength();
                if (len > 0) total = len;
            }
            byte[] buf = new byte[(int) (end - start + 1)];
            int off = 0;
            try (InputStream in = c.getInputStream()) {
                int n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            }
            if (off <= 0) return null;
            if (off != buf.length) {
                byte[] trimmed = new byte[off];
                System.arraycopy(buf, 0, trimmed, 0, off);
                return trimmed;
            }
            return buf;
        } finally {
            c.disconnect();
        }
    }

    private void startWorker(final long from) {
        final boolean sequential = noRange || from == 0;
        worker = new Thread(() -> {
            HttpURLConnection c = null;
            long p = sequential ? 0 : from;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                conn = c;
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept-Encoding", "identity");
                if (!noRange) c.setRequestProperty("Range", "bytes=" + from + "-");
                c.connect();
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                if (code == 200 && from > 0) throw new IOException("服务器不支持 Range");
                try (InputStream in = c.getInputStream();
                     RandomAccessFile out = new RandomAccessFile(cache, "rw")) {
                    out.seek(p);
                    if (p == 0) out.setLength(0);       // 顺序重下：清掉上次的残片
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while (!stop.get() && (n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        p += n;
                        boolean reached;
                        synchronized (lock) {
                            downloaded = p;
                            lock.notifyAll();
                            reached = softLimit >= 0 && p >= softLimit;
                        }
                        if (reached) {
                            // 预载到量就停：把带宽让给正在播的那一条，等 activate 再开
                            synchronized (lock) {
                                while (!stop.get() && softLimit >= 0 && p >= softLimit) {
                                    lock.wait(500);
                                }
                            }
                        }
                        progress(p, total, false);
                    }
                }
                downloaded = p;
                if (total >= 0 && p >= total) finishDownload();
                progress(p, total, finished);
            } catch (Throwable t) {
                if (!stop.get() && !isFinished()) {
                    failed = true;
                    Log.w(TAG, "下载线程结束: " + t);
                }
            } finally {
                if (c != null) c.disconnect();
                conn = null;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        }, "hg-stream");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishDownload() {
        finished = true;
        try {
            if (!doneMark.exists()) doneMark.createNewFile();
        } catch (IOException ignored) {
        }
    }

    private void progress(long p, long total, boolean done) {
        if (prog == null) return;
        long now = System.currentTimeMillis();
        if (!done && p - lastProgBytes < PROG_BYTES && now - lastProgMs < PROG_MS) return;
        lastProgBytes = p;
        lastProgMs = now;
        prog.on(p, total, done);
    }

    private boolean waitFor(long need) {
        long t0 = System.currentTimeMillis();
        synchronized (lock) {
            while (downloaded < need) {
                if (isFinished() || failed || stop.get()) return downloaded >= need;
                if (softLimit >= 0 && downloaded >= softLimit) {
                    // 卡在预载限速上不属于"网络卡住"：无限等，别当成流结束，
                    // 否则 ExoPlayer 会把没下完的片当成 EOF。activate 时会解锁。
                    t0 = System.currentTimeMillis();
                }
                long left = WAIT_TIMEOUT_MS - (System.currentTimeMillis() - t0);
                if (left <= 0) return downloaded >= need;
                try {
                    lock.wait(Math.min(left, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return downloaded >= need;
                }
            }
            return true;
        }
    }
}
