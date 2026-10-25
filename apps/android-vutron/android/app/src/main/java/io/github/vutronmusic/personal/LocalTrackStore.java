package io.github.vutronmusic.personal;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Persists the audio document URIs selected through Android's Storage Access Framework. */
final class LocalTrackStore {
    private static final String PREFS = "local_tracks";
    private static final String KEY = "tracks";

    private LocalTrackStore() { }

    static synchronized JSONArray load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    static synchronized void save(Context context, JSONArray tracks) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, tracks == null ? "[]" : tracks.toString()).apply();
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    static synchronized JSONArray loadFolderIndex(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("folder_index", "[]");
        try { return new JSONArray(raw); }
        catch (JSONException e) { return new JSONArray(); }
    }

    static synchronized void saveFolderIndex(Context context, JSONArray songs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("folder_index", songs.toString()).apply();
    }
}
