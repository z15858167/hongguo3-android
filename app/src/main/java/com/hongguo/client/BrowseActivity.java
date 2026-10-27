package com.hongguo.client;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类浏览：体裁（短剧/漫剧/AI短剧）+ 主题/设定/背景/排序/受众/时间/状态 多选筛选。
 *
 * 筛选面板优先用上游 filters() 实时返回；拿不到时退回内置表（与桌面版 FILTER_CATE 一致）。
 */
public class BrowseActivity extends AppCompatActivity {

    private static final int PINK = ItemsAdapter.PINK;
    private static final int TXT = 0xFF1A1A1A;
    private static final int DIM = 0xFF8A8A8E;
    private static final int BG = 0xFFF6F6F8;

    private static final String[][] GENRES = {
            {"short_play", "短剧"}, {"comic_series", "漫剧"}, {"ai_series", "AI短剧"}};

    private static final List<String> SINGLE_KEYS = Arrays.asList(
            "sort", "online_time", "gender", "creation_status");

    private String genre = "short_play";
    private final Map<String, List<String>> sel = new LinkedHashMap<>();
    private final List<String> shown = new ArrayList<>();

    private LinearLayout genreBar;
    private LinearLayout filterBox;
    private RecyclerView grid;
    private ProgressBar bar;
    private TextView status;
    private ItemsAdapter adapter;

    private boolean loading;
    private boolean hasMore = true;

    private final List<Row> rows = new ArrayList<>();

    private static class Row {
        String type;
        String label;
        boolean single;
        LinearLayout chips;
        List<Model.FilterItem> items = new ArrayList<>();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sel.put("genre", new ArrayList<>(Arrays.asList(genre)));
        setContentView(build());
        try {
            Api.init(this);
        } catch (Exception e) {
            toast("初始化失败: " + e);
        }
        loadFilters();
        reload();
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
        top.setPadding(dp(8), dp(8), dp(8), dp(4));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(24);
        back.setTextColor(TXT);
        back.setPadding(dp(8), 0, dp(10), dp(2));
        back.setOnClickListener(v -> finish());
        top.addView(back);

        TextView title = new TextView(this);
        title.setText("分类");
        title.setTextSize(17);
        title.setTextColor(TXT);
        title.getPaint().setFakeBoldText(true);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reset = new TextView(this);
        reset.setText("重置");
        reset.setTextSize(13);
        reset.setTextColor(DIM);
        reset.setPadding(dp(10), dp(6), dp(10), dp(6));
        reset.setOnClickListener(v -> {
            for (List<String> v2 : sel.values()) v2.clear();
            sel.put("genre", new ArrayList<>(Arrays.asList(genre)));
            markAll();
            reload();
        });
        top.addView(reset);
        root.addView(top);

        genreBar = new LinearLayout(this);
        genreBar.setOrientation(LinearLayout.HORIZONTAL);
        genreBar.setPadding(dp(12), 0, dp(12), dp(6));
        for (String[] g : GENRES) {
            final String id = g[0];
            TextView c = chip(g[1], genre.equals(id) ? PINK : 0x22000000,
                    genre.equals(id) ? 0xFFFFFFFF : TXT);
            c.setTag(id);
            c.setOnClickListener(v -> {
                if (genre.equals(id)) return;
                genre = id;
                for (List<String> v2 : sel.values()) v2.clear();
                sel.put("genre", new ArrayList<>(Arrays.asList(genre)));
                markGenre();
                loadFilters();
                reload();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            genreBar.addView(c, lp);
        }
        root.addView(genreBar);

        ScrollView fs = new ScrollView(this);
        filterBox = new LinearLayout(this);
        filterBox.setOrientation(LinearLayout.VERTICAL);
        filterBox.setPadding(dp(12), 0, dp(12), dp(4));
        fs.addView(filterBox);
        root.addView(fs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new RecyclerView(this);
        GridLayoutManager lm = new GridLayoutManager(this, 3);
        grid.setLayoutManager(lm);
        grid.setClipToPadding(false);
        grid.setPadding(dp(10), dp(6), dp(10), dp(24));
        adapter = new ItemsAdapter(this);
        adapter.setOnOpen(it -> PlayActivity.start(this, it.seriesId, it.title, it.cover));
        adapter.setOnFav((it, now) -> toast(now ? "已收藏" : "已取消收藏"));
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                GridLayoutManager g = (GridLayoutManager) rv.getLayoutManager();
                if (g == null) return;
                int last = g.findLastVisibleItemPosition();
                if (last >= adapter.size() - 4) loadMore();
            }
        });
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(36), dp(36));
        blp.gravity = Gravity.CENTER;
        body.addView(bar, blp);
        bar.setVisibility(View.GONE);

        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER;
        body.addView(status, slp);

