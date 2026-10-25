package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackQueueAppendTest {
    private JSONObject track(long id) throws Exception { return new JSONObject().put("id", id).put("name", "歌曲 " + id).put("playlistVolumeKey", "netease:42"); }

    @Test public void appendingKeepsCurrentCursorAndPendingFifo() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(track(1)).put(track(2)), 1)
                .appendNext(track(8)).appendNext(track(9));
        PlaybackQueue.Snapshot updated = queue.appendTail(track(3));
        assertEquals(queue.token, updated.token);
        assertEquals(1, updated.index); assertEquals(2, updated.current().getLong("id"));
        assertEquals(3, updated.songs.getJSONObject(2).getLong("id"));
        assertEquals(8, updated.upNext.getJSONObject(0).getLong("id"));
        assertEquals(9, updated.upNext.getJSONObject(1).getLong("id"));
        assertEquals(8, updated.next(0, false, 0).current().getLong("id"));
        assertEquals(9, updated.next(0, false, 0).next(0, false, 0).current().getLong("id"));
        assertEquals(3, updated.next(0, false, 0).next(0, false, 0).next(0, false, 0).current().getLong("id"));
    }

    @Test public void appendedRecordingIdentityIsCopiedAndRestored() throws Exception {
        JSONObject supplied = track(3).put("playlistVolumeKey", "folder:local").put("trackVolumeKey", "track:local:recording").put("localUri", "content://music/recording");
        PlaybackQueue.Snapshot updated = PlaybackQueue.create(new JSONArray().put(track(1)), 0).appendTail(supplied);
        supplied.put("name", "caller changed");
        PlaybackQueue.Snapshot restored = PlaybackQueue.decode(updated.encode(), updated.token, updated.index);
        JSONObject appended = restored.songs.getJSONObject(1);
        assertEquals("歌曲 3", appended.getString("name"));
        assertEquals("folder:local", appended.getString("playlistVolumeKey"));
        assertEquals("track:local:recording", appended.getString("trackVolumeKey"));
        assertEquals("content://music/recording", appended.getString("localUri"));
        assertEquals(1, restored.current().getLong("id"));
    }

    @Test public void emptyQueueCanStoreTracksWithoutPlayingOrConsumingPendingRows() throws Exception {
        PlaybackQueue.Snapshot empty = new PlaybackQueue.Snapshot("empty", new JSONArray(), -1);
        PlaybackQueue.Snapshot result = empty.appendNext(track(8)).appendTail(track(1)).appendTail(track(2));
        assertEquals(2, result.count()); assertEquals(0, result.index);
        assertEquals(8, result.upNext.getJSONObject(0).getLong("id"));
        assertEquals(1, result.songs.getJSONObject(0).getLong("id"));
        assertEquals(2, result.songs.getJSONObject(1).getLong("id"));
    }
}
