package io.github.vutronmusic.personal;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persists a Storage Access Framework tree and discovers audio files below it.
 *
 * <p>The returned rows use the same shape as the network track rows consumed by
 * {@link PlaybackService}: localUri is a content URI and therefore remains
 * usable after process restarts while the persisted tree grant is valid.</p>
 */
final class LocalFolderStore {
    private static final String PREFS = "local_folder";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_URIS = "tree_uris";
    private static final String TAG = "LocalFolderStore";
    private static final String METADATA_CACHE = "local-folder-metadata.json";
    private static final int MAX_CACHE_BYTES = 16 * 1024 * 1024;

    /** Keep a broken or malicious provider from causing an unbounded walk. */
    static final int MAX_DEPTH = 32;
    static final int MAX_FILES = 10000;
    private static final int MAX_ENTRIES = 20000;

    private static final String[] CHILD_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    private static final String[] CHILD_COLUMNS_WITH_METADATA = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private static final Set<String> AUDIO_EXTENSIONS;
    static {
        Set<String> extensions = new HashSet<>();
        Collections.addAll(extensions,
                "mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus",
                "wav", "aiff", "aif", "alac", "ape", "amr", "mid", "midi",
                "wma", "webm", "3gp");
        AUDIO_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    private LocalFolderStore() { }

    /**
     * Saves a tree URI and requests a persistable read grant for it.
     * Returns false when the provider does not support persistable access.
     */
    static synchronized boolean saveTree(Context context, Uri treeUri, int incomingFlags) {
        if (context == null || treeUri == null || !"content".equalsIgnoreCase(treeUri.getScheme())) {
            return false;
        }
        int grantFlags = incomingFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            grantFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(treeUri, grantFlags);
        } catch (SecurityException ignored) {
            return false;
        }
        JSONArray trees = mergeTreeUris(loadTreeUris(context), treeUri.toString());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_TREE_URIS, trees.toString()).apply();
        return true;
    }

    /** Convenience overload for callers that already have a read grant. */
    static synchronized boolean saveTree(Context context, Uri treeUri) {
        return saveTree(context, treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static synchronized Uri loadTree(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TREE_URI, "");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static synchronized void clear(Context context) {
        JSONArray trees = loadTreeUris(context);
        for (int index = 0; index < trees.length(); index++) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(trees.optString(index)), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The provider may have already revoked the grant.
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_TREE_URI).remove(KEY_TREE_URIS).apply();
    }

    static synchronized JSONArray loadTreeUris(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray saved;
        try { saved = new JSONArray(preferences.getString(KEY_TREE_URIS, "[]")); }
        catch (Exception invalidRegistry) { saved = new JSONArray(); }
        // Attribute the old completed index before a second folder can be added.
        String legacy = preferences.getString(KEY_TREE_URI, "");
        if (!preferences.contains(KEY_TREE_URIS) && legacy != null && !legacy.isEmpty()) {
            JSONArray rows = LocalTrackStore.loadFolderIndex(context);
            boolean changed = false;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row != null && row.optString("localFolderId").isEmpty()) {
                    try { row.put("localFolderId", folderId(legacy)); changed = true; }
                    catch (Exception ignored) { }
                }
            }
            if (changed) LocalTrackStore.saveFolderIndex(context, rows);
            saved = mergeTreeUris(saved, legacy);
            preferences.edit().putString(KEY_TREE_URIS, saved.toString()).apply();
        }
        // Upgrades retain the existing tree and its persisted permission.
        return mergeTreeUris(saved, legacy);
    }

    static JSONArray mergeTreeUris(JSONArray existing, String selected) {
        JSONArray result = new JSONArray();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (int index = 0; existing != null && index < existing.length(); index++) {
            String value = existing.optString(index, "").trim();
            if (!value.isEmpty() && seen.add(value)) result.put(value);
        }
        String value = selected == null ? "" : selected.trim();
        if (!value.isEmpty() && seen.add(value)) result.put(value);
        return result;
    }

    static synchronized void removeTree(Context context, String id) throws IOException {
        JSONArray trees = loadTreeUris(context), kept = new JSONArray();
        String removed = "";
        for (int i = 0; i < trees.length(); i++) { String uri = trees.optString(i); if (id.equals(folderId(uri))) removed = uri; else kept.put(uri); }
        if (removed.isEmpty()) throw new IOException("文件夹未授权或已移除");
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE_URIS, kept.toString())
                .putString(KEY_TREE_URI, kept.length() == 0 ? "" : kept.optString(kept.length() - 1)).commit()) throw new IOException("目录设置保存失败");
        try { context.getContentResolver().releasePersistableUriPermission(Uri.parse(removed), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException alreadyRevoked) { /* A revoked grant is already unavailable. */ }
        LocalTrackStore.removeFolder(context, id);
    }

    static String folderId(String uri) {
        return "folder:" + java.util.UUID.nameUUIDFromBytes(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static JSONObject descriptor(Context context, String uri) {
        String name = Uri.parse(uri).getLastPathSegment();
        try {
            Uri tree = Uri.parse(uri);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = context.getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
        } catch (Exception unavailableProvider) {
            // A revoked or temporarily unavailable folder remains visible for reauthorization.
        }
        int count = 0;
        String id = folderId(uri);
        boolean permissionGranted = false;
        for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && uri.equals(permission.getUri().toString())) { permissionGranted = true; break; }
        }
        JSONArray rows = LocalTrackStore.loadFolderIndex(context);
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null && id.equals(row.optString("localFolderId"))) count++;
        }
        JSONObject result = new JSONObject();
        try { result.put("id", id).put("name", name == null ? "本地文件夹" : name)
                .put("uri", uri).put("trackCount", count).put("permissionGranted", permissionGranted); } catch (Exception ignored) { }
        return result;
    }

    static JSONArray list(Context context) {
        JSONArray result = new JSONArray();
        JSONArray trees = loadTreeUris(context);
        for (int index = 0; index < trees.length(); index++) result.put(descriptor(context, trees.optString(index)));
        return result;
    }

    /** Scan every authorized folder, rather than replacing the previously selected one. */
    static List<JSONObject> scanPersisted(Context context) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray trees = loadTreeUris(context);
        for (int index = 0; index < trees.length() && !LibraryTaskRunner.isCancelled(); index++) {
            String uri = trees.optString(index);
            result.addAll(scanRegistered(context, uri));
        }
        return result;
    }

    static List<JSONObject> scanSelection(Context context, String selectedId) throws IOException {
        if (selectedId == null || selectedId.isEmpty()) {
            List<JSONObject> all = scanPersisted(context);
            LibraryTaskRunner.checkCancelled(); LocalTrackStore.saveFolderIndex(context, new JSONArray(all));
            return all;
        }
        JSONArray trees = loadTreeUris(context);
        String selectedUri = null;
        for (int index = 0; index < trees.length(); index++) {
            String uri = trees.optString(index);
            if (selectedId.equals(folderId(uri))) { selectedUri = uri; break; }
        }
        if (selectedUri == null) throw new IOException("请先授权该本地文件夹");
        List<JSONObject> tracks = scanRegistered(context, selectedUri);
        JSONArray merged = new JSONArray();
        JSONArray previous = LocalTrackStore.loadFolderIndex(context);
        for (int index = 0; index < previous.length(); index++) {
            JSONObject row = previous.optJSONObject(index);
            if (row != null && !selectedId.equals(row.optString("localFolderId"))) merged.put(row);
        }
        for (JSONObject row : tracks) merged.put(row);
        LibraryTaskRunner.checkCancelled(); LocalTrackStore.saveFolderIndex(context, merged);
        return tracks;
    }

    private static List<JSONObject> scanRegistered(Context context, String uri) {
        List<JSONObject> rows = scanFolder(context, Uri.parse(uri));
        for (JSONObject row : rows) {
            try { row.put("localFolderId", folderId(uri)); } catch (Exception ignored) { }
        }
        return rows;
    }

    /**
     * Recursively scans a SAF tree URI. Call from a background executor because
     * reading embedded tags can block while a document provider opens a file.
     * Each row can be sent directly to PlaybackService.
     */
    static List<JSONObject> scanFolder(Context context, Uri treeUri) {
        if (context == null || treeUri == null || !"content".equalsIgnoreCase(treeUri.getScheme())) {
            return new ArrayList<>();
        }
        final ContentResolver resolver = context.getContentResolver();
        final String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (RuntimeException ignored) {
            return new ArrayList<>();
        }
        if (rootDocumentId == null || rootDocumentId.isEmpty()) return new ArrayList<>();

        List<JSONObject> result = new ArrayList<>();
        JSONObject cache = loadMetadataCache(context, treeUri);
        JSONObject updatedCache = new JSONObject();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        int entriesSeen = 0;
        queue.add(new Node(rootDocumentId, 0));
        visited.add(rootDocumentId);

        while (!queue.isEmpty() && result.size() < MAX_FILES && entriesSeen < MAX_ENTRIES
                && !LibraryTaskRunner.isCancelled()) {
            Node node = queue.removeFirst();
            if (node.depth > MAX_DEPTH) continue;
            Uri childrenUri;
            try {
                childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId);
            } catch (RuntimeException ignored) {
                continue;
            }
            try (Cursor cursor = queryChildren(resolver, childrenUri)) {
                if (cursor == null) continue;
                int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                while (!LibraryTaskRunner.isCancelled() && cursor.moveToNext()
                        && result.size() < MAX_FILES && entriesSeen < MAX_ENTRIES) {
                    entriesSeen++;
                    if (idColumn < 0) continue;
                    String documentId = cursor.getString(idColumn);
                    if (documentId == null || documentId.isEmpty() || !visited.add(documentId)) continue;
                    String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "";
                    String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : "";

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (node.depth < MAX_DEPTH) {
                            queue.addLast(new Node(documentId, node.depth + 1));
                        }
                        continue;
                    }
                    if (!isAudio(name, mime)) continue;
                    Uri fileUri;
                    try {
                        fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    long size = optionalLong(cursor, sizeColumn);
                    long modified = optionalLong(cursor, modifiedColumn);
                    JSONObject track = trackWithMetadata(context, fileUri, name, size, modified, cache);
                    if (track != null) {
                        result.add(track);
                        if (result.size() % 25 == 0) LibraryTaskRunner.progress(result.size(), 0);
                        rememberMetadata(updatedCache, fileUri, size, modified, track);
                    }
                }
            } catch (Exception ignored) {
                // Providers can expose a stale or unreadable child. Keep scanning siblings.
            }
        }
        // An interrupted scan must not replace a complete cache with a partial one.
        if (!LibraryTaskRunner.isCancelled() && !result.isEmpty()) {
            saveMetadataCache(context, treeUri, updatedCache);
        }
        return result;
    }

    private static Cursor queryChildren(ContentResolver resolver, Uri children) {
        try {
            Cursor cursor = resolver.query(children, CHILD_COLUMNS_WITH_METADATA, null, null, null);
            if (cursor != null) return cursor;
            Log.w(TAG, "Document provider returned no metadata cursor; retrying with file names");
            return resolver.query(children, CHILD_COLUMNS, null, null, null);
        } catch (SecurityException denied) {
            throw denied;
        } catch (RuntimeException unsupportedColumns) {
            // Some third-party providers reject optional columns. Retry the
            // minimal projection rather than hiding the entire folder.
            Log.w(TAG, "Document provider rejected size/time columns; using file names and cached tags");
            return resolver.query(children, CHILD_COLUMNS, null, null, null);
        }
    }

    private static long optionalLong(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) return -1;
        try {
            return cursor.getLong(column);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private static boolean isAudio(String displayName, String mime) {
        String normalizedMime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (normalizedMime.startsWith("audio/")) return true;
        String name = displayName == null ? "" : displayName;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static JSONObject toTrack(Uri uri, String displayName) {
        String name = displayName == null || displayName.isEmpty() ? "本地歌曲" : displayName;
        try {
            String value = uri.toString();
            return new JSONObject()
                    .put("id", stableId(value))
                    .put("name", name)
                    .put("artist", "本地文件")
                    .put("localUri", value)
                    .put("localFileName", name)
                    .put("dt", 0)
                    .put("duration", 0)
                    .put("ar", new JSONArray().put(new JSONObject().put("name", "本地文件")))
                    .put("al", new JSONObject().put("name", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    static JSONObject trackWithMetadata(Context context, Uri uri, String name,
            long size, long modified, JSONObject cache) {
        JSONObject previous = cache.optJSONObject(uri.toString());
        if (previous != null && previous.optLong("size", -1) == size
                && previous.optLong("modified", -1) == modified) {
            JSONObject saved = previous.optJSONObject("track");
            if (saved != null) {
                try {
                    // Copy because callers may decorate queue rows later.
                    JSONObject reused = new JSONObject(saved.toString());
                    reused.put("localFileName", name == null ? "" : name);
                    if (!reused.optBoolean("localHasTitle") && name != null && !name.isEmpty()) {
                        reused.put("name", name);
                    }
                    return reused;
                } catch (Exception malformedCache) {
                    // Re-read this one file when a cache record is incomplete.
                }
            }
        }

        JSONObject track = toTrack(uri, name);
        if (track == null || LibraryTaskRunner.isCancelled()) return track;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            if (LibraryTaskRunner.isCancelled()) return track;
            String title = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            String artist = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            String album = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!title.isEmpty()) track.put("name", title).put("localHasTitle", true);
            if (!artist.isEmpty()) {
                track.put("artist", artist);
                track.put("ar", new JSONArray().put(new JSONObject().put("name", artist)));
            }
            if (!album.isEmpty()) track.put("al", new JSONObject().put("name", album));
            if (duration != null) {
                try {
                    long milliseconds = Long.parseLong(duration);
                    if (milliseconds > 0) track.put("dt", milliseconds).put("duration", milliseconds);
                } catch (NumberFormatException invalidDuration) {
                    // A malformed duration does not invalidate title/artist tags.
                }
            }
        } catch (Exception unreadableTags) {
            // Unsupported or damaged audio remains visible under its filename.
        } finally {
            try {
                retriever.release();
            } catch (Exception releaseFailure) {
                Log.w(TAG, "Failed to release audio metadata reader", releaseFailure);
            }
        }
        return track;
    }

    private static String cleanTag(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\u0000', ' ').trim();
        return cleaned.length() > 4096 ? cleaned.substring(0, 4096) : cleaned;
    }

    private static void rememberMetadata(JSONObject cache, Uri uri, long size, long modified,
            JSONObject track) {
        try {
            cache.put(uri.toString(), new JSONObject().put("size", size)
                    .put("modified", modified).put("track", track));
        } catch (Exception encodingFailure) {
            Log.w(TAG, "Unable to cache one audio metadata record", encodingFailure);
        }
    }

    private static File metadataCacheFile(Context context, Uri treeUri) {
        return new File(context.getCacheDir(), "local-folder-"
                + folderId(treeUri.toString()).substring(7) + "-metadata.json");
    }

    private static synchronized JSONObject loadMetadataCache(Context context, Uri treeUri) {
        File file = metadataCacheFile(context, treeUri);
        if (!file.isFile()) file = new File(context.getCacheDir(), METADATA_CACHE);
        if (!file.isFile() || file.length() > MAX_CACHE_BYTES) return new JSONObject();
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (LibraryTaskRunner.isCancelled() || output.size() + count > MAX_CACHE_BYTES) {
                    return new JSONObject();
                }
                output.write(buffer, 0, count);
            }
            return new JSONObject(output.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
        } catch (Exception invalidCache) {
            Log.w(TAG, "Audio metadata cache is unavailable; tags will be read again");
            return new JSONObject();
        }
    }

    private static synchronized void saveMetadataCache(Context context, Uri treeUri, JSONObject cache) {
        byte[] encoded = cache.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length > MAX_CACHE_BYTES) {
            Log.w(TAG, "Audio metadata cache exceeds its size limit");
            return;
        }
        File file = metadataCacheFile(context, treeUri);
        File temporary = new File(context.getCacheDir(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(encoded);
        } catch (IOException writeFailure) {
            Log.w(TAG, "Unable to save audio metadata cache", writeFailure);
            return;
        }
        if (!temporary.renameTo(file)) {
            Log.w(TAG, "Unable to replace audio metadata cache");
        }
    }

    private static long stableId(String value) {
        long id = value == null ? 0 : (value.hashCode() & 0x7fffffffL);
        return id == 0 ? 1 : id;
    }

    private static final class Node {
        final String documentId;
        final int depth;

        Node(String documentId, int depth) {
            this.documentId = documentId;
            this.depth = depth;
        }
    }
}
