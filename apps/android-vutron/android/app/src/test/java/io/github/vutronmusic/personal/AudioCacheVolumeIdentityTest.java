package io.github.vutronmusic.personal;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class AudioCacheVolumeIdentityTest {
    @Test public void onlineVolumeIdentitySurvivesCacheMetadataAndOfflineUriDecoration() throws Exception {
        JSONObject song = new JSONObject().put("id", 123).put("name", "在线歌曲");
        String original = PlaybackVolume.trackKey(song);
        JSONObject cached = AudioCache.metadata(song).put("cached", true).put("localUri", "file:///audio-cache/123.flac");
        assertEquals("track:id:123", original);
        assertEquals(original, PlaybackVolume.trackKey(cached));
        assertFalse(cached.has("neteaseId"));
    }

    @Test public void explicitStableKeyAndVerifiedRemoteIdArePreserved() throws Exception {
        JSONObject supplied = new JSONObject().put("id", 123).put("neteaseId", 123)
                .put("trackVolumeKey", "track:id:123").put("localUri", "content://music/matched");
        JSONObject cached = AudioCache.metadata(supplied);
        assertEquals(123, cached.getLong("neteaseId"));
        assertEquals("track:id:123", cached.getString("trackVolumeKey"));
    }

    @Test public void trueLocalRecordingRetainsItsLocalIdentityWithoutInventingRemoteId() throws Exception {
        JSONObject supplied = new JSONObject().put("id", 999).put("name", "自录音频")
                .put("localUri", "content://my-folder/recording");
        JSONObject cached = AudioCache.metadata(supplied);
        assertTrue(cached.getString("trackVolumeKey").startsWith("track:local:"));
        assertEquals(PlaybackVolume.trackKey(supplied), PlaybackVolume.trackKey(cached));
        assertFalse(cached.has("neteaseId"));
    }

    @Test public void oldOnlineCacheMetadataIsUpgradedBeforeFileUriIsAdded() throws Exception {
        JSONObject oldMetadata = new JSONObject().put("id", 456).put("name", "旧缓存");
        JSONObject upgraded = AudioCache.metadata(oldMetadata);
        upgraded.put("source", "cache").put("localUri", "file:///audio-cache/456.mp3");
        assertEquals("track:id:456", PlaybackVolume.trackKey(upgraded));
        assertFalse(upgraded.has("neteaseId"));
    }
}
