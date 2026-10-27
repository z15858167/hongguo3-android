package com.hongguo.client;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hongguo.client.cenc.Decryptor;
import com.hongguo.client.cenc.StreamSource;
import com.hongguo.client.cenc.Spade;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 播放页。链路：剧集详情 → 取轨道 → 下载密文 → 解密到本地 → ExoPlayer 播明文。
 *
 * 桌面版那套「虚拟明文 MP4 + Range 206」在这里不需要：那是为了骗浏览器 <video>，
 * 手机上直接解出一个明文文件更简单，也不需要本地 HTTP 服务。
 */
public class PlayActivity extends AppCompatActivity {

    private static final int PINK = 0xFFFF2C55;
    private static final int TXT = 0xFFF2F2F2;
    private static final int DIM = 0xFF8A8A8E;

    private static final String E_SID = "sid";
    private static final String E_TITLE = "title";
    private static final String E_COVER = "cover";

    private String sid, title, cover;
    private Model.Detail detail;
    private List<Model.Track> tracks = new ArrayList<>();
    private int curEp = 1;
    private String quality = "";

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status, favBtn, titleView, autoBtn, fsBtn;
    private LinearLayout qBar, speedBar;
    private LinearLayout top, panel;
    private FrameLayout stage;
    private RecyclerView epGrid;
    private EpAdapter epAdapter;
    private View loading;

    private float speed = 1.0f;
    private boolean autoNext = true;
    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    // 流式播放（边下边解）相关
    private StreamSource stream;
    private boolean fellBack;
    private Model.Track curTrack;
    private Model.Episode curEpObj;
    private int curStartSec;
    private boolean fullscreen;

