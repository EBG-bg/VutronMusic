package io.github.vutronmusic.personal;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Offline snapshot of playlist rows and their complete ordered track lists. */
final class PlaylistCache {
    private static final String TAG = "VutronPlaylistCache";
    private static final String DIRECTORY = "playlist-cache-v1";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PLAYLIST_BYTES = 64 * 1024 * 1024;
    private static final Object LOCK = new Object();

    private PlaylistCache() { }

    static void savePlaylists(Context context, long userId, List<JSONObject> values) throws IOException {
        if (userId <= 0) return;
        synchronized (LOCK) {
            JSONObject document = readDocument(context, userId);
            JSONArray rows = new JSONArray();
            if (values != null) for (JSONObject value : values) rows.put(copy(value));
            try { document.put("playlists", rows).put("updatedAt", System.currentTimeMillis()); }
            catch (JSONException invalid) { throw new IOException("Cannot encode playlist snapshot", invalid); }
            writeDocument(context, userId, document);
        }
        if (values != null) for (JSONObject value : values) ArtworkCache.enqueue(context, value.optString("coverImgUrl", value.optString("picUrl", "")));
    }

    static void saveRecommendedPlaylists(Context context, long userId, List<JSONObject> values) throws IOException {
        if (userId <= 0) return;
        JSONArray rows = new JSONArray();
        if (values != null) for (JSONObject value : values) rows.put(copy(value));
        JSONObject document;
        try { document = new JSONObject().put("version", 1).put("userId", userId)
                .put("updatedAt", System.currentTimeMillis()).put("playlists", rows); }
        catch (JSONException invalid) { throw new IOException("Cannot encode home recommendation snapshot", invalid); }
        writeAtomic(recommendationFile(context, userId), document, MAX_BYTES);
    }

