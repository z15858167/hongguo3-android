package com.hongguo.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 播放节目单：把"一部剧的集"摊平成一维列表，供上下滑切换。
 *
 * 两种模式：
 *   - FEED：推荐榜驱动，一部剧播完自动接下一部（第一条 = 推荐榜第一部的第 1 集）；
 *   - SERIES：从剧场点进某部剧，只在剧内前后切，到头就是到头。
 *
 * 所有方法都是**阻塞**的（内部直接调 Api），调用方负责放到 Api.run 的后台线程里。
 */
final class FeedQueue {

    static final class Item {
        String sid = "";
        String seriesTitle = "";
        String cover = "";
        String intro = "";
        int epCnt;
        Model.Episode ep;

        String vid() {
            return ep == null ? "" : ep.vid;
        }

        int epIndex() {
            return ep == null ? 0 : ep.index;
        }

        boolean sameAs(Item o) {
            if (o == null || o.ep == null || ep == null) return false;
            return o.ep.vid.equals(ep.vid);
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Model.Item> pool = new ArrayList<>();
    private final Set<String> seenSeries = new HashSet<>();
    private final java.util.Map<String, Model.Detail> details = new java.util.HashMap<>();
    private final String board;
    private final boolean feed;
    private int cursor = -1;
    private int poolCursor = -1;

    private FeedQueue(String board, boolean feed) {
        this.board = board;
        this.feed = feed;
    }

    /** 单剧模式：整部剧的集已就绪。 */
    static FeedQueue ofSeries(Model.Detail d) {
        FeedQueue q = new FeedQueue("", false);
        q.append(d, null);
        q.cursor = 0;
        return q;
    }

    /** feed 模式：需要先 prime() 拉榜单。 */
    static FeedQueue ofFeed(String board) {
        return new FeedQueue(board == null || board.isEmpty() ? "recommend" : board, true);
    }

    boolean isFeed() {
        return feed;
    }

    Item current() {
        return cursor >= 0 && cursor < items.size() ? items.get(cursor) : null;
    }

    Item peekNext() {
        int i = cursor + 1;
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    Item peekPrev() {
        int i = cursor - 1;
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    /** 下一部剧的第一集（feed 预载用；没有则 null）。 */
    Item peekNextSeries() {
        Item cur = current();
        if (cur == null) return null;
        for (int i = cursor + 1; i < items.size(); i++) {
            if (!cur.sid.equals(items.get(i).sid)) return items.get(i);
        }
        return null;
    }

    int cursorIndex() {
        return cursor;
    }

    void cursor(int i) {
        if (i >= 0 && i < items.size()) cursor = i;
    }

    /** 移动游标；越界返回 false（调用方据此回弹/提示）。 */
    boolean advance(int dir) {
        int i = cursor + dir;
        if (i < 0 || i >= items.size()) return false;
        cursor = i;
        trim();
        return true;
    }

    /** 后面还有没有（同剧的下一集 or 下一部剧）。 */
    boolean hasNext() {
        return peekNext() != null || feed;
    }

    /** 当前这部剧的第一集在列表里的位置（没有则 -1）。 */
    private int firstOfSeries(String sid) {
        for (int i = 0; i < items.size(); i++) {
            if (sid.equals(items.get(i).sid)) return i;
        }
        return -1;
    }

    /**
     * 首页 feed 里上下滑：整部剧跳，不是集跳。
     * dir=1 落到下一部剧的第 1 集，dir=-1 落到上一部剧的第 1 集。
     * 越界返回 false（调用方负责回弹/提示/预取）。
     */
    boolean advanceSeries(int dir) {
        Item cur = current();
        if (cur == null) return false;
        if (dir > 0) {
            for (int i = cursor + 1; i < items.size(); i++) {
                if (!cur.sid.equals(items.get(i).sid)) {
                    cursor = i;          // 摊平后新剧的第一条正好是第 1 集
                    trim();
                    return true;
                }
            }
            return false;
        }
        for (int i = cursor - 1; i >= 0; i--) {
            if (!cur.sid.equals(items.get(i).sid)) {
                int first = firstOfSeries(items.get(i).sid);
                cursor = first < 0 ? i : first;
                return true;
            }
        }
        return false;
    }

    /** 定位到某一集的 vid（剧场点进来时续播用），找不到返回 false。 */
    boolean seekVid(String vid) {
        if (vid == null) return false;
        for (int i = 0; i < items.size(); i++) {
            if (vid.equals(items.get(i).vid())) {
                cursor = i;
                return true;
            }
        }
        return false;
    }

    /** 定位到某一集（剧场续播用），找不到返回 false。 */
    boolean seekEp(int index) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).epIndex() == index) {
                cursor = i;
                return true;
            }
        }
        return false;
    }

