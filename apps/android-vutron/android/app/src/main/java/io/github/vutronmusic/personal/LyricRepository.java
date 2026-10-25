package io.github.vutronmusic.personal;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Private, durable lyric text cache. Audio URLs and account data are never persisted here. */
final class LyricRepository {
    private static final String TAG = "VutronLyrics";
    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private static final int MAX_CACHE_BYTES = 512 * 1024;
    private static final Object CACHE_LOCK = new Object();
    private static final int CACHE_REVISION = 3;
    private static final long VALIDATION_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final Set<Long> VALIDATING = new HashSet<>();
    private static final Map<Long, Long> LAST_ATTEMPT = new HashMap<>();

    static final class Lyrics {
        final LyricTimeline original;
        final LyricTimeline translation;
        final List<String> pairedTranslations;
        final boolean local;

        Lyrics(JSONObject texts) {
            this(texts, false);
        }

        Lyrics(JSONObject texts, boolean local) {
            this.local = local;
            LyricTimeline words = LyricTimeline.parseWords(texts.optString("yrc"));
            LyricTimeline lrc = LyricTimeline.parse(texts.optString("lrc"));
            LyricTimeline wordTranslation = LyricTimeline.parse(texts.optString("ytlrc"));
            LyricTimeline plainTranslation = LyricTimeline.parse(texts.optString("tlyric"));
            // Like the desktop parser: yrc goes with ytlrc; lrc goes with tlyric.
            // Old caches without ytlrc use the coherent plain pair until Wi-Fi refresh.
            boolean useWords = words.hasComparableCoverage(lrc) && (!wordTranslation.lines.isEmpty()
                    || lrc.lines.isEmpty() || plainTranslation.lines.isEmpty());
            original = useWords ? words : lrc;
            translation = useWords ? wordTranslation : plainTranslation;
            pairedTranslations = original.pairTranslations(translation);
        }

        String translationAt(int index) {
            return index >= 0 && index < pairedTranslations.size() ? pairedTranslations.get(index) : "";
        }
    }

    private LyricRepository() { }

    /** Must run on a worker thread. A completed cache entry is usable without a network. */
    static Lyrics load(Context context, long id) throws IOException {
        if (id <= 0) throw new IOException("Invalid lyric song ID");
        JSONObject cached = readCache(context, id);
        if (cached != null) return new Lyrics(cached);
        if (LocalOnlyMode.enabled(context)) return new Lyrics(new JSONObject());
        return fetch(context, id, null);
    }

    static Lyrics loadForTrack(Context context, JSONObject song) throws IOException {
        Lyrics local = LocalLyricSource.load(context, song);
        if (local != null) return local;
        long id = LyricOverlaySettings.remoteLyricId(song);
        return id > 0 ? load(context, id) : new Lyrics(new JSONObject());
    }

    static boolean hasValidationWifi(Context context) {
        if (LocalOnlyMode.enabled(context)) return false;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities caps = manager.getNetworkCapabilities(active);
        // Check the route actually used by this app, including VPN-inherited Wi-Fi transport.
        // A separate connected Wi-Fi must not authorize validation over a cellular default.
        return eligibleWifi(caps);
    }

