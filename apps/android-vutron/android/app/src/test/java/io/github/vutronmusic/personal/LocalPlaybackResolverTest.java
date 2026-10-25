package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class LocalPlaybackResolverTest {
    private JSONObject online() throws Exception {
        return new JSONObject().put("id", 123).put("name", "Same Title")
                .put("artists", new JSONArray().put("Original Singer")).put("album", "Original Album")
                .put("duration", 180000).put("playlistVolumeKey", "netease:1234");
    }

    private JSONObject local(String name, String singer, String album, long duration, String uri) throws Exception {
        return new JSONObject().put("id", 999).put("name", name).put("localHasTitle", true)
                .put("ar", new JSONArray().put(new JSONObject().put("name", singer)))
                .put("al", new JSONObject().put("name", album)).put("duration", duration).put("localUri", uri);
    }

    private JSONObject match(JSONObject candidate) throws Exception {
        return LocalPlaybackResolver.match(online(), Collections.singletonList(candidate));
    }

    @Test public void corroboratedRecordingKeepsOnlineAndPlaylistIdentity() throws Exception {
        JSONObject result = match(local("Same Title", "Original Singer", "Original Album", 180000, "content://music/1"));
        assertNotNull(result);
        assertEquals(123, result.getLong("id"));
        assertEquals("netease:1234", result.getString("playlistVolumeKey"));
        assertEquals("content://music/1", result.getString("localUri"));
    }

    @Test public void sameTitleDifferentSingerOrVersionIsRejected() throws Exception {
        assertNull(match(local("Same Title", "Other Singer", "Original Album", 180000, "content://music/2")));
        assertNull(match(local("Same Title (Live)", "Original Singer", "Original Album", 180000, "content://music/3")));
        assertNull(match(local("Same Title", "未知歌手", "Original Album", 180000, "content://music/4")));
    }

    @Test public void albumOrDurationMustCorroborateAndDuplicateFilesRemainAmbiguous() throws Exception {
        assertNull(match(local("Same Title", "Original Singer", "Other Album", 190000, "content://music/5")));
        assertNotNull(match(local("Same Title", "Original Singer", "", 181999, "content://music/6")));
        JSONObject one = local("Same Title", "Original Singer", "Original Album", 180000, "content://music/7");
        JSONObject two = local("Same Title", "Original Singer", "Original Album", 180000, "content://music/8");
        assertNull(LocalPlaybackResolver.match(online(), Arrays.asList(one, two)));
    }
}