        return root;
    }

    private TextView chip(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(fg);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(13));
        g.setColor(bg);
        t.setBackground(g);
        return t;
    }

    private void markGenre() {
        for (int i = 0; i < genreBar.getChildCount(); i++) {
            TextView c = (TextView) genreBar.getChildAt(i);
            boolean on = genre.equals(c.getTag());
            c.setTextColor(on ? 0xFFFFFFFF : TXT);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(13));
            g.setColor(on ? PINK : 0x22000000);
            c.setBackground(g);
        }
    }

    // ---------------- 筛选面板 ----------------
    private void loadFilters() {
        final String g = genre;
        Api.run(() -> Api.filters(g), new Api.Cb<List<Model.FilterRow>>() {
            @Override
            public void ok(List<Model.FilterRow> v) {
                if (!g.equals(genre)) return;
                if (v == null || v.isEmpty()) v = defaults();
                render(v);
            }

            @Override
            public void err(Exception e) {
                if (!g.equals(genre)) return;
                render(defaults());
            }
        });
    }

    private void render(List<Model.FilterRow> list) {
        filterBox.removeAllViews();
        rows.clear();
        for (Model.FilterRow r : list) {
            if (r.type == null || r.type.isEmpty() || "genre".equals(r.type)) continue;
            Row row = new Row();
            row.type = r.type;
            row.label = r.name == null || r.name.isEmpty() ? r.type : r.name;
            row.single = SINGLE_KEYS.contains(r.type);
            row.items = r.items;
            if (!sel.containsKey(row.type)) sel.put(row.type, new ArrayList<String>());

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(3), 0, dp(3));

            TextView lb = new TextView(this);
            lb.setText(row.label);
            lb.setTextSize(12);
            lb.setTextColor(DIM);
            lb.setPadding(0, 0, dp(8), 0);
            line.addView(lb, new LinearLayout.LayoutParams(dp(44),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            row.chips = new LinearLayout(this);
            row.chips.setOrientation(LinearLayout.HORIZONTAL);
            for (Model.FilterItem it : row.items) {
                TextView c = chip(it.name, 0x22000000, TXT);
                c.setTag(it.id);
                c.setOnClickListener(v -> toggle(row, it.id));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                row.chips.addView(c, lp);
            }
            hs.addView(row.chips);
            line.addView(hs, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            filterBox.addView(line);
            rows.add(row);
        }
        markAll();
    }

    private void toggle(Row row, String id) {
        List<String> cur = sel.get(row.type);
        if (cur == null) {
            cur = new ArrayList<>();
            sel.put(row.type, cur);
        }
        if (row.single) {
            boolean wasOn = cur.contains(id);
            cur.clear();
            if (!wasOn) cur.add(id);
        } else if (cur.contains(id)) {
            cur.remove(id);
        } else {
            cur.add(id);
        }
        mark(row);
        reload();
    }

    private void mark(Row row) {
        List<String> cur = sel.get(row.type);
        for (int i = 0; i < row.chips.getChildCount(); i++) {
            TextView c = (TextView) row.chips.getChildAt(i);
            boolean on = cur != null && cur.contains(c.getTag());
            c.setTextColor(on ? 0xFFFFFFFF : TXT);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(13));
            g.setColor(on ? PINK : 0x22000000);
            c.setBackground(g);
        }
    }

    private void markAll() {
        for (Row r : rows) mark(r);
    }

    /** 上游 filters 拿不到时的兜底（与桌面版 FILTER_CATE / FILTER_SORT 等一致）。 */
    private static List<Model.FilterRow> defaults() {
        List<Model.FilterRow> out = new ArrayList<>();
        out.add(row("category_dim_theme", "主题", new String[][]{
                {"cate_755", "脑洞"}, {"cate_6", "奇幻"}, {"cate_316", "剧情"}, {"cate_7", "玄幻"},
                {"cate_68", "末世"}, {"cate_936", "豪门"}, {"cate_1092", "科幻"}, {"cate_1182", "冒险"}}));
        out.add(row("category_dim_role", "设定", new String[][]{
                {"cate_36", "重生"}, {"cate_37", "穿越"}, {"cate_739", "逆袭"}, {"cate_598", "异能"},
                {"cate_19", "系统"}, {"cate_756", "反转"}, {"cate_43", "娱乐圈"}, {"cate_29", "总裁"}}));
        out.add(row("category_dim_epoch", "背景", new String[][]{
                {"cate_452", "架空"}, {"cate_1", "都市"}, {"cate_758", "古代"}, {"cate_599", "异界"},
                {"cate_4", "校园"}, {"cate_127", "职场"}, {"cate_79", "年代"},
                {"cate_11", "乡村"}, {"cate_390", "民国"}}));
        out.add(row("sort", "排序", new String[][]{
                {"online_time", "最新上架"}, {"hot_score", "最高热度"}, {"hot_collect", "最高收藏"}}));
        out.add(row("gender", "受众", new String[][]{{"1", "男频"}, {"0", "女频"}}));
        out.add(row("online_time", "时间", new String[][]{
                {"days_7", "7天内"}, {"days_14", "14天内"},
                {"days_30", "30天内"}, {"days_90", "90天内"}}));
        out.add(row("creation_status", "状态", new String[][]{
                {"creation_status_0", "已完结"}, {"creation_status_1", "连载中"}}));
        return out;
    }

    private static Model.FilterRow row(String type, String name, String[][] items) {
        Model.FilterRow r = new Model.FilterRow();
        r.type = type;
        r.name = name;
        for (String[] kv : items) {
            Model.FilterItem it = new Model.FilterItem();
            it.id = kv[0];
            it.name = kv[1];
            r.items.add(it);
        }
        return r;
    }

    // ---------------- 结果 ----------------
    private void reload() {
        shown.clear();
        hasMore = true;
        loading = false;
        adapter.set(new ArrayList<>());
        status.setText("");
        status.setVisibility(View.GONE);
        loadMore();
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        loading = true;
        if (adapter.size() == 0) bar.setVisibility(View.VISIBLE);

        final String g = genre;
        final Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : sel.entrySet()) {
            snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        final List<String> curShown = new ArrayList<>(shown);

        Api.run(() -> Api.browse(g, snapshot, curShown), new Api.Cb<Model.Page>() {
            @Override
            public void ok(Model.Page v) {
                loading = false;
                bar.setVisibility(View.GONE);
                if (!g.equals(genre)) return;
                if (v == null || v.items.isEmpty()) {
                    hasMore = false;
                    if (adapter.size() == 0) {
                        status.setText("这个筛选条件下没有内容\n换个条件试试");
                        status.setVisibility(View.VISIBLE);
                    } else {
                        status.setText("");
                        status.setVisibility(View.GONE);
                    }
                    return;
                }
                for (Model.Item it : v.items) shown.add(it.seriesId);
                adapter.add(v.items);
                hasMore = v.hasMore;
                status.setVisibility(View.GONE);
            }

            @Override
            public void err(Exception e) {
                loading = false;
                bar.setVisibility(View.GONE);
                hasMore = false;
                status.setText("加载失败\n" + (e.getMessage() == null ? e.toString() : e.getMessage())
                        + "\n点这里重试");
                status.setVisibility(View.VISIBLE);
                status.setOnClickListener(x -> {
                    status.setOnClickListener(null);
                    hasMore = true;
                    loadMore();
                });
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