    static List<JSONObject> readRecommendedPlaylists(Context context, long userId) {
        List<JSONObject> result = new ArrayList<>();
        if (userId <= 0) return result;
        try {
            JSONObject document = readAtomic(recommendationFile(context, userId), MAX_BYTES);
            JSONArray rows = document == null ? null : document.optJSONArray("playlists");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) result.add(decorate(context, row));
            }
        } catch (Exception error) { Log.w(TAG, "Home recommendation cache unavailable", error); }
        return result;
    }

    static void saveTracks(Context context, long userId, long playlistId, List<JSONObject> values) throws IOException {
        saveTracks(context, userId, playlistId, values, null);
    }

    static void saveTracks(Context context, long userId, long playlistId, List<JSONObject> values, JSONObject suppliedPlaylist) throws IOException {
        if (userId <= 0 || playlistId <= 0) return;
        JSONArray rows = new JSONArray();
        if (values != null) for (JSONObject value : values) rows.put(copy(value));
        JSONObject snapshot;
        try { snapshot = new JSONObject().put("version", 1).put("userId", userId).put("playlistId", playlistId)
                .put("updatedAt", System.currentTimeMillis()).put("tracks", rows); }
        catch (JSONException invalid) { throw new IOException("Cannot encode playlist track snapshot", invalid); }
        writeAtomic(trackFile(context, userId, playlistId), snapshot, MAX_PLAYLIST_BYTES);
        synchronized (LOCK) {
            JSONObject document = readDocument(context, userId);
            JSONObject recommendations = readAtomic(recommendationFile(context, userId), MAX_BYTES);
            JSONObject known = findPlaylist(document.optJSONArray("playlists"), playlistId);
            JSONObject recommended = findPlaylist(recommendations == null ? null : recommendations.optJSONArray("playlists"), playlistId);
            JSONObject visited = findPlaylist(document.optJSONArray("visitedPlaylists"), playlistId);
            JSONObject metadata = playlistMetadata(playlistId, rows.length(), known, recommended, suppliedPlaylist, visited);
            JSONArray updated = new JSONArray();
            JSONArray previous = document.optJSONArray("visitedPlaylists");
            if (previous != null) for (int index = 0; index < previous.length(); index++) {
                JSONObject row = previous.optJSONObject(index);
                if (row != null && row.optLong("id") != playlistId) updated.put(copy(row));
            }
            updated.put(metadata);
            try { document.put("visitedPlaylists", updated); }
            catch (JSONException invalid) { throw new IOException("Cannot encode visited playlist index", invalid); }
            writeDocument(context, userId, document);
        }
    }

    static List<JSONObject> readPlaylists(Context context, long userId) {
        List<JSONObject> result = new ArrayList<>();
        if (userId <= 0) return result;
        try {
            JSONObject document = readDocument(context, userId);
            JSONArray rows = mergedPlaylists(document.optJSONArray("playlists"), document.optJSONArray("visitedPlaylists"));
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) result.add(decorate(context, row));
            }
        } catch (Exception error) { Log.w(TAG, "Playlist cache unavailable", error); }
        return result;
    }

    static List<JSONObject> readTracks(Context context, long userId, long playlistId) {
        List<JSONObject> result = new ArrayList<>();
        if (userId <= 0 || playlistId <= 0) return result;
        try {
            JSONObject snapshot = readAtomic(trackFile(context, userId, playlistId), MAX_PLAYLIST_BYTES);
            JSONArray rows = snapshot == null ? null : snapshot.optJSONArray("tracks");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) result.add(decorate(context, row));
            }
        } catch (Exception error) { Log.w(TAG, "Playlist track cache unavailable", error); }
        return result;
    }

    static boolean hasTrackSnapshot(Context context, long userId, long playlistId) {
        if (userId <= 0 || playlistId <= 0) return false;
        try { return validTrackSnapshot(readAtomic(trackFile(context, userId, playlistId), MAX_PLAYLIST_BYTES), userId, playlistId); }
        catch (IOException unavailable) { return false; }
    }

    static boolean validTrackSnapshot(JSONObject snapshot, long userId, long playlistId) {
        return snapshot != null && snapshot.optLong("userId") == userId
                && snapshot.optLong("playlistId") == playlistId && snapshot.optJSONArray("tracks") != null;
    }

    static JSONArray mergedPlaylists(JSONArray owned, JSONArray visited) {
        JSONArray result = new JSONArray();
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (JSONArray source : new JSONArray[]{owned, visited}) {
            for (int index = 0; source != null && index < source.length(); index++) {
                JSONObject row = source.optJSONObject(index);
                if (row != null && row.optLong("id") > 0 && seen.add(row.optLong("id"))) result.put(copy(row));
            }
        }
        return result;
    }

    static JSONObject playlistMetadata(long playlistId, int trackCount, JSONObject... candidates) throws IOException {
        String name = "", cover = "";
        for (JSONObject candidate : candidates) {
            if (candidate == null || candidate.optLong("id", playlistId) != playlistId) continue;
            if (name.isEmpty() && candidate.opt("name") instanceof String) {
                name = candidate.optString("name").trim();
                if (name.length() > 500) name = name.substring(0, 500);
            }
            if (cover.isEmpty()) {
                String value = candidate.optString("coverImgUrl", candidate.optString("picUrl", "")).trim();
                try {
                    java.net.URI uri = new java.net.URI(value);
                    if (value.length() <= 2048 && uri.getHost() != null && uri.getUserInfo() == null
                            && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) cover = value;
                } catch (java.net.URISyntaxException invalidCover) { /* Keep searching known remote metadata. */ }
            }
        }
        try { return new JSONObject().put("id", playlistId).put("name", name.isEmpty() ? "歌单 " + playlistId : name)
                .put("coverImgUrl", cover).put("trackCount", Math.max(0, trackCount)); }
        catch (JSONException invalid) { throw new IOException("Cannot encode playlist display metadata", invalid); }
    }

    private static JSONObject findPlaylist(JSONArray rows, long playlistId) {
        for (int index = 0; rows != null && index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null && row.optLong("id") == playlistId) return row;
        }
        return null;
    }

    static long bytes(Context context) {
        File root = root(context);
        File[] files = root.listFiles(file -> file.isFile() && !file.getName().endsWith(".bak"));
        long total = 0;
        if (files != null) for (File file : files) total += Math.max(0, file.length());
        return total;
    }

    static void clear(Context context) {
        File root = root(context);
        File[] files = root.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        ArtworkCache.clear(context);
    }

    private static JSONObject decorate(Context context, JSONObject value) throws JSONException {
        JSONObject result = copy(value);
        String cover = result.optString("coverImgUrl", result.optString("picUrl", ""));
        if (!cover.isEmpty()) result.put("coverLocalUri", ArtworkCache.resolve(context, cover));
        JSONObject album = result.optJSONObject("al");
        if (album != null) {
            String pic = album.optString("picUrl", "");
            if (!pic.isEmpty()) album.put("picLocalUri", ArtworkCache.resolve(context, pic));
        }
        String pic = result.optString("picUrl", "");
        if (!pic.isEmpty()) result.put("picLocalUri", ArtworkCache.resolve(context, pic));
        return result;
    }

    private static JSONObject readDocument(Context context, long userId) throws IOException {
        AtomicFile file = new AtomicFile(new File(root(context), "user-" + userId + ".json"));
        if (!file.getBaseFile().isFile() && !new File(file.getBaseFile() + ".bak").isFile()) return emptyDocument(userId);
        synchronized (LOCK) {
            try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) throw new IOException("Playlist cache exceeds its size limit");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception invalid) {
                Log.w(TAG, "Unreadable playlist cache; replacing snapshot", invalid);
                return emptyDocument(userId);
            }
        }
    }

    private static JSONObject emptyDocument(long userId) throws IOException {
        try { return new JSONObject().put("version", 1).put("userId", userId).put("playlists", new JSONArray()).put("tracks", new JSONObject()); }
        catch (JSONException invalid) { throw new IOException("Cannot create playlist cache snapshot", invalid); }
    }

    private static void writeDocument(Context context, long userId, JSONObject document) throws IOException {
        writeAtomic(new File(root(context), "user-" + userId + ".json"), document, MAX_BYTES);
    }

    private static File trackFile(Context context, long userId, long playlistId) {
        return new File(root(context), "playlist-" + userId + "-" + playlistId + ".json");
    }

    private static File recommendationFile(Context context, long userId) {
        return new File(root(context), "recommendations-" + userId + ".json");
    }

    private static JSONObject readAtomic(File base, int maxBytes) throws IOException {
        AtomicFile file = new AtomicFile(base);
        if (!file.getBaseFile().isFile() && !new File(file.getBaseFile() + ".bak").isFile()) return null;
        synchronized (LOCK) {
            try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maxBytes) throw new IOException("Playlist snapshot exceeds its size limit");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception invalid) {
                Log.w(TAG, "Unreadable playlist track snapshot", invalid);
                return null;
            }
        }
    }

    private static void writeAtomic(File destination, JSONObject document, int maxBytes) throws IOException {
        File root = destination.getParentFile();
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IOException("Playlist cache directory unavailable");
        byte[] raw = document.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > maxBytes) throw new IOException("Playlist cache exceeds its size limit");
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(raw);
            file.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value == null ? "{}" : value.toString()); }
        catch (JSONException invalid) { throw new IllegalArgumentException("Invalid playlist cache value", invalid); }
    }
    private static File root(Context context) { return new File(context.getFilesDir(), DIRECTORY); }
}
