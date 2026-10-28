package com.hongguo.client;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;
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
 * 播放页。链路：剧集详情 → 取轨道 → 流式解密（边下边解）→ ExoPlayer 播放。
 *
 * 播放器交互对齐国内视频 App（爱奇艺/腾讯视频）通用范式：
 *  - 单击画面：显示/隐藏控制条；播放中 3.5s 自动隐藏；换集不弹出；
 *  - 双击左侧/右侧：快退/快进 10s；双击中间：播放/暂停；
 *  - 左半屏上下滑：亮度；右半屏上下滑：音量；横向滑：快进快退（实时预览）；
 *  - 控制条：顶部（返回/标题/收藏/上一集/下一集）+ 底部（播放键/进度条/时间/全屏）。
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
    private TextView status, autoBtn, oTitle;
    private TextView oFav, oPlay, oCur, oDur, oFull, oBack, oPrev, oNext;
    private LinearLayout qBar, speedBar, oTop, oBottom;
    private SeekBar oSeek;
    private TextView centerHint;
    private LinearLayout panel;
    private FrameLayout stage;
    private RecyclerView epGrid;
    private EpAdapter epAdapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean controlsShown;

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

    // 缓存生命周期：本集密文/明文文件 + 是否看完（看完的保留，方便秒开重看）
    private File curEnc, curPlain;
    private boolean watchedAll;

    // ---------------- 手势 ----------------
    private static final int G_NONE = 0, G_SEEK = 1, G_BRIGHT = 2, G_VOL = 3;
    private int gMode = G_NONE;
    private float gDownX, gDownY;
    private long gStartMs, gTargetMs;
    private float gBaseBright;
    private int gBaseVol, gMaxVol;
    private AudioManager am;
    private GestureDetectorCompat gd;
    private float baseBrightness = -1f; // 进入页面时的系统亮度，退出时还原

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
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        baseBrightness = getWindow().getAttributes().screenBrightness;
        setContentView(build());
        gd = new GestureDetectorCompat(this, new Gestures());
        playerView.setOnTouchListener((v, ev) -> onTouch(ev));
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

    // ================= UI 构建 =================
    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        // 关键：不用 media3 默认 controller（YouTube 风格、换集自动弹出），
        // 全部用下面的自定义顶栏/底栏 + 手势
        playerView.setUseController(false);
        stage.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶部控制栏（默认隐藏，单击画面出现）----
        oTop = new LinearLayout(this);
        oTop.setOrientation(LinearLayout.HORIZONTAL);
        oTop.setGravity(Gravity.CENTER_VERTICAL);
        oTop.setBackground(new GradientDrawable() {{
            setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            setColors(new int[]{0xB3000000, 0x00000000});
        }});
        oTop.setPadding(dp(6), dp(6), dp(10), dp(12));

        oBack = tv("‹", 22, TXT);
        oBack.setPadding(dp(10), dp(6), dp(10), dp(6));
        oBack.setOnClickListener(v -> finish());
        oTop.addView(oBack);

        oTitle = new TextView(this);
        oTitle.setText(title == null ? "" : title);
        oTitle.setTextSize(14);
        oTitle.setTextColor(TXT);
        oTitle.setMaxLines(1);
        oTitle.setPadding(dp(2), 0, dp(6), 0);
        oTop.addView(oTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        oFav = tv("♡", 17, TXT);
        oFav.setPadding(dp(10), dp(4), dp(10), dp(4));
        oFav.setOnClickListener(v -> {
            boolean on = Store.toggleFav(this, sid, title, cover);
            syncFav();
            toast(on ? "已收藏" : "已取消收藏");
        });
        oTop.addView(oFav);

        oPrev = tv("上一集", 12, TXT);
        oPrev.setPadding(dp(8), dp(6), dp(4), dp(6));
        oPrev.setOnClickListener(v -> playPrev());
        oTop.addView(oPrev);

        oNext = tv("下一集", 12, TXT);
        oNext.setPadding(dp(10), dp(6), dp(4), dp(6));
        oNext.setOnClickListener(v -> playNext());
        oTop.addView(oNext);

        oTop.setVisibility(View.GONE);
        stage.addView(oTop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // ---- 底部控制栏 ----
        oBottom = new LinearLayout(this);
        oBottom.setOrientation(LinearLayout.VERTICAL);
        oBottom.setBackground(new GradientDrawable() {{
            setOrientation(GradientDrawable.Orientation.BOTTOM_TOP);
            setColors(new int[]{0xCC000000, 0x00000000});
        }});
        oBottom.setPadding(dp(12), dp(16), dp(12), dp(6));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        oCur = tv("00:00", 11, TXT);
        oCur.setPadding(0, 0, dp(8), 0);
        seekRow.addView(oCur);

        oSeek = new SeekBar(this);
        oSeek.setMax(1000);
        oSeek.getProgressDrawable().setColorFilter(PINK, android.graphics.PorterDuff.Mode.SRC_IN);
        oSeek.getThumb().setColorFilter(PINK, android.graphics.PorterDuff.Mode.SRC_IN);
        oSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    oCur.setText(fmtMs(p * player.getDuration() / 1000L));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                ui.removeCallbacks(hideRun);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (player != null && player.getDuration() > 0) {
                    player.seekTo(s.getProgress() * player.getDuration() / 1000L);
                }
                scheduleHide();
            }
        });
        seekRow.addView(oSeek, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        oDur = tv("00:00", 11, TXT);
        oDur.setPadding(dp(8), 0, dp(6), 0);
        seekRow.addView(oDur);

        oFull = tv("全屏", 13, TXT);
        oFull.setPadding(dp(8), dp(6), dp(4), dp(6));
        oFull.setOnClickListener(v -> toggleFullscreen());
        seekRow.addView(oFull);

        oBottom.addView(seekRow);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);

        oPlay = tv("▶", 18, TXT);
        oPlay.setPadding(dp(4), dp(6), dp(10), dp(6));
        oPlay.setOnClickListener(v -> togglePlay());
        bottomRow.addView(oPlay);

        TextView spacer = new TextView(this);
        bottomRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 连播开关放在下方面板（与清晰度/倍速/选集在一起），底部栏保持干净
        TextView connHint = tv("清晰度 · 倍速 · 选集在下方", 11, 0x66FFFFFF);
        bottomRow.addView(connHint);

        oBottom.addView(bottomRow);
        oBottom.setVisibility(View.GONE);
        stage.addView(oBottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // ---- 中央手势反馈 / 状态 ----
        centerHint = tv("", 14, Color.WHITE);
        centerHint.setGravity(Gravity.CENTER);
        GradientDrawable hg = new GradientDrawable();
        hg.setCornerRadius(dp(10));
        hg.setColor(0x99000000);
        centerHint.setBackground(hg);
        centerHint.setPadding(dp(18), dp(12), dp(18), dp(12));
        centerHint.setVisibility(View.GONE);
        stage.addView(centerHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

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
        stageLp.weight = 45;
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

        autoBtn = tv("", 12, DIM);
        autoBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        autoBtn.setOnClickListener(v -> {
            autoNext = !autoNext;
            markAuto();
            toast(autoNext ? "已开启自动连播" : "已关闭自动连播");
        });
        ctrl.addView(autoBtn);
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
        panelLp.weight = 55;
        root.addView(panel, panelLp);

        markSpeed();
        markAuto();
        syncFav();
        return root;
    }

    private TextView tv(String text, float sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    private void syncFav() {
        boolean on = Store.isFav(this, sid);
        oFav.setText(on ? "♥" : "♡");
        oFav.setTextColor(on ? PINK : TXT);
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

    // ================= 控制条显隐 =================
    private void showControls() {
        ui.removeCallbacks(hideRun);
        controlsShown = true;
        oTop.setVisibility(View.VISIBLE);
        oBottom.setVisibility(View.VISIBLE);
        oFull.setText(fullscreen ? "退出全屏" : "全屏");
        oTitle.setText(title == null ? "" : title);
        tick();
        scheduleHide();
    }

    private void hideControls() {
        ui.removeCallbacks(hideRun);
        controlsShown = false;
        oTop.setVisibility(View.GONE);
        oBottom.setVisibility(View.GONE);
        centerHint.setVisibility(View.GONE);
        ui.removeCallbacks(tickRun);
    }

    private void toggleControls() {
        if (controlsShown) hideControls();
        else showControls();
    }

    /** 只在播放中倒计时隐藏；暂停时保持显示（跟国内播放器一致）。 */
    private void scheduleHide() {
        ui.removeCallbacks(hideRun);
        if (player != null && player.isPlaying()) {
            ui.postDelayed(hideRun, 3500);
        }
    }

    private final Runnable hideRun = this::hideControls;

    private final Runnable tickRun = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    private void tick() {
        ui.removeCallbacks(tickRun);
        if (!controlsShown) return;
        if (player != null) {
            long dur = player.getDuration();
            long pos = player.getCurrentPosition();
            if (dur > 0) {
                oSeek.setMax(1000);
                oSeek.setProgress((int) (pos * 1000L / dur));
                oDur.setText(fmtMs(dur));
            }
            oCur.setText(fmtMs(pos));
            oPlay.setText(player.isPlaying() ? "❚❚" : "▶");
        }
        ui.postDelayed(tickRun, 500);
        // 播放中续约隐藏计时
        if (player != null && player.isPlaying()) {
            ui.removeCallbacks(hideRun);
            ui.postDelayed(hideRun, 3500);
        }
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
        showControls();
        scheduleHide();
    }

    private String fmtMs(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    // ================= 手势 =================
    private boolean onTouch(MotionEvent ev) {
        if (gd != null) gd.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gDownX = ev.getX();
                gDownY = ev.getY();
                gMode = G_NONE;
                if (player != null && player.getDuration() > 0) {
                    gStartMs = player.getCurrentPosition();
                } else {
                    gStartMs = 0;
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - gDownX;
                float dy = ev.getY() - gDownY;
                if (gMode == G_NONE && Math.hypot(dx, dy) > dp(18)) {
                    if (Math.abs(dx) > Math.abs(dy) && player != null
                            && player.getDuration() > 0) {
                        gMode = G_SEEK;
                    } else if (ev.getX() < stage.getWidth() / 2f) {
                        gMode = G_BRIGHT;
                    } else {
                        gMode = G_VOL;
                    }
                    gBaseBright = getWindow().getAttributes().screenBrightness;
                    if (gBaseBright < 0) gBaseBright = 0.5f;
                    if (am != null) {
                        gBaseVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        gMaxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    } else {
                        gBaseVol = 0;
                        gMaxVol = 1;
                    }
                }
                if (gMode == G_SEEK) {
                    long dur = player.getDuration();
                    float frac = dx / (stage.getWidth() * 0.75f);
                    gTargetMs = gStartMs + (long) (frac * dur);
                    if (gTargetMs < 0) gTargetMs = 0;
                    if (gTargetMs > dur) gTargetMs = dur;
                    long diff = gTargetMs - gStartMs;
                    setHint((diff >= 0 ? "快进 " : "快退 ")
                            + fmtMs(Math.abs(diff)) + "\n"
                            + fmtMs(gTargetMs) + " / " + fmtMs(dur));
                } else if (gMode == G_BRIGHT) {
                    float delta = -dy / (stage.getHeight() * 0.75f);
                    float v = Math.min(1f, Math.max(0.01f, gBaseBright + delta));
                    android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v;
                    getWindow().setAttributes(lp);
                    setHint("亮度 " + Math.round(v * 100) + "%");
                } else if (gMode == G_VOL) {
                    float delta = -dy / (stage.getHeight() * 0.75f) * gMaxVol;
                    int v = Math.min(gMaxVol, Math.max(0,
                            Math.round(gBaseVol + delta)));
                    if (v != am.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    }
                    setHint("音量 " + v + " / " + gMaxVol);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gMode == G_SEEK && player != null && player.getDuration() > 0) {
                    player.seekTo(gTargetMs);
                    scheduleHide();
                }
                gMode = G_NONE;
                centerHint.setVisibility(View.GONE);
                return true;
        }
        return true;
    }

    private void setHint(String s) {
        centerHint.setText(s);
        centerHint.setVisibility(View.VISIBLE);
    }

    private class Gestures extends android.view.GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            toggleControls();
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            if (player == null) return true;
            float x = e.getX();
            float w = playerView.getWidth();
            if (x < w / 3f) {
                long t = Math.max(0, player.getCurrentPosition() - 10000L);
                player.seekTo(t);
                setHint("« 10s");
                ui.removeCallbacks(hintRun);
                ui.postDelayed(hintRun, 600);
            } else if (x > w * 2f / 3f) {
                long t = player.getCurrentPosition() + 10000L;
                if (player.getDuration() > 0 && t > player.getDuration()) {
                    t = player.getDuration();
                }
                player.seekTo(t);
                setHint("10s »");
                ui.removeCallbacks(hintRun);
                ui.postDelayed(hintRun, 600);
            } else {
                togglePlay();
            }
            return true;
        }
    }

    private final Runnable hintRun = () -> centerHint.setVisibility(View.GONE);

    // ================= 加载 =================
    private void loadDetail() {
        setStatus("正在获取剧集…");
        Api.run(() -> Api.episodes(sid), new Api.Cb<Model.Detail>() {
            @Override
            public void ok(Model.Detail v) {
                detail = v;
                warmed.clear();
                if (title == null || title.isEmpty()) {
                    title = v.title;
                    oTitle.setText(title);
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
                        + "\n\n如果是「空响应 / 签名未生效」，说明签名桥没跑起来。");
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
        // 换集：控制条立刻收起，不再弹出（用户单击画面才会出现）
        hideControls();
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
     * 下一集预热：当前集出画后，后台把下一集的密文缓存下来。
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

    // ================= 准备并播放 =================
    private void prepare(Model.Episode ep, Model.Track t, int startSec) {
        String tag = ep.vid + "." + safe(t.definition);
        File plain = new File(getCacheDir(), tag + ".mp4");
        if (plain.exists() && plain.length() > 1024) {
            Cache.touch(plain);          // 命中：刷新 LRU 时间，别被淘汰掉
            curPlain = plain;
            startPlayFile(plain, startSec);
            return;
        }
        File enc = new File(getCacheDir(), tag + ".enc");
        curEnc = enc;
        // 写盘前先做一次淘汰，避免整集预分配把磁盘吃满
        Cache.trim(getCacheDir(), Cache.limit(getCacheDir()));

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
            // 换集：上一集没看完的缓存直接删掉（看完整集的明文另算，见 onDestroy）
            File old = stream.cacheFile();
            stream.release();
            stream = null;
            if (old != null && !watchedAll) old.delete();
        }
        watchedAll = false;
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
            oSeek.setProgress(0);
            oCur.setText("00:00");
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
        curPlain = f;
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
                if (state == Player.STATE_READY) {
                    status.setVisibility(View.GONE);
                    oDur.setText(fmtMs(player.getDuration()));
                    if (controlsShown) tick();
                }
                if (state != Player.STATE_ENDED) return;
                watchedAll = true;      // 看完了：这集的缓存保留
                if (autoNext) {
                    playNext();
                } else if (detail != null) {
                    Store.recordHist(PlayActivity.this, sid, title, cover,
                            curEp, detail.episodeCnt, 0);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (controlsShown) {
                    oPlay.setText(isPlaying ? "❚❚" : "▶");
                    scheduleHide();
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

    // ================= 全屏 =================
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        hideControls(); // 切换后重新单击呼出，布局已变化
        if (panel != null) panel.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (stage != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) stage.getLayoutParams();
            lp.weight = fullscreen ? 100 : 45;
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

    // ================= 进度与生命周期 =================
    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (player != null) player.setPlayWhenReady(false);
    }

    private void saveProgress() {
        if (player == null || detail == null) return;
        try {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (dur > 0 && pos >= (long) (dur * 0.92)) watchedAll = true;
            int t = (int) (pos / 1000L);
            Store.recordHist(this, sid, title, cover, curEp, detail.episodeCnt, t);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        // 没看完的这集：缓存直接清掉（看了一半划走不留残留）；看完的保留以便秒开重看
        if (!watchedAll) {
            if (curEnc != null) curEnc.delete();
            if (curPlain != null) curPlain.delete();
        }
        Cache.trim(getCacheDir(), Cache.limit(getCacheDir()));
        // 还原进入时的系统亮度
        if (baseBrightness >= 0) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = baseBrightness;
            getWindow().setAttributes(lp);
        }
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

    // ================= 选集 =================
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
