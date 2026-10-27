package com.hongguo.client;

import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 搜索页：综合 tab 优先，接口停用时自动回退联想接口（见 Api.search）。 */
public class SearchActivity extends AppCompatActivity {

    private static final int PINK = ItemsAdapter.PINK;
    private static final int TXT = 0xFF1A1A1A;
    private static final int DIM = 0xFF8A8A8E;
    private static final int BG = 0xFFF6F6F8;

    private EditText input;
    private RecyclerView grid;
    private ProgressBar bar;
    private TextView empty;
    private ItemsAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        try {
            Api.init(this);
        } catch (Exception e) {
            toast("初始化失败: " + e);
        }
        input.requestFocus();
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
        top.setPadding(dp(8), dp(8), dp(12), dp(8));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(24);
        back.setTextColor(TXT);
        back.setPadding(dp(8), 0, dp(8), dp(2));
        back.setOnClickListener(v -> finish());
        top.addView(back);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("搜剧名 / 演员");
        input.setTextSize(15);
        input.setHintTextColor(DIM);
        input.setTextColor(TXT);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            doSearch();
            return true;
        });
        top.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(this);
        go.setText("搜索");
        go.setTextSize(14);
        go.setTextColor(PINK);
        go.setPadding(dp(10), dp(8), dp(6), dp(8));
        go.setOnClickListener(v -> doSearch());
        top.addView(go);

        root.addView(top);

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setClipToPadding(false);
        grid.setPadding(dp(10), dp(2), dp(10), dp(24));
        adapter = new ItemsAdapter(this);
        adapter.setTitleLines(3);   // 搜索结果剧名普遍很长，3 行显示
        adapter.setOnOpen(it -> PlayActivity.start(this, it.seriesId, it.title, it.cover));
        adapter.setOnFav((it, now) -> toast(now ? "已收藏" : "已取消收藏"));
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(36), dp(36));
        blp.gravity = Gravity.CENTER;
        body.addView(bar, blp);
        bar.setVisibility(View.GONE);

        empty = new TextView(this);
        empty.setTextColor(DIM);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setText("输入剧名后按回车或点「搜索」");
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER;
        body.addView(empty, elp);

        return root;
    }

    private void doSearch() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            toast("先输入关键词");
            return;
        }
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);

        bar.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        adapter.set(new ArrayList<>());

        Api.run(() -> Api.search(q, 40), new Api.Cb<List<Model.Item>>() {
            @Override
            public void ok(List<Model.Item> v) {
                bar.setVisibility(View.GONE);
                if (v == null || v.isEmpty()) {
                    empty.setText("没搜到「" + q + "」\n换个关键词试试（剧名的一部分也行）");
                    empty.setVisibility(View.VISIBLE);
                    return;
                }
                empty.setVisibility(View.GONE);
                grid.setVisibility(View.VISIBLE);
                adapter.set(v);
                grid.scrollToPosition(0);
            }

            @Override
            public void err(Exception e) {
                bar.setVisibility(View.GONE);
                empty.setText("搜索失败\n" + (e.getMessage() == null ? e.toString() : e.getMessage())
                        + "\n\n点这里重试");
                empty.setVisibility(View.VISIBLE);
                empty.setOnClickListener(x -> doSearch());
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
