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
 *   - 新增 softLimit（读窗）：预载的那一集只下到"读前沿 + N MB"就停，把带宽让给正在播的一集，
 *     激活时 setUnlimited() 立刻满速。
 *
 * v0.8.0 的关键改动（这三个是"切集卡好几秒"的真因）：
 *   - softLimit 从"绝对字节数"改成**读窗**：`softLimit = frontier + aheadBytes`，
 *     前台读到哪，后台就跟着下到"读前沿 + 16MB"。原来卡死在固定 8MB，
 *     前台读完 8MB 后再读就会无限期阻塞（waitFor 把超时计时重置了），
 *     ExoPlayer 永远进不了 READY，预载等于没做；
 *   - waitFor() 不再重置超时，也不会把"超时"当成 EOF 返回（改成抛 IOException），
 *     否则 ExoPlayer 会把没下完的片当成播完；
 *   - prepare() 支持**续传**：已经下好的半截文件不再删掉重下（原来等于把预载白做）。
 */
public final class StreamSource implements DataSource {

    private static final String TAG = "StreamSource";
    private static final int HEAD = 256 * 1024;
    /** 少于这么多字节的残片不值得比较（连 offset 0 的 4KB 都可能不完整）。 */
    private static final int RESUME_MIN = 32 * 1024;
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
    /** 下载闸门：后台最多下到这个绝对字节位置（-1 = 不限速）。 */
    private volatile long softLimit = -1;
    /** 读窗模式：true 时 softLimit 随前台读前沿自动前移。 */
    private volatile boolean aheadMode;
    /** 读窗大小：读前沿 + 这个字节数。 */
    private volatile long aheadBytes = 16L * 1024 * 1024;
    /** 前台（ExoPlayer 的 loader）已经读到的最远位置。 */
    private volatile long frontier;
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

    /**
     * 预载：开启"读窗"模式——前台读到 frontier，后台就跟着下到 frontier + bytes。
     *
     * 关键点在"跟着走"，而不是卡在某个固定字节：ExoPlayer 缓冲到 maxBuffer 就停止 read，
     * 此时 frontier 不再前进，后台自然停在 frontier+16MB；而 loader 需要的任何位置
     * 都落在窗内，所以 waitFor 不会因为限速而阻塞，预载槽能正常进 READY。
     */
    public void setAhead(long bytes) {
        aheadBytes = Math.max(0, bytes);
        aheadMode = bytes >= 0;
        synchronized (lock) {
            softLimit = aheadMode ? frontier + aheadBytes : -1;
            lock.notifyAll();
        }
    }

    /** 上台播放：解除限速，满速下载。 */
    public void setUnlimited() {
        aheadMode = false;
        synchronized (lock) {
            softLimit = -1;
            lock.notifyAll();
        }
    }

