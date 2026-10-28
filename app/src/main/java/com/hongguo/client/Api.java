package com.hongguo.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 上游接口封装。对应桌面版 hongguo.py 的 build_url / _api_once / rank / get_episodes / get_video_tracks。
 *
 * 签名策略：剧集详情与视频地址必须签名（实测免签返回空 body）；
 * 榜单接口免签可用，走 signed=false 省一次签名开销。
 */
public final class Api {

    private static final String TAG = "HgApi";
    private static final String RANK_CELL = "7470092475068071998";
    private static final Map<String, String> RANK_BOARDS = new LinkedHashMap<>();
    private static final Map<String, String> RANK_NAMES = new LinkedHashMap<>();

    static {
        RANK_BOARDS.put("recommend", "comic_series_hot_rank");
        RANK_BOARDS.put("hot", "comic_series_hot_play");
        RANK_BOARDS.put("new", "comic_series_new_rank");

        RANK_NAMES.put("recommend", "推荐榜");
        RANK_NAMES.put("hot", "热播榜");
        RANK_NAMES.put("new", "新剧榜");
    }

    private static String host = "";
    private static JSONObject baseQuery;
    private static JSONObject sessionHeaders;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ---- 接口结果缓存：切集/回上一集时不该再走一次签名 + 网络 ----
    /** 剧集详情：24 部，60 分钟。 */
    private static final android.util.LruCache<String, Entry<Model.Detail>> EP_CACHE =
            new android.util.LruCache<>(24);
    /** 播放地址：128 集，20 分钟（直链有时效，别缓存太久）。 */
    private static final android.util.LruCache<String, Entry<List<Model.Track>>> TRACK_CACHE =
            new android.util.LruCache<>(128);

    private static final long TTL_EP = 60L * 60 * 1000;
    private static final long TTL_TRACK = 20L * 60 * 1000;

    private static final class Entry<T> {
        final T v;
        final long at = System.currentTimeMillis();
        Entry(T v) {
            this.v = v;
        }
        boolean fresh(long ttl) {
            return System.currentTimeMillis() - at < ttl;
        }
    }

    public interface Cb<T> {
        void ok(T v);

        void err(Exception e);
    }

    private Api() {
    }

    public static String rankName(String board) {
        String n = RANK_NAMES.get(board);
        return n == null ? board : n;
    }

