package io.github.vutronmusic.personal;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.zip.*;

public class EnhancedLyricTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void localEsLrcWinsOverPlainLrcAndNativePayloadRetainsWords() throws Exception {
        File folder = temporary.newFolder(), audio = new File(folder, "song.flac"); Files.write(audio.toPath(), new byte[]{1});
        Files.write(new File(folder,"song.lrc").toPath(), "[00:01]Plain".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder,"song.eslrc").toPath(), "[00:01]<00:01>One<00:02>two<00:03>".getBytes(StandardCharsets.UTF_8));
        LyricRepository.Lyrics result = LocalLyricSource.load(null, new JSONObject().put("localPath", audio.getPath()));
        assertTrue(result.local); assertEquals("Onetwo",result.original.lines.get(0).text); assertEquals(2,result.original.lines.get(0).words.size());
    }
    @Test public void plainAudioZipKeepsOnlyMatchingSidecarsWithoutChangingTrackOrder() throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try(ZipOutputStream zip = new ZipOutputStream(data)) {
            for(String name:new String[]{"song.eslrc","orphan.lrc","song.flac","readme.txt"}) {
                zip.putNextEntry(new ZipEntry(name));zip.write("[00:01]<00:01>One<00:02>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        File root=temporary.newFolder(); JSONObject imported=LocalPlaylistStore.importArchive(root,new ByteArrayInputStream(data.toByteArray()),true,"Audio ZIP");
        File folder=LocalPlaylistStore.directory(root,imported.getString("id"));
        assertEquals(1,imported.getInt("trackCount"));assertTrue(new File(folder,"song.eslrc").isFile());assertFalse(new File(folder,"orphan.lrc").exists());assertFalse(new File(folder,"readme.txt").exists());
    }
    @Test public void enhancedWordTimesAndTerminalMarkerAreExact() {
        LyricTimeline.Line line = LyricTimeline.parse("[00:01]<00:01>Hello <00:01.5>world<00:02.25>").lines.get(0);
        assertEquals("Hello world", line.text); assertEquals(2, line.words.size());
        assertEquals(1000, line.words.get(0).start); assertEquals(500, line.words.get(0).duration);
        assertEquals(1500, line.words.get(1).start); assertEquals(750, line.words.get(1).duration);
        assertEquals(8.5f, line.displayCharacters(1875, 4000, false), .01f);
    }
    @Test public void wrcAndOrdinaryRepeatedLinesRemainDistinct() {
        LyricTimeline result = LyricTimeline.parse("[00:01]你[00:01.25]好[00:02]\n[00:03][00:05]repeat\n[00:07]end");
        assertEquals(4, result.lines.size()); assertEquals("你好", result.lines.get(0).text);
        assertEquals(2, result.lines.get(0).words.size()); assertTrue(result.lines.get(1).words.isEmpty());
        assertEquals(5000, result.lines.get(2).time);
    }
    @Test public void offsetTranslationAndSeekingUseTheSameWordClock() throws Exception {
        LyricRepository.Lyrics lyrics = new LyricRepository.Lyrics(new JSONObject().put("lrc", "[offset:100]\n[00:01]<00:01>a<00:02>b<00:03>")
                .put("tlyric", "[offset:100]\n[00:01]translation"));
        assertEquals(900, lyrics.original.lines.get(0).time); assertEquals(900, lyrics.original.lines.get(0).words.get(0).start);
        assertEquals("translation", lyrics.translationAt(0));
        assertEquals(-1, lyrics.original.indexAt(LyricTimeline.adjustedTime(1000, 200)));
        assertEquals(0, lyrics.original.indexAt(2500)); assertEquals(-1, lyrics.original.indexAt(100));
    }
    @Test public void mixedPlainLinesSurviveNativePayloadRoundTrip() throws Exception {
        LyricRepository.Lyrics source = new LyricRepository.Lyrics(new JSONObject().put("lrc", "[00:00]Intro\n[00:02]<00:02>你<00:03>好<00:04>\n[00:06]Outro"));
        LyricRepository.Lyrics restored = new LyricRepository.Lyrics(LyricPayload.encode(source));
        assertEquals(3, restored.original.lines.size()); assertEquals("Intro", restored.original.lines.get(0).text);
        assertEquals(2, restored.original.lines.get(1).words.size()); assertEquals("Outro", restored.original.lines.get(2).text);
    }
    @Test public void invalidOrderFallsBackToReadableTextAndBothSidecarNamesAreSupported() {
        LyricTimeline.Line line = LyricTimeline.parse("[00:02]<00:02>one<00:01>two<00:03>").lines.get(0);
        assertEquals("onetwo", line.text); assertTrue(line.words.isEmpty());
        assertEquals("Artist - Song.eslrc", LocalLyricSource.sidecarName("Artist - Song.flac", "eslrc"));
        assertEquals("Artist - Song.lrc", LocalLyricSource.sidecarName("Artist - Song.flac"));
    }
}
