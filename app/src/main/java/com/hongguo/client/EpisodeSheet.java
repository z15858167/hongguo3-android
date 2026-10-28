package com.hongguo.client;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 白底选集面板（对齐红果原生）。
 *
 *   - 从屏幕底部上推 260ms，占屏高 55%，顶部圆角；
 *   - 顶部只有「剧名 + 追剧」，**没有封面、没有简介 tab**；
 *   - 分页按 30 集切：1-30 / 31-60 / 61-78，单页时整行隐藏；
 *   - 6 列网格，当前集浅橙底 + 右上「播放中」；
 *   - 点某一集 → 先切集（回调），面板同时回落 200ms，不等加载完。
 *
 * 全局唯一的位移动画就是这里；进/出详情一律 0ms 硬切。
 */
final class EpisodeSheet {

    interface Cb {
        /** 点某一集：立刻切过去（不等首帧）。 */
        void onPick(int epIndex);
    }

    private static final int PER_PAGE = 30;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF17171A;
    private static final int SUB = 0xFF6A6A70;
    private static final int ORANGE = 0xFFFF6A00;
    private static final int ORANGE_BG = 0xFFFFF1E6;
    private static final int CELL_BG = 0xFFF4F4F6;

    private final Context ctx;
    private final ViewGroup host;
    private final Model.Detail detail;
    private final List<Model.Episode> all = new ArrayList<>();

    private View scrim;
    private LinearLayout panel;
    private RecyclerView grid;
    private LinearLayout pageRow;
    private Adapter adapter;

    private int curEp;
    private int page;
    private int panelH;
    private boolean open;
    private Cb cb;

    EpisodeSheet(Context ctx, ViewGroup host, Model.Detail d, int curEp) {
        this.ctx = ctx;
        this.host = host;
        this.detail = d;
        this.curEp = curEp;
        if (d != null) {
            for (Model.Episode e : d.eps) {
                if (e != null) all.add(e);
            }
            // 上游可能没按 index 排序，这里保证 1,2,3... 顺序
            all.sort((a, b) -> a.index - b.index);
        }
        this.page = pageOf(curEp);
    }

    void setCb(Cb c) {
        this.cb = c;
    }

    boolean isOpen() {
        return open;
    }

