package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PlaybackQueueTest {
    private JSONObject song(long id) throws Exception {
        return new JSONObject().put("id", id).put("name", "歌曲 " + id)
                .put("playlistVolumeKey", "netease:789");
    }

    @Test public void nextPreviousAndSingleTrackWrapKeepQueueIdentity() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(song(11)).put(song(22)), 0);
        assertEquals(22, queue.move(-1).current().getLong("id"));
        assertEquals(11, queue.move(1).move(1).current().getLong("id"));
        assertEquals(queue.token, queue.move(1).token);
        PlaybackQueue.Snapshot single = PlaybackQueue.create(new JSONArray().put(song(33)), 0);
        assertEquals(33, single.move(1).current().getLong("id"));
    }

    @Test public void suppliedRowsAreCopiedAndVolumeKeysSurviveSwitching() throws Exception {
        JSONObject supplied = song(11).put("trackVolumeKey", "track:local:recording").put("localUri", "content://music/recording");
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(supplied).put(song(22)), 0);
        supplied.put("name", "changed by caller");
        assertEquals("歌曲 11", queue.current().getString("name"));
        assertEquals("track:local:recording", queue.current().getString("trackVolumeKey"));
        assertEquals("netease:789", queue.move(1).current().getString("playlistVolumeKey"));
        assertEquals("track:id:22", queue.move(1).current().getString("trackVolumeKey"));
    }

    @Test public void persistedQueueRestoresCursorAndRejectsStaleToken() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(song(11)).put(song(22)), 0);
        PlaybackQueue.Snapshot restored = PlaybackQueue.decode(queue.encode(), queue.token, 1);
        assertEquals(22, restored.current().getLong("id"));
        assertEquals(2, restored.count());
        assertNull(PlaybackQueue.decode(queue.encode(), "other-queue", 0));
    }

    @Test public void largeQueueRoundTripExceedsBinderLimitWithoutDroppingTracks() throws Exception {
        JSONArray songs = new JSONArray();
        String name = new String(new char[600]).replace('\0', '字');
        for (int i = 0; i < 1000; i++) songs.put(song(i + 1).put("name", name));
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(songs, 999);
        String encoded = queue.encode();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024);
        PlaybackQueue.Snapshot restored = PlaybackQueue.decode(encoded, queue.token, 999);
        assertEquals(1000, restored.count());
        assertEquals(1000, restored.current().getLong("id"));
        assertEquals(1, restored.move(1).current().getLong("id"));
    }

    @Test public void invalidQueueRowsAreRejectedAtBridgeBoundary() throws Exception {
        for (JSONArray rows : new JSONArray[]{new JSONArray(), new JSONArray().put(new JSONObject()), new JSONArray().put("invalid")}) {
            try { PlaybackQueue.create(rows, 0); fail("Expected invalid queue rejection"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void insertedTracksPlayInAddedOrderThenContinueTheOriginalPlaylist() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(song(11)).put(song(22)).put(song(33)), 0);
        queue = queue.appendNext(song(99).put("playlistVolumeKey", "local:personal"))
                .appendNext(song(88).put("playlistVolumeKey", "netease:456"));
        assertEquals(11, queue.current().getLong("id"));
        PlaybackQueue.Snapshot first = queue.next(0, false, 0);
        assertEquals(99, first.current().getLong("id"));
        assertEquals(1, first.index);
        assertEquals("local:personal", first.current().getString("playlistVolumeKey"));
        assertEquals(88, first.upNext.getJSONObject(0).getLong("id"));
        PlaybackQueue.Snapshot second = first.next(0, true, 0);
        assertEquals(88, second.current().getLong("id"));
        assertEquals(0, second.upNext.length());
        assertEquals(22, second.next(0, true, 0).current().getLong("id"));
        assertEquals("netease:789", second.next(0, true, 0).current().getString("playlistVolumeKey"));
        assertEquals(99, second.move(-1).current().getLong("id"));
    }

    @Test public void pendingInsertionAndHistorySurviveAProcessRestart() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(song(11)).put(song(22)), 0)
                .appendNext(song(99)).appendNext(song(88)).consumeNext(0);
        PlaybackQueue.Snapshot restored = PlaybackQueue.decode(queue.encode(), queue.token, queue.index);
        assertEquals(99, restored.current().getLong("id"));
        assertEquals(88, restored.upNext.getJSONObject(0).getLong("id"));
        assertEquals(88, restored.next(0, false, 0).current().getLong("id"));
        assertEquals(22, restored.next(0, false, 0).next(0, false, 0).current().getLong("id"));
    }

    @Test public void singleRepeatYieldsToInsertedTracksButManualNextAdvances() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(song(11)).put(song(22)), 0);
        assertEquals(11, queue.next(1, true, 0).current().getLong("id"));
        assertEquals(22, queue.next(1, false, 0).current().getLong("id"));
        assertEquals(99, queue.appendNext(song(99)).next(1, true, 0).current().getLong("id"));
        PlaybackQueue.Snapshot inserted = queue.appendNext(song(99)).next(1, true, 0);
        PlaybackQueue.Snapshot restoredInsert = PlaybackQueue.decode(inserted.encode(), inserted.token, inserted.index);
        assertEquals(22, restoredInsert.next(1, true, 0).current().getLong("id"));
        assertEquals(22, queue.next(2, false, 0).current().getLong("id"));
    }

    @Test public void selectingAndDeletingPendingRowsPreservesTheOtherPendingRows() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(song(11)).put(song(22)), 0)
                .appendNext(song(99)).appendNext(song(88)).appendNext(song(77));
        PlaybackQueue.Snapshot selected = queue.consumeNext(1);
        assertEquals(88, selected.current().getLong("id"));
        assertEquals(99, selected.upNext.getJSONObject(0).getLong("id"));
        assertEquals(77, selected.upNext.getJSONObject(1).getLong("id"));
        assertEquals(77, selected.remove(0, true).upNext.getJSONObject(0).getLong("id"));
        assertEquals(0, selected.clearNext().upNext.length());
        assertEquals(88, selected.clearNext().current().getLong("id"));
    }

    @Test public void deletingBaseRowsKeepsTheCurrentRowAndCorrectsTheCursor() throws Exception {
        PlaybackQueue.Snapshot queue = PlaybackQueue.create(new JSONArray().put(song(11)).put(song(22)).put(song(33)), 1);
        PlaybackQueue.Snapshot before = queue.remove(0, false);
        assertEquals(22, before.current().getLong("id"));
        assertEquals(0, before.index);
        assertEquals(33, queue.remove(1, false).current().getLong("id"));
        assertEquals(11, queue.at(2).remove(2, false).current().getLong("id"));
        PlaybackQueue.Snapshot single = PlaybackQueue.create(new JSONArray().put(song(11)), 0);
        assertEquals(0, single.remove(0, false).count());
        assertEquals(-1, single.remove(0, false).index);
        assertEquals(99, single.appendNext(song(99)).remove(0, false).current().getLong("id"));
    }

    @Test public void oldSavedQueueFormatHasAnEmptyPendingList() throws Exception {
        String old = new JSONObject().put("token", "old").put("songs", new JSONArray().put(song(11))).toString();
        PlaybackQueue.Snapshot decoded = PlaybackQueue.decode(old, "old", 0);
        assertEquals(11, decoded.current().getLong("id"));
        assertEquals(0, decoded.upNext.length());
    }

    @Test public void pendingOnlyQueueCanStartAndRejectsInvalidPendingIndexes() throws Exception {
        PlaybackQueue.Snapshot empty = new PlaybackQueue.Snapshot("pending", new JSONArray(), -1).appendNext(song(99));
        PlaybackQueue.Snapshot decoded = PlaybackQueue.decode(empty.encode(), empty.token, -1);
        assertEquals(99, decoded.next(0, false, 0).current().getLong("id"));
        assertEquals(0, decoded.next(0, false, 0).index);
        for (int invalid : new int[]{-1, 1, 5}) {
            try { decoded.consumeNext(invalid); fail("Accepted invalid pending index"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