    /** 让出带宽：停在当前下载点 + 4MB（已经在缓冲里的部分别浪费）。 */
    public void setStopAhead() {
        aheadMode = false;
        synchronized (lock) {
            softLimit = downloaded + 4L * 1024 * 1024;
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

        // 等到至少 1 字节可用（下载结束仍不够才是 EOF，超时不能当 EOF）
        if (!waitFor(pos + 1)) {
            if (failed) throw new IOException("下载中断");
            if (isFinished() || (total >= 0 && pos >= total)) return C.RESULT_END_OF_INPUT;
            throw new IOException("读超时（已下 " + downloaded + " / " + total + "）");
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
        frontier = pos;
        if (aheadMode) {
            // 读窗跟着前台走：前台读多少，后台就跟到"这里 + 16MB"
            synchronized (lock) {
                softLimit = frontier + aheadBytes;
                lock.notifyAll();
            }
        }
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

    // ---------------- 准备 ----------------
    private void prepare() throws IOException {
        // 注意：不能在开头就置 prepared=true。ExoPlayer 失败后会拿同一个 DataSource
        // 再 open 一次（Factory 返回的是这个实例），早置位会让第二次直接跳过准备，
        // 拿着 info/ranger 都是 null 的状态进 read() → NPE。
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

        if (have >= total) {
            // 整集已经下满：.done 标记丢了（进程被杀）也不能当成"没下过"重下一遍
            downloaded = total;
            finished = true;
            if (!done) markDone();
            if (prog != null) prog.on(total, total, true);
        } else if (canResume(have, noRange, head)) {
            // 上次下到一半（比如预载到 16MB 就被切走）：能对得上就接着下，别作废
            Cache.ensure(cache.getParentFile(), 8L * 1024 * 1024);
            downloaded = have;
            lastProgBytes = have;
            lastProgMs = System.currentTimeMillis();
            Log.i(TAG, "续传：已有 " + have + " / " + total + " 字节");
            startWorker(have);
        } else {
            // 头不完整 / 不是同一条流 / 旧版预分配遗留 → 只能重下
            if (have > 0) {
                cache.delete();
            }
            if (done) {
                doneMark.delete();          // 孤儿 .done：标记还在但文件没了
            }
            // 只按一段的量预留空间，绝不按整集预分配（预分配正是"假完整"的来源）
            Cache.ensure(cache.getParentFile(), Math.min(total, 32L * 1024 * 1024));
            try (RandomAccessFile out = new RandomAccessFile(cache, "rw")) {
                out.seek(0);
                out.write(head, 0, Math.min(head.length, (int) total));
            }
            downloaded = Math.min(head.length, (int) total);
            lastProgBytes = downloaded;
            lastProgMs = System.currentTimeMillis();
            if (downloaded >= total) {
                finishDownload();
            } else {
                startWorker(noRange ? 0 : downloaded);
            }
        }
        Log.i(TAG, "prepare ok: total=" + total + " cached=" + downloaded
                + " noRange=" + noRange + " tracks=" + info.tracks.size()
                + " patches=" + info.patches.size());
        prepared = true;      // 全部成功之后才算准备好
    }

    /**
     * 能否续传：服务器支持 Range、文件里已经有头、且缓存前 4KB 与刚取到的头一致
     * （换 CDN / 换了清晰度后偏移会变，必须比对）。
     *
     * 门槛只有 RESUME_MIN：以前要求 ≥256KB（完整头）才肯续传，结果"预载刚开始就被切走"
     * 的半截文件全被删掉重下 —— 上滑越快越慢，正是用户看到的"切换要等下载完"。
     * 头的一致性由 headMatches 单独保证，不需要文件长度够长。
     */
    private boolean canResume(long have, boolean done, byte[] head) {
        if (done || noRange || have < RESUME_MIN) return false;
        return headMatches(head, 4096);
    }

    private boolean headMatches(byte[] head, int n) {
        int len = Math.min(n, head.length);
        if (len <= 0 || cache.length() < len) return false;
        byte[] got = new byte[len];
        try (RandomAccessFile f = new RandomAccessFile(cache, "r")) {
            f.seek(0);
            f.readFully(got);
        } catch (IOException e) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (got[i] != head[i]) return false;
        }
        return true;
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
        markDone();
    }

    private void markDone() {
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
            // seek 保护：前台要读的位置已经越过读窗（拖进度条 / 跳到没下过的地方），
            // 把窗一次性往前推到 need + aheadBytes，否则 loader 会一直等一个永远不来的字节
            if (aheadMode && softLimit >= 0 && need >= softLimit) {
                softLimit = need + aheadBytes;
                lock.notifyAll();
            }
            while (downloaded < need) {
                if (isFinished() || failed || stop.get()) return downloaded >= need;
                long left = WAIT_TIMEOUT_MS - (System.currentTimeMillis() - t0);
                if (left <= 0) return false;      // 超时单独处理：调用方会抛 IOException，不能当 EOF
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