    private static boolean eligibleWifi(NetworkCapabilities caps) {
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    static boolean validationDue(int revision, long checkedAt, long now) {
        return revision != CACHE_REVISION || checkedAt <= 0 || checkedAt > now
                || now - checkedAt >= VALIDATION_AGE_MS;
    }

    /** Worker-only. Cached lyrics remain available when validation fails or Wi-Fi is absent. */
    static Lyrics revalidateOnWifi(Context context, long id) throws IOException {
        if (LocalOnlyMode.enabled(context)) return null;
        if (id <= 0 || !LyricOverlaySettings.wifiValidation(context) || !hasValidationWifi(context)) return null;
        JSONObject cached = readCache(context, id);
        if (cached != null && !validationDue(cached.optInt("_revision"),
                cached.optLong("_checkedAt"), System.currentTimeMillis())) return null;
        synchronized (CACHE_LOCK) {
            Long previous = LAST_ATTEMPT.get(id);
            long now = SystemClock.elapsedRealtime();
            if (VALIDATING.contains(id) || previous != null && now - previous < 60_000) return null;
            if (LAST_ATTEMPT.size() >= 256) LAST_ATTEMPT.clear();
            LAST_ATTEMPT.put(id, now);
            VALIDATING.add(id);
        }
        try {
            if (!hasValidationWifi(context)) return null;
            return fetch(context, id, cached);
        } finally {
            synchronized (CACHE_LOCK) { VALIDATING.remove(id); }
        }
    }

    private static JSONObject readCache(Context context, long id) {
        File root = new File(context.getFilesDir(), "lyric-cache");
        AtomicFile cache = new AtomicFile(new File(root, id + ".json"));
        if (cache.getBaseFile().isFile() || new File(cache.getBaseFile() + ".bak").isFile()) {
            synchronized (CACHE_LOCK) {
                try (FileInputStream in = cache.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (bytes.size() + count > MAX_CACHE_BYTES) throw new IOException("Lyric cache exceeds its size limit");
                        bytes.write(buffer, 0, count);
                    }
                    JSONObject texts = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                    if (texts.has("_songId") && texts.optLong("_songId") != id) throw new IOException("Lyric cache song ID mismatch");
                    return texts;
                } catch (Exception invalid) {
                    Log.w(TAG, "Unreadable lyric cache; requesting lyric text again");
                }
            }
        }
        return null;
    }

    private static Lyrics fetch(Context context, long id, JSONObject previous) throws IOException {
        LocalOnlyMode.requireOnline(context);
        JSONObject response = new NeteaseApi(SessionStore.load(context),
                () -> !LocalOnlyMode.enabled(context)).lyricData(id);
        JSONObject texts = new JSONObject();
        try {
            int size = 0;
            // Keep the broadly supported original and translation before optional word timing.
            for (String key : new String[]{"lrc", "tlyric", "yrc", "ytlrc"}) {
                JSONObject part = response.optJSONObject(key);
                String text = part == null ? "" : part.optString("lyric");
                int bytes = text.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_TEXT_BYTES || size + bytes > MAX_CACHE_BYTES - 128) {
                    Log.w(TAG, "Skipping an oversized lyric text field: " + key);
                    continue;
                }
                texts.put(key, text);
                size += bytes;
            }
            texts.put("_revision", CACHE_REVISION);
            texts.put("_checkedAt", System.currentTimeMillis());
            texts.put("_songId", id);
        } catch (org.json.JSONException impossible) {
            throw new IOException("Cannot prepare lyric text", impossible);
        }
        Lyrics result = new Lyrics(texts);
        if (previous != null) {
            Lyrics saved = new Lyrics(previous);
            if (!saved.original.lines.isEmpty() && result.original.lines.isEmpty())
                throw new IOException("Refreshed lyrics are incomplete; keeping the offline copy");
            if (hasTranslation(saved) && !hasTranslation(result))
                throw new IOException("Refreshed translation is incomplete; keeping the offline copy");
        }
        byte[] raw = texts.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_CACHE_BYTES) return result;
        File root = new File(context.getFilesDir(), "lyric-cache");
        AtomicFile cache = new AtomicFile(new File(root, id + ".json"));
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            Log.w(TAG, "Cannot create lyric cache directory");
            return result;
        }
        synchronized (CACHE_LOCK) {
            FileOutputStream out = null;
            try {
                out = cache.startWrite();
                out.write(raw);
                cache.finishWrite(out);
            } catch (IOException unavailable) {
                if (out != null) cache.failWrite(out);
                Log.w(TAG, "Lyrics are usable but could not be saved for offline use", unavailable);
            }
        }
        return result;
    }

    private static boolean hasTranslation(Lyrics lyrics) {
        for (String text : lyrics.pairedTranslations) if (!text.isEmpty()) return true;
        return false;
    }
}
