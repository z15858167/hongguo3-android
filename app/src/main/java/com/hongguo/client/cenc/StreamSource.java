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
 * 而不是等整集下完。播过的集密文留在缓存里，重看/回拖几乎是零等待。
 */
public final class StreamSource implements DataSource {

    private static final String TAG = "StreamSource";
    private static final int HEAD = 256 * 1024;
    private static final int WAIT_TIMEOUT_MS = 30_000;

    public interface Progress {
        /** downloaded 已下载的密文字节数，total 总大小（未知为 -1），done 是否下完。 */
        void on(long downloaded, long total, boolean done);
    }

    private final String url;
    private final byte[] key;          // null = 明文源，只打补丁不解密
    private final File cache;
    private final String mode;
    private final Progress prog;

    private long total = -1;
    private Moov.Info info;
    private Ranger ranger;
    private RandomAccessFile raf;

    /** 缓存文件（getCacheDir 下的 .enc），供播放页退出时精确清理。 */
    public File cacheFile() {
        return cache;
    }

    private volatile long downloaded;
    private volatile boolean finished;
    private volatile boolean failed;
    private final AtomicBoolean stop = new AtomicBoolean();
    private Thread worker;

    private long pos;
    private boolean prepared;

    public StreamSource(String url, String keyHex, File cache, String mode, Progress prog) {
        this.url = url;
        this.key = keyHex == null ? null : Ctr.keyFromHex(keyHex);
        this.cache = cache;
        this.mode = mode == null ? "full" : mode;
        this.prog = prog;
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
        int n = (int) Math.min(length, downloaded - pos);
        if (n <= 0) return 0;

        raf.seek(pos);
        raf.readFully(buffer, offset, n);

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
        if (worker != null) {
            worker.interrupt();
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

        // 3) 头部写入缓存（等长替换，密文偏移 = 明文偏移，直接落盘即可）
        if (!cache.exists() || cache.length() < total) {
            // 整集预分配前先腾地方：按这一集的真实大小预留，触发就清掉一半
            Cache.ensure(cache.getParentFile(), total);
            try (RandomAccessFile out = new RandomAccessFile(cache, "rw")) {
                out.setLength(total);
                out.seek(0);
                out.write(head, 0, Math.min(head.length, (int) total));
            }
            downloaded = Math.min(head.length, (int) total);
            if (downloaded < total) startWorker(downloaded);
        } else {
            downloaded = cache.length();
            finished = downloaded >= total;
            if (prog != null) prog.on(downloaded, total, finished);
        }
        Log.i(TAG, "prepare ok: total=" + total + " cached=" + downloaded
                + " tracks=" + info.tracks.size() + " patches=" + info.patches.size());
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
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
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
        worker = new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Range", "bytes=" + from + "-");
                c.connect();
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                long p = from;
                try (InputStream in = c.getInputStream();
                     RandomAccessFile out = new RandomAccessFile(cache, "rw")) {
                    out.seek(from);
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while (!stop.get() && (n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        p += n;
                        downloaded = p;
                        if (prog != null) prog.on(p, total, false);
                    }
                }
                downloaded = p;
                if (p >= total || total < 0) finished = true;
                if (prog != null) prog.on(downloaded, total, finished);
            } catch (Throwable t) {
                if (!stop.get()) {
                    failed = true;
                    Log.w(TAG, "下载线程结束: " + t);
                }
            } finally {
                if (c != null) c.disconnect();
            }
        }, "hg-stream");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean waitFor(long need) {
        long t0 = System.currentTimeMillis();
        while (downloaded < need) {
            if (finished || stop.get()) return downloaded >= need;
            if (System.currentTimeMillis() - t0 > WAIT_TIMEOUT_MS) return downloaded >= need;
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // ---------------- 索引 / 补丁 / 解密 ----------------
}