    void setCurrent(int epIndex) {
        curEp = epIndex;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** 上推 260ms。 */
    void show() {
        if (open) {
            setCurrent(curEp);
            return;
        }
        if (panel == null) build();
        open = true;
        // 面板是按"打开那一刻的当前集"算页签的：中途切过集（尤其跨页）再打开，
        // 页签还停在上一次的页码，当前集就不在可见页里了。
        int want = pageOf(curEp);
        if (want != page) {
            page = want;
            markPages();
            adapter.submit(itemsOfPage());
        }
        scrim.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
        panel.animate().cancel();
        panel.setTranslationY(panelH);
        panel.animate().translationY(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
        scrollToCurrent();
    }

    /** 回落 200ms（点完某一集、返回键、点遮罩）。 */
    void collapse() {
        if (!open) return;
        open = false;
        if (panel == null) return;
        panel.animate().cancel();
        panel.animate().translationY(panelH).setDuration(200)
                .withEndAction(() -> {
                    if (!open) panel.setVisibility(View.INVISIBLE);
                }).start();
        scrim.setVisibility(View.GONE);
    }

    /** 换了一部剧：把自己加进 host 的两个视图摘掉，别留在层级里（宿主再建新面板）。 */
    void destroy() {
        open = false;
        cb = null;
        if (panel != null) {
            panel.animate().cancel();
            host.removeView(panel);
            panel = null;
        }
        if (scrim != null) {
            host.removeView(scrim);
            scrim = null;
        }
        grid = null;
        pageRow = null;
        adapter = null;
    }

    // ---------------- 构建 ----------------
    private void build() {
        panelH = (int) (host.getHeight() > 0 ? host.getHeight() * 0.55f
                : ctx.getResources().getDisplayMetrics().heightPixels * 0.55f);

        scrim = new View(ctx);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> collapse());
        scrim.setVisibility(View.GONE);
        host.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(WHITE);
        bg.setCornerRadii(new float[]{dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setPadding(dp(14), dp(6), dp(14), 0);
        panel.setVisibility(View.INVISIBLE);
        panel.setTranslationY(panelH);

        // 顶部把手
        View handle = new View(ctx);
        GradientDrawable hg = new GradientDrawable();
        hg.setCornerRadius(dp(2));
        hg.setColor(0xFFD8D8DC);
        handle.setBackground(hg);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.topMargin = dp(4);
        panel.addView(handle, hLp);

        // 标题行：剧名 + 追剧
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(10), 0, dp(8));
        TextView title = new TextView(ctx);
        title.setText(detail == null || detail.title == null ? "" : detail.title);
        title.setTextSize(15);
        title.setTextColor(INK);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView total = new TextView(ctx);
        total.setText("全 " + all.size() + " 集");
        total.setTextSize(12);
        total.setTextColor(SUB);
        titleRow.addView(total);
        panel.addView(titleRow);

        // 分页页签（只有一页时整行不显示）
        pageRow = new LinearLayout(ctx);
        pageRow.setOrientation(LinearLayout.HORIZONTAL);
        pageRow.setPadding(0, 0, 0, dp(8));
        int pages = pageCount();
        if (pages > 1) {
            for (int p = 0; p < pages; p++) {
                final int target = p;
                int from = p * PER_PAGE + 1;
                int to = Math.min(all.size(), (p + 1) * PER_PAGE);
                TextView chip = new TextView(ctx);
                chip.setText(from + "-" + to);
                chip.setTextSize(12);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(12), dp(5), dp(12), dp(5));
                chip.setTag(p);
                chip.setOnClickListener(v -> {
                    page = target;
                    markPages();
                    adapter.submit(itemsOfPage());
                    scrollToCurrent();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                pageRow.addView(chip, lp);
            }
            panel.addView(pageRow);
            markPages();
        } else {
            pageRow.setVisibility(View.GONE);
            panel.addView(pageRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0));
        }

        // 6 列网格
        grid = new RecyclerView(ctx);
        grid.setLayoutManager(new GridLayoutManager(ctx, 6));
        grid.setPadding(0, 0, 0, dp(10));
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        adapter.submit(itemsOfPage());
        panel.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelH, Gravity.BOTTOM);
        host.addView(panel, plp);
    }

    private int pageCount() {
        return Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
    }

    private int pageOf(int epIndex) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).index == epIndex) return i / PER_PAGE;
        }
        return 0;
    }

    private List<Model.Episode> itemsOfPage() {
        int from = page * PER_PAGE;
        int to = Math.min(all.size(), from + PER_PAGE);
        if (from >= to) return new ArrayList<>();
        return new ArrayList<>(all.subList(from, to));
    }

    private void markPages() {
        if (pageRow == null) return;
        for (int i = 0; i < pageRow.getChildCount(); i++) {
            TextView c = (TextView) pageRow.getChildAt(i);
            boolean on = ((Integer) c.getTag()) == page;
            c.setTextColor(on ? ORANGE : SUB);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(13));
            g.setColor(on ? ORANGE_BG : 0xFFF4F4F6);
            c.setBackground(g);
        }
    }

    private void scrollToCurrent() {
        if (grid == null) return;
        int idx = -1;
        List<Model.Episode> pageItems = itemsOfPage();
        for (int i = 0; i < pageItems.size(); i++) {
            if (pageItems.get(i).index == curEp) {
                idx = i;
                break;
            }
        }
        final int target = idx;
        if (target < 0) return;
        grid.post(() -> grid.scrollToPosition(Math.max(0, target - 6)));
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    // ---------------- 网格 ----------------
    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private List<Model.Episode> data = new ArrayList<>();

        void submit(List<Model.Episode> d) {
            data = d;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout box = new FrameLayout(ctx);
            TextView num = new TextView(ctx);
            num.setGravity(Gravity.CENTER);
            num.setTextSize(13);
            box.addView(num, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView tag = new TextView(ctx);
            tag.setText("播放中");
            tag.setTextSize(9);
            tag.setGravity(Gravity.END);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.TOP);
            tlp.rightMargin = dp(3);
            tlp.topMargin = dp(2);
            box.addView(tag, tlp);
            return new Holder(box, num, tag);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Model.Episode e = data.get(position);
            boolean cur = e.index == curEp;
            h.num.setText(String.valueOf(e.index));
            h.num.setTextColor(cur ? ORANGE : INK);
            h.tag.setVisibility(cur ? View.VISIBLE : View.GONE);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(7));
            g.setColor(cur ? ORANGE_BG : CELL_BG);
            h.box.setBackground(g);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.box.getLayoutParams();
            if (lp != null) {
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                int hgt = dp(42);
                lp.height = hgt;
                h.box.setLayoutParams(lp);
            }
            h.box.setOnClickListener(v -> pick(e));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout box;
        final TextView num;
        final TextView tag;

        Holder(FrameLayout box, TextView num, TextView tag) {
            super(box);
            this.box = box;
            this.num = num;
            this.tag = tag;
        }
    }

    private void pick(Model.Episode e) {
        curEp = e.index;
        if (adapter != null) adapter.notifyDataSetChanged();
        // 先切集（立刻发起），再回落 —— 不等首帧
        if (cb != null) cb.onPick(e.index);
        collapse();
    }
}
