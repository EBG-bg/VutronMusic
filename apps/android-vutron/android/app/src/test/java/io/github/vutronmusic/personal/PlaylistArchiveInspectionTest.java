package io.github.vutronmusic.personal;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class PlaylistArchiveInspectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File zip(String manifest, String... paths) throws Exception {
        File file = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            if (manifest != null) { out.putNextEntry(new ZipEntry("manifest.json")); out.write(manifest.getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
            for (String path : paths) { out.putNextEntry(new ZipEntry(path)); out.write(path.getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        return file;
    }
    @Test public void missingGeneratorIsRejectedInsteadOfFallingBackToPlainAudio() throws Exception {
        File archive = zip("{\"format\":1,\"tracks\":[]}", "song.mp3");
        IOException error = assertThrows(IOException.class, () -> PlaylistArchiveInspection.inspect(archive));
        assertTrue(error.getMessage().contains("桌面版导出工具"));
    }
    @Test public void correctlyIdentifiedManifestIsAccepted() throws Exception {
        File archive = zip("{\"format\":1,\"generator\":\"vutron-desktop-export\",\"tracks\":[]}", "song.mp3");
        assertTrue(PlaylistArchiveInspection.inspect(archive).desktop);
        assertEquals(1, PlaylistArchiveInspection.inspect(archive).audioCount);
    }
    @Test public void plainAudioUsesArchiveOrderAndIgnoresNonAudio() throws Exception {
        File archive = zip(null, "audio/z.FLAC", "readme.txt", "a.mp3", "audio/c.wav");
        PlaylistArchiveInspection info = PlaylistArchiveInspection.inspect(archive);
        assertFalse(info.desktop); assertEquals(3, info.audioCount);
        JSONObject result = LocalPlaylistStore.importArchive(temporary.newFolder(), new FileInputStream(archive), true, "Audio");
        JSONArray rows = result.getJSONArray("tracks");
        assertEquals("audio/z.FLAC", rows.getJSONObject(0).getString("fileName"));
        assertEquals("a.mp3", rows.getJSONObject(1).getString("fileName"));
        assertEquals("audio/c.wav", rows.getJSONObject(2).getString("fileName"));
        File directory = new File(result.getString("bundleDir"));
        assertFalse(new File(directory, "readme.txt").exists());
        assertEquals("audio/z.FLAC", new String(Files.readAllBytes(new File(directory,"audio/z.FLAC").toPath()), StandardCharsets.UTF_8));
    }
    @Test public void alreadyImportedLegacyManifestRemainsReadable() throws Exception {
        File directory = temporary.newFolder();
        Files.write(new File(directory,"song.mp3").toPath(), new byte[]{1,2,3});
        Files.write(new File(directory,"manifest.json").toPath(), "{\"format\":1,\"name\":\"Old\",\"tracks\":[{\"fileName\":\"song.mp3\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, LocalPlaylistStore.load(directory).getInt("trackCount"));
    }
    @Test public void cancelledExtractionDoesNotLeaveAPartialPlaylist() throws Exception {
        File archive = zip(null, "a.mp3"), root = temporary.newFolder();
        Thread.currentThread().interrupt();
        try { assertThrows(java.util.concurrent.CancellationException.class, () -> LocalPlaylistStore.importArchive(root, new FileInputStream(archive), true, "Cancelled")); }
        finally { Thread.interrupted(); }
        assertEquals(0, Objects.requireNonNull(root.list()).length);
        assertTrue(archive.isFile());
    }
    @Test public void onlineMetadataRequiresBothSessionAndOnlineMode() {
        assertFalse(PlaylistImportController.canEnrich(true, false));
        assertFalse(PlaylistImportController.canEnrich(true, true));
        assertFalse(PlaylistImportController.canEnrich(false, false));
        assertTrue(PlaylistImportController.canEnrich(false, true));
    }
}
