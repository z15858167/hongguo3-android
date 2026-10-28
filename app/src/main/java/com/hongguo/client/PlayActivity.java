package com.hongguo.client;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
 * 播放页（v0.6.0）：抖音式 feed。
 *
 *  启动即播推荐第一部剧的第 1 集；上滑下一条、下滑上一条（跨剧自动接）；
 *  竖屏是「视频 + 信息 + 常显细进度条 + 首页/剧场」，全屏是「顶栏 + 底栏」叠层。
 *
 *  手势（竖屏/全屏统一）：
 *    单击 = 显隐控制层；双击 = 暂停/播放；长按不松 = 临时 2 倍速（松手恢复）；
 *    上下滑 = 切换上一条/下一条；左右滑 = 快进快退。
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
    private int pendingDir = 1;
    private int swapSeq;
    private boolean switching;
    private boolean preloading;
    private boolean extending;
    private boolean noMore;
    private boolean firstReady = true;
    private boolean fullscreen;
    private boolean drawerOpen;
    private boolean autoNext = true;

    private String quality = "";
    private String shownSeriesId = "";
    private float speed = 1.0f;
    private float speedBeforeHold = 1.0f;
    private boolean boosting;

    // ---------------- UI ----------------
    private FrameLayout root, stage, rail;
    private LinearLayout column, info, tabBar, oTop, oBottom, drawer, qBlock, spdBlock, epBlock;
    private LinearLayout speedBar, qBar, oTopRow, oEpRow;
    private View spacer;
    private TextView status, centerHint, oCenterPlay, oFsBtn, oSearch, oRailFav;
    private TextView oBack, oEpTitle, oSpd, oMore, oPlay, oCur, oDur, oEpBtn, oFull, autoBtn;
    private TextView tabHome, tabTheater;
    private TextView infoTitle, infoTag, infoEp, infoIntro, infoMore, infoOpen;
    private ThinSeek thinSeek, oSeek;
    private View scrim, gestureLayer;
    private RecyclerView epGrid;
    private EpAdapter epAdapter;
    private boolean seeking;

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
    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.BLACK);

        // ---- 视频区 ----
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        column.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));

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

        // 全屏胶囊（竖屏，压在视频底部）
        oFsBtn = tv("\u26f6 全屏观看", 12, Color.WHITE);
        oFsBtn.setGravity(Gravity.CENTER);
        oFsBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable fsg = new GradientDrawable();
        fsg.setCornerRadius(dp(16));
        fsg.setColor(0x66000000);
        oFsBtn.setBackground(fsg);
        oFsBtn.setOnClickListener(v -> toggleFullscreen());
        FrameLayout.LayoutParams fsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        fsLp.rightMargin = dp(12);
        fsLp.bottomMargin = dp(10);
        stage.addView(oFsBtn, fsLp);

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

        // ---- 信息区（竖屏）----
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), dp(10), dp(14), dp(6));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        infoTitle = tv("", 16, TXT);
        infoTitle.setMaxLines(1);
        titleRow.addView(infoTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = tv("\u203a", 18, DIM);
        arrow.setPadding(dp(6), 0, 0, 0);
        titleRow.addView(arrow);
        titleRow.setOnClickListener(v -> openDrawer());
        info.addView(titleRow);

        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setPadding(0, dp(6), 0, 0);
        infoTag = tv("剧情", 11, 0xFFE8A0AE);
        infoTag.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(4));
        tg.setColor(0x33FF2C55);
        infoTag.setBackground(tg);
        tagRow.addView(infoTag);
        info.addView(tagRow);

        infoEp = tv("", 12, DIM);
        infoEp.setPadding(0, dp(8), 0, 0);
        info.addView(infoEp);

        infoIntro = tv("", 12, 0xFFBDBDC2);
        infoIntro.setMaxLines(2);
        infoIntro.setPadding(0, dp(4), 0, 0);
        info.addView(infoIntro);

        infoMore = tv("展开", 12, 0xFFE8A0AE);
        infoMore.setPadding(0, dp(4), 0, 0);
        infoMore.setOnClickListener(v -> {
            if (infoIntro.getMaxLines() == 2) {
                infoIntro.setMaxLines(20);
                infoMore.setText("收起");
            } else {
                infoIntro.setMaxLines(2);
                infoMore.setText("展开");
            }
        });
        info.addView(infoMore);

        infoOpen = tv("", 13, Color.WHITE);
        infoOpen.setGravity(Gravity.CENTER);
        infoOpen.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable og = new GradientDrawable();
        og.setCornerRadius(dp(8));
        og.setColor(0xFF23232A);
        infoOpen.setBackground(og);
        infoOpen.setOnClickListener(v -> {
            if (curItem != null && isFullSeries()) openDrawer();
            else openTheater();
        });
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = dp(10);
        info.addView(infoOpen, openLp);
        column.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 占位：把进度条和 tab 顶到屏幕底部
        spacer = new View(this);
        column.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        thinSeek = new ThinSeek();
        column.addView(thinSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setBackgroundColor(0xFF0E0E11);
        tabHome = tv("首页", 14, PINK);
        tabHome.setGravity(Gravity.CENTER);
        tabTheater = tv("剧场", 14, DIM);
        tabTheater.setGravity(Gravity.CENTER);
        tabTheater.setOnClickListener(v -> openTheater());
        tabBar.addView(tabHome, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabBar.addView(tabTheater, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        column.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        oEpBtn.setOnClickListener(v -> openDrawer());
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

        qBlock = new LinearLayout(this);
        qBlock.setOrientation(LinearLayout.VERTICAL);
        qBlock.addView(label("清晰度"));
        qBar = new LinearLayout(this);
        qBar.setOrientation(LinearLayout.HORIZONTAL);
        qBar.setPadding(0, dp(6), 0, dp(8));
        HorizontalScrollView qs = new HorizontalScrollView(this);
        qs.setHorizontalScrollBarEnabled(false);
        qs.addView(qBar);
        qBlock.addView(qs);
        drawer.addView(qBlock);

        spdBlock = new LinearLayout(this);
        spdBlock.setOrientation(LinearLayout.VERTICAL);
        spdBlock.addView(label("倍速"));
        speedBar = new LinearLayout(this);
        speedBar.setOrientation(LinearLayout.HORIZONTAL);
        speedBar.setPadding(0, dp(6), 0, dp(8));
        for (float sp : SPEEDS) {
            final float fs = sp;
            TextView c = new TextView(this);
            c.setText(fmtSpeed(sp));
            c.setTextSize(12);
            c.setPadding(dp(10), dp(5), dp(10), dp(5));
            c.setTag(sp);
            c.setOnClickListener(v -> {
                speed = fs;
                engine.setSpeed(fs, activeSlot);
                markSpeed();
                if (oSpd != null) oSpd.setText("倍速 " + fmtSpeed(fs));
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
        HorizontalScrollView ss = new HorizontalScrollView(this);
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
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.bottomMargin = dp(6);
        spdBlock.addView(autoBtn, abLp);
        drawer.addView(spdBlock);

        epBlock = new LinearLayout(this);
        epBlock.setOrientation(LinearLayout.VERTICAL);
        epBlock.addView(label("选集"));
        epGrid = new RecyclerView(this);
        epGrid.setLayoutManager(new GridLayoutManager(this, 6));
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
        markSpeed();
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

    /** 竖屏：视频 16:9 + 信息 + 细进度条 + tab；全屏：视频铺满 + 顶底栏。 */
    private void applyOrientation() {
        int w = getResources().getDisplayMetrics().widthPixels;
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) stage.getLayoutParams();
        if (fullscreen) {
            slp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            int h = (int) (w * 9f / 16f);
            int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
            slp.height = Math.min(h, max);
        }
        stage.setLayoutParams(slp);

        info.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        tabBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        thinSeek.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        spacer.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        oSearch.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        rail.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        oFsBtn.setVisibility(fullscreen ? View.GONE : View.VISIBLE);

        oTop.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        oBottom.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        if (!fullscreen) oCenterPlay.setVisibility(View.GONE);
        drawer.setLayoutParams(drawerLp(fullscreen));
        if (epGrid.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) epGrid.getLayoutManager()).setSpanCount(6);
        }
        if (oSpd != null) oSpd.setText("倍速 " + fmtSpeed(speed));
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
                if (queue == null) return;
                Model.Track t = (Model.Track) v[0];
                String key = (String) v[1];
                @SuppressWarnings("unchecked")
                List<Model.Track> ts = (List<Model.Track>) v[2];
                lastTracks = ts;
                quality = t.definition;
                buildQualityBar();
                markQuality();
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
                    if (queue != null) queue.advance(pendingDir > 0 ? -1 : 1);
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
        infoTitle.setText(curItem.seriesTitle);
        infoEp.setText("第 " + curItem.epIndex() + " 集 / 全 " + curItem.epCnt + " 集");
        infoIntro.setText(curItem.intro == null ? "" : curItem.intro);
        infoIntro.setMaxLines(2);
        infoMore.setText("展开");
        infoMore.setVisibility(curItem.intro == null || curItem.intro.isEmpty()
                ? View.GONE : View.VISIBLE);
        infoOpen.setText(isFullSeries()
                ? "观看完整短剧 · 全 " + curItem.epCnt + " 集"
                : "去剧场看更多");
        oEpTitle.setText("第 " + curItem.epIndex() + " 集");
        oEpBtn.setText("选集 · 全 " + curItem.epCnt + " 集");
        epAdapter.highlight(curItem.epIndex());
        syncFav();
        Store.recordHist(this, curItem.sid, curItem.seriesTitle, curItem.cover,
                curItem.epIndex(), curItem.epCnt, 0);
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
    /** 上滑(dir=1) / 下滑(dir=-1)。 */
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
        queue.advance(dir);

        int idle = 1 - activeSlot;
        PlayerEngine.Slot s = engine.slot(idle);
        engine.throttle(activeSlot);   // 还在播的这一条限速，别跟预载抢带宽
        if (s.item != null && s.item.sameAs(target)) {
            if (s.ready) {
                doSwap(dir);
                return;
            }
            pendingSwapSlot = idle;
            armSwapTimeout();
            return;
        }
        setStatus("正在打开…");
        pendingSwapSlot = idle;
        armSwapTimeout();
        openInto(idle, target, 0, false, null);
    }

    private void armSwapTimeout() {
        final int slot = pendingSwapSlot;
        final int token = ++swapSeq;
        ui.postDelayed(() -> {
            if (token == swapSeq && pendingSwapSlot == slot && pendingSwapSlot >= 0) doSwap(pendingDir);
        }, 5000);
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
    private void toggleControls() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (fullscreen) {
            boolean show = oTop.getVisibility() != View.VISIBLE;
            oTop.setVisibility(show ? View.VISIBLE : View.GONE);
            oBottom.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) scheduleHide();
            else ui.removeCallbacks(hideRun);
            return;
        }
        // 竖屏：视频区只有中央播放键跟着显隐（信息区/进度条/tab 常显）
        boolean show = oCenterPlay.getVisibility() != View.VISIBLE;
        oCenterPlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) scheduleHide();
        else ui.removeCallbacks(hideRun);
    }

    /** 首次就绪亮一下控制层，之后换集不再弹。 */
    private void showControls() {
        if (fullscreen) {
            oTop.setVisibility(View.VISIBLE);
            oBottom.setVisibility(View.VISIBLE);
        }
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player != null && !s.player.isPlaying()) {
            oCenterPlay.setVisibility(View.VISIBLE);
        }
        scheduleHide();
    }

    private final Runnable hideRun = () -> {
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player == null || !s.player.isPlaying()) return;
        if (drawerOpen) return;
        oCenterPlay.setVisibility(View.GONE);
        if (fullscreen) {
            oTop.setVisibility(View.GONE);
            oBottom.setVisibility(View.GONE);
        }
    };

    private void scheduleHide() {
        ui.removeCallbacks(hideRun);
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player != null && s.player.isPlaying() && !drawerOpen) {
            ui.postDelayed(hideRun, 3500);
        }
    }

    private void togglePlay() {
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player == null) return;
        if (s.player.isPlaying()) {
            s.player.pause();
            oCenterPlay.setVisibility(View.VISIBLE);
            oPlay.setText("\u25b6");
        } else {
            s.player.play();
            if (!fullscreen) oCenterPlay.setVisibility(View.GONE);
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
    private final Runnable longPressRun = () -> {
        if (gMode != G_NONE || drawerOpen) return;
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player == null || !s.player.isPlaying()) return;
        longFired = true;
        boosting = true;
        speedBeforeHold = speed;
        s.player.setPlaybackSpeed(LONG_PRESS_SPEED);
        setHint("2x 快进中");
        ui.removeCallbacks(hintRun);
    };

    private void endBoost() {
        if (!boosting) return;
        boosting = false;
        PlayerEngine.Slot s = engine.slot(activeSlot);
        if (s.player != null) s.player.setPlaybackSpeed(speedBeforeHold);
        centerHint.setVisibility(View.GONE);
    }

    private final Runnable singleTapRun = this::toggleControls;

    private boolean onGesture(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gDownX = ev.getX();
                gDownY = ev.getY();
                gDy = 0;
                gMode = G_NONE;
                longFired = false;
                gStartMs = curPos();
                ui.removeCallbacks(longPressRun);
                ui.postDelayed(longPressRun, 500);
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
                    stage.setTranslationY(dy * 0.55f);
                    if (Math.abs(dy) > stage.getHeight() * 0.10f) {
                        setHint(dy < 0 ? "松开看下一集" : "松开看上一集");
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
                    longFired = false;
                    endBoost();
                    stage.setTranslationY(0);
                    return true;
                }
                if (gMode == G_SWITCH) {
                    stage.setTranslationY(0);
                    centerHint.setVisibility(View.GONE);
                    if (Math.abs(gDy) > stage.getHeight() * 0.15f) {
                        switchBy(gDy < 0 ? 1 : -1);
                    } else {
                        bounce(gDy < 0 ? 1 : -1);
                    }
                } else if (gMode == G_SEEK) {
                    seekTo((float) gTargetMs / Math.max(1, curDur()));
                    ui.removeCallbacks(hintRun);
                    ui.postDelayed(hintRun, 400);
                } else if (gMode == G_NONE) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastTapAt < 300) {
                        lastTapAt = 0;
                        ui.removeCallbacks(singleTapRun);
                        togglePlay();
                    } else {
                        lastTapAt = now;
                        ui.postDelayed(singleTapRun, 260);
                    }
                }
                gMode = G_NONE;
                return true;
            }
        }
        return true;
    }

    // ================= 抽屉 =================
    private void openDrawer() {
        drawerOpen = true;
        ui.removeCallbacks(hideRun);
        qBlock.setVisibility(View.VISIBLE);
        spdBlock.setVisibility(View.VISIBLE);
        epBlock.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        markSpeed();
        markQuality();
        markAuto();
        if (curItem != null) scrollToEp(curItem.epIndex());
    }

    private void closeDrawer() {
        drawerOpen = false;
        if (drawer != null) drawer.setVisibility(View.GONE);
        if (scrim != null) scrim.setVisibility(View.GONE);
    }

    private void buildQualityBar() {
        qBar.removeAllViews();
        List<String> defs = new ArrayList<>();
        for (Model.Track t : lastTracks) {
            if (!defs.contains(t.definition)) defs.add(t.definition);
        }
        for (String def : defs) {
            TextView c = new TextView(this);
            c.setText(def.isEmpty() ? "默认" : def.toUpperCase());
            c.setTextSize(12);
            c.setPadding(dp(12), dp(6), dp(12), dp(6));
            c.setTag(def);
            c.setOnClickListener(v -> {
                quality = def;
                markQuality();
                closeDrawer();
                curItem = queue == null ? null : queue.current();
                startCurrent(0);
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

    private List<Model.Track> lastTracks = new ArrayList<>();

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
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (fullscreen) {
            toggleFullscreen();
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

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float cy = h / 2f;
            float t = Math.max(2f, dp(2));
            bg.setStrokeWidth(t);
            c.drawLine(0, cy, w, cy, bg);
            fg.setStrokeWidth(t);
            c.drawLine(0, cy, w * Math.max(0f, Math.min(1f, frac)), cy, fg);
            fg.setStyle(Paint.Style.FILL);
            c.drawCircle(w * Math.max(0f, Math.min(1f, frac)), cy, dp(3), fg);
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
