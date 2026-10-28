package com.hongguo.client;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hongguo.client.cenc.Decryptor;
import com.hongguo.client.cenc.Spade;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放页（v0.7.0）：对齐原生红果的两段式。
 *
 *  第一段 feed（启动即进入）：视频铺满整屏（按片源比例 fit，横屏上下留黑），
 *    左下叠剧名/标签/「第N集 | 简介」/观看完整短剧，右侧追剧，底部 首页|剧场。
 *    上下滑 = 整部剧切换（跟手），单击 = 显隐信息层，双击 = 暂停，长按 = 2 倍速。
 *
 *  第二段 详情：点「观看完整短剧」→ 视频**向上收**成 16:9 居中，下方展开信息区，
 *    单击唤出控制层（顶部 ‹第N集 倍速 ⋮ / 底部 播放|进度|选集），
 *    上下滑 = 本剧切集，选集 = 手动点「选集」弹面板（1-30/31-60 分页 + 6 列）。
 *
 *  切集速度：PlayerEngine 双 ExoPlayer，空闲槽预载下一条（只缓冲、不解码），
 *  切换时交换两个槽的 surface，首帧几乎无等待。
 */
public class PlayActivity extends AppCompatActivity {

    private static final int PINK = 0xFFFF2C55;
    private static final int TXT = 0xFFF2F2F2;
    private static final int DIM = 0xFF8A8A8E;

    private static final String E_SID = "sid";
    private static final String E_TITLE = "title";
    private static final String E_COVER = "cover";

    /** 无 sid 启动 = feed（launcher）；带 sid = 剧场点进来。 */
    private static final int MODE_FEED = 0;
    private static final int MODE_SERIES = 1;

    /** 倍速档位上限就是 2.0x；长按临时加速用同一个值，保持完全一致。 */
    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final float LONG_PRESS_SPEED = 2.0f;

    private int mode = MODE_FEED;
    private FeedQueue queue;
    private FeedQueue.Item curItem;

    private PlayerEngine engine;
    private int activeSlot = 0;
    private int pendingSwapSlot = -1;
    /** 换台前记住游标，取流失败时原地退回（否则会停在一个打不开的条目上）。 */
    private int pendingPrevCursor = -1;
    private int pendingDir = 1;
    private int swapSeq;
    /** 每个槽的请求序号：槽被换内容后，先前那次 Api 回调必须自动作废。 */
    private final int[] slotToken = new int[2];
    private boolean switching;
    private boolean preloading;
    private boolean extending;
    private boolean noMore;
    private boolean firstReady = true;
    private boolean fullscreen;
    private boolean drawerOpen;
    private boolean autoNext = true;
    /** false = 首页 feed 全屏态；true = 详情态（视频收到 16:9 居中，下面展开信息）。 */
    private boolean detail;

    private String quality = "";
    private String shownSeriesId = "";
    private float speed = 1.0f;
    private boolean boosting;

    // ---------------- UI ----------------
    private FrameLayout root, stage, rail;
    private LinearLayout bottomStack, tabBar, capBar, oTop, oBottom, drawer, epBlock;
    private LinearLayout oTopRow, oEpRow;
    /** 首页 feed 态叠在画面左下角的信息层（剧名/标签/第N集|简介/观看完整短剧）。 */
    private LinearLayout feedLayer;
    private TextView feedTitle, feedTag, feedEp, feedOpen;
    private TextView status, centerHint, oCenterPlay, oSearch, oRailFav;
    private TextView oBack, oEpTitle, oSpd, oMore, oPlay, oCur, oDur, oEpBtn, oFull, autoBtn;
    private TextView tabHome, tabTheater;
    /** 详情态底部一行：选集 / 倍速 / 清晰度 / 全屏。 */
    private TextView epCapsule, spdCapsule, qCapsule, fsBtn;
    private ThinSeek thinSeek, oSeek;
    private View scrim, gestureLayer;
    private RecyclerView epGrid;
    private EpAdapter epAdapter;
    private boolean seeking;
    /** 锚定在胶囊上的选择弹窗（倍速/清晰度），同一时刻只允许一个。 */
    private PopupWindow popup;
    private View popupAnchor;
    private List<String> qDefs = new ArrayList<>();
    /** 详情态队列快照：进详情时换成该剧整剧，返回时还原 feed 队列与游标。 */
    private FeedQueue feedQueue;
    private int feedCursor = -1;
    /** 白底选集面板（同一部剧复用，换剧重建）。 */
    private EpisodeSheet sheet;
    private String sheetDetailId = "";

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------- 手势状态 ----------------
    private static final int G_NONE = 0, G_SEEK = 1, G_SWITCH = 2;
    private int gMode = G_NONE;
    private float gDownX, gDownY, gDy;
    private long gStartMs, gTargetMs;
    private long lastTapAt;
    private boolean longFired;

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
        String sid = getIntent().getStringExtra(E_SID);
        mode = sid == null || sid.isEmpty() ? MODE_FEED : MODE_SERIES;
        detail = mode == MODE_SERIES;      // 从剧场点进来就直接是详情态
        setContentView(build());
        try {
            Api.init(this);
        } catch (Exception e) {
            setStatus("初始化失败: " + e);
            return;
        }
        if (mode == MODE_FEED) {
            loadFeed();
        } else {
            loadSeries(sid, getIntent().getStringExtra(E_TITLE),
                    getIntent().getStringExtra(E_COVER));
        }
        ui.post(tickRun);   // 进度条常显，定时器一直跑
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ================= 布局 =================
    /**
     * 单容器布局：stage 永远铺满整屏，feed / 详情 / 全屏三态**只切叠加层可见性**，
     * 绝不改 stage 的 LayoutParams —— 这样进详情时视频一像素都不会动。
     *
     * root
     *  ├─ stage（播放器唯一宿主，MATCH_PARENT）
     *  ├─ feedLayer（feed 态左下信息层，底部对齐，避让底部栏）
     *  ├─ bottomStack（底部堆叠，自下而上：thinSeek → tabBar/capBar）
     *  ├─ oSearch / rail / 全屏顶底栏 / 抽屉 / 提示
     */
    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        // ---- 视频区：直接铺满 root ----
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        engine = new PlayerEngine(this, new EngineCb());
        engine.attach(stage);

