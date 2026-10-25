package io.github.vutronmusic.personal;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LibraryBackupTest {
    private JSONObject song() throws Exception { return new JSONObject().put("id", 1).put("name", "Song").put("artists", new JSONArray().put("Artist")).put("album", "Album").put("duration", 1000).put("localUri", "content://private/folder/audio").put("localFileName", "song.flac").put("cookie", "secret"); }
    @Test public void portableTracksExcludePathsCredentialsAndLocalNumericIdentity() throws Exception {
        JSONObject portable = LibraryBackup.portableTrack(song());
        assertFalse(portable.has("id")); assertFalse(portable.has("localUri")); assertFalse(portable.has("cookie"));
        assertFalse(portable.toString().contains("private/folder")); assertEquals("song.flac", portable.getString("fileName"));
        assertEquals(LibraryBackup.fingerprint(song()), LibraryBackup.fingerprint(portable));
    }
    @Test public void preferenceWhitelistRejectsSessionsPathsAndOutOfRangeValues() throws Exception {
        JSONObject source = new JSONObject().put("session", new JSONObject().put("cookie", "secret"))
                .put("audio_cache_settings", new JSONObject().put("enabled", true).put("capacity_mib", 1024).put("index", "private"))
                .put("lyric_overlay", new JSONObject().put("font_size", 999).put("translation", true).put("windowPath", "private"));
        JSONObject safe = LibraryBackup.safePreferences(source);
        assertFalse(safe.has("session")); assertFalse(safe.toString().contains("private")); assertFalse(safe.toString().contains("secret"));
        assertFalse(safe.getJSONObject("lyric_overlay").has("font_size")); assertTrue(safe.getJSONObject("lyric_overlay").getBoolean("translation"));
    }
    private JSONObject backup(JSONArray rows) throws Exception { return new JSONObject().put("format", 1).put("generator", LibraryBackup.GENERATOR)
            .put("collections", new JSONArray().put(new JSONObject().put("name", "List").put("tracks", rows))); }
    @Test public void duplicateSongEntriesKeepIndependentRestorableKeys() throws Exception {
        JSONArray rows = LibraryBackup.validate(backup(new JSONArray().put(song()).put(song()))).getJSONArray("collections").getJSONObject(0).getJSONArray("tracks");
        assertNotEquals(rows.getJSONObject(0).getString("key"), rows.getJSONObject(1).getString("key"));
        assertEquals(LibraryBackup.fingerprint(rows.getJSONObject(0)), LibraryBackup.fingerprint(rows.getJSONObject(1)));
    }
    @Test public void frontendWhitelistCannotImportSessionOrDirectoryValues() throws Exception {
        JSONObject safe = LibraryBackup.safeFrontend(new JSONObject().put("vutron:appearance", "dark").put("vutron:playlist-view", "cards")
                .put("vutron_user", "secret").put("vutron:language", "../../secret").put("vutron:playlist-columns", "999"));
        assertEquals(2, safe.length()); assertFalse(safe.toString().contains("secret"));
    }
    @Test public void wrongBackupSourceIsRejected() throws Exception {
        try { LibraryBackup.validate(backup(new JSONArray()).put("generator", "other")); fail(); } catch (java.io.IOException expected) { }
        try { LibraryBackup.validate(backup(new JSONArray()).put("format", "1")); fail(); } catch (java.io.IOException expected) { }
    }
    @Test public void restoredKeysRemainStableWhenFolderPermissionIsRegranted() throws Exception {
        JSONObject unavailable = song().put("localPlaylistId", "restored:one").put("localEntryKey", "key"); unavailable.remove("localUri");
        JSONObject available = new JSONObject(unavailable.toString()).put("localUri", "content://new-permission/file");
        assertEquals(LocalLibraryIds.key(unavailable), LocalLibraryIds.key(available)); assertTrue(LocalLibraryIds.same(unavailable, available));
    }
}
