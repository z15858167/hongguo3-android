package com.hongguo.client;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.hongguo.client.cenc.StreamSource;

import java.io.File;

/**
 * 双播放器引擎：一个槽在播，另一个槽把下一条预载好（只缓冲、不出声、不占解码器），
 * 上下滑时两个槽交换位置，首帧几乎无等待。
 *
 * 为什么预载槽不建解码器：ExoPlayer 的 renderer 只有 play() 之后才 enable，
 * 所以 playWhenReady=false 的预载只占网络/buffer，不占硬解实例，
 * 避免"低端机第二路 MediaCodec 起不来"（计划里的 R1）。
 *
 * 带宽控制：预载槽用 StreamSource 的"读窗"模式（setAhead），后台只下到
 * 「ExoPlayer 已读到的位置 + 16MB」就停，activate 时 setUnlimited 满速。
 * v0.6.0~0.7.0 用的是固定 8MB 闸门，结果 loader 读满 8MB 后永久阻塞、
 * 预载槽永远进不了 READY —— 这是"双播放器形同虚设"的根因。
 */
final class PlayerEngine {

    interface Listener {
        void onSlotReady(int slot, long durationMs);

        void onSlotEnded(int slot);

        void onSlotError(int slot, Exception e);

        void onSlotBuffer(int slot, long done, long total, boolean doneAll);
    }

    static final class Slot {
        final int id;
        ExoPlayer player;
        PlayerView view;
        StreamSource stream;
        FeedQueue.Item item;
        Model.Track track;
        File file;
        boolean plain;
        boolean ready;
        int errCount;

        Slot(int id) {
            this.id = id;
        }
    }

    /**
     * 预载读窗：后台最多下到「前台已读位置 + 这个字节数」。
     * 16MB ≈ 4K/1080P 十几秒，足够切过去立刻出画，又不会把整集下完占带宽。
     */
    private static final long PRELOAD_AHEAD = 16L * 1024 * 1024;

    private final Context ctx;
    private final Listener listener;
    private final Slot[] slots = new Slot[]{new Slot(0), new Slot(1)};
    private FrameLayout host;
    /** 当前"在台上"的槽：load 完成时靠它决定要不要直接开播（应对超时换台的场景）。 */
    private int activeId = 0;

    /** 低内存设备：关掉预载、缩小缓冲，宁可慢一点也别被系统回收。 */
    private final boolean lowRam;
    private final boolean preloadOn;
    private final int targetBufferBytes;
    private final long preloadAhead;

    PlayerEngine(Context ctx, Listener l) {
        this.ctx = ctx;
        this.listener = l;
        this.lowRam = detectLowRam(ctx);
        this.preloadOn = !lowRam;
        this.targetBufferBytes = (lowRam ? 12 : 24) * 1024 * 1024;
        this.preloadAhead = lowRam ? 6L * 1024 * 1024 : PRELOAD_AHEAD;
    }

