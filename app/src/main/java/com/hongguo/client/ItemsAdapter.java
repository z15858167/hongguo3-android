package com.hongguo.client;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 三列卡片网格。首页 / 搜索 / 分类共用（之前是三处各写一份）。
 *
 * 纯代码布局，不引 Glide/图片库：封面走 Img 的内存 LRU。
 */
public class ItemsAdapter extends RecyclerView.Adapter<ItemsAdapter.Holder> {

    public static final int PINK = 0xFFFF2C55;
    private static final int TXT = 0xFF1A1A1A;
    private static final int DIM = 0xFF8A8A8E;

    public interface OnOpen {
        void open(Model.Item it);
    }

    public interface OnFav {
        void fav(Model.Item it, boolean nowFav);
    }

    private final Activity act;
    private final List<Model.Item> data = new ArrayList<>();
    private final boolean showFav;
    private OnOpen onOpen;
    private OnFav onFav;

    public ItemsAdapter(Activity act) {
        this(act, true);
    }

    public ItemsAdapter(Activity act, boolean showFav) {
        this.act = act;
        this.showFav = showFav;
    }

    public void setOnOpen(OnOpen l) {
        this.onOpen = l;
    }

    public void setOnFav(OnFav l) {
        this.onFav = l;
    }

    public void set(List<Model.Item> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void add(List<Model.Item> list) {
        if (list == null || list.isEmpty()) return;
        int from = data.size();
        data.addAll(list);
        notifyItemRangeInserted(from, list.size());
    }

    public List<Model.Item> items() {
        return data;
    }

    public int size() {
        return data.size();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(8));

        FrameLayout poster = new FrameLayout(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0xFFE6E6EA);
        poster.setBackground(bg);
        poster.setClipToOutline(true);

        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView fav = new TextView(act);
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
        fav.setVisibility(showFav ? View.VISIBLE : View.GONE);

        TextView badge = new TextView(act);
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

        TextView t = new TextView(act);
        t.setTextSize(13);
        t.setTextColor(TXT);
        t.setMaxLines(1);
        t.setPadding(dp(2), dp(6), dp(2), 0);
        card.addView(t);

        TextView s = new TextView(act);
        s.setTextSize(11);
        s.setTextColor(DIM);
        s.setMaxLines(1);
        s.setPadding(dp(2), dp(2), dp(2), 0);
        card.addView(s);

        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.setLayoutParams(lp);
        return new Holder(card, iv, t, s, fav, badge);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        final Model.Item it = data.get(pos);
        h.title.setText(it.title);
        String sub = it.hot == null || it.hot.isEmpty() ? it.sub() : it.hot;
        h.sub.setText(sub == null ? "" : sub);
        h.badge.setVisibility(it.episodeCnt > 0 ? View.VISIBLE : View.GONE);
        h.badge.setText(it.episodeCnt > 0 ? "全 " + it.episodeCnt + " 集" : "");
        Img.load(h.img, it.cover);

        boolean on = Store.isFav(act, it.seriesId);
        h.fav.setTextColor(on ? PINK : 0xEEFFFFFF);

        h.card.setOnClickListener(v -> {
            if (onOpen != null) onOpen.open(it);
        });

        h.fav.setOnClickListener(v -> {
            boolean now = Store.toggleFav(act, it.seriesId, it.title, it.cover);
            h.fav.setTextColor(now ? PINK : 0xEEFFFFFF);
            if (onFav != null) onFav.fav(it, now);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView img;
        final TextView title;
        final TextView sub;
        final TextView fav;
        final TextView badge;

        Holder(View card, ImageView img, TextView title, TextView sub, TextView fav, TextView badge) {
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
