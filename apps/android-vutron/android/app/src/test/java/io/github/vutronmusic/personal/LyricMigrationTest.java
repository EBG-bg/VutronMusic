package io.github.vutronmusic.personal;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class LyricMigrationTest {
    @Test public void plainPayloadUsesRealNewlinesAndExactMilliseconds() throws Exception {
        LyricRepository.Lyrics source = new LyricRepository.Lyrics(new JSONObject()
                .put("lrc", "[00:01.001]One\n[00:02.009]Two\n")
                .put("tlyric", "[00:01.001]译一\n[00:02.009]译二\n"));
        JSONObject payload = LyricPayload.encode(source);
        assertTrue(payload.getString("lrc").contains("One\n[00:02.009]"));
        assertFalse(payload.getString("lrc").contains("\\n"));
        LyricRepository.Lyrics restored = new LyricRepository.Lyrics(payload);
        assertEquals(2, restored.original.lines.size());
        assertEquals(1001, restored.original.lines.get(0).time);
        assertEquals(2009, restored.original.lines.get(1).time);
        assertEquals("译二", restored.translationAt(1));
    }

    @Test public void wordPayloadKeepsEachWordAndCoherentTranslationFamily() throws Exception {
        LyricRepository.Lyrics source = new LyricRepository.Lyrics(new JSONObject()
                .put("yrc", "[1001,1500](1001,500,0)One (1501,1000,0)two\n[3001,1000](3001,1000,0)Three\n")
                .put("ytlrc", "[00:01.001]一二\n[00:03.001]三\n"));
        JSONObject payload = LyricPayload.encode(source);
        assertEquals("", payload.getString("lrc"));
        assertTrue(payload.getString("yrc").contains("(1501,1000,0)two"));
        LyricRepository.Lyrics restored = new LyricRepository.Lyrics(payload);
        assertEquals(2, restored.original.lines.get(0).words.size());
        assertEquals(1501, restored.original.lines.get(0).words.get(1).start);
        assertEquals("一二", restored.translationAt(0));
        assertEquals("三", restored.translationAt(1));
    }

    @Test public void duplicateTranslationCannotLeakIntoAdjacentLine() throws Exception {
        LyricRepository.Lyrics source = new LyricRepository.Lyrics(new JSONObject()
                .put("lrc", "[00:01.001]One\n[00:02.001]Two\n")
                .put("tlyric", "[00:01.001]A\n[00:01.001]B\n[00:02.002]Near but wrong\n"));
        LyricRepository.Lyrics restored = new LyricRepository.Lyrics(LyricPayload.encode(source));
        assertEquals("", restored.translationAt(0));
        assertEquals("", restored.translationAt(1));
    }

    @Test public void positiveDelayShowsLyricsLaterIncludingZeroTimeFirstLine() {
        LyricTimeline lyrics = LyricTimeline.parse("[00:00.000]First\n[00:02.000]Second");
        assertEquals(-1, lyrics.indexAt(LyricTimeline.adjustedTime(0, 1000)));
        assertEquals(0, lyrics.indexAt(LyricTimeline.adjustedTime(1000, 1000)));
        assertEquals(1, lyrics.indexAt(LyricTimeline.adjustedTime(1000, -1000)));
    }

    @Test public void offlineCacheKeepsRemoteLyricIdWhileLocalGeneratedIdStaysPrivate() throws Exception {
        JSONObject cached = new JSONObject().put("id", 123).put("source", "cache").put("localUri", "file:///private/cache/123.audio");
        assertEquals(123, LyricOverlaySettings.remoteLyricId(cached));
        JSONObject local = new JSONObject().put("id", 456).put("source", "local").put("localUri", "content://music/user-recording");
        assertEquals(0, LyricOverlaySettings.remoteLyricId(local));
        local.put("neteaseId", 789);
        assertEquals(789, LyricOverlaySettings.remoteLyricId(local));
    }

    @Test public void pauseHidesAfterGraceButAddressLoadingDoesNot() {
        LyricVisibility state = new LyricVisibility();
        assertTrue(state.visible(false, false, true, 0, 1000, 0));
        assertTrue(state.visible(false, false, true, 2999, 3999, 0));
        assertFalse(state.visible(false, false, true, 3000, 4000, 0));
        assertTrue(state.visible(false, true, true, 4000, 5000, 0));
        assertTrue(state.visible(false, true, true, 14000, 15000, 0));
        assertTrue(state.visible(true, false, true, 15000, 16000, 0));
    }

    @Test public void manualHideSurvivesResumeAndExpiryDoesNotShowPausedWindow() {
        LyricVisibility state = new LyricVisibility();
        assertFalse(state.visible(true, false, true, 0, 1000, 31000));
        assertFalse(state.visible(false, false, true, 1000, 2000, 31000));
        assertFalse(state.visible(false, false, true, 31000, 32000, 31000));
        assertTrue(state.visible(true, false, true, 32000, 33000, 31000));
        assertTrue(state.visible(false, false, true, 33000, 34000, 0));
        assertFalse(state.visible(false, false, true, 36000, 37000, 0));
        state.preview(36000);
        assertTrue(state.visible(false, false, true, 36500, 37500, 0));
        assertFalse(state.visible(false, false, true, 39000, 40000, 0));
    }

    @Test public void localSidecarNamesAndLegacyEncodingsRemainExact() throws Exception {
        assertEquals("Track (Live).lrc", LocalLyricSource.sidecarName("Track (Live).flac"));
        String lyric = "[00:00.000]中文歌词";
        assertEquals(lyric, LocalLyricSource.decode(lyric.getBytes("GB18030")));
        byte[] utf16 = lyric.getBytes(StandardCharsets.UTF_16);
        assertEquals(lyric, LocalLyricSource.decode(utf16));
    }

    @Test public void mp3UsltAndFlacVorbisKeepTimedText() throws Exception {
        String lyric = "[00:01.001]Embedded\n[00:02.009]Second";
        byte[] text = lyric.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(3); body.write(new byte[]{'e','n','g',0}); body.write(text);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(new byte[]{'U','S','L','T',0,0,0,(byte)body.size(),0,0}); frame.write(body.toByteArray());
        ByteArrayOutputStream mp3 = new ByteArrayOutputStream();
        mp3.write(new byte[]{'I','D','3',3,0,0,0,0,0,(byte)frame.size()}); mp3.write(frame.toByteArray());
        assertEquals(lyric, LocalLyricSource.embedded(new ByteArrayInputStream(mp3.toByteArray())));
        byte[] comment = ("LYRICS=" + lyric).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream vorbis = new ByteArrayOutputStream();
        writeLe(vorbis, 0); writeLe(vorbis, 1); writeLe(vorbis, comment.length); vorbis.write(comment);
        ByteArrayOutputStream flac = new ByteArrayOutputStream(); flac.write(new byte[]{'f','L','a','C',(byte)0x84,0,0,(byte)vorbis.size()}); flac.write(vorbis.toByteArray());
        assertEquals(lyric, LocalLyricSource.embedded(new ByteArrayInputStream(flac.toByteArray())));
    }

    private static void writeLe(ByteArrayOutputStream out, int value) { out.write(value); out.write(value >> 8); out.write(value >> 16); out.write(value >> 24); }
}
