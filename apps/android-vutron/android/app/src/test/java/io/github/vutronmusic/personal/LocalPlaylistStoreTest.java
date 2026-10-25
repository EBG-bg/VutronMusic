package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class LocalPlaylistStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private byte[] archive(String manifest, String audioPath) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            if (manifest != null) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(audioPath));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test public void importsTheExistingDesktopFormatAndRetainsStableTrackIdentity() throws Exception {
        File root = temporary.newFolder("bundles");
        JSONObject original = new JSONObject().put("format", 1).put("generator", "vutron-desktop-export").put("name", "桌面歌单")
                .put("tracks", new JSONArray().put(new JSONObject().put("id", -12)
                        .put("name", "歌曲").put("artist", "歌手").put("album", "专辑")
                        .put("duration", 180000).put("fileName", "audio/song.flac")));
        JSONObject imported = LocalPlaylistStore.importArchive(root,
                new ByteArrayInputStream(archive(original.toString(), "audio/song.flac")));
        assertEquals("桌面歌单", imported.getString("name"));
        assertTrue(imported.getString("id").startsWith("bundle:"));
        assertEquals(1, imported.getInt("trackCount"));
        JSONObject row = imported.getJSONArray("localTracks").getJSONObject(0);
        assertEquals("专辑", row.getJSONObject("al").getString("name"));
        assertEquals(180000, row.getLong("dt"));
        assertTrue(new File(new java.net.URI(row.getString("localUri"))).isFile());
        assertTrue(row.getString("localUri").startsWith("file:///"));
        assertFalse(row.has("neteaseId"));
        JSONObject reloaded = LocalPlaylistStore.list(root).getJSONObject(0);
        assertEquals(imported.getString("id"), reloaded.getString("id"));
        assertEquals(row.getLong("id"), reloaded.getJSONArray("localTracks").getJSONObject(0).getLong("id"));
        assertEquals(1, LocalPlaylistStore.tracks(root).length());
    }

    @Test public void acceptsOriginalFileAliasAndExplicitRemoteIdentity() throws Exception {
        File root = temporary.newFolder("alias");
        String manifest = "{\"format\":1,\"generator\":\"vutron-desktop-export\",\"name\":\"旧格式\",\"tracks\":[{\"file\":\"song.mp3\",\"neteaseId\":123}]}";
        JSONObject imported = LocalPlaylistStore.importArchive(root, new ByteArrayInputStream(archive(manifest, "song.mp3")));
        assertEquals(123, imported.getJSONArray("localTracks").getJSONObject(0).getLong("neteaseId"));
    }

    @Test public void rejectsTraversalAndRemovesOnlyTheFailedNewImport() throws Exception {
        File root = temporary.newFolder("traversal");
        File keep = new File(root, "keep-user-files");
        assertTrue(keep.mkdir());
        for (String name : new String[]{"../escape.mp3", "audio/../../escape.mp3", "C:/escape.mp3", "\\escape.mp3"}) {
            try {
                LocalPlaylistStore.importArchive(root, new ByteArrayInputStream(archive("{}", name)));
                fail("Accepted unsafe path: " + name);
            } catch (java.io.IOException expected) {
                assertEquals(1, root.list().length);
                assertTrue(keep.isDirectory());
            }
        }
    }

    @Test public void rejectsArchivesWithoutAPlayableManifest() throws Exception {
        File root = temporary.newFolder("invalid");
        for (String manifest : new String[]{null, "{}", "{\"tracks\":[{\"fileName\":\"missing.mp3\"}]}"}) {
            try {
                LocalPlaylistStore.importArchive(root, new ByteArrayInputStream(archive(manifest, "song.mp3")));
                fail("Accepted unusable manifest");
            } catch (java.io.IOException expected) { assertEquals(0, root.list().length); }
        }
    }
}