    private static String readAsset(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            return new String(readAll(in), "UTF-8");
        }
    }

    public static synchronized void init(Context ctx) throws Exception {
        if (baseQuery != null) return;
        JSONObject cfg = new JSONObject(readAsset(ctx, "config.json"));
        host = cfg.optString("api_host", "api5-normal-sinfonlinea.fqnovel.com");
        baseQuery = cfg.optJSONObject("base_query");
        sessionHeaders = cfg.optJSONObject("session_headers");
        if (baseQuery == null) baseQuery = new JSONObject();
        if (sessionHeaders == null) sessionHeaders = new JSONObject();
        // 只挂 Context，**不**在这里 loadLibrary：so 的加载留给第一次真正签名时
        // （跑在 Api 的工作线程上），避免开屏时在主线程 dlopen 造成卡顿。
        com.bytedance.mobsec.metasec.ml.MS.init(ctx);
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return v;
        }
    }

    private static String buildUrl(String path, Map<String, String> extra) throws Exception {
        StringBuilder sb = new StringBuilder("https://").append(host).append(path).append('?');
        boolean first = true;
        Iterator<String> it = baseQuery.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = baseQuery.optString(k, "");
            if (v.isEmpty()) continue;
            if (!first) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
            first = false;
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (!first) sb.append('&');
                sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
                first = false;
            }
        }
        if (!first) sb.append('&');
        sb.append("_rticket=").append(System.currentTimeMillis());
        return sb.toString();
    }

    private static String md5Upper(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString().toUpperCase();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        Iterator<String> it = sessionHeaders.keys();
        while (it.hasNext()) {
            String k = it.next();
            h.put(k, sessionHeaders.optString(k, ""));
        }
        return h;
    }

    private static JSONObject http(String method, String url, Map<String, String> headers, byte[] body)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            c.setRequestProperty(e.getKey(), v);
        }
        // 不再写死 identity：JSON 接口走 gzip（HttpURLConnection 会自动解压），
        // 榜单/详情响应体小一个量级。下载视频仍用 identity（见 download()，要 Content-Length 算进度）
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] raw = readAll(is);
        c.disconnect();
        if (raw.length == 0) throw new IOException("空响应 HTTP " + code + "（多半是签名未生效）");
        return new JSONObject(new String(raw, "UTF-8"));
    }

    private static JSONObject request(String method, String path, JSONObject body,
                                      Map<String, String> extraQuery, boolean signed) throws Exception {
        String url = buildUrl(path, extraQuery);
        Map<String, String> h = baseHeaders();
        h.put("content-type", "application/json; charset=utf-8");
        byte[] data = null;
        if (body != null) {
            data = body.toString().getBytes("UTF-8");
            h.put("x-ss-stub", md5Upper(data));
        }
        if (signed) {
            Map<String, String> sig = Signer.sign(url, h);
            h.putAll(sig);
            Log.d(TAG, "signed " + path + " -> " + sig.keySet());
        }
        return http(method, url, h, data);
    }

    private static boolean hasData(JSONObject j) {
        return j != null && j.opt("data") != null && !JSONObject.NULL.equals(j.opt("data"));
    }

    /**
     * 两种姿势都试：signedFirst=true 先签名、失败/无 data 再免签，反之亦然。
     *
     * 上游各接口的口径并不统一（实测）：
     *   episodes  —— 免签返回空 body，必须签名
     *   tracks    —— 免签正常，反而带错签名会返回 code=110001
     *   rank      —— 免签可用
     * 所以不要写死，统一走这里。
     */
    private static JSONObject requestEither(String method, String path, JSONObject body,
                                            Map<String, String> extraQuery,
                                            boolean signedFirst) throws Exception {
        boolean[] order = signedFirst ? new boolean[]{true, false} : new boolean[]{false, true};
        JSONObject last = null;
        Exception err = null;
        for (boolean s : order) {
            try {
                JSONObject j = request(method, path, body, extraQuery, s);
                last = j;
                if (hasData(j)) return j;
            } catch (Exception e) {
                if (err == null) err = e;
                Log.d(TAG, (s ? "签名" : "免签") + " 请求 " + path + " 失败: " + e);
            }
        }
        if (last != null) return last;
        throw err != null ? err : new IOException("请求失败: " + path);
    }


    // ---------------- 榜单 ----------------
    public static List<Model.Item> rank(String board, int limit) throws Exception {
        return rank(board, limit, null);
    }

    /**
     * 榜单。分页是串行的（下一轮 offset 依赖上一轮 next_offset），为了不再"等 60 条全齐才显示"，
     * 加了一个 sink：每攒够 firstBatch 条或每轮结束就回调一次，调用方先渲染再后台续拉。
     */
    public static List<Model.Item> rank(String board, int limit, Sink<List<Model.Item>> sink)
            throws Exception {
        String sub = RANK_BOARDS.get(board);
        if (sub == null) sub = RANK_BOARDS.get("hot");
        List<Model.Item> out = new ArrayList<>();
        int offset = 0;
        String sess = UUID.randomUUID().toString();
        Stats.clearRounds();
        for (int guard = 0; guard < 8 && out.size() < limit; guard++) {
            long t0 = System.currentTimeMillis();
            Map<String, String> q = new LinkedHashMap<>();
            q.put("cell_id", RANK_CELL);
            q.put("tab_type", "26");
            q.put("client_req_type", "2");
            q.put("client_template", "2");
            q.put("screen_width_px", "1350");
            q.put("selected_items", "comic_series_rank");
            q.put("sub_selected_items", sub);
            q.put("session_uuid", sess);
            if (offset > 0) q.put("offset", String.valueOf(offset));

            // 榜单实测免签即可用；锁死免签单次，不再"免签无 data 再签名重试"
            // （那样每轮可能翻倍，还会把首次 dlopen so 拖进首屏）
            JSONObject j = request("GET", "/reading/bookapi/bookmall/cell/change/v",
                    null, q, false);
            Stats.round(System.currentTimeMillis() - t0);
            JSONObject data = j.optJSONObject("data");
            JSONObject cv = data == null ? null : data.optJSONObject("cell_view");
            JSONArray cells = cv == null ? null : cv.optJSONArray("cell_data");
            if (cells == null || cells.length() == 0) break;

            for (int i = 0; i < cells.length(); i++) {
                JSONObject cell = cells.optJSONObject(i);
                if (cell == null) continue;
                Object v = cell.opt("video_data");
                JSONObject vd = null;
                if (v instanceof JSONArray) {
                    JSONArray a = (JSONArray) v;
                    if (a.length() > 0) vd = a.optJSONObject(0);
                } else if (v instanceof JSONObject) {
                    vd = (JSONObject) v;
                }
                if (vd == null) continue;
                String sid = vd.optString("series_id", "");
                if (sid.isEmpty()) sid = vd.optString("book_id", "");
                if (sid.isEmpty()) continue;
                Model.Item it = new Model.Item();
                it.seriesId = sid;
                it.title = vd.optString("title", "");
                it.episodeCnt = vd.optInt("episode_cnt", 0);
                it.score = vd.optString("score", "");
                it.playCnt = vd.optLong("play_cnt", 0);
                it.hot = vd.optString("rec_text", "");
                it.cover = vd.optString("cover", "");
                it.intro = cut(vd.optString("video_desc", ""), 50);
                out.add(it);
            }
            // 每轮结束就把已攒到的条目推给调用方先渲染
            if (sink != null) sink.push(new ArrayList<>(out), out.size() >= limit);
            if (cv != null && !cv.optBoolean("has_more", false)) break;
            int next = cv == null ? offset : cv.optInt("next_offset", offset + cells.length());
            if (next == offset) break;
            offset = next;
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ---------------- 搜索 ----------------
    /**
     * 搜索短剧。优先级与桌面版 server.api_search 一致：
     * 官网网页版接口（全量 + 每条都有真封面、免签）> App 综合 tab > App 联想接口。
     *
     * 为什么官网接口排第一（桌面版 2026-10 实测结论）：
     *  - /reading/bookapi/search/tab/v 已下线，返回空 body；
     *  - /reading/bookapi/search/suggest/v 硬上限 10 条，只有前 1~2 条带 video_data，
     *    其余 pic_url 是同一张灰色占位图 —— 这就是"搜出一堆但只有前几个有封面"的根因。
     */
    public static List<Model.Item> search(String query, int limit) throws Exception {
        return search(query, limit, null);
    }

    /**
     * @param sink 不为 null 时，每拿到一批就回调一次（首次回调通常是第一页的 10 条），
     *             调用方可以先把这批渲染出来，剩下的后台续拉——搜索不再"等全部翻完才出结果"。
     */
    public static List<Model.Item> search(String query, int limit, Sink<List<Model.Item>> sink)
            throws Exception {
        List<Model.Item> out = new ArrayList<>();
        try {
            out = webSearch(query, limit, sink);
        } catch (Exception e) {
            Log.d(TAG, "官网搜索不可用: " + e);
        }
        // searchTab 上游已下线（返回空 body），不再串行降级，省掉一次注定失败且要签名的请求。
        if (out.isEmpty()) out = searchSuggest(query, limit, sink);
        return out;
    }

    public interface Sink<T> {
        void push(T v, boolean done);
    }

    // ---- 官网网页版搜索：GET /api/search/result，免签、约 200ms、每页 10 条 ----
    private static final String WEB_BASE = "https://hongguoduanju.com";
    private static final String WEB_AID = "8662";
    private static final String WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static List<Model.Item> webSearch(String query, int limit) throws Exception {
        return webSearch(query, limit, null);
    }

    private static List<Model.Item> webSearch(String query, int limit,
                                              Sink<List<Model.Item>> sink) throws Exception {
        List<Model.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 8 && out.size() < limit; page++) {
            String url = WEB_BASE + "/api/search/result?aid=" + WEB_AID
                    + "&query=" + URLEncoder.encode(query, "UTF-8")
                    + "&page=" + page;
            long t0 = System.currentTimeMillis();
            JSONObject j;
            try {
                j = webGet(url);
            } catch (NoMorePage e) {
                // 200 + 空 body = 没有更多页：直接收尾，不再翻页（之前会重试 4 次并白睡 2s）
                Stats.page("第" + page + "页 空body(无更多)", System.currentTimeMillis() - t0);
                break;
            } catch (Exception e) {
                Log.d(TAG, "官网搜索第 " + page + " 页失败: " + e);
                Stats.page("第" + page + "页 失败:" + e.getMessage(), System.currentTimeMillis() - t0);
                break;
            }
            Stats.page("第" + page + "页", System.currentTimeMillis() - t0);

            JSONArray lst = j.optJSONArray("searchList");
            if (lst == null || lst.length() == 0) break;
            for (int i = 0; i < lst.length() && out.size() < limit; i++) {
                Model.Item it = parseWebCell(lst.optJSONObject(i));
                if (it == null || !seen.add(it.seriesId)) continue;
                out.add(it);
            }
            if (sink != null) sink.push(new ArrayList<>(out), out.size() >= limit);
            if (!j.optBoolean("hasMore", false)) break;
        }
        return out;
    }

    /** 官网返回 200 但 body 为空：表示翻到没有更多页了，不是错误。 */
    private static class NoMorePage extends Exception {
        NoMorePage() {
            super("空body");
        }
    }

    private static Model.Item parseWebCell(JSONObject x) {
        if (x == null) return null;
        JSONObject vd = x.optJSONObject("video_data");
        if (vd == null) return null;
        String sid = vd.optString("series_id", "");
        if (sid.isEmpty()) return null;
        String title = stripTags(vd.optString("series_title", ""));
        if (title.isEmpty()) title = stripTags(x.optString("name", ""));
        if (title.isEmpty()) return null;

        Model.Item it = new Model.Item();
        it.seriesId = sid;
        it.title = title;
        it.episodeCnt = vd.optInt("episode_cnt", 0);
        it.cover = vd.optString("series_cover", "");   // 官网给的是竖版真封面
        JSONObject hs = vd.optJSONObject("hot_score_data");
        it.hot = hs == null ? "" : hs.optString("text", "");
        if (it.hot.isEmpty()) it.hot = vd.optString("episode_right_text", "");
        it.score = vd.optString("score", "");
        it.intro = cut(stripTags(vd.optString("series_intro", "")), 50);
        return it;
    }

    /**
     * 官网接口偶发 502，所以只对 **5xx / 超时 / IO 异常** 重试（3 次，退避 0/150/300ms，
     * 最后一次失败后不再 sleep）；HTTP 200 但空 body 视为"没有更多页"，抛 NoMorePage 不重试。
     * 之前空 body 也走 4 次重试 + 固定 2.0s 退避，是搜索慢的主因。
     */
    private static JSONObject webGet(String url) throws Exception {
        String last = "未知";
        for (int i = 0; i < 3; i++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", WEB_UA);
                c.setRequestProperty("Accept", "application/json, text/plain, */*");
                c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                c.setRequestProperty("Referer", WEB_BASE + "/");
                c.setRequestProperty("Origin", WEB_BASE);
                int code = c.getResponseCode();
                if (code == 200) {
                    String s = slurp(c.getInputStream());
                    if (s != null && !s.trim().isEmpty()) return new JSONObject(s);
                    throw new NoMorePage();          // 没有更多页，不重试
                }
                last = "HTTP " + code;
                if (code >= 400 && code < 500) {     // 4xx 重试也没用
                    throw new IOException("官网搜索失败(" + last + ")");
                }
            } catch (NoMorePage e) {
                throw e;
            } catch (IOException e) {
                if (e instanceof java.net.SocketTimeoutException
                        || e instanceof java.net.ConnectException) {
                    last = e.toString();
                } else {
                    throw e;
                }
            } catch (Exception e) {
                last = e.toString();
            } finally {
                if (c != null) c.disconnect();
            }
            if (i < 2) Thread.sleep(150L * i);   // 第 1 次失败不睡，第 2 次睡 150ms
        }
        throw new IOException("官网搜索失败(" + last + ")");
    }

    private static String slurp(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static List<Model.Item> searchTab(String query, int limit) {
        List<Model.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int offset = 0;
        String passback = "";
        String searchId = "";
        for (int page = 0; page < 12 && out.size() < limit; page++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("query", query);
            q.put("tab_name", "feed");
            q.put("search_source", "1");
            q.put("offset", String.valueOf(offset));
            q.put("count", "0");
            q.put("use_correct", "true");
            if (!passback.isEmpty()) q.put("passback", passback);
            if (!searchId.isEmpty()) q.put("search_id", searchId);

            JSONObject j;
            try {
                j = requestEither("GET", "/reading/bookapi/search/tab/v", null, q, true);
            } catch (Exception e) {
                return out;   // 接口停用 / 空 body：退出，交给 suggest 兜底
            }
            JSONArray tabs = j.optJSONArray("search_tabs");
            if (tabs == null || tabs.length() == 0) return out;
            JSONObject tab = tabs.optJSONObject(0);
            if (tab == null) return out;
            JSONArray data = tab.optJSONArray("data");
            if (data == null || data.length() == 0) return out;

            for (int i = 0; i < data.length(); i++) {
                Model.Item it = parseSearchCell(data.optJSONObject(i));
                if (it == null || !seen.add(it.seriesId)) continue;
                out.add(it);
                if (out.size() >= limit) break;
            }
            if (!tab.optBoolean("has_more", false)) return out;
            int next = tab.optInt("next_offset", offset);
            if (next == offset) return out;
            offset = next;
            passback = tab.optString("passback", passback);
            searchId = tab.optString("search_id", searchId);
        }
        return out;
    }

    private static Model.Item parseSearchCell(JSONObject cell) {
        if (cell == null) return null;
        String sid = cell.optString("book_id", "");
        if (sid.isEmpty()) sid = cell.optString("search_result_id", "");
        if (sid.isEmpty()) return null;

        JSONObject vd = cell.optJSONObject("video_detail");
        JSONObject vdata = null;
        Object raw = cell.opt("video_data");
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            if (a.length() > 0) vdata = a.optJSONObject(0);
        } else if (raw instanceof JSONObject) {
            vdata = (JSONObject) raw;
        }
        JSONObject inner = vdata == null ? null : vdata.optJSONObject("video_detail");

        int ep = optIntAny(vd, "episode_cnt");
        if (ep == 0) ep = optIntAny(vdata, "episode_cnt");
        if (ep == 0) ep = optIntAny(inner, "episode_cnt");
        if (ep == 0) return null;   // 只要短剧（有集数的）

        Model.Item it = new Model.Item();
        it.seriesId = sid;
        JSONObject hl = cell.optJSONObject("search_high_light");
        JSONObject hlTitle = hl == null ? null : hl.optJSONObject("title");
        String title = hlTitle == null ? "" : stripTags(hlTitle.optString("text", ""));
        if (title.isEmpty()) title = stripTags(optStrAny(vd, "series_title"));
        if (title.isEmpty()) title = stripTags(optStrAny(vdata, "title"));
        if (title.isEmpty()) title = stripTags(optStrAny(inner, "series_title"));
        it.title = title;
        it.episodeCnt = ep;
        it.score = optStrAny(vdata, "score");
        if (it.score.isEmpty()) it.score = optStrAny(inner, "score");
        it.hot = optStrAny(vdata, "rec_text");
        // 封面字段在不同接口的 cell 里位置不固定，按桌面版实测的优先级逐个兜底：
        // video_data.cover → video_data.video_detail.series_cover → horiz_cover → 其它
        it.cover = optStrAny(vdata, "cover");
        if (it.cover.isEmpty()) it.cover = optStrAny(inner, "series_cover");
        if (it.cover.isEmpty()) it.cover = optStrAny(vdata, "horiz_cover");
        if (it.cover.isEmpty()) it.cover = optStrAny(inner, "cover");
        if (it.cover.isEmpty()) it.cover = optStrAny(vd, "cover");
        if (it.cover.isEmpty()) it.cover = optStrAny(vd, "series_cover");
        it.intro = cut(optStrAny(vdata, "video_desc"), 50);
        return it;
    }

    /** 联想接口兜底：2026-10 起 search/tab 对客户端返回空 body，suggest 仍可用。 */
    private static List<Model.Item> searchSuggest(String query, int limit) throws Exception {
        return searchSuggest(query, limit, null);
    }

    private static List<Model.Item> searchSuggest(String query, int limit,
                                                  Sink<List<Model.Item>> sink) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("query", query);
        JSONObject j = requestEither("GET", "/reading/bookapi/search/suggest/v", null, q, true);
        JSONObject data = j.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("query_result_v2");
        if (items == null && data != null) items = data.optJSONArray("query_result");

        List<Model.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (items == null) return out;
        for (int i = 0; i < items.length() && out.size() < limit; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            JSONObject vd = it.optJSONObject("video_data");
            String sid = vd == null ? "" : vd.optString("series_id", "");
            if (sid.isEmpty() && it.optInt("word_type", 0) == 2) {
                String kw = it.optString("keyword", "").trim();
                if (!kw.isEmpty() && kw.matches("[0-9]+")) sid = kw;
            }
            String title = stripTags(it.optString("name", ""));
            if (title.isEmpty() && vd != null) title = stripTags(vd.optString("title", ""));
            if (sid.isEmpty() || title.isEmpty() || !seen.add(sid)) continue;

            Model.Item m = new Model.Item();
            m.seriesId = sid;
            m.title = title;
            m.episodeCnt = vd == null ? 0 : vd.optInt("episode_cnt", 0);
            m.score = vd == null ? "" : vd.optString("score", "");
            m.cover = vd == null ? "" : vd.optString("cover", "");
            if (m.cover.isEmpty()) m.cover = it.optString("pic_url", "");
            m.hot = vd == null ? it.optString("sug_abstract", "") : "";
            m.intro = cut(vd != null ? vd.optString("video_desc", "")
                    : it.optString("sug_abstract", ""), 50);
            out.add(m);
        }
        if (sink != null) sink.push(new ArrayList<>(out), true);
        return out;
    }

    // ---------------- 分类 / 筛选 ----------------
    private static final String[][] GENRES = {
            {"short_play", "短剧"},
            {"comic_series", "漫剧"},
            {"ai_series", "AI短剧"},
    };

    public static String genreName(String genre) {
        for (String[] g : GENRES) if (g[0].equals(genre)) return g[1];
        return "短剧";
    }

    /** 体裁的实时筛选面板：主题/设定/背景/排序/受众/时间/状态。 */
    public static List<Model.FilterRow> filters(String genre) {
        JSONObject sel = new JSONObject();
        try {
            sel.put("category_dim_epoch", new JSONArray());
            sel.put("online_time", new JSONArray());
            sel.put("gender", new JSONArray());
            sel.put("category_dim_role", new JSONArray());
            JSONArray g = new JSONArray();
            g.put(genre);
            sel.put("genre", g);
            sel.put("sort", new JSONArray());
            sel.put("category_dim_theme", new JSONArray());

            JSONObject body = new JSONObject();
            body.put("filter_ids", "");
            body.put("req_scene", genre);
            body.put("offset", 0);
            body.put("limit", 1);
            body.put("need_selector_panel", true);
            body.put("req_type", "default");
            body.put("client_req_type", 3);
            body.put("select_items", sel);
            body.put("session_id", "");

            JSONObject j = requestEither("POST", "/reading/distribution/category/landpage/v",
                    body, null, true);
            JSONObject data = j.optJSONObject("data");
            JSONArray rows = data == null ? null : data.optJSONArray("selector_rows");
            List<Model.FilterRow> out = new ArrayList<>();
            if (rows == null) return out;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                Model.FilterRow row = new Model.FilterRow();
                row.type = r.optString("type", "");
                row.name = r.optString("row_name", "");
                JSONArray its = r.optJSONArray("items");
                if (its != null) {
                    for (int k = 0; k < its.length(); k++) {
                        JSONObject o = its.optJSONObject(k);
                        if (o == null) continue;
                        Model.FilterItem fi = new Model.FilterItem();
                        fi.id = o.optString("selector_item_id", "");
                        fi.name = o.optString("show_name", "");
                        if (!fi.id.isEmpty() && !fi.name.isEmpty()) row.items.add(fi);
                    }
                }
                if (!row.items.isEmpty()) out.add(row);
            }
            return out;
        } catch (Exception e) {
            Log.d(TAG, "filters 失败: " + e);
            return new ArrayList<>();
        }
    }

    /**
     * 按筛选浏览。sel 的 key 是 select_items 的键（genre / category_dim_theme /
     * category_dim_role / category_dim_epoch / sort / gender / online_time / creation_status），
     * value 是 id 列表。shown 是本轮已经出现过的 series_id（上游用它去重，也当作游标）。
     */
    public static Model.Page browse(String genre, Map<String, List<String>> sel,
                                    List<String> shown) throws Exception {
        JSONObject select = new JSONObject();
        try {
            select.put("category_dim_epoch", arr(sel.get("category_dim_epoch")));
            select.put("online_time", arr(sel.get("online_time")));
            select.put("gender", arr(sel.get("gender")));
            select.put("category_dim_role", arr(sel.get("category_dim_role")));
            select.put("genre", arr(sel.get("genre")));
            select.put("sort", arr(sel.get("sort")));
            select.put("category_dim_theme", arr(sel.get("category_dim_theme")));
            if (sel.containsKey("creation_status")) {
                select.put("creation_status", arr(sel.get("creation_status")));
            }

            JSONObject body = new JSONObject();
            body.put("filter_ids", joinIds(shown));
            body.put("req_scene", genre);
            body.put("offset", shown == null ? 0 : shown.size());
            body.put("need_selector_panel", false);
            body.put("limit", 18);
            body.put("select_items", select);
            body.put("session_id", "");
            body.put("req_type", "only_content");
            body.put("client_req_type", 3);

            JSONObject j = requestEither("POST", "/reading/distribution/category/landpage/v",
                    body, null, true);
            Model.Page page = new Model.Page();
            JSONObject data = j.optJSONObject("data");
            if (data == null) return page;
            JSONArray items = data.optJSONArray("video_data");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    Model.Item it = parseListItem(items.optJSONObject(i));
                    if (it != null) page.items.add(it);
                }
            }
            page.hasMore = data.optBoolean("has_more", true);
            page.nextOffset = (shown == null ? 0 : shown.size()) + page.items.size();
            return page;
        } catch (Exception e) {
            throw e;
        }
    }

    private static JSONArray arr(List<String> v) {
        JSONArray a = new JSONArray();
        if (v != null) for (String s : v) a.put(s);
        return a;
    }

    private static String joinIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : ids) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    /** category/landpage、cell/change 里的条目结构基本一致。 */
    private static Model.Item parseListItem(JSONObject it) {
        if (it == null) return null;
        String sid = it.optString("series_id", "");
        if (sid.isEmpty()) sid = it.optString("book_id", "");
        if (sid.isEmpty()) return null;
        Model.Item m = new Model.Item();
        m.seriesId = sid;
        m.title = stripTags(it.optString("title", ""));
        m.episodeCnt = it.optInt("episode_cnt", 0);
        m.score = it.optString("score", "");
        m.playCnt = it.optLong("play_cnt", 0);
        m.cover = it.optString("cover", "");
        m.intro = cut(it.optString("video_desc", ""), 50);
        String cat = categories(it.optString("category_schema", ""));
        m.hot = cat;
        return m;
    }

    /** category_schema 里抽出分类名，拼成「玄幻 / 逆袭」。 */
    private static String categories(String schema) {
        if (schema == null || schema.isEmpty()) return "";
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(schema);
        List<String> out = new ArrayList<>();
        while (mt.find()) {
            String n = mt.group(1);
            if (!n.isEmpty() && !out.contains(n)) out.add(n);
        }
        StringBuilder sb = new StringBuilder();
        for (String n : out) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(n);
        }
        return sb.toString();
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").trim();
    }

    private static int optIntAny(JSONObject o, String k) {
        return o == null ? 0 : o.optInt(k, 0);
    }

    private static String optStrAny(JSONObject o, String k) {
        return o == null ? "" : o.optString(k, "");
    }

    // ---------------- 剧集详情 ----------------
    private static JSONObject episodeBody(String seriesId) throws Exception {

        JSONObject biz = new JSONObject();
        biz.put("detail_page_version", 0);
        biz.put("disable_digg_stat", false);
        biz.put("disable_video_relate_book", false);
        biz.put("image_shrink_datas_str",
                "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjkwMCwic2hyaW5rX3R5cGUiOjN9XQ==");
        biz.put("need_all_video_definition", false);
        biz.put("need_mp4_align", false);
        biz.put("screen_width_px", "900");
        biz.put("source", 7);
        biz.put("use_os_player", false);
        biz.put("use_server_dns", false);
        JSONObject b = new JSONObject();
        b.put("biz_param", biz);
        b.put("series_id", seriesId);
        return b;
    }

    public static Model.Detail episodes(String seriesId) throws Exception {
        if (seriesId == null || seriesId.isEmpty()) throw new IOException("series_id 为空");
        Entry<Model.Detail> hit = EP_CACHE.get(seriesId);
        if (hit != null && hit.fresh(TTL_EP)) return hit.v;
        Model.Detail d = episodesNet(seriesId);
        EP_CACHE.put(seriesId, new Entry<>(d));
        return d;
    }

    private static Model.Detail episodesNet(String seriesId) throws Exception {
        JSONObject j = requestEither("POST", "/novel/player/multi_video_detail/v1/",
                episodeBody(seriesId), null, true);
        JSONObject data = j.optJSONObject("data");
        JSONObject one = data == null ? null : data.optJSONObject(seriesId);
        JSONObject vd = one == null ? null : one.optJSONObject("video_data");
        if (vd == null) throw new IOException("剧集详情为空（code=" + j.optInt("code") + "）");

        Model.Detail d = new Model.Detail();
        d.seriesId = seriesId;
        d.title = vd.optString("series_title", seriesId);
        d.intro = vd.optString("series_intro", "");
        d.cover = vd.optString("series_cover", "");
        d.episodeCnt = vd.optInt("episode_cnt", 0);
        d.status = vd.optInt("series_status", 0) == 1 ? "完结" : "连载中";

        JSONArray vl = vd.optJSONArray("video_list");
        if (vl != null) {
            for (int i = 0; i < vl.length(); i++) {
                JSONObject e = vl.optJSONObject(i);
                if (e == null) continue;
                Model.Episode ep = new Model.Episode();
                ep.index = e.optInt("vid_index", i + 1);
                ep.vid = e.optString("vid", "");
                ep.title = cut(e.optString("title", ""), 30);
                ep.duration = e.optInt("duration", 0);
                if (ep.vid.isEmpty()) continue;
                d.eps.add(ep);
            }
        }
        if (d.eps.isEmpty()) throw new IOException("这部剧没有可播放的集");
        return d;
    }

    // ---------------- 播放地址 ----------------
    public static List<Model.Track> tracks(String vid) throws Exception {
        Entry<List<Model.Track>> hit = TRACK_CACHE.get(vid);
        if (hit != null && hit.fresh(TTL_TRACK)) return hit.v;
        List<Model.Track> out = tracksNet(vid);
        if (!out.isEmpty()) TRACK_CACHE.put(vid, new Entry<>(out));
        return out;
    }

    private static List<Model.Track> tracksNet(String vid) throws Exception {
        JSONObject biz = new JSONObject();
        biz.put("detail_page_version", 0);
        biz.put("device_level", 3);
        biz.put("disable_digg_stat", false);
        biz.put("disable_video_relate_book", false);
        biz.put("need_all_video_definition", true);
        biz.put("need_mp4_align", false);
        biz.put("use_os_player", false);
        biz.put("use_server_dns", false);
        biz.put("video_platform", 1024);

        JSONArray arr = new JSONArray();
        arr.put(vid);
        JSONObject map = new JSONObject();
        map.put("1", arr);
        JSONObject body = new JSONObject();
        body.put("biz_param", biz);
        body.put("mixed_video_id_map", map);

        JSONObject j = requestEither("POST", "/novel/player/multi_video_model/v1/", body, null, false);
        JSONObject data = j.optJSONObject("data");
        JSONObject one = data == null ? null : data.optJSONObject(vid);
        String vm = one == null ? null : one.optString("video_model", null);
        if (vm == null || vm.isEmpty()) {
            throw new IOException("该集没有可用直链（code=" + j.optInt("code") + "）");
        }
        JSONObject root = new JSONObject(vm);
        JSONArray list = root.optJSONArray("video_list");
        List<Model.Track> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject t = list.optJSONObject(i);
            if (t == null) continue;
            String url = t.optString("main_url", "");
            if (url.isEmpty()) continue;
            JSONObject meta = t.optJSONObject("video_meta");
            JSONObject enc = t.optJSONObject("encrypt_info");
            Model.Track tr = new Model.Track();
            tr.url = url;
            tr.backup = t.optString("backup_url", "");
            tr.definition = meta == null ? "" : meta.optString("definition", "");
            tr.size = meta == null ? 0 : meta.optLong("size", 0);
            tr.width = meta == null ? 0 : meta.optInt("vwidth", 0);
            tr.height = meta == null ? 0 : meta.optInt("vheight", 0);
            tr.encrypted = enc != null && enc.optBoolean("encrypt", false);
            tr.spadeA = enc == null ? "" : enc.optString("spade_a", "");
            out.add(tr);
        }
        return out;
    }

    // ---------------- 下载 ----------------
    public static void download(String url, java.io.File dst, Progress p) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.connect();
        int total = c.getContentLength();
        try (InputStream in = c.getInputStream();
             OutputStream os = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n, done = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, total);
            }
        } finally {
            c.disconnect();
        }
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    // ---------------- 异步 ----------------
    public static <T> void run(final Callable<T> task, final Cb<T> cb) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T v = task.call();
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.ok(v);
                        }
                    });
                } catch (final Exception e) {
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.err(e);
                        }
                    });
                }
            }
        });
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
