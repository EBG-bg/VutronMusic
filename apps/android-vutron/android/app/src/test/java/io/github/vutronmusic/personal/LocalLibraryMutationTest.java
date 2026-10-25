package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class LocalLibraryMutationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private JSONObject track(String uri, String folder) throws Exception { return new JSONObject().put("id", 1).put("localUri", uri).put("localFolderId", folder); }
    @Test public void exclusionsSurviveRescanningAndAreScopedByFolderRatherThanNumericId() throws Exception {
        JSONObject removed = track("content://music/song", "folder:one"), other = track("content://music/other", "folder:one"), duplicate = track("content://music/song", "folder:two");
        Set<String> exclusions = new HashSet<>(Collections.singleton(LocalTrackStore.exclusionKey(removed)));
        JSONArray scanned = new JSONArray().put(removed).put(other).put(duplicate);
        JSONArray filtered = LocalTrackStore.filterExcluded(scanned, exclusions);
        assertEquals(2, filtered.length()); assertEquals("content://music/other", filtered.getJSONObject(0).getString("localUri"));
        assertEquals("folder:two", filtered.getJSONObject(1).getString("localFolderId"));
        assertEquals(3, LocalTrackStore.filterExcluded(scanned, Collections.emptySet()).length());
    }
    @Test public void queueRemovalPreservesUnrelatedTracksWithTheSameIdAndAdjustsTheCursor() throws Exception {
        JSONObject a = track("content://music/a", "folder:one"), b = track("content://music/b", "folder:two"), c = track("content://music/c", "folder:two");
        PlaybackQueue.Snapshot queue = new PlaybackQueue.Snapshot("token", new JSONArray().put(a).put(b).put(c), 1, new JSONArray().put(a).put(c));
        PlaybackQueue.Snapshot filtered = queue.withoutMatching(row -> LocalLibraryIds.inCollection(row, "folder:one"));
        assertEquals(2, filtered.count()); assertEquals(0, filtered.index); assertEquals("content://music/b", LocalLibraryIds.uri(filtered.current()));
        assertEquals(1, filtered.upNext.length()); assertNotEquals(queue.token, filtered.token);
    }
    @Test public void localKeysDoNotContainPathsAndDistinctUrisDoNotCollapseToAnOnlineId() throws Exception {
        JSONObject a = track("content://music/a", "folder:one"), b = track("content://music/b", "folder:one");
        assertFalse(LocalLibraryIds.same(a, b)); assertTrue(LocalLibraryIds.key(a).matches("[a-f0-9]{64}"));
        assertNotEquals(LocalLibraryIds.key(a), LocalLibraryIds.key(b));
    }
    private byte[] archive() throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(data)) {
            JSONObject manifest = new JSONObject().put("format", 1).put("generator", "vutron-desktop-export").put("name", "Local")
                    .put("tracks", new JSONArray().put(new JSONObject().put("name", "A").put("fileName", "audio/a.mp3")));
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("audio/a.mp3")); zip.write(new byte[]{1,2,3}); zip.closeEntry();
        }
        return data.toByteArray();
    }
    @Test public void removingAnImportedEntryLeavesAudioAndAnEmptyPlaylistUntilExplicitDeletion() throws Exception {
        File root = temporary.newFolder("bundles"), original = temporary.newFile("original.zip"); Files.write(original.toPath(), archive());
        JSONObject imported = LocalPlaylistStore.importArchive(root, new FileInputStream(original));
        String id = imported.getString("id"); JSONObject row = imported.getJSONArray("localTracks").getJSONObject(0);
        File directory = LocalPlaylistStore.directory(root, id), audio = new File(directory, "audio/a.mp3");
        LocalPlaylistStore.removeTrack(root, id, row);
        assertTrue(audio.isFile()); assertEquals(0, LocalPlaylistStore.load(directory).getInt("trackCount"));
        assertEquals(1, LocalPlaylistStore.list(root).length());
        LocalPlaylistStore.delete(root, id);
        assertFalse(directory.exists()); assertTrue(original.isFile()); assertEquals(0, LocalPlaylistStore.list(root).length());
    }
    @Test public void forgedPlaylistPathsCannotDeleteAnExternalOriginal() throws Exception {
        File root = temporary.newFolder("owned"), original = temporary.newFile("keep.mp3");
        assertThrows(IOException.class, () -> LocalPlaylistStore.delete(root, "bundle:../keep.mp3"));
        assertThrows(IOException.class, () -> LibraryFiles.deleteOwnedTree(root, original));
        assertTrue(original.exists());
    }
}
