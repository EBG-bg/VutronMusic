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
        LibraryTaskRunner.checkCancelled();
        JSONArray filtered = filterExcluded(songs, exclusions(context));
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("folder_index", filtered.toString())
                .putLong("folder_index_updated_at", System.currentTimeMillis()).commit()) throw new IllegalStateException("本地索引保存失败");
    }

    static String exclusionKey(JSONObject track) { return track.optString("localFolderId") + "\n" + track.optString("localUri", track.optString("uri", track.optString("localPath"))); }
    static java.util.Set<String> exclusions(Context context) {
        return new java.util.HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("excluded_tracks", java.util.Collections.emptySet()));
    }
    static JSONArray filterExcluded(JSONArray songs, java.util.Set<String> excluded) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < songs.length(); i++) {
            JSONObject row = songs.optJSONObject(i);
            if (row != null && !excluded.contains(exclusionKey(row))) result.put(row);
        }
        return result;
    }
    static synchronized void exclude(Context context, JSONObject track) {
        java.util.Set<String> excluded = exclusions(context); excluded.add(exclusionKey(track));
        JSONArray folders = filterExcluded(loadFolderIndex(context), excluded);
        JSONArray selected = filterExcluded(load(context), excluded);
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("excluded_tracks", excluded)
                .putString("folder_index", folders.toString()).putString(KEY, selected.toString()).commit()) throw new IllegalStateException("移除记录保存失败");
    }
    static synchronized void restoreExcluded(Context context) {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("excluded_tracks").commit()) throw new IllegalStateException("恢复记录保存失败");
    }
    static synchronized void removeMatching(Context context, java.util.function.Predicate<JSONObject> removed) {
        JSONArray folders = keep(loadFolderIndex(context), removed), selected = keep(load(context), removed);
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("folder_index", folders.toString())
                .putString(KEY, selected.toString()).commit()) throw new IllegalStateException("本地曲目索引更新失败");
    }
    static JSONArray keep(JSONArray rows, java.util.function.Predicate<JSONObject> removed) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && !removed.test(row)) kept.put(row); }
        return kept;
    }
    static synchronized void removeFolder(Context context, String folderId) {
        removeMatching(context, row -> folderId.equals(row.optString("localFolderId")));
    }

    static synchronized boolean hasFolderIndex(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("folder_index");
    }

    static synchronized long folderIndexUpdatedAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("folder_index_updated_at", 0);
    }
}
