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
    private TextView oFav, oPlay, oCur, oDur, oFull, oBack, oPrev, oNext, epBtn, spdBtn;
    private LinearLayout qBar, speedBar, oTop, oBottom, sideNav, drawer, qBlock, spdBlock, epBlock;
    private View scrim;
    private SeekBar oSeek;
    private TextView centerHint;
    private FrameLayout root, stage;
    private RecyclerView epGrid;
    private EpAdapter epAdapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 只表示顶栏是否显示：底栏（进度条）在竖屏/全屏都常显。 */
    private boolean controlsShown;
    /** 抽屉是否打开（打开时不自动隐藏顶栏）。 */
    private boolean drawerOpen;
    /** 抽屉内容：DRAWER_EP 选集 / DRAWER_SPEED 倍速。 */
    private static final int DRAWER_EP = 0, DRAWER_SPEED = 1;
    /** 用户正在拖进度条：此时 tick 不要覆盖进度。 */
    private boolean seeking;
    /** 首次播放就绪：只在这一刻把顶栏亮出来一次。 */
    private boolean firstReady = true;

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
    private static final int G_NONE = 0, G_SEEK = 1, G_BRIGHT = 2, G_VOL = 3, G_SWITCH = 4;
    private int gMode = G_NONE;
    private float gDownX, gDownY, gDy;
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
        tick();   // 底栏常显，进度定时器一直跑
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

    // ================= UI 构建（沉浸式叠层：视频铺满，控件浮在上面） =================
    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        // 关键：不用 media3 默认 controller（YouTube 风格、换集自动弹出），
        // 顶栏/底栏/右侧竖排/抽屉全部自己画
        playerView.setUseController(false);
        stage.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setOnTouchListener((v, ev) -> onTouch(ev));

        // ---- 顶栏（竖屏：轻点画面浮出；全屏不显示）----
        oTop = new LinearLayout(this);
        oTop.setOrientation(LinearLayout.HORIZONTAL);
        oTop.setGravity(Gravity.CENTER_VERTICAL);
        oTop.setBackground(topGrad());
        oTop.setPadding(dp(6), dp(8), dp(10), dp(14));

        oBack = tv("\u2039", 22, TXT);
        oBack.setPadding(dp(10), dp(4), dp(10), dp(4));
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

        oFav = tv("\u2661", 18, TXT);
        oFav.setPadding(dp(12), dp(4), dp(12), dp(4));
        oFav.setOnClickListener(v -> {
            boolean on = Store.toggleFav(this, sid, title, cover);
            syncFav();
            toast(on ? "已收藏" : "已取消收藏");
        });
        oTop.addView(oFav);
        oTop.setVisibility(View.GONE);
        stage.addView(oTop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // ---- 底栏（一行常显：播放 / 当前时间 / 进度 / 总时长 / 选集 / [倍速] / 全屏）----
        oBottom = new LinearLayout(this);
        oBottom.setOrientation(LinearLayout.HORIZONTAL);
        oBottom.setGravity(Gravity.CENTER_VERTICAL);
        oBottom.setBackground(bottomGrad());
        oBottom.setPadding(dp(8), dp(12), dp(8), dp(8));

        oPlay = tv("\u25b6", 16, TXT);
        oPlay.setPadding(dp(6), dp(4), dp(8), dp(4));
        oPlay.setOnClickListener(v -> togglePlay());
        oBottom.addView(oPlay);

        oCur = tv("00:00", 10, TXT);
        oCur.setPadding(0, 0, dp(6), 0);
        oBottom.addView(oCur);

        oSeek = new SeekBar(this);
        oSeek.setMax(1000);
        oSeek.setPadding(0, 0, 0, 0);
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
                seeking = true;
                ui.removeCallbacks(hideRun);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo(s.getProgress() * player.getDuration() / 1000L);
                }
                scheduleHide();
            }
        });
        oBottom.addView(oSeek, new LinearLayout.LayoutParams(0, dp(30), 1f));

        oDur = tv("00:00", 10, TXT);
        oDur.setPadding(dp(6), 0, dp(6), 0);
        oBottom.addView(oDur);

        epBtn = tv("选集", 12, TXT);
        epBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        epBtn.setOnClickListener(v -> openDrawer(DRAWER_EP));
        oBottom.addView(epBtn);

        spdBtn = tv("倍速", 12, TXT);
        spdBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        spdBtn.setOnClickListener(v -> openDrawer(DRAWER_SPEED));
        spdBtn.setVisibility(View.GONE);   // 竖屏没有：倍速在抽屉里
        oBottom.addView(spdBtn);

        oFull = tv("全屏", 12, TXT);
        oFull.setPadding(dp(8), dp(6), dp(2), dp(6));
        oFull.setOnClickListener(v -> toggleFullscreen());
        oBottom.addView(oFull);
        stage.addView(oBottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // ---- 右侧竖排：上一集 / 下一集（全屏恒显，不受控制条显隐影响）----
        sideNav = new LinearLayout(this);
        sideNav.setOrientation(LinearLayout.VERTICAL);
        sideNav.setGravity(Gravity.CENTER);
        GradientDrawable navBg = new GradientDrawable();
        navBg.setCornerRadius(dp(12));
        navBg.setColor(0x66000000);
        sideNav.setBackground(navBg);
        sideNav.setPadding(dp(4), dp(6), dp(4), dp(6));

        oPrev = tv("上一集", 12, TXT);
        oPrev.setGravity(Gravity.CENTER);
        oPrev.setPadding(dp(8), dp(10), dp(8), dp(10));
        oPrev.setOnClickListener(v -> playPrev());
        sideNav.addView(oPrev);

        oNext = tv("下一集", 12, TXT);
        oNext.setGravity(Gravity.CENTER);
        oNext.setPadding(dp(8), dp(10), dp(8), dp(10));
        oNext.setOnClickListener(v -> playNext());
        sideNav.addView(oNext);
        sideNav.setVisibility(View.GONE);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        navLp.rightMargin = dp(8);
        stage.addView(sideNav, navLp);

        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 抽屉遮罩：点画面空白收起抽屉 ----
        scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 抽屉：竖屏=底部半屏；全屏=右侧 1/3（层级最高，会盖住右侧竖排）----
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xF2121216);
        drawer.setPadding(dp(14), dp(10), dp(14), dp(10));
        drawer.setVisibility(View.GONE);

        qBlock = new LinearLayout(this);
        qBlock.setOrientation(LinearLayout.VERTICAL);
        qBlock.addView(label("清晰度"));
        qBar = new LinearLayout(this);
        qBar.setOrientation(LinearLayout.HORIZONTAL);
        qBar.setPadding(0, dp(6), 0, dp(10));
        android.widget.HorizontalScrollView qs = new android.widget.HorizontalScrollView(this);
        qs.setHorizontalScrollBarEnabled(false);
        qs.addView(qBar);
        qBlock.addView(qs);
        drawer.addView(qBlock);

        spdBlock = new LinearLayout(this);
        spdBlock.setOrientation(LinearLayout.VERTICAL);
        spdBlock.addView(label("倍速"));
        speedBar = new LinearLayout(this);
        speedBar.setOrientation(LinearLayout.HORIZONTAL);
        speedBar.setPadding(0, dp(6), 0, dp(10));
        for (float sp : SPEEDS) {
            final float fs = sp;
            TextView c = new TextView(this);
            c.setText(fmtSpeed(sp));
            c.setTextSize(12);
            c.setPadding(dp(10), dp(5), dp(10), dp(5));
            c.setTag(sp);
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
        android.widget.HorizontalScrollView ss = new android.widget.HorizontalScrollView(this);
        ss.setHorizontalScrollBarEnabled(false);
        ss.addView(speedBar);
        spdBlock.addView(ss);

        autoBtn = tv("", 12, DIM);
        autoBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        autoBtn.setOnClickListener(v -> {
            autoNext = !autoNext;
            markAuto();
            toast(autoNext ? "已开启自动连播" : "已关闭自动连播");
        });
        spdBlock.addView(autoBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        drawer.addView(spdBlock);

        epBlock = new LinearLayout(this);
        epBlock.setOrientation(LinearLayout.VERTICAL);
        epBlock.addView(label("选集"));
        epGrid = new RecyclerView(this);
        epGrid.setLayoutManager(new GridLayoutManager(this, 4));
        epGrid.setPadding(0, dp(6), 0, dp(6));
        epAdapter = new EpAdapter();
        epGrid.setAdapter(epAdapter);
        epBlock.addView(epGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        drawer.addView(epBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(drawer, drawerLp(false));

        // ---- 中央手势反馈 / 状态 ----
        centerHint = tv("", 14, Color.WHITE);
        centerHint.setGravity(Gravity.CENTER);
        GradientDrawable hg = new GradientDrawable();
        hg.setCornerRadius(dp(10));
        hg.setColor(0x99000000);
        centerHint.setBackground(hg);
        centerHint.setPadding(dp(18), dp(12), dp(18), dp(12));
        centerHint.setVisibility(View.GONE);
        root.addView(centerHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setText("正在打开…");
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        applyOrientationLayout();
        markSpeed();
        markAuto();
        syncFav();
        return root;
    }

    private GradientDrawable topGrad() {
        GradientDrawable g = new GradientDrawable();
        g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        g.setColors(new int[]{0xB3000000, 0x00000000});
        return g;
    }

    private GradientDrawable bottomGrad() {
        GradientDrawable g = new GradientDrawable();
        g.setOrientation(GradientDrawable.Orientation.BOTTOM_TOP);
        g.setColors(new int[]{0xCC000000, 0x00000000});
        return g;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(DIM);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    /** 抽屉尺寸：竖屏=底部半屏；全屏=右侧 1/3（最小 260dp）。 */
    private FrameLayout.LayoutParams drawerLp(boolean fs) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        if (fs) {
            int dw = Math.max(dp(260), w / 3);
            return new FrameLayout.LayoutParams(dw,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        }
        return new FrameLayout.LayoutParams(w, (int) (h * 0.55f), Gravity.BOTTOM);
    }

    /** 按竖屏/全屏应用差异：顶栏、右侧竖排、倍速键、抽屉位置、选集列数。 */
    private void applyOrientationLayout() {
        if (drawer == null) return;
        closeDrawer();
        if (fullscreen) {
            oTop.setVisibility(View.GONE);
            sideNav.setVisibility(View.VISIBLE);
            spdBtn.setVisibility(View.VISIBLE);
            oFull.setText("退出全屏");
            drawer.setLayoutParams(drawerLp(true));
            if (epGrid.getLayoutManager() instanceof GridLayoutManager) {
                ((GridLayoutManager) epGrid.getLayoutManager()).setSpanCount(3);
            }
        } else {
            sideNav.setVisibility(View.GONE);
            spdBtn.setVisibility(View.GONE);
            oFull.setText("全屏");
            oTop.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
            drawer.setLayoutParams(drawerLp(false));
            if (epGrid.getLayoutManager() instanceof GridLayoutManager) {
                ((GridLayoutManager) epGrid.getLayoutManager()).setSpanCount(4);
            }
        }
    }

    // ================= 抽屉 =================
    private void openDrawer(int mode) {
        drawerOpen = true;
        ui.removeCallbacks(hideRun);
        boolean ep = mode == DRAWER_EP;
        boolean speed = mode == DRAWER_SPEED;
        // 竖屏只有一个抽屉入口 → 三块都显示；全屏按按钮分开：
        //   倍速 → 清晰度 + 倍速 + 连播；选集 → 只有选集网格
        qBlock.setVisibility(speed || !fullscreen ? View.VISIBLE : View.GONE);
        spdBlock.setVisibility(speed || !fullscreen ? View.VISIBLE : View.GONE);
        epBlock.setVisibility(ep || !fullscreen ? View.VISIBLE : View.GONE);
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        if (ep && detail != null) scrollToEp(curEp);
    }

    private void closeDrawer() {
        drawerOpen = false;
        if (drawer != null) drawer.setVisibility(View.GONE);
        if (scrim != null) scrim.setVisibility(View.GONE);
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

    // ================= 控制层显隐（底栏常显，只有顶栏会隐藏） =================
    private void showControls() {
        ui.removeCallbacks(hideRun);
        controlsShown = true;
        if (!fullscreen) oTop.setVisibility(View.VISIBLE);
        oTitle.setText(title == null ? "" : title);
        tick();
        scheduleHide();
    }

    private void hideControls() {
        ui.removeCallbacks(hideRun);
        controlsShown = false;
        oTop.setVisibility(View.GONE);
    }

    /** 顶栏只在播放中倒计时隐藏；暂停/抽屉打开时保持显示。 */
    private void scheduleHide() {
        ui.removeCallbacks(hideRun);
        if (player != null && player.isPlaying() && !drawerOpen) {
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

    /** 进度条常显，所以 tick 一直跑；只做刷新 + 顶栏续约隐藏。 */
    private void tick() {
        ui.removeCallbacks(tickRun);
        if (player != null) {
            long dur = player.getDuration();
            long pos = player.getCurrentPosition();
            if (dur > 0) {
                oDur.setText(fmtMs(dur));
                if (!seeking) oSeek.setProgress((int) (pos * 1000L / dur));
            }
            if (!seeking) oCur.setText(fmtMs(pos));
            oPlay.setText(player.isPlaying() ? "\u275a\u275a" : "\u25b6");
        }
        ui.postDelayed(tickRun, 500);
        if (controlsShown && !drawerOpen && player != null && player.isPlaying()) {
            ui.removeCallbacks(hideRun);
            ui.postDelayed(hideRun, 3500);
        }
    }

    /** 单击/双击都走这里：暂停就把顶栏带出来（收藏/上一集/下一集都在）。 */
    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            showControls();
        } else {
            player.play();
            showControls();
            scheduleHide();
        }
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
                gDy = 0;
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
                gDy = dy;
                if (gMode == G_NONE && Math.hypot(dx, dy) > dp(18)) {
                    if (!fullscreen && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        // 竖屏：上下滑切集（红果/抖音的短剧手势）
                        gMode = G_SWITCH;
                    } else if (Math.abs(dx) > Math.abs(dy) && player != null
                            && player.getDuration() > 0) {
                        gMode = G_SEEK;
                    } else if (fullscreen) {
                        gMode = ev.getX() < stage.getWidth() / 2f ? G_BRIGHT : G_VOL;
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
                }
                if (gMode == G_SWITCH) {
                    playerView.setTranslationY(dy);
                    if (Math.abs(dy) > stage.getHeight() * 0.10f) {
                        setHint(dy < 0 ? "松开看下一集" : "松开看上一集");
                    } else {
                        centerHint.setVisibility(View.GONE);
                    }
                } else if (gMode == G_SEEK) {
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
            case MotionEvent.ACTION_CANCEL: {
                if (gMode == G_SWITCH) {
                    float h = stage.getHeight();
                    if (Math.abs(gDy) > h * 0.15f) {
                        playerView.setTranslationY(0);
                        if (gDy < 0) playNext();
                        else playPrev();
                    } else {
                        playerView.animate().translationY(0).setDuration(160).start();
                    }
                } else if (gMode == G_SEEK && player != null && player.getDuration() > 0) {
                    player.seekTo(gTargetMs);
                    scheduleHide();
                }
                gMode = G_NONE;
                centerHint.setVisibility(View.GONE);
                return true;
            }
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
            if (drawerOpen) {
                closeDrawer();
                return true;
            }
            // 单击 = 暂停/恢复，同时把顶栏带出来（收藏/上一集/下一集都在）
            togglePlay();
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
                setHint("\u00ab 10s");
                ui.removeCallbacks(hintRun);
                ui.postDelayed(hintRun, 600);
            } else if (x > w * 2f / 3f) {
                long t = player.getCurrentPosition() + 10000L;
                if (player.getDuration() > 0 && t > player.getDuration()) {
                    t = player.getDuration();
                }
                player.seekTo(t);
                setHint("10s \u00bb");
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
        // 换集：顶栏收起、抽屉关闭、上下滑的位移归零
        hideControls();
        closeDrawer();
        if (playerView != null) playerView.setTranslationY(0);
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
            Cache.pin(plain);            // 正在播的这集任何清理都不许删
            curPlain = plain;
            startPlayFile(plain, startSec);
            return;
        }
        File enc = new File(getCacheDir(), tag + ".enc");
        curEnc = enc;
        Cache.pin(enc);
        // 写盘前先做一次淘汰，避免整集预分配把磁盘吃满
        Cache.ensure(getCacheDir(), 0);

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
        Cache.pin(enc);
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
                    tick();
                    if (firstReady) {          // 首次进页面先亮一下顶栏，之后换集不再弹
                        firstReady = false;
                        showControls();
                    }
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
        hideControls();
        applyOrientationLayout();
        if (fullscreen) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        // 转屏后屏幕尺寸变了，抽屉宽高/列数再套一次
        ui.postDelayed(this::applyOrientationLayout, 400);
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
    }

    @Override
    public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        ui.post(this::applyOrientationLayout);
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
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
        Cache.unpin();
        Cache.trim(getCacheDir());
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
