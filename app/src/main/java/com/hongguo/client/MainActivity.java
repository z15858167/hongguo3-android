package com.hongguo.client;

import android.graphics.Color;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 首页：推荐 / 热播 / 新剧 / 收藏 / 历史，外加搜索与分类入口。 */
public class MainActivity extends AppCompatActivity {

    private static final int PINK = ItemsAdapter.PINK;
    private static final int TXT = 0xFF1A1A1A;
    private static final int DIM = 0xFF8A8A8E;
    private static final int BG = 0xFFF6F6F8;

    private static final String[] BOARDS = {"recommend", "hot", "new", "fav", "hist"};
    private static final String[] TABS = {"推荐", "热播", "新剧", "收藏", "历史"};

    private LinearLayout tabBar;
    private RecyclerView grid;
    private ProgressBar bar;
    private TextView empty;
    private TextView clearBtn;
    private ItemsAdapter adapter;
    private String cur = "recommend";
    private boolean loaded;
    /** 每次 load() 自增，避免快速切 tab 时旧请求回来把新结果覆盖掉。 */
    private int session;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        try {
            Api.init(this);
        } catch (Exception e) {
            toast("初始化失败: " + e);
        }
        trimCacheAsync();
        select(0);
    }

    /** 启动时后台做一次缓存淘汰（水位见 Cache：1GB 上限，达到 824MB 清一半）。 */
    private void trimCacheAsync() {
        new Thread(() -> {
            try {
                long freed = Cache.trim(getCacheDir());
                if (freed > 0) {
                    Stats.note("启动时自动清理缓存 " + Cache.fmt(freed));
                }
            } catch (Throwable ignored) {
            }
        }, "hg-cache").start();
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
        top.setPadding(dp(16), dp(12), dp(8), dp(6));

        TextView title = new TextView(this);
        title.setText("红果短剧");
        title.setTextSize(21);
        title.setTextColor(TXT);
        title.getPaint().setFakeBoldText(true);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(action("分类", v -> startActivity(
                new android.content.Intent(this, BrowseActivity.class))));
        clearBtn = action("清空", v -> clearCurrent());
        clearBtn.setVisibility(View.GONE);
        top.addView(clearBtn);
        top.addView(action("缓存", v -> showCache()));
        top.addView(action("诊断", v -> showDiag()));
        root.addView(top);

        // ---- 搜索框：标题下方一条横向输入样式（点击进搜索页）----
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setCornerRadius(dp(20));
        sbBg.setColor(0x14FFFFFF);
        searchBox.setBackground(sbBg);
        searchBox.setPadding(dp(14), dp(9), dp(14), dp(9));
        searchBox.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, SearchActivity.class)));

        TextView sbIcon = new TextView(this);
        sbIcon.setText("\ud83d\udd0d");
        sbIcon.setTextSize(13);
        searchBox.addView(sbIcon);

        TextView sbHint = new TextView(this);
        sbHint.setText("搜索短剧名称");
        sbHint.setTextSize(14);
        sbHint.setTextColor(0xFF8A8A8E);
        sbHint.setPadding(dp(6), 0, 0, 0);
        searchBox.addView(sbHint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.leftMargin = dp(16);
        sbLp.rightMargin = dp(16);
        sbLp.topMargin = dp(2);
        sbLp.bottomMargin = dp(8);
        root.addView(searchBox, sbLp);

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
        adapter = new ItemsAdapter(this);
        adapter.setOnOpen(it -> PlayActivity.start(this, it.seriesId, it.title, it.cover));
        adapter.setOnFav((it, now) -> {
            toast(now ? "已收藏" : "已取消收藏");
            if ("fav".equals(cur)) load();
        });
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

    private TextView action(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(PINK);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private void clearCurrent() {
        boolean fav = "fav".equals(cur);
        String what = fav ? "收藏" : "观看历史";
        new AlertDialog.Builder(this)
                .setTitle("清空" + what)
                .setMessage("确定要清空全部" + what + "吗？此操作不能撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    if (fav) Store.clearFav(this);
                    else Store.clearHist(this);
                    load();
                    toast("已清空" + what);
                })
                .show();
    }

    private void select(int idx) {
        cur = BOARDS[idx];
        clearBtn.setVisibility(("fav".equals(cur) || "hist".equals(cur))
                ? View.VISIBLE : View.GONE);
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
        final int my = ++session;
        bar.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        empty.setOnClickListener(null);
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

        final String board = cur;
        final boolean[] first = {true};
        Api.run(() -> Api.rank(board, 60, (batch, done) -> {
                    // 榜单分页是串行的，这里每一轮结束就把"目前为止的全部条目"推过来先渲染，
                    // 后面的轮次后台续拉 —— 首屏不再等 60 条全齐
                    runOnUiThread(() -> render(my, first, batch, done));
                }),
                new Api.Cb<List<Model.Item>>() {
                    @Override
                    public void ok(List<Model.Item> v) {
                        runOnUiThread(() -> render(my, first, v, true));
                    }

                    @Override
                    public void err(Exception e) {
                        if (my != session) return;
                        fail(e);
                    }
                });
    }

    /** 分批渲染：第一批走 show()（带进度条/空态处理），后续只替换数据不再滚动到顶。 */
    private void render(int my, boolean[] first, List<Model.Item> v, boolean done) {
        if (my != session) return;
        if (first[0]) {
            first[0] = false;
            show(v, "榜单为空");
            return;
        }
        if (v == null || v.isEmpty()) return;
        adapter.set(v);
        if (done) bar.setVisibility(View.GONE);
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
        Stats.firstPaint();
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

    /** 缓存占用 + 一键清空。 */
    private void showCache() {
        java.io.File dir = getCacheDir();
        long total = Cache.size(dir);
        StringBuilder detail = new StringBuilder();
        for (java.io.File f : Cache.top(dir, 5)) {
            detail.append(f.getName()).append("  ").append(Cache.fmt(f.length())).append('\n');
        }
        String msg = Cache.line(dir) + "\n"
                + "文件数 " + Cache.list(dir).size()
                + "，设备剩余 " + Cache.fmt(dir.getUsableSpace()) + "\n\n"
                + (detail.length() > 0 ? "最大的几个：\n" + detail : "（暂无缓存）\n")
                + "\n缓存区上限 1GB；占用到 824MB（即空闲不足 200MB）时自动清掉一半，"
                + "\n设备剩余不足 150MB 时会继续清。没看完的集离开播放页即删。";
        new AlertDialog.Builder(this)
                .setTitle("缓存")
                .setMessage(msg)
                .setNegativeButton("关闭", null)
                .setPositiveButton("清空缓存", (d, w) -> {
                    long freed = Cache.clear(getCacheDir());
                    toast("已释放 " + Cache.fmt(freed));
                })
                .show();
    }

    private void showDiag() {
        StringBuilder sb = new StringBuilder();
        sb.append(Stats.report(this)).append('\n');
        sb.append("签名状态\n").append(Signer.status()).append("\n\n");
        sb.append("接口口径（实测）\n");
        sb.append("榜单：免签可用\n");
        sb.append("剧集详情：免签返回空 body，必须签名\n");
        sb.append("播放地址：免签正常，签名反而可能报 110001\n\n");
        sb.append("签名不可用时，榜单能看但点进去会报错。");
        new AlertDialog.Builder(this)
                .setTitle("诊断")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .setNeutralButton("测一下签名", (d, w) -> toast(
                        Signer.ready() ? "签名就绪" : "签名不可用，见上方原因"))
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
