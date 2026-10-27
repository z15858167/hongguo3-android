package com.hongguo.client;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 收藏与观看历史，存在 SharedPreferences 里（等价于网页版的 hg2_favs / hg2_hist）。 */
public final class Store {

    private static final String PREF = "hongguo";
    private static final String K_FAV = "favs";
    private static final String K_HIST = "hist";
    private static final int MAX_HIST = 200;

    public static class Rec {
        public String sid = "";
        public String title = "";
        public String cover = "";
        public int ep = 1;
        public int cnt;
        public int t;
        public long ts;

        public String sub() {
            StringBuilder sb = new StringBuilder();
            sb.append("第 ").append(ep).append(" 集");
            if (cnt > 0) sb.append(" / 共 ").append(cnt).append(" 集");
            return sb.toString();
        }

        public String time() {
            if (ts <= 0) return "";
            long d = System.currentTimeMillis() - ts;
            if (d < 60_000) return "刚刚";
            if (d < 3_600_000) return (d / 60_000) + " 分钟前";
            if (d < 86_400_000L) return (d / 3_600_000) + " 小时前";
            return (d / 86_400_000L) + " 天前";
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static List<Rec> load(Context c, String key) {
        List<Rec> out = new ArrayList<>();
        try {
            String s = sp(c).getString(key, "");
            if (s.isEmpty()) return out;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Rec r = new Rec();
                r.sid = o.optString("sid", "");
                r.title = o.optString("title", "");
                r.cover = o.optString("cover", "");
                r.ep = o.optInt("ep", 1);
                r.cnt = o.optInt("cnt", 0);
                r.t = o.optInt("t", 0);
                r.ts = o.optLong("ts", 0);
                if (!r.sid.isEmpty()) out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, String key, List<Rec> list) {
        JSONArray a = new JSONArray();
        for (Rec r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("sid", r.sid);
                o.put("title", r.title);
                o.put("cover", r.cover);
                o.put("ep", r.ep);
                o.put("cnt", r.cnt);
                o.put("t", r.t);
                o.put("ts", r.ts);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        sp(c).edit().putString(key, a.toString()).apply();
    }

    public static List<Rec> favs(Context c) {
        return load(c, K_FAV);
    }

    public static boolean isFav(Context c, String sid) {
        for (Rec r : favs(c)) if (r.sid.equals(sid)) return true;
        return false;
    }

    public static boolean toggleFav(Context c, String sid, String title, String cover) {
        List<Rec> list = favs(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).sid.equals(sid)) {
                list.remove(i);
                save(c, K_FAV, list);
                return false;
            }
        }
        Rec r = new Rec();
        r.sid = sid;
        r.title = title;
        r.cover = cover;
        r.ts = System.currentTimeMillis();
        list.add(0, r);
        save(c, K_FAV, list);
        return true;
    }

    public static List<Rec> hists(Context c) {
        return load(c, K_HIST);
    }

    public static void recordHist(Context c, String sid, String title, String cover,
                                  int ep, int cnt, int t) {
        List<Rec> list = hists(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).sid.equals(sid)) {
                list.remove(i);
                break;
            }
        }
        Rec r = new Rec();
        r.sid = sid;
        r.title = title;
        r.cover = cover;
        r.ep = ep;
        r.cnt = cnt;
        r.t = t;
        r.ts = System.currentTimeMillis();
        list.add(0, r);
        while (list.size() > MAX_HIST) list.remove(list.size() - 1);
        save(c, K_HIST, list);
    }

    public static void removeHist(Context c, String sid) {
        List<Rec> list = hists(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).sid.equals(sid)) {
                list.remove(i);
                break;
            }
        }
        save(c, K_HIST, list);
    }

    public static void clearHist(Context c) {
        save(c, K_HIST, new ArrayList<Rec>());
    }

    public static void clearFav(Context c) {
        save(c, K_FAV, new ArrayList<Rec>());
    }

    /** 某部剧上次看到第几集、第几秒。 */
    public static int[] progress(Context c, String sid) {
        for (Rec r : hists(c)) {
            if (r.sid.equals(sid)) return new int[]{r.ep, r.t};
        }
        return new int[]{1, 0};
    }

    private Store() {
    }
}
