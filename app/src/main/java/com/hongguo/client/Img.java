package com.hongguo.client;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简图片加载：内存 LRU + 线程池，避免为几张封面引入 Glide。 */
public final class Img {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static void load(final ImageView iv, final String url) {
        if (url == null || url.isEmpty()) return;
        final String key = url;
        iv.setTag(key);
        Bitmap b = CACHE.get(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = fetch(url);
                if (bm == null) return;
                CACHE.put(key, bm);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(iv.getTag())) iv.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept-Encoding", "identity");
            InputStream in = c.getInputStream();
            Bitmap bm = BitmapFactory.decodeStream(in);
            in.close();
            return bm;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private Img() {
    }
}
