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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

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
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            c.setRequestProperty(e.getKey(), v);
        }
        c.setRequestProperty("Accept-Encoding", "identity");
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

    // ---------------- 榜单 ----------------
    public static List<Model.Item> rank(String board, int limit) throws Exception {
        String sub = RANK_BOARDS.get(board);
        if (sub == null) sub = RANK_BOARDS.get("hot");
        List<Model.Item> out = new ArrayList<>();
        int offset = 0;
        String sess = UUID.randomUUID().toString();
        for (int guard = 0; guard < 8 && out.size() < limit; guard++) {
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

            JSONObject j = request("GET", "/reading/bookapi/bookmall/cell/change/v", null, q, false);
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
            if (cv != null && !cv.optBoolean("has_more", false)) break;
            int next = cv == null ? offset : cv.optInt("next_offset", offset + cells.length());
            if (next == offset) break;
            offset = next;
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
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
        JSONObject j = request("POST", "/novel/player/multi_video_detail/v1/",
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

        JSONObject j = request("POST", "/novel/player/multi_video_model/v1/", body, null, true);
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
