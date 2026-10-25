package io.github.vutronmusic.personal;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class LocalLibraryIds {
    private LocalLibraryIds() { }
    static String uri(JSONObject row) { return row.optString("localUri", row.optString("uri", row.optString("localPath"))); }
    static String key(JSONObject row) {
        String source = row.optString("localFolderId", row.optString("localPlaylistId")) + "\n" + (uri(row).isEmpty() || row.optString("localPlaylistId").startsWith("restored:") ? row.optString("localEntryKey") : uri(row));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static boolean same(JSONObject a, JSONObject b) {
        if (a.optString("localPlaylistId").startsWith("restored:")) return a.optString("localPlaylistId").equals(b.optString("localPlaylistId"))
                && !a.optString("localEntryKey").isEmpty() && a.optString("localEntryKey").equals(b.optString("localEntryKey"));
        String uri = uri(a);
        if (uri.isEmpty()) return !a.optString("localEntryKey").isEmpty()
                && a.optString("localEntryKey").equals(b.optString("localEntryKey"))
                && a.optString("localPlaylistId").startsWith("bundle:") && a.optString("localPlaylistId").equals(b.optString("localPlaylistId"));
        if (!uri.equals(uri(b))) return false;
        String folder = a.optString("localFolderId"), bundle = a.optString("localPlaylistId");
        return (folder.isEmpty() || b.optString("localFolderId").isEmpty() || folder.equals(b.optString("localFolderId")))
                && (bundle.isEmpty() || b.optString("localPlaylistId").isEmpty() || bundle.equals(b.optString("localPlaylistId")));
    }
    static boolean inCollection(JSONObject row, String id) {
        return id.equals(row.optString("localFolderId")) || id.equals(row.optString("localPlaylistId")) || id.equals(row.optString("playlistVolumeKey"));
    }
}
