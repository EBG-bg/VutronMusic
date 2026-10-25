package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class LyricOverlaySettings {
    private static final String FILE = "lyric_overlay";
    private static final String ENABLED = "enabled";
    private static final String LOCKED = "locked";
    static final int DEFAULT_PLAYED_COLOR = 0xff37cf88;
    static final int DEFAULT_UNPLAYED_COLOR = 0xffd2d2d2;
    static final int DEFAULT_BACKGROUND_COLOR = 0xff181920;
    static final int DEFAULT_SHADOW_COLOR = 0x99000000;
    static final int DEFAULT_SHADOW_RADIUS = 2;

    private LyricOverlaySettings() { }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) { return preferences(context).getBoolean(ENABLED, false); }
    static boolean locked(Context context) { return preferences(context).getBoolean(LOCKED, false); }
    static void enabled(Context context, boolean value) { preferences(context).edit().putBoolean(ENABLED, value).apply(); }
    static void locked(Context context, boolean value) { preferences(context).edit().putBoolean(LOCKED, value).apply(); }
    /** Missing preferences adopt click-through; an explicitly saved opacity choice is preserved. */
    static boolean touchPassthroughPriority(Context context) { return preferences(context).getBoolean("touch_passthrough_priority", true); }
    static void touchPassthroughPriority(Context context, boolean value) {
        preferences(context).edit().putBoolean("touch_passthrough_priority", value).apply();
    }

    static float windowOpacity(boolean locked, boolean prioritizeTouches, float maximumOpacity) {
        if (!locked || !prioritizeTouches) return 1f;
        if (!Float.isFinite(maximumOpacity) || maximumOpacity < 0f || maximumOpacity > 1f) return 0f;
        // Keep the window below Android's per-UID obscuring limit without altering any saved ARGB.
        return maximumOpacity * .95f;
    }
    static int fontSize(Context context) { return preferences(context).getInt("font_size", 22); }
    static void fontSize(Context context, int value) { preferences(context).edit().putInt("font_size", Math.max(16, Math.min(34, value))).apply(); }
    static boolean translation(Context context) { return preferences(context).getBoolean("translation", true); }
    static boolean wifiValidation(Context context) { return preferences(context).getBoolean("wifi_validation", true); }
    static void wifiValidation(Context context, boolean value) { preferences(context).edit().putBoolean("wifi_validation", value).apply(); }
    static void translation(Context context, boolean value) { preferences(context).edit().putBoolean("translation", value).apply(); }
    static boolean nextLine(Context context) { return preferences(context).getBoolean("next_line", true); }
    static void nextLine(Context context, boolean value) { preferences(context).edit().putBoolean("next_line", value).apply(); }
    static boolean estimatedWordTiming(Context context) { return preferences(context).getBoolean("estimated_word_timing", false); }
    static void estimatedWordTiming(Context context, boolean value) { preferences(context).edit().putBoolean("estimated_word_timing", value).apply(); }
    static int playedColor(Context context) { return preferences(context).getInt("played_color", DEFAULT_PLAYED_COLOR); }
    static void playedColor(Context context, int value) { preferences(context).edit().putInt("played_color", value).apply(); }
    static int unplayedColor(Context context) { return preferences(context).getInt("unplayed_color", DEFAULT_UNPLAYED_COLOR); }
    static void unplayedColor(Context context, int value) { preferences(context).edit().putInt("unplayed_color", value).apply(); }
    static int backgroundColor(Context context) { return preferences(context).getInt("background_color", DEFAULT_BACKGROUND_COLOR); }
    static void backgroundColor(Context context, int value) { preferences(context).edit().putInt("background_color", value).apply(); }
    static boolean shadowEnabled(Context context) { return preferences(context).getBoolean("shadow_enabled", true); }
    static void shadowEnabled(Context context, boolean value) { preferences(context).edit().putBoolean("shadow_enabled", value).apply(); }
    static int shadowColor(Context context) { return preferences(context).getInt("shadow_color", DEFAULT_SHADOW_COLOR); }
    static void shadowColor(Context context, int value) { preferences(context).edit().putInt("shadow_color", value).apply(); }
    /** Shadow blur radius in density-independent pixels; zero disables the shadow. */
    static int shadowRadius(Context context) { return Math.max(0, Math.min(8, preferences(context).getInt("shadow_radius", DEFAULT_SHADOW_RADIUS))); }
    static void shadowRadius(Context context, int value) { preferences(context).edit().putInt("shadow_radius", Math.max(0, Math.min(8, value))).apply(); }
    static void resetColors(Context context) {
        preferences(context).edit().remove("played_color").remove("unplayed_color").remove("background_color").apply();
    }

    /** Display delay in milliseconds; a positive value shows lyrics later. */
    static long lyricOffsetMs(Context context, JSONObject song) {
        String key = lyricOffsetPreferenceKey(song);
        return key.isEmpty() ? 0 : preferences(context).getLong(key, 0);
    }

    /** Persists a per-track adjustment, clamped to a practical +/-30 second range. */
    static void lyricOffsetMs(Context context, JSONObject song, long value) {
        String key = lyricOffsetPreferenceKey(song);
        if (key.isEmpty()) return;
        preferences(context).edit().putLong(key, Math.max(-30_000, Math.min(30_000, value))).apply();
    }

    private static String lyricOffsetPreferenceKey(JSONObject song) {
        String identity = trackIdentity(song);
        if (identity.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return "lyric_offset_ms_" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Android", impossible);
        }
    }

    /** Local replacement recordings can have different lead-in silence from the online track. */
    static String trackIdentity(JSONObject song) {
        if (song == null) return "";
        String localUri = song.optString("localUri", "").trim();
        if (!localUri.isEmpty()) return "local-uri:" + localUri;
        String localPath = song.optString("localPath", "").trim();
        if (!localPath.isEmpty()) return "local-path:" + localPath;
        long remoteId = remoteLyricId(song);
        return remoteId > 0 ? "netease:" + remoteId : "";
    }

    /** Generated local IDs must not be sent to the online lyric endpoint. */
    static long remoteLyricId(JSONObject song) {
        if (song == null) return 0;
        long remoteId = song.optLong("neteaseId", 0);
        if (remoteId > 0) return remoteId;
        // Offline audio keeps the original online ID; its private file URI is
        // a cache location, not a generated local-recording identity.
        if (song.optBoolean("cached") || song.optBoolean("playbackCached")
                || "cache".equals(song.optString("source"))) return Math.max(0, song.optLong("id", 0));
        boolean local = !song.optString("localUri", "").trim().isEmpty()
                || !song.optString("localPath", "").trim().isEmpty();
        JSONObject album = song.optJSONObject("al");
        boolean hasRemoteIdentity = song.optJSONObject("privilege") != null
                || (album != null && album.optLong("id", 0) > 0);
        return !local || hasRemoteIdentity ? Math.max(0, song.optLong("id", 0)) : 0;
    }
}
