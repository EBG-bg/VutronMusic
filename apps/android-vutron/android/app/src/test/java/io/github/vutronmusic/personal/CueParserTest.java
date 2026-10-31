package io.github.vutronmusic.personal;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class CueParserTest {
    @Test public void zeroStartAndZeroResumeDoNotWaitForASeekCallback() throws Exception {
        JSONObject first=new JSONObject().put("cueTrackNo",1).put("cueOffsetMs",0).put("cueDurationMs",12000);
        PlaybackSegment zero=PlaybackSegment.of(first,30000);
        assertFalse(zero.needsInitialSeek(0));
        assertTrue(zero.needsInitialSeek(1000));
        PlaybackSegment later=PlaybackSegment.of(new JSONObject(first.toString()).put("cueOffsetMs",12000),30000);
        assertTrue(later.needsInitialSeek(0));
        assertFalse(PlaybackSegment.of(null,30000).needsInitialSeek(0));
        assertTrue(PlaybackSegment.of(null,30000).needsInitialSeek(1000));
    }
    @Test public void malformedCueWarningsOnlyBelongToItsRecording() {
        java.util.Map<String,String> warnings=new java.util.HashMap<>();
        for(String target:CueParser.warningTargets("Album.cue","FILE \"Album.flac\" WAVE\nTRACK broken"))
            warnings.put(target,"bad cue");
        assertEquals("bad cue",CueParser.warningFor(warnings,"ALBUM.FLAC"));
        assertEquals("",CueParser.warningFor(warnings,"Unrelated.mp3"));
        warnings.clear();
        for(String target:CueParser.warningTargets("Album.cue","unreadable")) warnings.put(target,"unreadable cue");
        assertEquals("unreadable cue",CueParser.warningFor(warnings,"Album.wav"));
        assertEquals("",CueParser.warningFor(warnings,"Ordinary.flac"));
        assertFalse(CueParser.warningTargets("Album.cue","FILE \"../Secret.flac\" WAVE").contains("secret.flac"));
        assertEquals(java.util.Set.of("a.flac","b.wav"),CueParser.warningTargets("Album.cue","FILE \"a.flac\" WAVE\nFILE \"b.wav\" WAVE"));
    }
    private static String cue(String body) { return "FILE \"Album.flac\" WAVE\n" + body; }
    @Test public void parsesFramesTagsGapsAndLastTrack() {
        CueParser.Sheet sheet = CueParser.parse("TITLE \"Album\"\nPERFORMER \"Artist\"\n" + cue("TRACK 01 AUDIO\nTITLE \"One\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nPREGAP 00:02:00\nINDEX 00 00:09:00\nINDEX 01 00:10:01\nPOSTGAP 00:01:00"), 30000);
        assertEquals("Album", sheet.title); assertEquals("Artist", sheet.tracks.get(1).performer);
        assertEquals(10013, sheet.tracks.get(1).startMs); assertEquals(19987, sheet.tracks.get(1).durationMs);
        assertEquals(10013, sheet.tracks.get(0).durationMs);
    }
    @Test public void rejectsInvalidSheetsAtomically() {
        String[] bad = {"TRACK 01 AUDIO\nINDEX 00 00:00:00", "TRACK 01 AUDIO\nINDEX 01 00:04:00\nTRACK 02 AUDIO\nINDEX 01 00:03:00", "TRACK 01 AUDIO\nINDEX 01 00:30:00", "TRACK 01 AUDIO\nINDEX 01 00:00:00\nFILE \"B.wav\" WAVE", "TRACK 01 AUDIO\nINDEX 01 00:60:00"};
        for (String text : bad) assertThrows(IllegalArgumentException.class, () -> CueParser.parse(cue(text),30000));
        for (String file : new String[]{"../x.flac", "C:\\x.flac", "/x.flac", "https://x", "sub/x.flac"})
            assertThrows(IllegalArgumentException.class, () -> CueParser.safeFile(file));
        assertThrows(IllegalArgumentException.class, () -> CueParser.timeMs("9999999:59:74"));
        assertThrows(IllegalArgumentException.class, () -> CueParser.timeMs("00:00:75"));
    }
    @Test public void segmentClampsAndUsesUniqueIdentities() throws Exception {
        JSONObject a = new JSONObject().put("cueTrackNo",1).put("cueOffsetMs",10000).put("cueDurationMs",12000);
        PlaybackSegment s = PlaybackSegment.of(a,20000);
        assertEquals(10000,s.length); assertEquals(15000,s.absolute(5000)); assertEquals(5000,s.relative(15000));
        assertEquals(10000,s.absolute(-20)); assertEquals(19999,s.absolute(30000));
        assertEquals(0,s.relative(0)); assertEquals(10000,s.relative(30000));
        assertNotEquals(PlaybackSegment.suffix(a),PlaybackSegment.suffix(new JSONObject(a.toString()).put("cueTrackNo",2)));
    }
    @Test public void scanRowsAndAllPerTrackIdentitiesKeepSegmentsSeparate() throws Exception {
        JSONObject source=new JSONObject().put("id",1).put("localUri","content://test/album").put("localFolderId","folder:a").put("duration",30000);
        java.util.List<JSONObject> rows=LocalFolderStore.cueTracks(source,"content://test/album.cue",
                CueParser.parse(cue("TRACK 01 AUDIO\nTITLE \"One\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"Two\"\nINDEX 01 00:10:00"),30000));
        JSONObject a=rows.get(0),b=rows.get(1);
        assertNotEquals(a.getLong("id"),b.getLong("id"));assertFalse(LocalLibraryIds.same(a,b));
        assertNotEquals(LocalLibraryIds.key(a),LocalLibraryIds.key(b));
        assertNotEquals(LocalTrackStore.exclusionKey(a),LocalTrackStore.exclusionKey(b));
        assertNotEquals(PlaybackVolume.trackKey(a),PlaybackVolume.trackKey(b));
        org.json.JSONArray kept=LocalTrackStore.filterExcluded(new org.json.JSONArray(rows),java.util.Set.of(LocalTrackStore.exclusionKey(a)));
        assertEquals(1,kept.length());assertEquals("Two",kept.getJSONObject(0).getString("name"));
        assertTrue(b.getLong("id")<9007199254740991L);
    }
}