    public static void start(Context c, String sid, String title, String cover) {
        Intent i = new Intent(c, PlayActivity.class);
        i.putExtra(E_SID, sid);
        i.putExtra(E_TITLE, title);
        i.putExtra(E_COVER, cover);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sid = getIntent().getStringExtra(E_SID);
        title = getIntent().getStringExtra(E_TITLE);
        cover = getIntent().getStringExtra(E_COVER);
        if (sid == null) {
            finish();
            return;
        }
        setContentView(build());
        try {
            Api.init(this);
        } catch (Exception e) {
            setStatus("初始化失败: " + e);
        }
        loadDetail();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);

        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(12), dp(6));
        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(15);
        back.setTextColor(TXT);
        back.setPadding(dp(10), dp(8), dp(10), dp(8));
        back.setOnClickListener(v -> finish());
        top.addView(back);

        titleView = new TextView(this);
        titleView.setText(title == null ? "" : title);
        titleView.setTextSize(15);
        titleView.setTextColor(TXT);
        titleView.setMaxLines(1);
        titleView.setPadding(dp(6), 0, dp(6), 0);
        top.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        favBtn = new TextView(this);
        favBtn.setTextSize(17);
        favBtn.setPadding(dp(10), dp(4), dp(4), dp(4));
        favBtn.setOnClickListener(v -> {
            boolean on = Store.toggleFav(this, sid, title, cover);
            syncFav();
            toast(on ? "已收藏" : "已取消收藏");
        });
        top.addView(favBtn);

        TextView prevBtn = new TextView(this);
        prevBtn.setText("上一集");
        prevBtn.setTextSize(12);
        prevBtn.setTextColor(TXT);
        prevBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        prevBtn.setOnClickListener(v -> playPrev());
        top.addView(prevBtn);

        TextView nextBtn = new TextView(this);
        nextBtn.setText("下一集");
        nextBtn.setTextSize(12);
        nextBtn.setTextColor(TXT);
        nextBtn.setPadding(dp(10), dp(6), dp(6), dp(6));
        nextBtn.setOnClickListener(v -> playNext());
        top.addView(nextBtn);

        syncFav();
        root.addView(top);

        stage = new FrameLayout(this);
        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(true);
        // 控制条 2.5 秒自动隐藏、点一下再出来（默认常驻会一直盖在画面上）
        playerView.setControllerShowTimeoutMs(2500);
        playerView.setControllerHideOnTouch(true);
        playerView.setControllerAutoShow(true);
        stage.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fsBtn = new TextView(this);
        fsBtn.setText("\u23cf");
        fsBtn.setTextSize(18);
        fsBtn.setTextColor(0xFFFFFFFF);
        fsBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        fsBtn.setBackgroundColor(0x66000000);
        fsBtn.setOnClickListener(v -> toggleFullscreen());
        FrameLayout.LayoutParams fslp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fslp.gravity = Gravity.BOTTOM | Gravity.END;
        fslp.setMargins(0, 0, dp(8), dp(8));
        stage.addView(fsBtn, fslp);

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setText("正在打开…");
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER;
        stage.addView(status, slp);

        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        stageLp.weight = 42;
        root.addView(stage, stageLp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF121216);
        panel.setPadding(dp(12), dp(10), dp(12), 0);

        TextView qLabel = new TextView(this);
        qLabel.setText("清晰度");
        qLabel.setTextSize(12);
        qLabel.setTextColor(DIM);
        panel.addView(qLabel);

        qBar = new LinearLayout(this);
        qBar.setOrientation(LinearLayout.HORIZONTAL);
        qBar.setPadding(0, dp(6), 0, dp(8));
        panel.addView(qBar);

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        ctrl.setPadding(0, 0, 0, dp(8));

        TextView spLabel = new TextView(this);
        spLabel.setText("倍速");
        spLabel.setTextSize(12);
        spLabel.setTextColor(DIM);
        spLabel.setPadding(0, 0, dp(8), 0);
        ctrl.addView(spLabel);

        speedBar = new LinearLayout(this);
        speedBar.setOrientation(LinearLayout.HORIZONTAL);
        for (float s : SPEEDS) {
            final float fs = s;
            TextView c = new TextView(this);
            c.setText(fmtSpeed(s));
            c.setTextSize(12);
            c.setPadding(dp(9), dp(5), dp(9), dp(5));
            c.setTag(s);
            c.setOnClickListener(v -> {
                speed = fs;
                if (player != null) player.setPlaybackSpeed(fs);
                markSpeed();
            });
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(0x22FFFFFF);
            c.setBackground(g);
            c.setTextColor(TXT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            speedBar.addView(c, lp);
        }
        ctrl.addView(speedBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        autoBtn = new TextView(this);
        autoBtn.setTextSize(12);
        autoBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        autoBtn.setOnClickListener(v -> {
            autoNext = !autoNext;
            markAuto();
            toast(autoNext ? "已开启自动连播" : "已关闭自动连播");
        });
        ctrl.addView(autoBtn);
        markSpeed();
        markAuto();
        panel.addView(ctrl);

        TextView epLabel = new TextView(this);
        epLabel.setText("选集");
        epLabel.setTextSize(12);
        epLabel.setTextColor(DIM);
        panel.addView(epLabel);

        epGrid = new RecyclerView(this);
        epGrid.setLayoutManager(new GridLayoutManager(this, 6));
        epGrid.setPadding(0, dp(6), 0, dp(20));
        epAdapter = new EpAdapter();
        epGrid.setAdapter(epAdapter);
        panel.addView(epGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        panelLp.weight = 58;
        root.addView(panel, panelLp);

        return root;
    }

    private void syncFav() {
        boolean on = Store.isFav(this, sid);
        favBtn.setText(on ? "♥" : "♡");
        favBtn.setTextColor(on ? PINK : TXT);
    }

    private void setStatus(String s) {
        runOnUiThread(() -> {
            status.setText(s);
            status.setVisibility(View.VISIBLE);
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 加载 ----------------
    private void loadDetail() {
        setStatus("正在获取剧集…");
        Api.run(() -> Api.episodes(sid), new Api.Cb<Model.Detail>() {
            @Override
            public void ok(Model.Detail v) {
                detail = v;
                warmed.clear();
                if (title == null || title.isEmpty()) {
                    title = v.title;
                    titleView.setText(title);
                    syncFav();
                }
                epAdapter.set(v.eps);
                int[] pg = Store.progress(PlayActivity.this, sid);
                curEp = pg[0];
                if (v.ep(curEp) == null && !v.eps.isEmpty()) curEp = v.eps.get(0).index;
                play(curEp, pg[1]);
            }

            @Override
            public void err(Exception e) {
                setStatus("获取剧集失败\n" + msg(e)
                        + "\n\n如果是「空响应 / 签名未生效」，说明签名桥没跑起来，点右上角诊断看原因。");
            }
        });
    }

    private void play(int epIndex, int startSec) {
        Model.Episode ep = detail == null ? null : detail.ep(epIndex);
        if (ep == null) {
            setStatus("第 " + epIndex + " 集不存在");
            return;
        }
        curEp = epIndex;
        epAdapter.highlight(curEp);
        setStatus("正在取播放地址…");
        Api.run(() -> Api.tracks(ep.vid), new Api.Cb<List<Model.Track>>() {
            @Override
            public void ok(List<Model.Track> v) {
                tracks = v == null ? new ArrayList<>() : v;
                if (tracks.isEmpty()) {
                    setStatus("该集没有可用直链");
                    return;
                }
                Collections.sort(tracks, (a, b) -> b.num() - a.num());
                buildQualityBar();
                Model.Track pick = null;
                for (Model.Track t : tracks) {
                    if (quality.isEmpty() && pick == null) pick = t;
                    if (!quality.isEmpty() && quality.equals(t.definition)) pick = t;
                }
                if (pick == null) pick = tracks.get(0);
                quality = pick.definition;
                markQuality();
                prepare(ep, pick, startSec);
            }

            @Override
            public void err(Exception e) {
                setStatus("取播放地址失败\n" + msg(e));
            }
        });
    }

    private void buildQualityBar() {
        qBar.removeAllViews();
        for (Model.Track t : tracks) {
            TextView c = new TextView(this);
            c.setText(t.label());
            c.setTextSize(12);
            c.setPadding(dp(12), dp(6), dp(12), dp(6));
            c.setTag(t.definition);
            c.setOnClickListener(v -> {
                quality = t.definition;
                markQuality();
                play(curEp, 0);
            });
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(13));
            g.setColor(0x22FFFFFF);
            c.setBackground(g);
            c.setTextColor(TXT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            qBar.addView(c, lp);
        }
    }

    private void markQuality() {
        for (int i = 0; i < qBar.getChildCount(); i++) {
            TextView c = (TextView) qBar.getChildAt(i);
            boolean on = quality.equals(c.getTag());
            c.setTextColor(on ? PINK : TXT);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(13));
            g.setColor(on ? 0x33FF2C55 : 0x22FFFFFF);
            c.setBackground(g);
        }
    }

    private String fmtSpeed(float s) {
        return (s == Math.round(s) ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    private void markSpeed() {
        if (speedBar == null) return;
        for (int i = 0; i < speedBar.getChildCount(); i++) {
            TextView c = (TextView) speedBar.getChildAt(i);
            boolean on = ((Float) c.getTag()) == speed;
            c.setTextColor(on ? PINK : TXT);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(on ? 0x33FF2C55 : 0x22FFFFFF);
            c.setBackground(g);
        }
    }

    private void markAuto() {
        if (autoBtn == null) return;
        autoBtn.setText(autoNext ? "连播 开" : "连播 关");
        autoBtn.setTextColor(autoNext ? PINK : DIM);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(autoNext ? 0x33FF2C55 : 0x22FFFFFF);
        autoBtn.setBackground(g);
    }

    private int nextOf() {
        int next = Integer.MAX_VALUE;
        for (Model.Episode e : detail.eps) {
            if (e.index > curEp && e.index < next) next = e.index;
        }
        return next == Integer.MAX_VALUE ? -1 : next;
    }

    private int prevOf() {
        int prev = -1;
        for (Model.Episode e : detail.eps) {
            if (e.index < curEp && e.index > prev) prev = e.index;
        }
        return prev;
    }

    /** 自动连播 / 手动下一集。 */
    private void playNext() {
        if (detail == null) return;
        int next = nextOf();
        if (next < 0) {
            toast("已经是最后一集");
            return;
        }
        play(next, 0);
        scrollToEp(next);
    }

    private void playPrev() {
        if (detail == null) return;
        int prev = prevOf();
        if (prev < 0) {
            toast("已经是第一集");
            return;
        }
        play(prev, 0);
        scrollToEp(prev);
    }

    private void scrollToEp(int epIndex) {
        if (epGrid == null) return;
        epGrid.post(() -> {
            int p = indexOfEp(epIndex);
            RecyclerView l = epGrid;
            if (l.getLayoutManager() != null) l.getLayoutManager().scrollToPosition(p);
        });
    }

    /**
     * 下一集预热：当前集出画后，后台把下一集下载 + 解密好。
     * 这样"手点下一集"和"自动连播"都能秒开；只热一次，且最多同时进行 1 个。
     */
    private final Set<String> warmed = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean warming;

    private void warmNext() {
        if (detail == null || warming) return;
        int next = nextOf();
        if (next < 0) return;
        Model.Episode ep = detail.ep(next);
        if (ep == null || !warmed.add(ep.vid)) return;
        warming = true;
        Api.run(() -> {
            try {
                List<Model.Track> ts = Api.tracks(ep.vid);
                Model.Track pick = null;
                for (Model.Track t : ts) if (pick == null || t.num() > pick.num()) pick = t;
                if (pick != null) {
                    // 现在走流式解密，预热只需把**密文**缓存下来即可（播放时现场解密）
                    File enc = new File(getCacheDir(), ep.vid + "." + safe(pick.definition) + ".enc");
                    if (!enc.exists() || enc.length() <= 1024) {
                        Api.download(pick.url, enc, null);
                    }
                }
            } catch (Throwable ignored) {
                // 预热失败不影响正在播的这一集
            } finally {
                warming = false;
            }
            return null;
        }, new Api.Cb<Object>() {
            @Override
            public void ok(Object v) {
            }

            @Override
            public void err(Exception e) {
            }
        });
    }

    private int indexOfEp(int epIndex) {
        for (int i = 0; i < detail.eps.size(); i++) {
            if (detail.eps.get(i).index == epIndex) return i;
        }
        return 0;
    }

    // ---------------- 准备并播放 ----------------
    /**
     * 默认走**边下边解边播**（StreamSource）：只先取 256 KB 头拿样本表，随后边播边解密，
     * 不用等整集下完。只有当源片不支持 Range / moov 不在头部时才回退到"整集下完再解"。
     */
    private void prepare(Model.Episode ep, Model.Track t, int startSec) {
        String tag = ep.vid + "." + safe(t.definition);
        File plain = new File(getCacheDir(), tag + ".mp4");
        if (plain.exists() && plain.length() > 1024) {
            startPlayFile(plain, startSec);
            return;
        }
        File enc = new File(getCacheDir(), tag + ".enc");

        setStatus("正在打开…");
        Api.run(() -> {
            String key = t.encrypted ? Spade.spadeToKey(t.spadeA) : null;
            return new Object[]{key};
        }, new Api.Cb<Object[]>() {
            @Override
            public void ok(Object[] v) {
                startStream(t, (String) v[0], enc, startSec, ep);
            }

            @Override
            public void err(Exception e) {
                setStatus("取密钥失败\n" + msg(e));
            }
        });
    }

    /** 流式播放：ExoPlayer 要多少解多少。 */
    private void startStream(Model.Track t, String key, File enc, int startSec, Model.Episode ep) {
        if (stream != null) {
            stream.release();
            stream = null;
        }
        StreamSource src = new StreamSource(t.url, key, enc, "full",
                (done, total, doneAll) -> {
                    if (doneAll) return;
                    runOnUiThread(() -> {
                        if (status.getVisibility() == View.VISIBLE) {
                            status.setText("缓冲中 " + (done / 1048576) + " / "
                                    + (total > 0 ? total / 1048576 : 0) + " MB");
                        }
                    });
                });
        stream = src;
        fellBack = false;
        curTrack = t;
        curEpObj = ep;
        curStartSec = startSec;
        runOnUiThread(() -> {
            status.setVisibility(View.VISIBLE);
            status.setText("正在打开…");
            if (player == null) buildPlayer();
            playerView.setPlayer(player);
            player.setPlaybackSpeed(speed);
            androidx.media3.exoplayer.source.ProgressiveMediaSource ms =
                    new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                            () -> src)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(t.url)));
            player.setMediaSource(ms);
            player.prepare();
            if (startSec > 0) player.seekTo(startSec * 1000L);
            player.setPlayWhenReady(true);
        });
    }

    /** 流式播不了（不支持 Range / moov 在尾部）时的兜底：整集下完再解密。 */
    private void fallbackDownload(Model.Episode ep, Model.Track t, int startSec) {
        String tag = ep.vid + "." + safe(t.definition);
        File plain = new File(getCacheDir(), tag + ".mp4");
        File enc = new File(getCacheDir(), tag + ".enc");
        setStatus("该片不支持边下边播，整集下载中 0%");
        Api.run(() -> {
            Api.download(t.url, enc, (done, total) -> {
                int pct = total > 0 ? (int) (done * 100L / total) : 0;
                runOnUiThread(() -> status.setText("下载中 " + pct + "%"
                        + (total > 0 ? "（" + (total / 1048576) + " MB）" : "")));
            });
            runOnUiThread(() -> status.setText("解密中…"));
            String key = t.encrypted ? Spade.spadeToKey(t.spadeA) : null;
            Decryptor.Stats st = Decryptor.decrypt(enc, plain, key, "full");
            if (enc.exists()) enc.delete();
            return st;
        }, new Api.Cb<Decryptor.Stats>() {
            @Override
            public void ok(Decryptor.Stats v) {
                if (!plain.exists() || plain.length() < 1024) {
                    setStatus("解密失败：产物为空");
                    return;
                }
                startPlayFile(plain, startSec);
            }

            @Override
            public void err(Exception e) {
                setStatus("下载/解密失败\n" + msg(e));
            }
        });
    }

    /** 播放已解密好的本地明文（回退路径 / 旧缓存）。 */
    private void startPlayFile(File f, int startSec) {
        runOnUiThread(() -> {
            status.setVisibility(View.GONE);
            if (player == null) buildPlayer();
            playerView.setPlayer(player);
            player.setPlaybackSpeed(speed);
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)));
            player.prepare();
            if (startSec > 0) player.seekTo(startSec * 1000L);
            player.setPlayWhenReady(true);
        });
    }

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) status.setVisibility(View.GONE);
                if (state != Player.STATE_ENDED) return;
                if (autoNext) {
                    playNext();
                } else if (detail != null) {
                    Store.recordHist(PlayActivity.this, sid, title, cover,
                            curEp, detail.episodeCnt, 0);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // 流式路径起不来（不支持 Range / moov 在尾部等）→ 回退到整集下载再解密
                if (stream != null && !fellBack && curTrack != null && curEpObj != null) {
                    fellBack = true;
                    stream.release();
                    stream = null;
                    setStatus("边下边播失败，改为整集下载\n" + msg(error));
                    fallbackDownload(curEpObj, curTrack, curStartSec);
                } else {
                    setStatus("播放失败\n" + msg(error));
                }
            }
        });
    }

    // ---------------- 全屏 ----------------
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        if (top != null) top.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (panel != null) panel.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (stage != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) stage.getLayoutParams();
            if (fullscreen) {
                lp.height = 0;
                lp.weight = 100;
            } else {
                lp.height = 0;
                lp.weight = 42;
            }
            stage.setLayoutParams(lp);
        }
        if (fullscreen) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        androidx.core.view.WindowInsetsControllerCompat ic =
                androidx.core.view.ViewCompat.getWindowInsetsController(
                        getWindow().getDecorView());
        if (ic != null) {
            if (fullscreen) {
                ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                ic.setSystemBarsBehavior(
                        androidx.core.view.WindowInsetsControllerCompat
                                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            }
        }
        fsBtn.setText(fullscreen ? "退出" : "全屏");
        toast(fullscreen ? "已进入全屏" : "已退出全屏");
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            toggleFullscreen();
            return;
        }
        super.onBackPressed();
    }

    private String safe(String s) {
        if (s == null) return "best";
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.length() == 0 ? "best" : b.toString();
    }

    private String msg(Exception e) {
        String s = e.getMessage();
        return s == null || s.isEmpty() ? e.toString() : s;
    }

    // ---------------- 进度与生命周期 ----------------
    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (player != null) player.setPlayWhenReady(false);
    }

    private void saveProgress() {
        if (player == null || detail == null) return;
        try {
            int t = (int) (player.getCurrentPosition() / 1000L);
            Store.recordHist(this, sid, title, cover, curEp, detail.episodeCnt, t);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ---------------- 选集 ----------------
    private class EpAdapter extends RecyclerView.Adapter<EpHolder> {
        private final List<Model.Episode> data = new ArrayList<>();

        void set(List<Model.Episode> list) {
            data.clear();
            if (list != null) data.addAll(list);
            notifyDataSetChanged();
        }

        void highlight(int idx) {
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public EpHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView t = new TextView(PlayActivity.this);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(13);
            t.setPadding(0, dp(10), 0, dp(10));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            t.setLayoutParams(lp);
            return new EpHolder(t);
        }

        @Override
        public void onBindViewHolder(@NonNull EpHolder h, int pos) {
            Model.Episode e = data.get(pos);
            h.t.setText(String.valueOf(e.index));
            boolean on = e.index == curEp;
            h.t.setTextColor(on ? Color.WHITE : 0xFFBBBBBB);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(6));
            g.setColor(on ? PINK : 0x22FFFFFF);
            h.t.setBackground(g);
            h.t.setOnClickListener(v -> play(e.index, 0));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private static class EpHolder extends RecyclerView.ViewHolder {
        final TextView t;

        EpHolder(TextView t) {
            super(t);
            this.t = t;
        }
    }
}