        gestureLayer = new View(this);
        gestureLayer.setBackgroundColor(Color.TRANSPARENT);
        gestureLayer.setOnTouchListener((v, ev) -> onGesture(ev));
        stage.addView(gestureLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 中央播放键（暂停时 / 单击后可见）
        oCenterPlay = tv("\u25b6", 26, Color.WHITE);
        oCenterPlay.setGravity(Gravity.CENTER);
        GradientDrawable cpg = new GradientDrawable();
        cpg.setShape(GradientDrawable.OVAL);
        cpg.setColor(0x66000000);
        oCenterPlay.setBackground(cpg);
        oCenterPlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams cpLp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        oCenterPlay.setOnClickListener(v -> {
            togglePlay();
            if (engine.slot(activeSlot).player != null
                    && engine.slot(activeSlot).player.isPlaying()) oCenterPlay.setVisibility(View.GONE);
        });
        stage.addView(oCenterPlay, cpLp);

        // 右侧追剧
        rail = new FrameLayout(this);
        oRailFav = tv("\u2606", 22, Color.WHITE);
        oRailFav.setGravity(Gravity.CENTER);
        oRailFav.setPadding(dp(8), dp(8), dp(8), dp(4));
        oRailFav.setOnClickListener(v -> {
            if (curItem == null) return;
            boolean on = Store.toggleFav(this, curItem.sid, curItem.seriesTitle, curItem.cover);
            syncFav();
            toast(on ? "已追剧" : "已取消追剧");
        });
        rail.addView(oRailFav, new FrameLayout.LayoutParams(dp(40), dp(40)));
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        railLp.rightMargin = dp(6);
        stage.addView(rail, railLp);

        // ---- 首页 feed 信息层：叠在画面左下（原生就是这样，压在视频上）----
        feedLayer = new LinearLayout(this);
        feedLayer.setOrientation(LinearLayout.VERTICAL);
        feedLayer.setPadding(dp(14), dp(18), dp(62), dp(10));
        GradientDrawable flg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x99000000, 0x00000000});
        feedLayer.setBackground(flg);

        LinearLayout feedTitleRow = new LinearLayout(this);
        feedTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        feedTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        feedTitle = tv("", 17, Color.WHITE);
        feedTitle.setMaxLines(1);
        feedTitle.setShadowLayer(4, 1, 1, 0x99000000);
        feedTitleRow.addView(feedTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView feedArrow = tv("\u203a", 18, 0xFFDDDDDD);
        feedArrow.setPadding(dp(6), 0, 0, 0);
        feedTitleRow.addView(feedArrow);
        feedTitleRow.setOnClickListener(v -> enterDetail());
        feedLayer.addView(feedTitleRow);

        feedTag = tv("剧情", 11, 0xFFE8A0AE);
        feedTag.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable ftg = new GradientDrawable();
        ftg.setCornerRadius(dp(4));
        ftg.setColor(0x33FF2C55);
        feedTag.setBackground(ftg);
        LinearLayout.LayoutParams ftLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ftLp.topMargin = dp(6);
        feedLayer.addView(feedTag, ftLp);

        feedEp = tv("", 12, 0xFFE2E2E6);
        feedEp.setMaxLines(2);
        feedEp.setLineSpacing(dp(2), 1f);
        feedEp.setShadowLayer(4, 1, 1, 0x99000000);
        feedEp.setPadding(0, dp(8), 0, 0);
        feedLayer.addView(feedEp);

        feedOpen = tv("", 13, 0xFFEDEDF0);
        feedOpen.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable fog = new GradientDrawable();
        fog.setCornerRadius(dp(6));
        fog.setColor(0x33FFFFFF);
        feedOpen.setBackground(fog);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(10);
        feedOpen.setOnClickListener(v -> enterDetail());
        feedLayer.addView(feedOpen, fLp);

        stage.addView(feedLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // ---- 底部堆叠（自下而上：细进度条 → tabBar / capBar），都叠在视频上、不占视频高度 ----
        bottomStack = new LinearLayout(this);
        bottomStack.setOrientation(LinearLayout.VERTICAL);

        // 详情态：选集 78集 ｜ 1.0x ｜ 1080P ｜ ⛶
        capBar = new LinearLayout(this);
        capBar.setOrientation(LinearLayout.HORIZONTAL);
        capBar.setGravity(Gravity.CENTER_VERTICAL);
        capBar.setBackgroundColor(0x9E000000);
        capBar.setPadding(dp(12), dp(7), dp(10), dp(7));
        capBar.setVisibility(View.GONE);

        LinearLayout caps = new LinearLayout(this);
        caps.setOrientation(LinearLayout.HORIZONTAL);
        caps.setGravity(Gravity.CENTER_VERTICAL);
        epCapsule = capsule("");
        epCapsule.setOnClickListener(v -> openSheet());
        caps.addView(epCapsule);
        spdCapsule = capsule("1.0x");
        spdCapsule.setOnClickListener(v -> showSpeedPopup(spdCapsule));
        caps.addView(spdCapsule);
        qCapsule = capsule("1080P");
        qCapsule.setOnClickListener(v -> showQualityPopup(qCapsule));
        caps.addView(qCapsule);
        HorizontalScrollView capScroll = new HorizontalScrollView(this);
        capScroll.setHorizontalScrollBarEnabled(false);
        capScroll.addView(caps);
        capBar.addView(capScroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        fsBtn = tv("\u26f6", 17, Color.WHITE);
        fsBtn.setGravity(Gravity.CENTER);
        GradientDrawable fsg = new GradientDrawable();
        fsg.setShape(GradientDrawable.OVAL);
        fsg.setColor(0x33FFFFFF);
        fsBtn.setBackground(fsg);
        fsBtn.setOnClickListener(v -> toggleFullscreen());
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        fsLp.leftMargin = dp(6);
        capBar.addView(fsBtn, fsLp);
        bottomStack.addView(capBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // feed 态：首页 ｜ 剧场
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setBackgroundColor(0x9E000000);
        tabHome = tv("首页", 14, PINK);
        tabHome.setGravity(Gravity.CENTER);
        tabTheater = tv("剧场", 14, DIM);
        tabTheater.setGravity(Gravity.CENTER);
        tabTheater.setOnClickListener(v -> openTheater());
        tabBar.addView(tabHome, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabBar.addView(tabTheater, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomStack.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        // 最底：常显细进度条（贴屏幕最底）
        thinSeek = new ThinSeek();
        thinSeek.setBottomStyle(true);
        bottomStack.addView(thinSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));

        root.addView(bottomStack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // ---- 右上搜索（竖屏常显）----
        oSearch = tv("\ud83d\udd0d", 18, Color.WHITE);
        oSearch.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(dp(40), dp(40),
                Gravity.TOP | Gravity.END);
        sLp.topMargin = dp(6);
        sLp.rightMargin = dp(8);
        oSearch.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        root.addView(oSearch, sLp);

        // ---- 全屏顶栏 ----
        oTop = new LinearLayout(this);
        oTop.setOrientation(LinearLayout.VERTICAL);
        oTop.setBackground(topGrad());
        oTopRow = new LinearLayout(this);
        oTopRow.setOrientation(LinearLayout.HORIZONTAL);
        oTopRow.setGravity(Gravity.CENTER_VERTICAL);
        oTopRow.setPadding(dp(6), dp(8), dp(10), dp(10));
        oBack = tv("\u2039", 24, TXT);
        oBack.setPadding(dp(8), 0, dp(8), dp(2));
        oBack.setOnClickListener(v -> onBackPressed());
        oTopRow.addView(oBack);
        oEpTitle = tv("", 13, TXT);
        oEpTitle.setMaxLines(1);
        oTopRow.addView(oEpTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        oSpd = tv("倍速", 13, TXT);
        oSpd.setPadding(dp(10), dp(4), dp(10), dp(4));
        oSpd.setOnClickListener(v -> openDrawer());
        oTopRow.addView(oSpd);
        oMore = tv("\u22ee", 18, TXT);
        oMore.setPadding(dp(10), dp(2), dp(10), dp(2));
        oMore.setOnClickListener(v -> openDrawer());
        oTopRow.addView(oMore);
        oTop.addView(oTopRow);
        oTop.setVisibility(View.GONE);
        root.addView(oTop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // ---- 全屏底栏（两行：进度 + 选集/全屏）----
        oBottom = new LinearLayout(this);
        oBottom.setOrientation(LinearLayout.VERTICAL);
        oBottom.setBackground(bottomGrad());
        oBottom.setPadding(dp(8), dp(10), dp(10), dp(6));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        oPlay = tv("\u25b6", 16, TXT);
        oPlay.setPadding(dp(6), dp(4), dp(8), dp(4));
        oPlay.setOnClickListener(v -> togglePlay());
        row1.addView(oPlay);
        oCur = tv("00:00", 11, TXT);
        row1.addView(oCur);
        oSeek = new ThinSeek();
        row1.addView(oSeek, new LinearLayout.LayoutParams(0, dp(18), 1f));
        oDur = tv("00:00", 11, TXT);
        oDur.setPadding(dp(6), 0, 0, 0);
        row1.addView(oDur);
        oBottom.addView(row1);

        oEpRow = new LinearLayout(this);
        oEpRow.setOrientation(LinearLayout.HORIZONTAL);
        oEpRow.setGravity(Gravity.CENTER_VERTICAL);
        oEpRow.setPadding(0, dp(2), 0, 0);
        oEpBtn = tv("选集", 12, TXT);
        oEpBtn.setPadding(dp(6), dp(6), dp(8), dp(6));
        oEpBtn.setOnClickListener(v -> openSheet());
        oEpRow.addView(oEpBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        oFull = tv("退出全屏", 12, TXT);
        oFull.setPadding(dp(8), dp(6), dp(6), dp(6));
        oFull.setOnClickListener(v -> toggleFullscreen());
        oEpRow.addView(oFull);
        oBottom.addView(oEpRow);
        oBottom.setVisibility(View.GONE);
        root.addView(oBottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // ---- 抽屉 ----
        scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xF2121216);
        drawer.setPadding(dp(14), dp(10), dp(14), dp(10));
        drawer.setVisibility(View.GONE);

        // 倍速 / 清晰度已经改成贴着胶囊的锚定弹窗，抽屉里只留选集与连播开关
        autoBtn = tv("", 12, DIM);
        autoBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        autoBtn.setOnClickListener(v -> {
            autoNext = !autoNext;
            markAuto();
            toast(autoNext ? "已开启自动连播" : "已关闭自动连播");
        });
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.bottomMargin = dp(6);
        drawer.addView(autoBtn, abLp);

        epBlock = new LinearLayout(this);
        epBlock.setOrientation(LinearLayout.VERTICAL);
        epBlock.addView(label("选集"));
        epGrid = new RecyclerView(this);
        GridLayoutManager glm = new GridLayoutManager(this, 6);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return epAdapter != null && epAdapter.isHeader(position) ? 6 : 1;
            }
        });
        epGrid.setLayoutManager(glm);
        epGrid.setPadding(0, dp(6), 0, dp(6));
        epAdapter = new EpAdapter();
        epGrid.setAdapter(epAdapter);
        epBlock.addView(epGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        drawer.addView(epBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(drawer, drawerLp(false));

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

        applyOrientation();
        markAuto();
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

    private TextView tv(String text, float sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(DIM);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

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

    /**
     * 三态（feed / 详情 / 横屏全屏）**只切叠加层可见性**。
     * stage 永远是 MATCH_PARENT，这里绝不改它的 LayoutParams ——
     * 之前那版把详情态改成 16:9 居中，导致"进详情视频往上跑"，是本轮要彻底去掉的行为。
     */
    private void applyOrientation() {
        boolean dt = detail || mode == MODE_SERIES;
        boolean feedUi = !fullscreen && !dt;

        ViewGroup.LayoutParams slp = stage.getLayoutParams();
        if (slp.width != ViewGroup.LayoutParams.MATCH_PARENT
                || slp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            stage.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        feedLayer.setVisibility(feedUi ? View.VISIBLE : View.GONE);
        // 底部栏叠在画面上，feed 的信息层要让开（tabBar 46 + 进度条 14 + 余量 8）
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) feedLayer.getLayoutParams();
        flp.bottomMargin = dp(46 + 14 + 8);
        feedLayer.setLayoutParams(flp);

        bottomStack.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        tabBar.setVisibility(feedUi ? View.VISIBLE : View.GONE);
        capBar.setVisibility(!fullscreen && dt ? View.VISIBLE : View.GONE);
        thinSeek.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        oSearch.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        rail.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        oTop.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        oBottom.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        oSpd.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        oMore.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        if (!fullscreen) oCenterPlay.setVisibility(View.GONE);
        drawer.setLayoutParams(drawerLp(fullscreen));
        if (epGrid != null && epGrid.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) epGrid.getLayoutManager()).setSpanCount(6);
        }
        updateCapsules();
    }

    /**
     * 首页 → 详情：**0ms 硬切，没有任何动画**。
     * 只做三件事：队列换成该剧整剧（这样上下滑才是切集而不是切剧）、
     * 左下信息层换成底部按钮行、预载改成"下一集"。
     * 视频的位置与尺寸一个字都不改。
     */
    private void enterDetail() {
        if (detail || fullscreen) return;
        detail = true;
        switchToSeriesQueue();
        applyOrientation();
        preloadNext();
    }

    /** 详情 → 首页：同样 0ms，队列还原成 feed（游标回到进的哪一部）。 */
    private void exitDetail() {
        if (!detail) return;
        detail = false;
        oTop.setVisibility(View.GONE);
        oBottom.setVisibility(View.GONE);
        restoreFeedQueue();
        applyOrientation();
        preloadNext();
    }

    /** 进入详情时把 feed 队列（每部剧一条）换成该剧的整剧队列，并把 feed 队列存快照。 */
    private void switchToSeriesQueue() {
        if (queue == null || !queue.isFeed()) return;
        Model.Detail d = queue.curDetail();
        if (d == null) return;
        FeedQueue.Item cur = curItem != null ? curItem : queue.current();
        if (cur == null) return;

        feedQueue = queue;
        feedCursor = queue.cursorIndex();
        FeedQueue q = FeedQueue.ofSeries(d);
        if (!q.seekEp(cur.epIndex())) q.seekVid(cur.vid());
        queue = q;
        curItem = q.current();
        epAdapter.set(q.curDetail());
        // 空闲槽里预载的是"下一部剧"，语境已变，作废重来
        slotToken[1 - activeSlot]++;
        engine.releaseSlot(1 - activeSlot);
        preloading = false;
        updateUi();
    }

    private void restoreFeedQueue() {
        if (feedQueue == null) return;
        queue = feedQueue;
        feedQueue = null;
        if (feedCursor >= 0) queue.cursor(feedCursor);
        feedCursor = -1;
        curItem = queue.current();
        slotToken[1 - activeSlot]++;
        engine.releaseSlot(1 - activeSlot);
        preloading = false;
        updateUi();
    }

    // ================= 加载 =================
    private void loadFeed() {
        setStatus("正在打开…");
        Api.run(() -> {
            FeedQueue q = FeedQueue.ofFeed("recommend");
            q.prime();
            return q;
        }, new Api.Cb<FeedQueue>() {
            @Override
            public void ok(FeedQueue v) {
                queue = v;
                startCurrent(0);
            }

            @Override
            public void err(Exception e) {
                setStatus("加载推荐失败\n" + msg(e) + "\n\n下拉或重进 App 试试。");
            }
        });
    }

    private void loadSeries(String sid, String title, String cover) {
        setStatus("正在获取剧集…");
        Api.run(() -> Api.episodes(sid), new Api.Cb<Model.Detail>() {
            @Override
            public void ok(Model.Detail v) {
                queue = FeedQueue.ofSeries(v);
                int[] pg = Store.progress(PlayActivity.this, sid);
                queue.seekEp(pg[0]);
                startCurrent(pg[1]);
            }

            @Override
            public void err(Exception e) {
                setStatus("获取剧集失败\n" + msg(e));
            }
        });
    }

    private void startCurrent(int startSec) {
        FeedQueue.Item it = queue == null ? null : queue.current();
        if (it == null) {
            setStatus("这部剧没有可播放的集");
            return;
        }
        curItem = it;
        epAdapter.set(queue.curDetail());
        updateUi();
        // 另一槽可能是同一条的预载（切清晰度会撞同一个缓存文件），先放掉再重开
        engine.releaseSlot(1 - activeSlot);
        View v = engine.view(activeSlot);
        v.animate().cancel();
        v.setTranslationY(0);
        v.setVisibility(View.VISIBLE);
        openInto(activeSlot, it, startSec, false, null);
    }

    /** 取直链/密钥 → 建流 → 交给引擎（preload=true 时只缓冲不出声）。 */
    private void openInto(final int slot, final FeedQueue.Item it, final int startSec,
                          final boolean preload, final Runnable after) {
        final int token = ++slotToken[slot];     // 同一槽的新请求会让旧回调作废
        Api.run(() -> {
            List<Model.Track> ts = Api.tracks(it.vid());
            if (ts == null || ts.isEmpty()) throw new java.io.IOException("该集没有可用直链");
            Collections.sort(ts, (a, b) -> b.num() - a.num());
            Model.Track pick = ts.get(0);
            for (Model.Track t : ts) {
                if (!quality.isEmpty() && quality.equals(t.definition)) {
                    pick = t;
                    break;
                }
            }
            String key = pick.encrypted ? Spade.spadeToKey(pick.spadeA) : null;
            return new Object[]{pick, key, ts};
        }, new Api.Cb<Object[]>() {
            @Override
            public void ok(Object[] v) {
                if (slotToken[slot] != token) return;   // 期间这个槽被换了别的东西，丢弃
                if (queue == null) return;
                Model.Track t = (Model.Track) v[0];
                String key = (String) v[1];
                @SuppressWarnings("unchecked")
                List<Model.Track> ts = (List<Model.Track>) v[2];
                lastTracks = ts;
                quality = t.definition;
                buildQualityList();
                updateCapsules();
                String tag = it.vid() + "." + safe(t.definition);
                File plain = new File(getCacheDir(), tag + ".mp4");
                File enc = new File(getCacheDir(), tag + ".enc");
                if (plain.exists() && plain.length() > 1024) {
                    Cache.touch(plain);
                    Cache.pin(plain);
                    engine.load(slot, it, t, null, plain, true, startSec, preload);
                } else {
                    engine.load(slot, it, t, key, enc, false, startSec, preload);
                }
                if (after != null) after.run();
            }

            @Override
            public void err(Exception e) {
                if (slotToken[slot] != token) return;
                preloading = false;
                if (after != null) after.run();
                if (slot == activeSlot) {
                    setStatus("取播放地址失败\n" + msg(e));
                    return;
                }
                // 换集失败：把游标退回原处，别停在一个打不开的条目上
                if (pendingSwapSlot == slot) {
                    pendingSwapSlot = -1;
                    switching = false;
                    swapSeq++;
                    if (queue != null && pendingPrevCursor >= 0) queue.cursor(pendingPrevCursor);
                    pendingPrevCursor = -1;
                    curItem = queue == null ? null : queue.current();
                    updateUi();
                }
                toast("这条打不开：" + msg(e));
            }
        });
    }

    private void updateUi() {
        if (curItem == null) return;
        if (!curItem.sid.equals(shownSeriesId)) {
            shownSeriesId = curItem.sid;
            epAdapter.set(queue == null ? null : queue.curDetail());
        }

        // 首页 feed 的左下信息层（原生样式：剧名 / 标签 / 第N集|简介 / 观看完整短剧）
        if (feedTitle != null) {
            feedTitle.setText(curItem.seriesTitle);
            String intro = curItem.intro == null ? "" : curItem.intro.trim();
            feedEp.setText("第 " + curItem.epIndex() + " 集"
                    + (intro.isEmpty() ? "" : " | " + intro));
            feedOpen.setText("观看完整短剧 · 全 " + curItem.epCnt + " 集");
        }
        oEpTitle.setText("第 " + curItem.epIndex() + " 集");
        oEpBtn.setText("选集 · 全 " + curItem.epCnt + " 集");
        epAdapter.highlight(curItem.epIndex());
        updateCapsules();
        if (sheet != null && sheet.isOpen()) sheet.setCurrent(curItem.epIndex());
        syncFav();
        Store.recordHist(this, curItem.sid, curItem.seriesTitle, curItem.cover,
                curItem.epIndex(), curItem.epCnt, 0);
    }

    /** 底部三枚胶囊：选集 N集 ｜ 1.0x ｜ 1080P（长按加速时倍速胶囊临时变 2.0x 并转橙）。 */
    private void updateCapsules() {
        if (epCapsule == null) return;
        int n = curItem == null ? 0 : curItem.epCnt;
        epCapsule.setText(n > 0 ? "选集 " + n + "集" : "选集");
        spdCapsule.setText(fmtSpeed(boosting ? LONG_PRESS_SPEED : speed));
        spdCapsule.setTextColor(boosting ? PINK : TXT);
        qCapsule.setText(quality == null || quality.isEmpty() ? "清晰度" : quality.toUpperCase());
    }

    private boolean isFullSeries() {
        return queue != null && !queue.isFeed();
    }

    private void syncFav() {
        boolean on = curItem != null && Store.isFav(this, curItem.sid);
        oRailFav.setText(on ? "\u2605" : "\u2606");
        oRailFav.setTextColor(on ? PINK : Color.WHITE);
    }

    // ================= 切换 =================
    /**
     * 首页 feed 的上下滑：**整部剧**切换（落到新剧第 1 集），不是集切换。
     * 这是原生 feed 的行为，也是之前"滑一下只换了一集"的地方。
     */
    private void switchSeries(int dir) {
        if (switching || queue == null) return;
        if (!queue.isFeed()) {
            switchBy(dir);
            return;
        }
        int prevCursor = queue.cursorIndex();
        if (!queue.advanceSeries(dir)) {
            if (dir > 0 && !noMore) {
                extendThenSeries(dir);
                return;
            }
            bounce(dir);
            toast(dir > 0 ? "没有更多了" : "已经是第一部");
            return;
        }
        FeedQueue.Item target = queue.current();
        switching = true;
        pendingDir = dir;
        pendingSwapSlot = -1;
        pendingPrevCursor = prevCursor;
        int idle = 1 - activeSlot;
        PlayerEngine.Slot s = engine.slot(idle);
        engine.throttle(activeSlot);
        if (s.item != null && s.item.sameAs(target)) {
            if (s.ready) {
                doSwap(dir);
                return;
            }
            doSwap(dir);
            armStallHint(idle);
            return;
        }
        setStatus("正在打开…");
        pendingSwapSlot = idle;
        armSwapTimeout();
        openInto(idle, target, 0, false, null);
    }

    /** 榜单用完了：先补一部，再切过去。 */
    private void extendThenSeries(final int dir) {
        if (extending) return;
        extending = true;
        setHint("正在加载下一部…");
        Api.run(() -> queue.extend(), new Api.Cb<Boolean>() {
            @Override
            public void ok(Boolean v) {
                extending = false;
                centerHint.setVisibility(View.GONE);
                if (v != null && v) {
                    switchSeries(dir);
                } else {
                    noMore = true;
                    bounce(dir);
                    toast("没有更多了");
                }
            }

            @Override
            public void err(Exception e) {
                extending = false;
                centerHint.setVisibility(View.GONE);
                toast("加载失败：" + msg(e));
            }
        });
    }

    /** 上滑(dir=1) / 下滑(dir=-1)。详情与全屏都走这里 = 本剧切集。 */
    private void switchBy(int dir) {
        if (switching || queue == null) return;
        FeedQueue.Item target = dir > 0 ? queue.peekNext() : queue.peekPrev();
        if (target == null) {
            if (dir > 0 && queue.isFeed() && !noMore) {
                extendThen(dir);
                return;
            }
            bounce(dir);
            toast(dir > 0 ? "已经是最后一集" : "已经是第一集");
            return;
        }
        switching = true;
        pendingDir = dir;
        pendingSwapSlot = -1;
        pendingPrevCursor = queue.cursorIndex();
        queue.advance(dir);

        int idle = 1 - activeSlot;
        PlayerEngine.Slot s = engine.slot(idle);
        engine.throttle(activeSlot);   // 还在播的这一条限速，别跟预载抢带宽
        if (s.item != null && s.item.sameAs(target)) {
            if (s.ready) {
                doSwap(dir);
                return;
            }
            // 预载到了这一条但还没 READY：别干等，先上台让 ExoPlayer 自己接缓冲。
            // 之前这里是"挂 5 秒超时什么都不做"，于是每次切集都像死了 5 秒。
            doSwap(dir);
            armStallHint(idle);
            return;
        }
        setStatus("正在打开…");
        pendingSwapSlot = idle;
        armSwapTimeout();
        openInto(idle, target, 0, false, null);
    }

    /** 没预载过的条目：等它 READY 再交换；4 秒还没好就强制换，别把界面锁死。 */
    private void armSwapTimeout() {
        final int slot = pendingSwapSlot;
        final int token = ++swapSeq;
        ui.postDelayed(() -> {
            if (token != swapSeq || pendingSwapSlot != slot || pendingSwapSlot < 0) return;
            setStatus("加载有点慢…");
            doSwap(pendingDir);
        }, 4000);
    }

    /** 已经上台但还没出画：800ms 后才提示，不再拿"等 5 秒"当常态。 */
    private void armStallHint(final int slot) {
        final int token = ++swapSeq;
        ui.postDelayed(() -> {
            if (token != swapSeq || slot != activeSlot) return;
            if (engine.slot(slot).ready) return;
            setStatus("正在缓冲…");
        }, 800);
    }

    private void doSwap(int dir) {
        int newSlot = pendingSwapSlot >= 0 ? pendingSwapSlot : (1 - activeSlot);
        int oldSlot = activeSlot;
        pendingSwapSlot = -1;

        View nv = engine.view(newSlot);
        View ov = engine.view(oldSlot);
        int h = Math.max(1, stage.getHeight());
        nv.animate().cancel();
        ov.animate().cancel();
        nv.setTranslationY(dir > 0 ? h : -h);
        nv.setVisibility(View.VISIBLE);
        engine.activate(newSlot, boosting ? LONG_PRESS_SPEED : speed);
        activeSlot = newSlot;

        nv.animate().translationY(0).setDuration(200).start();
        final int park = h;
        ov.animate().translationY(dir > 0 ? -h : h).setDuration(200)
                .withEndAction(() -> {
                    ov.setVisibility(View.INVISIBLE);
                    ov.setTranslationY(park);
                }).start();

        switching = false;
        curItem = queue == null ? null : queue.current();
        updateUi();
        status.setVisibility(View.GONE);
        ui.postDelayed(() -> {
            if (oldSlot == activeSlot) return;   // 又切回来了，别把正在播的释放掉
            engine.releaseSlot(oldSlot);
            preloadNext();
        }, 260);
    }

    private void bounce(int dir) {
        int h = stage.getHeight();
        stage.animate().cancel();
        stage.setTranslationY(dir < 0 ? -h * 0.06f : h * 0.06f);
        stage.animate().translationY(0).setDuration(160).start();
    }

    /** 空闲槽预载下一条（只缓冲、不出声、不解码）。 */
    private void preloadNext() {
        if (queue == null || switching) return;
        if (!engine.preloadEnabled()) return;      // 低内存机：不预载，省内存
        // 三态统一预载「下一条」：feed 里是同一部的下一集（播完自动连播走 switchBy），
        // 详情/全屏也是下一集。feed 上滑切整部剧走 openInto，不指望命中预载。
        FeedQueue.Item next = queue.peekNext();
        if (next == null) {
            if (queue.isFeed() && queue.needExtend(2)) extendSilently();
            return;
        }
        int idle = 1 - activeSlot;
        PlayerEngine.Slot s = engine.slot(idle);
        if (s.item != null && s.item.sameAs(next) && s.player != null) return;
        if (preloading) return;
        preloading = true;
        openInto(idle, next, 0, true, () -> preloading = false);
    }

    /** 预取下一部（不打扰用户，也不提示）。 */
    private void extendSilently() {
        if (extending || noMore || queue == null || !queue.isFeed()) return;
        extending = true;
        Api.run(() -> queue.extend(), new Api.Cb<Boolean>() {
            @Override
            public void ok(Boolean v) {
                extending = false;
                if (v != null && v) preloadNext();
                else noMore = true;
            }

            @Override
            public void err(Exception e) {
                extending = false;
            }
        });
    }

    private void extendThen(final int dir) {
        if (extending || queue == null || !queue.isFeed()) {
            bounce(1);
            return;
        }
        extending = true;
        setHint("正在加载下一条…");
        Api.run(() -> queue.extend(), new Api.Cb<Boolean>() {
            @Override
            public void ok(Boolean v) {
                extending = false;
                centerHint.setVisibility(View.GONE);
                if (v != null && v && queue.peekNext() != null) {
                    if (!switching) switchBy(dir);
                } else {
                    noMore = true;
                    bounce(1);
                    toast("已经是最后一条了");
                }
            }

            @Override
            public void err(Exception e) {
                extending = false;
                centerHint.setVisibility(View.GONE);
                toast("加载失败：" + msg(e));
            }
        });
    }

    // ================= 控制层 =================
    /**
     * 顶部「‹ 第N集」+ 中央播放键的显隐。底部按钮行（capBar）/ tab / 进度条**常显**，
     * 不参与显隐，所以这里不碰它们。
     */
    private void toggleControls() {
        if (sheetOpen()) {
            sheet.collapse();
            return;
        }
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (popup != null) {
            dismissPopup();
            return;
        }
        boolean show = oTop.getVisibility() != View.VISIBLE;
        oTop.setVisibility(show ? View.VISIBLE : View.GONE);
        if (fullscreen) oBottom.setVisibility(show ? View.VISIBLE : View.GONE);
        oCenterPlay.setVisibility(show && !isPlayingNow() ? View.VISIBLE : View.GONE);
        if (show) scheduleHide();
        else ui.removeCallbacks(hideRun);
    }

    /** 暂停 / 首次就绪时亮一下顶部与中央播放键。 */
    private void showControls() {
        if (fullscreen || detail) oTop.setVisibility(View.VISIBLE);
        if (fullscreen) oBottom.setVisibility(View.VISIBLE);
        if (!isPlayingNow()) oCenterPlay.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private boolean isPlayingNow() {
        PlayerEngine.Slot s = engine.slot(activeSlot);
        return s.player != null && s.player.isPlaying();
    }

    private final Runnable hideRun = () -> {
        if (!isPlayingNow()) return;
        if (drawerOpen || popup != null || sheetOpen()) return;
        oCenterPlay.setVisibility(View.GONE);
        oTop.setVisibility(View.GONE);
        if (fullscreen) oBottom.setVisibility(View.GONE);
    };

    private void scheduleHide() {
        ui.removeCallbacks(hideRun);
        if (isPlayingNow() && !drawerOpen && popup == null && !sheetOpen()) {
            ui.postDelayed(hideRun, 2500);
        }
    }

    /** 单击 = 暂停 / 播放；暂停时顶部与中央键出现，播放 2.5s 后自动隐藏。 */
    private void togglePlay() {
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player == null) return;
        if (s.player.isPlaying()) {
            s.player.pause();
            ui.removeCallbacks(hideRun);
            oCenterPlay.setVisibility(View.VISIBLE);
            if (fullscreen || detail) oTop.setVisibility(View.VISIBLE);
            if (fullscreen) oBottom.setVisibility(View.VISIBLE);
            oPlay.setText("\u25b6");
        } else {
            s.player.play();
            oCenterPlay.setVisibility(View.GONE);
            oPlay.setText("\u275a\u275a");
            scheduleHide();
        }
    }

    private void tick() {
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player != null) {
            long dur = s.player.getDuration();
            long pos = s.player.getCurrentPosition();
            if (dur > 0) {
                oDur.setText(fmtMs(dur));
                if (!seeking) {
                    thinSeek.setProgress(pos / (float) dur);
                    oSeek.setProgress(pos / (float) dur);
                }
            }
            if (!seeking) oCur.setText(fmtMs(pos));
            oPlay.setText(s.player.isPlaying() ? "\u275a\u275a" : "\u25b6");
        }
        ui.postDelayed(tickRun, 500);
    }

    private final Runnable tickRun = this::tick;

    private long curPos() {
        PlayerEngine.Slot s = engine.slot(activeSlot);
        return s.player == null ? 0 : s.player.getCurrentPosition();
    }

    private long curDur() {
        PlayerEngine.Slot s = engine.slot(activeSlot);
        return s.player == null ? 0 : s.player.getDuration();
    }

    private void seekTo(float frac) {
        long dur = curDur();
        if (dur > 0) {
            PlayerEngine.Slot s = engine.slot(activeSlot);
            if (s.player != null) s.player.seekTo((long) (dur * Math.max(0f, Math.min(1f, frac))));
        }
    }

    private void setHint(String s) {
        centerHint.setText(s);
        centerHint.setVisibility(View.VISIBLE);
    }

    private final Runnable hintRun = () -> centerHint.setVisibility(View.GONE);

    private void setStatus(String s) {
        runOnUiThread(() -> {
            status.setText(s);
            status.setVisibility(View.VISIBLE);
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String fmtMs(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private String fmtSpeed(float s) {
        return (s == Math.round(s) ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    private String msg(Exception e) {
        if (e == null) return "";
        String s = e.getMessage();
        return s == null || s.isEmpty() ? e.toString() : s;
    }

    private String safe(String s) {
        if (s == null) return "best";
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.length() == 0 ? "best" : b.toString();
    }

    // ================= 手势 =================
    /** 长按 450ms：临时 2.0x（恒定，和手动档位无关）。 */
    private final Runnable longPressRun = () -> {
        if (gMode != G_NONE || drawerOpen || popup != null || sheetOpen()) return;
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player == null || !s.player.isPlaying()) return;
        longFired = true;
        boosting = true;
        s.player.setPlaybackSpeed(LONG_PRESS_SPEED);
        setHint("2x 快进中");
        updateCapsules();
    };

    /** 松手：回到**手动设定的档位**（设定 1.5x 就回 1.5x），任何路径都不写 1.0f。 */
    private void endBoost() {
        if (!boosting) return;
        boosting = false;
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player != null) s.player.setPlaybackSpeed(speed);
        centerHint.setVisibility(View.GONE);
        updateCapsules();
    }

    private boolean onGesture(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (switching) return true;   // 正在换集，别叠加手势
                gDownX = ev.getX();
                gDownY = ev.getY();
                gDy = 0;
                gMode = G_NONE;
                longFired = false;
                gStartMs = curPos();
                ui.removeCallbacks(longPressRun);
                ui.postDelayed(longPressRun, 450);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - gDownX;
                float dy = ev.getY() - gDownY;
                gDy = dy;
                if (Math.hypot(dx, dy) > dp(14)) ui.removeCallbacks(longPressRun);
                if (gMode == G_NONE && !longFired && Math.hypot(dx, dy) > dp(16)) {
                    if (Math.abs(dy) > Math.abs(dx) * 1.15f) gMode = G_SWITCH;
                    else if (Math.abs(dx) > Math.abs(dy) * 1.15f && curDur() > 0) gMode = G_SEEK;
                }
                if (gMode == G_SWITCH) {
                    // feed 态整屏跟手（像抖音），详情/全屏只做 55% 的轻度跟手
                    boolean feedSwipe = !fullscreen && !detail;
                    stage.setTranslationY(dy * (feedSwipe ? 1f : 0.55f));
                    if (Math.abs(dy) > stage.getHeight() * 0.10f) {
                        setHint(feedSwipe ? (dy < 0 ? "松开看下一部" : "松开看上一部")
                                : (dy < 0 ? "松开看下一集" : "松开看上一集"));
                    }
                } else if (gMode == G_SEEK) {
                    long dur = curDur();
                    float frac = dx / (stage.getWidth() * 0.75f);
                    gTargetMs = gStartMs + (long) (frac * dur);
                    if (gTargetMs < 0) gTargetMs = 0;
                    if (gTargetMs > dur) gTargetMs = dur;
                    long diff = gTargetMs - gStartMs;
                    setHint((diff >= 0 ? "快进 " : "快退 ") + fmtMs(Math.abs(diff)) + "\n"
                            + fmtMs(gTargetMs) + " / " + fmtMs(dur));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                ui.removeCallbacks(longPressRun);
                if (longFired) {
                    // 长按抬指只结束加速，**不能**再判成一次单击（否则松手就暂停）
                    longFired = false;
                    endBoost();
                    stage.setTranslationY(0);
                    gMode = G_NONE;
                    return true;
                }
                if (gMode == G_SWITCH) {
                    stage.setTranslationY(0);
                    centerHint.setVisibility(View.GONE);
                    if (Math.abs(gDy) > stage.getHeight() * 0.15f) {
                        int d = gDy < 0 ? 1 : -1;
                        // feed = 切整部剧；详情与全屏 = 切本剧集（全屏也走这条）
                        if (!fullscreen && !detail) switchSeries(d);
                        else switchBy(d);
                    } else {
                        bounce(gDy < 0 ? 1 : -1);
                    }
                } else if (gMode == G_SEEK) {
                    seekTo((float) gTargetMs / Math.max(1, curDur()));
                    ui.removeCallbacks(hintRun);
                    ui.postDelayed(hintRun, 400);
                } else if (gMode == G_NONE) {
                    // 单击 = 暂停 / 播放（不再用于显隐）；280ms 去抖，避免连点抖成两次
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastTapAt > 280) {
                        lastTapAt = now;
                        togglePlay();
                    }
                }
                gMode = G_NONE;
                return true;
            }
        }
        return true;
    }

    // ================= 选集面板 =================
    private boolean sheetOpen() {
        return sheet != null && sheet.isOpen();
    }

    /** 打开白底选集面板（55% 屏高，上推 260ms）。 */
    private void openSheet() {
        if (curItem == null || queue == null) return;
        dismissPopup();
        Model.Detail d = queue.curDetail();
        if (d == null || d.eps.isEmpty()) {
            toast("还没拿到剧集列表");
            return;
        }
        if (sheet == null || !d.seriesId.equals(sheetDetailId)) {
            sheet = new EpisodeSheet(this, root, d, curItem.epIndex());
            sheet.setCb(this::jumpToEp);
            sheetDetailId = d.seriesId;
        } else {
            sheet.setCurrent(curItem.epIndex());
        }
        ui.removeCallbacks(hideRun);
        sheet.show();
    }

    /** 选了某一集：立即切过去（命中预载就秒切），面板自己回落。 */
    private void jumpToEp(int epIndex) {
        if (queue == null) return;
        if (curItem != null && curItem.epIndex() == epIndex) return;
        int from = curItem == null ? epIndex : curItem.epIndex();
        if (!queue.seekEp(epIndex)) return;
        curItem = queue.current();
        if (curItem == null) return;
        updateUi();

        int idle = 1 - activeSlot;
        PlayerEngine.Slot s = engine.slot(idle);
        if (s.item != null && s.item.sameAs(curItem) && s.player != null) {
            engine.throttle(activeSlot);
            doSwap(epIndex >= from ? 1 : -1);       // 正好是预载的那条：秒切
            return;
        }
        startCurrent(0);                            // 没预载：重开当前槽
    }

    // ================= 抽屉（只剩选集）=================
    private void openDrawer() {
        dismissPopup();
        drawerOpen = true;
        ui.removeCallbacks(hideRun);
        epBlock.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        markAuto();
        if (curItem != null) scrollToEp(curItem.epIndex());
    }

    private void closeDrawer() {
        drawerOpen = false;
        if (drawer != null) drawer.setVisibility(View.GONE);
        if (scrim != null) scrim.setVisibility(View.GONE);
    }

    // ================= 贴着胶囊弹出的选择窗 =================
    private TextView capsule(String text) {
        TextView t = tv(text, 13, TXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0x33FFFFFF);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 倍速档位弹窗：贴着「1.0x」胶囊正上方浮出，再点一次收起。 */
    private void showSpeedPopup(View anchor) {
        if (popupAnchor == anchor) {
            dismissPopup();
            return;
        }
        dismissPopup();
        LinearLayout box = popupBox();
        for (final float sp : SPEEDS) {
            boolean on = Math.abs(sp - speed) < 0.001f;
            TextView row = popupRow(fmtSpeed(sp), on);
            row.setOnClickListener(v -> {
                speed = sp;
                engine.setSpeed(sp, activeSlot);
                updateCapsules();
                oSpd.setText("倍速 " + fmtSpeed(sp));
                dismissPopup();
            });
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        }
        showAnchored(anchor, box, 100, SPEEDS.length * 34 + 16);
    }

    /** 清晰度弹窗：切档保留当前进度重新取流（不清缓存、不回到 0）。 */
    private void showQualityPopup(View anchor) {
        if (popupAnchor == anchor) {
            dismissPopup();
            return;
        }
        dismissPopup();
        if (qDefs.isEmpty()) buildQualityList();
        if (qDefs.isEmpty()) {
            toast("还没有拿到清晰度列表");
            return;
        }
        LinearLayout box = popupBox();
        for (final String def : qDefs) {
            boolean on = quality.equals(def);
            TextView row = popupRow(def.isEmpty() ? "默认" : def.toUpperCase(), on);
            row.setOnClickListener(v -> {
                int posSec = (int) (curPos() / 1000);
                quality = def;
                updateCapsules();
                dismissPopup();
                curItem = queue == null ? null : queue.current();
                startCurrent(posSec);          // 保留进度，重新取这一档的流
            });
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        }
        showAnchored(anchor, box, 100, qDefs.size() * 34 + 16);
    }

    private LinearLayout popupBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(8), dp(6), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(0xFF2A2A2E);
        box.setBackground(g);
        return box;
    }

    private TextView popupRow(String text, boolean on) {
        TextView t = tv(text, 13, on ? 0xFFFF7A2F : TXT);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(on ? 0x1FFF7A2F : 0x00000000);
        t.setBackground(g);
        return t;
    }

    /** 贴着 anchor（胶囊）正上方浮出；上方空间不足时翻到下方。 */
    private void showAnchored(View anchor, View content, int wDp, int hDp) {
        PopupWindow pw = new PopupWindow(content, dp(wDp), dp(hDp), true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(10));
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int x = loc[0] + anchor.getWidth() / 2 - dp(wDp) / 2;
        x = Math.max(dp(8), Math.min(x, sw - dp(wDp) - dp(8)));
        int y = loc[1] - dp(hDp) - dp(10) > dp(24)
                ? loc[1] - dp(hDp) - dp(10)
                : loc[1] + anchor.getHeight() + dp(10);
        pw.showAtLocation(root, Gravity.NO_GRAVITY, x, y);
        popup = pw;
        popupAnchor = anchor;
    }

    private void dismissPopup() {
        if (popup != null) {
            popup.dismiss();
            popup = null;
        }
        popupAnchor = null;
    }

    private void buildQualityList() {
        List<String> defs = new ArrayList<>();
        for (Model.Track t : lastTracks) {
            if (t.definition != null && !defs.contains(t.definition)) defs.add(t.definition);
        }
        qDefs = defs;
    }

    private List<Model.Track> lastTracks = new ArrayList<>();

    private void markAuto() {
        if (autoBtn == null) return;
        autoBtn.setText(autoNext ? "连播 开" : "连播 关");
        autoBtn.setTextColor(autoNext ? PINK : DIM);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(autoNext ? 0x33FF2C55 : 0x22FFFFFF);
        autoBtn.setBackground(g);
    }

    private void scrollToEp(int epIndex) {
        if (epGrid == null) return;
        epGrid.post(() -> {
            int p = epAdapter.positionOf(epIndex);
            if (p >= 0 && epGrid.getLayoutManager() != null) {
                epGrid.getLayoutManager().scrollToPosition(p);
            }
        });
    }

    // ================= 全屏 / 剧场 =================
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        applyOrientation();
        if (fullscreen) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        ui.postDelayed(this::applyOrientation, 400);
        androidx.core.view.WindowInsetsControllerCompat ic =
                androidx.core.view.ViewCompat.getWindowInsetsController(getWindow().getDecorView());
        if (ic != null) {
            if (fullscreen) {
                ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                ic.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            }
        }
    }

    private void openTheater() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        ui.post(this::applyOrientation);
    }

    @Override
    public void onBackPressed() {
        if (sheetOpen()) {
            sheet.collapse();
            return;
        }
        if (popup != null) {
            dismissPopup();
            return;
        }
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (fullscreen) {
            toggleFullscreen();
            return;
        }
        // 详情态先退回首页 feed（画面不动，只还原左下信息与底栏），再按一次才退出
        if (detail && mode == MODE_FEED) {
            exitDetail();
            return;
        }
        if (mode == MODE_FEED) {
            finish();
            return;
        }
        super.onBackPressed();
    }

    // ================= 引擎回调 =================
    private class EngineCb implements PlayerEngine.Listener {
        @Override
        public void onSlotReady(int slot, long durationMs) {
            if (slot == pendingSwapSlot) {
                doSwap(pendingDir);
                return;
            }
            if (slot != activeSlot) return;
            status.setVisibility(View.GONE);
            oDur.setText(fmtMs(durationMs));
            tick();
            if (firstReady) {
                firstReady = false;
                showControls();
            }
            preloadNext();   // 开播就立刻把下一条预载好
        }

        @Override
        public void onSlotEnded(int slot) {
            if (slot != activeSlot) return;
            if (autoNext) {
                switchBy(1);
            } else {
                saveProgress();
            }
        }

        @Override
        public void onSlotError(final int slot, Exception e) {
            if (slot != activeSlot) {
                // 预载失败：清掉这个槽，真正切过去时再走一次
                engine.releaseSlot(slot);
                preloading = false;
                return;
            }
            PlayerEngine.Slot s = engine.slot(slot);
            if (s.track == null || s.file == null) {
                setStatus("播放失败\n" + msg(e));
                return;
            }
            fallbackDownload(slot, s);
        }

        @Override
        public void onSlotBuffer(int slot, long done, long total, boolean doneAll) {
            // 这个回调来自下载线程，碰 UI 必须切回主线程
            if (slot != activeSlot || doneAll) return;
            final String s = "缓冲中 " + (done / 1048576) + " / "
                    + (total > 0 ? total / 1048576 : 0) + " MB";
            ui.post(() -> {
                if (status.getVisibility() == View.VISIBLE) status.setText(s);
            });
        }
    }

    /** 流式播不了（不支持 Range / moov 在尾部）时：整集下载 + 解密，再按明文播。 */
    private void fallbackDownload(final int slot, final PlayerEngine.Slot s) {
        final File enc = s.file;
        final Model.Track t = s.track;
        final FeedQueue.Item it = s.item;
        if (it == null || t == null || enc == null) return;
        if (enc.getName().endsWith(".mp4")) {
            setStatus("播放失败\n" + t.url);
            return;
        }
        final File plain = new File(enc.getAbsolutePath().replace(".enc", ".mp4"));
        // 先把这一槽的下载线程停掉并释放句柄，否则整集下载会与 worker 同时写同一个文件
        engine.releaseSlot(slot);
        Cache.pin(enc);
        setStatus("该片不支持边下边播，整集下载中…");
        Api.run(() -> {
            if (!enc.exists() || enc.length() < 1024) Api.download(t.url, enc, null);
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
                Cache.pin(plain);
                engine.load(slot, it, t, null, plain, true, 0, false);
                engine.view(slot).setTranslationY(0);
                engine.activate(slot, speed);
            }

            @Override
            public void err(Exception e) {
                setStatus("下载/解密失败\n" + msg(e));
            }
        });
    }

    // ================= 生命周期 =================
    private boolean needReload;
    private int resumeSec;

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        PlayerEngine.Slot s = engine == null ? null : engine.slot(activeSlot);
        if (s != null && s.player != null) s.player.setPlayWhenReady(false);
    }

    /**
     * 进后台/被其它页面盖住就把两个播放器全部释放：剧场↔feed 来回跳会堆多个
     * PlayActivity，每个都占硬解实例，低端机会直接起不来。回来靠磁盘缓存秒开。
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations() || engine == null) return;
        resumeSec = (int) (curPos() / 1000L);
        needReload = true;
        switching = false;
        preloading = false;
        pendingSwapSlot = -1;
        swapSeq++;
        engine.releaseAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRequestedOrientation(fullscreen
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (needReload) {
            needReload = false;
            if (queue != null && curItem != null) {
                View v = engine.view(activeSlot);
                v.setTranslationY(0);
                v.setVisibility(View.VISIBLE);
                int sec = resumeSec;
                resumeSec = 0;
                curItem = queue.current();
                updateUi();
                setStatus("正在打开…");
                openInto(activeSlot, curItem, sec, false, null);
            }
        }
    }

    private void saveProgress() {
        if (curItem == null) return;
        PlayerEngine.Slot s = engine.slot(activeSlot);
        long pos = s.player == null ? 0 : s.player.getCurrentPosition();
        Store.recordHist(this, curItem.sid, curItem.seriesTitle, curItem.cover,
                curItem.epIndex(), curItem.epCnt, (int) (pos / 1000L));
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (engine != null) engine.releaseAll();
        Cache.unpinAll();
        Cache.trim(getCacheDir());
        super.onDestroy();
    }

    // ================= 细进度条 =================
    private class ThinSeek extends View {
        private float frac;
        private boolean drag;
        /** true = 贴屏幕最底画一条细线（竖屏常显），false = 居中画（全屏底栏用）。 */
        private boolean bottom;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);

        ThinSeek() {
            super(PlayActivity.this);
            bg.setColor(0x33FFFFFF);
            fg.setColor(PINK);
        }

        void setProgress(float f) {
            if (drag) return;
            frac = f;
            invalidate();
        }

        void setBottomStyle(boolean b) {
            bottom = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float t = Math.max(2f, dp(2));
            float cy = bottom ? h - t / 2f : h / 2f;
            bg.setStrokeWidth(t);
            c.drawLine(0, cy, w, cy, bg);
            fg.setStrokeWidth(t);
            c.drawLine(0, cy, w * Math.max(0f, Math.min(1f, frac)), cy, fg);
            if (!bottom) {
                fg.setStyle(Paint.Style.FILL);
                c.drawCircle(w * Math.max(0f, Math.min(1f, frac)), cy, dp(3), fg);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    drag = true;
                    seeking = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    frac = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
                    invalidate();
                    long dur = curDur();
                    if (dur > 0) oCur.setText(fmtMs((long) (dur * frac)));
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    seeking = false;
                    drag = false;
                    seekTo(frac);
                    return true;
            }
            return true;
        }
    }

    // ================= 选集 =================
    private class EpAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Object> rows = new ArrayList<>();
        private static final int T_HEAD = 0, T_EP = 1;

        void set(Model.Detail d) {
            rows.clear();
            if (d != null) {
                int last = -1;
                for (Model.Episode e : d.eps) {
                    int group = (e.index - 1) / 30;
                    if (group != last) {
                        rows.add((group * 30 + 1) + "-" + (group * 30 + 30));
                        last = group;
                    }
                    rows.add(e);
                }
            }
            notifyDataSetChanged();
        }

        void highlight(int idx) {
            notifyDataSetChanged();
        }

        int positionOf(int epIndex) {
            for (int i = 0; i < rows.size(); i++) {
                Object o = rows.get(i);
                if (o instanceof Model.Episode && ((Model.Episode) o).index == epIndex) return i;
            }
            return -1;
        }

        boolean isHeader(int pos) {
            return pos >= 0 && pos < rows.size() && rows.get(pos) instanceof String;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? T_HEAD : T_EP;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView t = new TextView(PlayActivity.this);
            if (viewType == T_HEAD) {
                t.setTextSize(11);
                t.setTextColor(DIM);
                t.setPadding(dp(2), dp(10), dp(2), dp(4));
                return new HeadHolder(t);
            }
            t.setGravity(Gravity.CENTER);
            t.setTextSize(13);
            t.setPadding(0, dp(8), 0, dp(8));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            t.setLayoutParams(lp);
            return new EpHolder(t);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof HeadHolder) {
                ((HeadHolder) holder).t.setText(String.valueOf(rows.get(pos)));
                return;
            }
            Model.Episode e = (Model.Episode) rows.get(pos);
            TextView t = ((EpHolder) holder).t;
            t.setText(String.valueOf(e.index));
            boolean on = curItem != null && e.index == curItem.epIndex();
            t.setTextColor(on ? Color.WHITE : 0xFFBBBBBB);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(6));
            g.setColor(on ? PINK : 0x22FFFFFF);
            t.setBackground(g);
            t.setOnClickListener(v -> {
                if (queue == null) return;
                int dir = e.index - (curItem == null ? 0 : curItem.epIndex());
                if (dir == 0) {
                    closeDrawer();
                    return;
                }
                if (queue.seekEp(e.index)) {
                    closeDrawer();
                    switching = true;
                    pendingDir = dir > 0 ? 1 : -1;
                    pendingSwapSlot = 1 - activeSlot;
                    curItem = queue.current();
                    updateUi();
                    setStatus("正在打开…");
                    armSwapTimeout();
                    openInto(pendingSwapSlot, curItem, 0, false, null);
                }
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static class EpHolder extends RecyclerView.ViewHolder {
        final TextView t;

        EpHolder(TextView t) {
            super(t);
            this.t = t;
        }
    }

    private static class HeadHolder extends RecyclerView.ViewHolder {
        final TextView t;

        HeadHolder(TextView t) {
            super(t);
            this.t = t;
        }
    }
}
