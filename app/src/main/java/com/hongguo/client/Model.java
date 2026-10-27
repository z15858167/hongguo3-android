package com.hongguo.client;

import java.util.ArrayList;
import java.util.List;

/** 数据模型。字段名尽量与上游 JSON 对齐，减少解析时的映射。 */
public final class Model {

    public static class Item {
        public String seriesId = "";
        public String title = "";
        public String cover = "";
        public String hot = "";
        public String intro = "";
        public String score = "";
        public int episodeCnt;
        public long playCnt;

        public String sub() {
            if (episodeCnt > 0) return "全 " + episodeCnt + " 集";
            return score.isEmpty() ? "" : "评分 " + score;
        }
    }

    public static class Episode {
        public int index;
        public String vid = "";
        public String title = "";
        public int duration;
    }

    public static class Detail {
        public String seriesId = "";
        public String title = "";
        public String intro = "";
        public String cover = "";
        public String status = "";
        public int episodeCnt;
        public final List<Episode> eps = new ArrayList<>();

        public Episode ep(int idx) {
            for (Episode e : eps) if (e.index == idx) return e;
            return null;
        }
    }

    public static class Track {
        public String url = "";
        public String backup = "";
        public String definition = "";
        public String spadeA = "";
        public long size;
        public int width;
        public int height;
        public boolean encrypted;

        public String label() {
            return definition.isEmpty() ? "默认" : definition.toUpperCase();
        }

        public int num() {
            StringBuilder d = new StringBuilder();
            for (char c : definition.toCharArray()) if (Character.isDigit(c)) d.append(c);
            try {
                return d.length() == 0 ? 0 : Integer.parseInt(d.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private Model() {
    }
}
