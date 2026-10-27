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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private TextView status, favBtn, titleView;
    private LinearLayout qBar;
    private RecyclerView epGrid;
    private EpAdapter epAdapter;
    private View loading;

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

        LinearLayout top = new LinearLayout(this);
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
        syncFav();
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(true);
        stage.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        LinearLayout panel = new LinearLayout(this);
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

    // ---------------- 准备并播放 ----------------
    private void prepare(Model.Episode ep, Model.Track t, int startSec) {
        String tag = ep.vid + "." + safe(t.definition);
        File plain = new File(getCacheDir(), tag + ".mp4");
        if (plain.exists() && plain.length() > 1024) {
            startPlay(plain, startSec);
            return;
        }
        File enc = new File(getCacheDir(), tag + ".enc");

        setStatus("下载中 0%");
        Api.run(() -> {
            Api.download(t.url, enc, (done, total) -> {
                int pct = total > 0 ? (int) (done * 100L / total) : 0;
                runOnUiThread(() -> status.setText("下载中 " + pct + "%"
                        + (total > 0 ? "（" + (total / 1048576) + " MB）" : "")));
            });
            runOnUiThread(() -> status.setText("解密中…"));
            String key = t.encrypted ? cenc.Spade.spadeToKey(t.spadeA) : null;
            cenc.Decryptor.Stats st = cenc.Decryptor.decrypt(enc, plain, key, "full");
            // 密文用完即删，省空间
            if (enc.exists()) enc.delete();
            return st;
        }, new Api.Cb<cenc.Decryptor.Stats>() {
            @Override
            public void ok(cenc.Decryptor.Stats v) {
                if (!plain.exists() || plain.length() < 1024) {
                    setStatus("解密失败：产物为空");
                    return;
                }
                startPlay(plain, startSec);
            }

            @Override
            public void err(Exception e) {
                setStatus("下载/解密失败\n" + msg(e));
            }
        });
    }

    private void startPlay(File f, int startSec) {
        runOnUiThread(() -> {
            status.setVisibility(View.GONE);
            if (player == null) player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)));
            player.prepare();
            if (startSec > 0) player.seekTo(startSec * 1000L);
            player.setPlayWhenReady(true);
        });
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
