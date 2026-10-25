package io.github.vutronmusic.personal;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ImportedAccountPlaylistTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void onlineReferencesRemainOrderedAndCanBeRemovedWithoutAudio() throws Exception {
        JSONObject manifest = new JSONObject().put("format", 1).put("generator", "vutron-desktop-export").put("name", "Online")
                .put("tracks", new JSONArray().put(new JSONObject().put("neteaseId", 123).put("name", "One").put("onlineOnly", true).put("entryKey", "first"))
                .put(new JSONObject().put("neteaseId", 123).put("name", "Duplicate entry").put("onlineOnly", true).put("entryKey", "second")));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        File root = temporary.newFolder("bundles");
        JSONObject imported = LocalPlaylistStore.importArchive(root, new ByteArrayInputStream(bytes.toByteArray()));
        JSONArray tracks = imported.getJSONArray("localTracks");
        assertEquals(2, tracks.length()); assertEquals(123, tracks.getJSONObject(0).getLong("id"));
        assertFalse(tracks.getJSONObject(0).has("localUri"));
        assertFalse(LocalLibraryIds.same(tracks.getJSONObject(0), tracks.getJSONObject(1)));
        LocalPlaylistStore.removeTrack(root, imported.getString("id"), tracks.getJSONObject(0));
        JSONArray remaining = LocalPlaylistStore.load(LocalPlaylistStore.directory(root, imported.getString("id"))).getJSONArray("localTracks");
        assertEquals(1, remaining.length()); assertEquals("second", remaining.getJSONObject(0).getString("localEntryKey"));
    }
    @Test public void queueReorderKeepsCurrentTrackInsertedSongsAndForeignTail() throws Exception {
        JSONObject a = row("a"), b = row("b"), c = row("c"), foreign = row("foreign").put("localPlaylistId", "bundle:other");
        JSONArray pending = new JSONArray().put(row("next"));
        PlaybackQueue.Snapshot queue = new PlaybackQueue.Snapshot("token", new JSONArray().put(a).put(b).put(c).put(foreign), 1, pending, true);
        PlaybackQueue.Snapshot sorted = queue.reorderCollection("bundle:test", new JSONArray().put(LocalLibraryIds.key(c)).put(LocalLibraryIds.key(a)).put(LocalLibraryIds.key(b)));
        assertEquals("c", sorted.songs.getJSONObject(0).getString("localEntryKey"));
        assertEquals(2, sorted.index); assertEquals("foreign", sorted.songs.getJSONObject(3).getString("localEntryKey"));
        assertTrue(sorted.insertedPlaying); assertEquals(pending.toString(), sorted.upNext.toString());
    }
    private JSONObject row(String key) throws Exception { return new JSONObject().put("id", 1).put("localPlaylistId", "bundle:test").put("localEntryKey", key); }
}
