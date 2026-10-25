package io.github.vutronmusic.personal;

import android.content.Context;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Reuses the personal Android edition's volume identities and preferences.
 * Only the settings-change callback targets the Capacitor playback service.
 */
public final class PlaybackVolume {
    private static final String PREFS = "playback_volume";
    private static final int DEFAULT_PERCENT = 100;

    private PlaybackVolume() { }

    public static int trackPercent(Context context, JSONObject song) {
        return read(context, trackKey(song));
    }

    public static int playlistPercent(Context context, JSONObject song) {
        return read(context, playlistKey(song));
    }

    public static int playlistPercent(Context context, String playlistVolumeKey) {
        return read(context, playlistKey(playlistVolumeKey));
    }

    public static void setTrackPercent(Context context, JSONObject song, int percent) {
        write(context, trackKey(song), percent);
    }

    public static void setPlaylistPercent(Context context, JSONObject song, int percent) {
        write(context, playlistKey(song), percent);
    }

    public static void setPlaylistPercent(Context context, String playlistVolumeKey, int percent) {
        write(context, playlistKey(playlistVolumeKey), percent);
    }

    /** A queue row keeps its original identity when it resolves to a local file. */
    public static String trackKey(JSONObject song) {
        if (song == null) return "";
        String savedKey = song.optString("trackVolumeKey", "").trim();
        if (!savedKey.isEmpty()) return savedKey;
        long id = song.optLong("neteaseId", song.optLong("id", 0));
        String uri = song.optString("localUri", "").trim();
        if (uri.isEmpty()) uri = song.optString("localPath", "").trim();
        JSONObject album = song.optJSONObject("al");
        boolean onlineIdentity = song.optLong("neteaseId") > 0 || song.optJSONObject("privilege") != null
                || album != null && album.optLong("id") > 0;
        if (id > 0 && (uri.isEmpty() || onlineIdentity)) return "track:id:" + id;
        return uri.isEmpty() ? "" : "track:local:" + sha256(uri);
    }

    /** An absent playlist identity leaves its volume at 100%. */
    public static String playlistKey(JSONObject song) {
        return song == null ? "" : playlistKey(song.optString("playlistVolumeKey", ""));
    }

    private static String playlistKey(String key) {
        if (key == null || key.trim().isEmpty()) return "";
        return "playlist:key:" + sha256(key.trim());
    }

    private static int read(Context context, String key) {
        if (context == null || key.isEmpty()) return DEFAULT_PERCENT;
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(key, DEFAULT_PERCENT);
    }

    private static void write(Context context, String key, int percent) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        if (key.isEmpty()) throw new IllegalArgumentException("A stable track or playlist key is required");
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Volume must be between 0 and 100");
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(key, percent).apply();
        AndroidPlaybackService.onVolumeSettingsChanged(context.getApplicationContext());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