    int epCount() {
        Item it = current();
        return it == null ? 0 : it.epCnt;
    }

    /** 当前这部剧的完整详情（选集面板用）。 */
    Model.Detail curDetail() {
        Item it = current();
        return it == null ? null : details.get(it.sid);
    }

    // ---------------- 加载 ----------------

    /** feed 首次加载：拉榜单 + 展开第一部的集。 */
    Item prime() throws Exception {
        List<Model.Item> r = Api.rank(board, 12);
        if (r.isEmpty()) throw new IOException("推荐榜为空");
        for (Model.Item it : r) {
            if (it.seriesId != null && !it.seriesId.isEmpty() && seenSeries.add(it.seriesId)) {
                pool.add(it);
            }
        }
        for (int i = 0; i < pool.size(); i++) {
            appendSeries(pool.get(i));
            if (!items.isEmpty()) {
                poolCursor = i;
                cursor = 0;
                return current();
            }
        }
        throw new IOException("推荐榜里的剧都没有可播放的集");
    }

    /**
     * 往后接一部剧。返回 false 表示确实没有更多了。
     * 调用方应在游标接近尾部时提前调用（预取下一部），不要等到用户滑到底才拉。
     */
    boolean extend() throws Exception {
        if (!feed) return false;
        if (poolCursor + 1 < pool.size()) {
            int before = items.size();
            appendSeries(pool.get(++poolCursor));
            return items.size() > before;
        }
        List<Model.Item> more = Api.rank(board, 12);
        boolean added = false;
        for (Model.Item it : more) {
            if (it.seriesId != null && !it.seriesId.isEmpty() && seenSeries.add(it.seriesId)) {
                pool.add(it);
                added = true;
            }
        }
        if (!added) return false;
        int before = items.size();
        appendSeries(pool.get(++poolCursor));
        return items.size() > before;
    }

    /** 距尾部不足 n 条时该预取下一部了。 */
    boolean needExtend(int n) {
        return feed && cursor >= 0 && items.size() - cursor <= n;
    }

    private void appendSeries(Model.Item mi) {
        try {
            Model.Detail d = Api.episodes(mi.seriesId);
            append(d, mi);
        } catch (Exception ignored) {
            // 这一部拿不到就跳过，不影响后面
        }
    }

    private void append(Model.Detail d, Model.Item mi) {
        if (d == null) return;
        details.put(d.seriesId, d);
        String title = d.title == null || d.title.isEmpty()
                ? (mi == null ? "" : mi.title) : d.title;
        String cover = d.cover == null || d.cover.isEmpty()
                ? (mi == null ? "" : mi.cover) : d.cover;
        int cnt = d.episodeCnt > 0 ? d.episodeCnt : d.eps.size();
        for (Model.Episode e : d.eps) {
            Item it = new Item();
            it.sid = d.seriesId;
            it.seriesTitle = title;
            it.cover = cover;
            it.intro = d.intro == null ? "" : d.intro;
            it.epCnt = cnt;
            it.ep = e;
            items.add(it);
        }
    }

    /** 长刷不掉内存：只保留当前剧往前一部的内容。 */
    private void trim() {
        if (items.size() < 800) return;
        Item cur = current();
        if (cur == null) return;
        int first = 0;
        for (int i = 0; i < items.size(); i++) {
            if (cur.sid.equals(items.get(i).sid)) {
                first = i;
                break;
            }
        }
        if (first <= 200) return;
        items.subList(0, first).clear();
        cursor -= first;
    }
}
