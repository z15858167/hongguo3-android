package com.hongguo.client;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 首页：榜单 / 收藏 / 观看历史。 */
public class MainActivity extends AppCompatActivity {

    private static final int PINK = 0xFFFF2C55;
    private static final int TXT = 0xFF1A1A1A;
    private static final int DIM = 0xFF8A8A8E;
    private static final int BG = 0xFFF6F6F8;

    private static final String[] BOARDS = {"recommend", "hot", "new", "fav", "hist"};
    private static final String[] TABS = {"推荐", "热播", "新剧", "收藏", "历史"};

    private LinearLayout tabBar;
    private RecyclerView grid;
    private ProgressBar bar;
    private TextView empty;
    private Adapter adapter;
    private String cur = "recommend";
    private boolean loaded;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        try {
            Api.init(this);
        } catch (Exception e) {
            toast("初始化失败: " + e);
        }
        select(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loaded && ("fav".equals(cur) || "hist".equals(cur))) select(indexOf(cur));
    }

    private int indexOf(String board) {
        for (int i = 0; i < BOARDS.length; i++) if (BOARDS[i].equals(board)) return i;
        return 0;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(12), dp(12), dp(6));

        TextView title = new TextView(this);
        title.setText("红果短剧");
        title.setTextSize(21);
        title.setTextColor(TXT);
        title.getPaint().setFakeBoldText(true);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView diag = new TextView(this);
        diag.setText("诊断");
        diag.setTextSize(13);
        diag.setTextColor(PINK);
        diag.setPadding(dp(12), dp(6), dp(4), dp(6));
        diag.setOnClickListener(v -> showDiag());
        top.addView(diag);
        root.addView(top);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(12), 0, dp(12), dp(8));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(15);
            t.setPadding(dp(14), dp(7), dp(14), dp(7));
            t.setOnClickListener(v -> select(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            tabBar.addView(t, lp);
        }
        hs.addView(tabBar);
        root.addView(hs);

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setClipToPadding(false);
        grid.setPadding(dp(10), dp(2), dp(10), dp(24));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(36), dp(36));
        blp.gravity = Gravity.CENTER;
        body.addView(bar, blp);

        empty = new TextView(this);
        empty.setTextColor(DIM);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), 0, dp(24), 0);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER;
        body.addView(empty, elp);

        return root;
    }

    private void select(int idx) {
        cur = BOARDS[idx];
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            boolean on = i == idx;
            t.setTextColor(on ? PINK : DIM);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            g.setColor(on ? 0x1AFF2C55 : Color.TRANSPARENT);
            t.setBackground(g);
        }
        load();
    }

    private void load() {
        loaded = false;
        bar.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        adapter.set(new ArrayList<>());

        if ("fav".equals(cur) || "hist".equals(cur)) {
            List<Model.Item> out = new ArrayList<>();
            for (Store.Rec r : ("fav".equals(cur) ? Store.favs(this) : Store.hists(this))) {
                Model.Item it = new Model.Item();
                it.seriesId = r.sid;
                it.title = r.title;
                it.cover = r.cover;
                it.episodeCnt = r.cnt;
                it.hot = "hist".equals(cur) ? r.sub() : "";
                out.add(it);
            }
            show(out, "fav".equals(cur) ? "还没有收藏，点卡片右下角的 ♥" : "还没有观看记录");
            return;
        }

        Api.run(() -> Api.rank(cur, 60), new Api.Cb<List<Model.Item>>() {
            @Override
            public void ok(List<Model.Item> v) {
                show(v, "榜单为空");
            }

            @Override
            public void err(Exception e) {
                fail(e);
            }
        });
    }

    private void show(List<Model.Item> list, String tip) {
        bar.setVisibility(View.GONE);
        loaded = true;
        if (list == null || list.isEmpty()) {
            grid.setVisibility(View.GONE);
            empty.setText(tip);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        adapter.set(list);
        grid.scrollToPosition(0);
    }

    private void fail(Exception e) {
        bar.setVisibility(View.GONE);
        loaded = true;
        grid.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        empty.setText("加载失败\n" + (e.getMessage() == null ? e.toString() : e.getMessage())
                + "\n\n点这里重试");
        empty.setOnClickListener(v -> load());
    }

    private void showDiag() {
        StringBuilder sb = new StringBuilder();
        sb.append("签名状态\n").append(Signer.status()).append("\n\n");
        sb.append("接口\n");
        sb.append("榜单：免签\n剧集/播放地址：需签名\n\n");
        sb.append("签名不可用时，榜单能看但点进去会报错。");
        new AlertDialog.Builder(this)
                .setTitle("诊断")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .setNeutralButton("重试签名", (d, w) -> {
                    toast(Signer.ready() ? "签名可用" : "签名不可用，见诊断");
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 列表 ----------------
    private class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<Model.Item> data = new ArrayList<>();

        void set(List<Model.Item> list) {
            data.clear();
            if (list != null) data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(4), dp(4), dp(4), dp(8));

            FrameLayout poster = new FrameLayout(MainActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(0xFFE6E6EA);
            poster.setBackground(bg);
            poster.setClipToOutline(true);

            ImageView iv = new ImageView(MainActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView fav = new TextView(MainActivity.this);
            fav.setTextSize(15);
            fav.setText("♥");
            fav.setGravity(Gravity.CENTER);
            fav.setTextColor(0xFFFFFFFF);
            GradientDrawable fbg = new GradientDrawable();
            fbg.setShape(GradientDrawable.OVAL);
            fbg.setColor(0x66000000);
            fav.setBackground(fbg);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(28), dp(28));
            flp.gravity = Gravity.END | Gravity.BOTTOM;
            flp.setMargins(0, 0, dp(6), dp(6));
            poster.addView(fav, flp);

            TextView badge = new TextView(MainActivity.this);
            badge.setTextSize(11);
            badge.setTextColor(0xFFFFFFFF);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            GradientDrawable bbg = new GradientDrawable();
            bbg.setCornerRadius(dp(4));
            bbg.setColor(0x99000000);
            badge.setBackground(bbg);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.START | Gravity.TOP;
            blp.setMargins(dp(6), dp(6), 0, 0);
            poster.addView(badge, blp);

            card.addView(poster, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

            TextView t = new TextView(MainActivity.this);
            t.setTextSize(13);
            t.setTextColor(TXT);
            t.setMaxLines(1);
            t.setPadding(dp(2), dp(6), dp(2), 0);
            card.addView(t);

            TextView s = new TextView(MainActivity.this);
            s.setTextSize(11);
            s.setTextColor(DIM);
            s.setMaxLines(1);
            s.setPadding(dp(2), dp(2), dp(2), 0);
            card.addView(s);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            card.setLayoutParams(lp);
            return new Holder(card, poster, iv, t, s, fav, badge);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Model.Item it = data.get(pos);
            h.title.setText(it.title);
            String sub = it.hot == null || it.hot.isEmpty() ? it.sub() : it.hot;
            h.sub.setText(sub == null ? "" : sub);
            h.badge.setVisibility(it.episodeCnt > 0 ? View.VISIBLE : View.GONE);
            h.badge.setText(it.episodeCnt > 0 ? "全 " + it.episodeCnt + " 集" : "");
            Img.load(h.img, it.cover);

            boolean on = Store.isFav(MainActivity.this, it.seriesId);
            h.fav.setTextColor(on ? PINK : 0xEEFFFFFF);

            h.card.setOnClickListener(v -> PlayActivity.start(MainActivity.this,
                    it.seriesId, it.title, it.cover));

            h.fav.setOnClickListener(v -> {
                boolean now = Store.toggleFav(MainActivity.this, it.seriesId, it.title, it.cover);
                h.fav.setTextColor(now ? PINK : 0xEEFFFFFF);
                toast(now ? "已收藏" : "已取消收藏");
                if ("fav".equals(cur)) load();
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView img;
        final TextView title;
        final TextView sub;
        final TextView fav;
        final TextView badge;

        Holder(View card, View poster, ImageView img, TextView title, TextView sub,
               TextView fav, TextView badge) {
            super(card);
            this.card = card;
            this.img = img;
            this.title = title;
            this.sub = sub;
            this.fav = fav;
            this.badge = badge;
        }
    }
}
