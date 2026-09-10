package com.reality.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CacheManager {

    private static final String PREF_NAME = "RealityAICachePrefs";
    private static final String KEY_CACHE_VERSION = "cache_version";
    private static CacheManager instance;

    private final Context context;
    private final File cacheDir;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static synchronized CacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new CacheManager(context.getApplicationContext());
        }
        return instance;
    }

    private CacheManager(Context context) {
        this.context = context;
        this.cacheDir = new File(context.getCacheDir(), "web_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    public void syncCacheVersion(String remoteVersion) {
        if (remoteVersion == null || remoteVersion.trim().isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String localVersion = prefs.getString(KEY_CACHE_VERSION, "1.0.0");
        if (!localVersion.equals(remoteVersion)) {
            clearCache();
            prefs.edit().putString(KEY_CACHE_VERSION, remoteVersion).apply();
        }
    }

    public void clearCache() {
        executor.execute(() -> {
            try {
                if (cacheDir.exists()) {
                    File[] files = cacheDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            f.delete();
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public WebResourceResponse intercept(Uri uri) {
        if (uri == null) return null;
        String urlStr = uri.toString();
        String path = uri.getPath();
        if (path == null) return null;
        path = path.toLowerCase();

        // 仅拦截静态库与重型资产文件
        if (path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".woff2") ||
            path.endsWith(".woff") || path.endsWith(".ttf") || path.endsWith(".svg") ||
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".webp") || urlStr.contains("cdn.beacons.ai") || urlStr.contains("user_content")) {

            String mimeType = getMimeType(path);
            String cacheKeyFull = md5(urlStr);
            String cleanUrl = urlStr.split("\\?")[0];
            String cacheKeyClean = md5(cleanUrl);

            // 👑 第一优先级：直接从 APK 本地内置的 assets/static_preload/ 中 0ms 瞬间秒开！
            try {
                InputStream assetStream = null;
                try {
                    assetStream = context.getAssets().open("static_preload/" + cacheKeyFull);
                } catch (Exception e1) {
                    try {
                        assetStream = context.getAssets().open("static_preload/" + cacheKeyClean);
                    } catch (Exception ignored) {}
                }

                if (assetStream != null) {
                    return new WebResourceResponse(mimeType, "UTF-8", assetStream);
                }
            } catch (Exception ignored) {}

            // 👑 第二优先级：从手机沙盒动态持久化磁盘缓存读取
            File cachedFile = new File(cacheDir, cacheKeyFull);
            if (!cachedFile.exists() || cachedFile.length() == 0) {
                cachedFile = new File(cacheDir, cacheKeyClean);
            }

            if (cachedFile.exists() && cachedFile.length() > 0) {
                try {
                    return new WebResourceResponse(mimeType, "UTF-8", new FileInputStream(cachedFile));
                } catch (Exception ignored) {}
            }

            // 👑 第三优先级：本地沙盒未命中时，异步拉取并写入磁盘，供后续毫秒级直接复用
            asyncWarmUp(urlStr, new File(cacheDir, cacheKeyFull));
        }
        return null;
    }

    private void asyncWarmUp(String urlStr, File targetFile) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("User-Agent", "RealityAI-WarmCache/1.0");

                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream();
                         FileOutputStream fos = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            } catch (Exception ignored) {
                if (targetFile.exists()) targetFile.delete();
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private String getMimeType(String path) {
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private String md5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte[] messageDigest = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String h = Integer.toHexString(0xFF & b);
                while (h.length() < 2) h = "0" + h;
                hexString.append(h);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
