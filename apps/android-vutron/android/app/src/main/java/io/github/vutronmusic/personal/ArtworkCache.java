package io.github.vutronmusic.personal;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, durable artwork cache used by playlists, tracks and the player metadata. */
final class ArtworkCache {
    private static final String TAG = "VutronArtworkCache";
    private static final String DIRECTORY = "artwork-cache-v1";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FILES = 4096;
    private static final String PREFS = "artwork_cache_settings";
    private static final long DEFAULT_CAPACITY_MB = 128;
    private static final Map<String, List<Result>> PENDING = new HashMap<>();
    private static final Object PENDING_LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "vutron-artwork-cache");
        thread.setDaemon(true);
        return thread;
    });

    private ArtworkCache() { }

    interface Result {
        void success(String uri);
        void failure(Exception error);
    }

    static void enqueue(Context context, String remoteUrl) {
        request(context, remoteUrl, null);
    }

    /** Resolve on the artwork pool, sharing any in-flight prefetch for this URL. */
    static void fetch(Context context, String remoteUrl, Result result) {
        request(context, remoteUrl, result);
    }

    private static void request(Context context, String remoteUrl, Result result) {
        String url = normalized(remoteUrl);
        if (url.isEmpty()) {
            if (result != null) result.failure(new IOException("Artwork URL must use HTTP or HTTPS"));
            return;
        }
        // Earlier snapshots used the original URL as their cache key. Reuse
        // those images when the HTTPS/512px normalization changes that key.
        File cached = cachedFile(context, remoteUrl, url);
        if (cached != null) {
            if (result != null) result.success("file://" + cached.getAbsolutePath());
            return;
        }
        synchronized (PENDING_LOCK) {
            List<Result> listeners = PENDING.get(url);
            if (listeners != null) {
                if (result != null) listeners.add(result);
                return;
            }
            listeners = new ArrayList<>();
            if (result != null) listeners.add(result);
            PENDING.put(url, listeners);
        }
        WORKER.execute(() -> {
            String uri = null;
            Exception failure = null;
            try { uri = download(context.getApplicationContext(), url); }
            catch (Exception error) { failure = error; }
            List<Result> listeners;
            synchronized (PENDING_LOCK) {
                listeners = PENDING.remove(url);
                PENDING_LOCK.notifyAll();
            }
            if (failure != null) Log.d(TAG, "Artwork cache request failed: " + failure.getMessage());
            for (Result listener : listeners) {
                if (failure == null) listener.success(uri);
                else listener.failure(failure);
            }
        });
    }

    /** Wait for queued cover downloads during an explicit bulk-cache action. */
    static int awaitPending(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (PENDING_LOCK) {
            while (!PENDING.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                PENDING_LOCK.wait(remaining);
            }
        }
        synchronized (PENDING_LOCK) { return PENDING.size(); }
    }

    static String resolve(Context context, String remoteUrl) {
        String url = normalized(remoteUrl);
        if (url.isEmpty()) return "";
        File file = cachedFile(context, remoteUrl, url);
        // Return the same HTTPS/512px URL used by the native downloader when
        // the first request has not finished yet.  The old raw HTTP CDN URL
        // frequently redirects to a URL that WebView cannot display, which
        // left every first-render playlist card with a broken-image icon.
        return file != null ? "file://" + file.getAbsolutePath() : url;
    }

    static long bytes(Context context) {
        File root = root(context);
        File[] files = root.listFiles(file -> file.isFile() && !file.getName().endsWith(".part"));
        long total = 0;
        if (files != null) for (File file : files) total += Math.max(0, file.length());
        return total;
    }

    static long capacityMiB(Context context) {
        return preferences(context).getLong("capacity_mib", DEFAULT_CAPACITY_MB);
    }

    static void setCapacityMiB(Context context, long value) throws IOException {
        if (value < 32 || value > 1024) throw new IllegalArgumentException("Artwork cache capacity must be between 32 and 1024 MiB");
        preferences(context).edit().putLong("capacity_mib", value).apply();
        trim(root(context), value * 1024L * 1024L);
    }

    static void clear(Context context) {
        File root = root(context);
        File[] files = root.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
    }

    private static String download(Context context, String urlText) throws IOException {
        File root = root(context);
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IOException("Artwork directory unavailable");
        File target = fileFor(context, urlText);
        if (target.isFile() && target.length() > 0) return "file://" + target.getAbsolutePath();
        URL url = new URL(urlText);
        if (url.getUserInfo() != null) throw new IOException("Artwork URL must not contain credentials");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36");
        connection.setRequestProperty("Referer", "https://music.163.com/");
        File part = new File(target.getAbsolutePath() + ".part");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Artwork HTTP " + status);
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_BYTES) throw new IOException("Artwork exceeds 4 MiB (" + contentLength + " bytes)");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("Artwork too large");
                    output.write(buffer, 0, count);
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Artwork response is not a decodable image");
            if (!part.renameTo(target)) throw new IOException("Artwork commit failed");
            trim(root, capacityMiB(context) * 1024L * 1024L);
            return "file://" + target.getAbsolutePath();
        } finally {
            connection.disconnect();
            if (part.isFile()) part.delete();
        }
    }

    private static void trim(File root, long maxBytes) {
        File[] files = root.listFiles(file -> file.isFile() && !file.getName().endsWith(".part"));
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File file : files) total += Math.max(0, file.length());
        int count = files.length;
        for (File file : files) {
            if (total <= maxBytes && count <= MAX_FILES) break;
            long length = Math.max(0, file.length());
            if (file.delete()) { total -= length; count--; }
        }
    }

    private static File root(Context context) { return new File(context.getFilesDir(), DIRECTORY); }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File fileFor(Context context, String url) {
        return new File(root(context), sha256(url) + ".img");
    }

    private static File cachedFile(Context context, String source, String url) {
        File target = fileFor(context, url);
        if (target.isFile() && target.length() > 0) return target;
        String original = source == null ? "" : source.trim();
        if (!original.equals(url)) {
            File legacy = fileFor(context, original);
            if (legacy.isFile() && legacy.length() > 0) return legacy;
        }
        return null;
    }

    private static String normalized(String value) {
        return ArtworkSource.requestUrl(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
