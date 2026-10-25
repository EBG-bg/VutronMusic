package io.github.vutronmusic.personal;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricOffsetTrackTest {
    @Test public void delayedWriteKeepsOriginalLocalRecordingWhenIdsMatch() throws Exception {
        JSONObject original = new JSONObject().put("id", 7).put("localUri", "content://music/original");
        JSONObject current = new JSONObject().put("id", 7).put("localUri", "content://music/another");
        JSONObject selected = VutronMusicPlugin.lyricOffsetTrack(7, original, current);
        assertEquals("local-uri:content://music/original", LyricOverlaySettings.trackIdentity(selected));
        assertNotSame(original, selected);
        assertEquals("content://music/another", current.getString("localUri"));
    }

    @Test public void localPathAndRemoteLyricIdSurviveTheCapturedSnapshot() throws Exception {
        JSONObject original = new JSONObject().put("id", 7).put("localPath", "/Music/old.flac").put("neteaseId", 88);
        JSONObject selected = VutronMusicPlugin.lyricOffsetTrack(7, original, new JSONObject().put("id", 9));
        assertEquals("local-path:/Music/old.flac", LyricOverlaySettings.trackIdentity(selected));
        assertEquals(88, LyricOverlaySettings.remoteLyricId(selected));
    }

    @Test public void oldBridgeCallsRetainCurrentTrackAndOnlineFallback() throws Exception {
        JSONObject current = new JSONObject().put("id", 7).put("localUri", "content://music/7");
        assertEquals("local-uri:content://music/7", LyricOverlaySettings.trackIdentity(VutronMusicPlugin.lyricOffsetTrack(7, null, current)));
        assertEquals("netease:8", LyricOverlaySettings.trackIdentity(VutronMusicPlugin.lyricOffsetTrack(8, null, current)));
    }

    @Test public void mismatchedOrInvalidSongsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> VutronMusicPlugin.lyricOffsetTrack(7, new JSONObject().put("id", 9), null));
        assertThrows(IllegalArgumentException.class, () -> VutronMusicPlugin.lyricOffsetTrack(0, null, null));
    }
}