    static boolean detectLowRam(Context ctx) {
        boolean low = false;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                low = am.isLowRamDevice() || am.getMemoryClass() < 192;
            }
        } catch (Throwable ignored) {
        }
        if (Runtime.getRuntime().maxMemory() / (1024 * 1024) < 192) low = true;
        return low;
    }

    /** 预载是否可用（低内存机直接关掉，退回"点一下才加载"）。 */
    boolean preloadEnabled() {
        return preloadOn;
    }

    boolean isLowRam() {
        return lowRam;
    }

    void attach(FrameLayout stage) {
        host = stage;
        for (Slot s : slots) {
            PlayerView v = (PlayerView) LayoutInflater.from(ctx)
                    .inflate(R.layout.view_player, stage, false);
            v.setBackgroundColor(Color.BLACK);
            v.setUseController(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            stage.addView(v, lp);
            s.view = v;
        }
        slots[1].view.setVisibility(android.view.View.INVISIBLE);
    }

    Slot slot(int id) {
        return slots[id];
    }

    boolean isReady(int id) {
        Slot s = slots[id];
        return s.ready && s.player != null;
    }

    PlayerView view(int id) {
        return slots[id].view;
    }

    /** 载入一条：file 为密文缓存（plain=false，走 StreamSource）或已解密明文（plain=true）。 */
    void load(int id, FeedQueue.Item item, Model.Track track, String key,
              File file, boolean plain, int startSec, boolean preload) {
        Slot s = slots[id];
        releaseStream(s);
        s.item = item;
        s.track = track;
        s.file = file;
        s.plain = plain;
        s.ready = false;
        if (s.player == null) {
            s.player = buildPlayer(id);
            // 必须把 player 绑到这一槽的 PlayerView 上：player 不绑 view 就没有 video
            // surface 的消费者，ExoPlayer 会退化成"只有声音/空转"，画面永远是黑的。
            if (s.view != null) s.view.setPlayer(s.player);
        }

        ExoPlayer p = s.player;
        p.setPlaybackSpeed(1f);
        if (plain) {
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        } else {
            StreamSource src = new StreamSource(track.url, key, file, "full",
                    (done, total, doneAll) -> {
                        if (listener != null) listener.onSlotBuffer(id, done, total, doneAll);
                    });
            if (preload && preloadOn) src.setAhead(preloadAhead);
            s.stream = src;
            Cache.pin(file);
            ProgressiveMediaSource ms = new ProgressiveMediaSource.Factory(() -> src)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(track.url)));
            p.setMediaSource(ms);
        }
        p.prepare();
        if (startSec > 0) p.seekTo(startSec * 1000L);
        // 只有"在台上"的槽才自动开播；预载槽静音待命。
        // 超时换台的场景下 activate() 早已把 activeId 指过来，这里自然接上。
        p.setPlayWhenReady(!preload && id == activeId);
        // 可见性交给调用方：真正的"上台"由 activate() 打开，
        // 否则待切换的那一槽会提前盖住正在播的画面。
    }

    /** 开始出声播放（顺带解除预载限速）。 */
    void activate(int id, float speed) {
        Slot s = slots[id];
        activeId = id;
        if (s.stream != null) s.stream.setUnlimited();
        s.view.setVisibility(android.view.View.VISIBLE);
        if (s.player != null) {
            s.player.setPlaybackSpeed(speed);
            s.player.setPlayWhenReady(true);
            s.player.play();
        }
    }

    /** 只让带宽，不停播：换集时旧的那一条继续正常播，只是不再猛拉数据。 */
    void throttle(int id) {
        Slot s = slots[id];
        if (s.stream != null) s.stream.setStopAhead();
    }

    /** 让出带宽：停住并只保留 4MB 缓冲。 */
    void deactivate(int id) {
        Slot s = slots[id];
        if (s.player != null) {
            s.player.setPlayWhenReady(false);
            s.player.pause();
        }
        if (s.stream != null) s.stream.setStopAhead();
    }

    /** 停播并丢掉这条的流（文件保留在缓存，由 Cache 的 LRU 决定去留）。 */
    void releaseSlot(int id) {
        Slot s = slots[id];
        if (s.player != null) {
            s.player.setPlayWhenReady(false);
            s.player.stop();
            s.player.clearMediaItems();
        }
        releaseStream(s);
        s.ready = false;
        s.item = null;
        s.track = null;
        if (s.file != null) {
            Cache.unpin(s.file);
            s.file = null;
        }
        if (s.view != null) s.view.setVisibility(android.view.View.INVISIBLE);
    }

    void releaseAll() {
        for (Slot s : slots) {
            if (s.player != null) {
                if (s.view != null) s.view.setPlayer(null);   // 先解绑再 release，免得 view 持有已释放的 player
                s.player.release();
                s.player = null;
            }
            releaseStream(s);
            s.ready = false;
            if (s.view != null) s.view.setVisibility(android.view.View.INVISIBLE);
        }
    }

    void setSpeed(float speed, int id) {
        Slot s = slots[id];
        if (s.player != null) s.player.setPlaybackSpeed(speed);
    }

    private void releaseStream(Slot s) {
        if (s.stream != null) {
            s.stream.release();
            s.stream = null;
        }
    }

    private ExoPlayer buildPlayer(final int id) {
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                // 起播只要 400ms 缓冲（默认 2500ms 就是"点开干等"的元凶）
                // maxBuffer 12s→20s：预载槽能多备几秒，切过去不会立刻耗尽
                .setBufferDurationsMs(3_000, lowRam ? 12_000 : 20_000, 400, 1_000)
                .setBackBuffer(2_000, false)
                .setTargetBufferBytes(targetBufferBytes)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(ctx)
                .setLoadControl(lc)
                .build();
        p.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                Slot s = slots[id];
                if (state == Player.STATE_READY) {
                    s.ready = true;
                    if (listener != null) listener.onSlotReady(id, p0(s));
                } else if (state == Player.STATE_ENDED) {
                    if (listener != null) listener.onSlotEnded(id);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                slots[id].ready = false;
                slots[id].errCount++;
                if (listener != null) listener.onSlotError(id, error);
            }
        });
        return p;
    }

    private long p0(Slot s) {
        try {
            return s.player == null ? 0 : s.player.getDuration();
        } catch (Throwable t) {
            return 0;
        }
    }
}
