package com.thermalglider.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TileLoader — асинхронная загрузка OSM-тайлов.
 * Поток: LruCache → диск → HTTP.
 * Кэш: ThermalGlider/maps/cache/osm/{z}/{x}_{y}.png
 */
public class TileLoader {

    private static final String TAG = "TileLoader";
    private static final String CACHE_DIR = "ThermalGlider/maps/cache/osm/";
    private static final int MAX_CONCURRENT = 4;
    private static final int MEMORY_CACHE_SIZE = 200; // тайлов
    private static final long DISK_CACHE_DAYS = 30;

    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Bitmap> memoryCache;
    private String basePath;

    public interface TileCallback {
        void onTileLoaded(int zoom, int x, int y, Bitmap bitmap);
    }

    public TileLoader() {
        memoryCache = new LinkedHashMap<String, Bitmap>(MEMORY_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                return size() > MEMORY_CACHE_SIZE;
            }
        };
    }

    public void init(String basePath) {
        this.basePath = basePath;
        new File(basePath + "/" + CACHE_DIR).mkdirs();
    }

    /** Запрос тайла */
    public void requestTile(int zoom, int x, int y, TileCallback callback) {
        String key = zoom + "/" + x + "/" + y;

        // 1. Memory cache
        synchronized (memoryCache) {
            Bitmap cached = memoryCache.get(key);
            if (cached != null && !cached.isRecycled()) {
                mainHandler.post(() -> callback.onTileLoaded(zoom, x, y, cached));
                return;
            }
        }

        executor.execute(() -> loadTile(zoom, x, y, key, callback));
    }

    private void loadTile(int zoom, int x, int y, String key, TileCallback callback) {
        // 2. Disk cache
        String localPath = basePath + "/" + CACHE_DIR + zoom + "/" + x + "_" + y + ".png";
        File localFile = new File(localPath);

        if (localFile.exists()) {
            // Check age
            if (System.currentTimeMillis() - localFile.lastModified() > DISK_CACHE_DAYS * 86400000L) {
                localFile.delete();
            } else {
                Bitmap bmp = BitmapFactory.decodeFile(localPath);
                if (bmp != null) {
                    cacheAndDeliver(key, zoom, x, y, bmp, callback);
                    return;
                }
            }
        }

        // 3. HTTP download
        try {
            String urlStr = "https://tile.openstreetmap.org/" + zoom + "/" + x + "/" + y + ".png";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "ThermalGlider/1.0");

            InputStream is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();

            if (bmp != null) {
                // Save to disk
                new File(localFile.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(localFile);
                bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
                fos.close();

                cacheAndDeliver(key, zoom, x, y, bmp, callback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load tile " + key + ": " + e.getMessage());
        }
    }

    private void cacheAndDeliver(String key, int zoom, int x, int y,
                                  Bitmap bmp, TileCallback callback) {
        synchronized (memoryCache) {
            memoryCache.put(key, bmp);
        }
        mainHandler.post(() -> callback.onTileLoaded(zoom, x, y, bmp));
    }

    /** Очистка старого кэша на диске */
    public void cleanDiskCache() {
        executor.execute(() -> {
            File cacheDir = new File(basePath + "/" + CACHE_DIR);
            if (!cacheDir.exists()) return;
            long cutoff = System.currentTimeMillis() - DISK_CACHE_DAYS * 86400000L;
            deleteOldFiles(cacheDir, cutoff);
        });
    }

    private void deleteOldFiles(File dir, long cutoff) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                deleteOldFiles(f, cutoff);
            } else if (f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
