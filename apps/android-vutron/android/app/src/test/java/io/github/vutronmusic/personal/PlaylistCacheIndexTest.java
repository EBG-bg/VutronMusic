package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class PlaylistCacheIndexTest {
    private JSONObject playlist(long id, String name) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("coverImgUrl", "https://p1.music.126.net/cover.jpg");
    }

    @Test public void visitedSearchPlaylistSurvivesOwnedListRefreshAndDeduplicates() throws Exception {
        JSONArray owned = new JSONArray().put(playlist(1, "我的歌单"));
        JSONArray visited = new JSONArray().put(playlist(1, "旧标题")).put(playlist(2, "搜索打开的歌单"));
        JSONArray first = PlaylistCache.mergedPlaylists(owned, visited);
        assertEquals(2, first.length());
        assertEquals("我的歌单", first.getJSONObject(0).getString("name"));
        JSONObject persisted = new JSONObject().put("playlists", owned).put("visitedPlaylists", visited);
        persisted = new JSONObject(persisted.toString());
        persisted.put("playlists", new JSONArray().put(playlist(3, "刷新后的我的歌单")));
        JSONArray afterRefresh = PlaylistCache.mergedPlaylists(persisted.getJSONArray("playlists"), persisted.getJSONArray("visitedPlaylists"));
        assertEquals(3, afterRefresh.length());
        assertEquals(2, afterRefresh.getJSONObject(2).getLong("id"));
        assertEquals("搜索打开的歌单", afterRefresh.getJSONObject(2).getString("name"));
    }

    @Test public void displayMetadataPrefersKnownRowsAndAcceptsOnlyRemoteCovers() throws Exception {
        JSONObject known = playlist(9, "已知歌单");
        JSONObject supplied = playlist(9, "外部标题").put("trackCount", 999)
                .put("coverLocalUri", "file:///private/secret").put("path", "../private").put("cookie", "not persisted");
        JSONObject result = PlaylistCache.playlistMetadata(9, 12, known, supplied);
        assertEquals("已知歌单", result.getString("name"));
        assertEquals(12, result.getInt("trackCount"));
        assertEquals(4, result.length());
        assertFalse(result.has("coverLocalUri")); assertFalse(result.has("path")); assertFalse(result.has("cookie"));
        supplied.put("coverImgUrl", "file:///private/secret");
        assertEquals("", PlaylistCache.playlistMetadata(9, 0, supplied).getString("coverImgUrl"));
        supplied.put("coverImgUrl", "https://user:password@example.com/a.jpg");
        assertEquals("", PlaylistCache.playlistMetadata(9, 0, supplied).getString("coverImgUrl"));
    }

    @Test public void missingDisplayNameFallsBackToIdAndWrongPlaylistMetadataIsIgnored() throws Exception {
        JSONObject result = PlaylistCache.playlistMetadata(9, 0, playlist(8, "不属于这个歌单"));
        assertEquals("歌单 9", result.getString("name"));
        assertEquals(9, result.getLong("id"));
        assertEquals(0, result.getInt("trackCount"));
    }

    @Test public void validEmptySnapshotIsDistinctFromMissingOrMalformedSnapshot() throws Exception {
        JSONObject empty = new JSONObject().put("userId", 123).put("playlistId", 9).put("tracks", new JSONArray());
        assertTrue(PlaylistCache.validTrackSnapshot(empty, 123, 9));
        assertFalse(PlaylistCache.validTrackSnapshot(null, 123, 9));
        assertFalse(PlaylistCache.validTrackSnapshot(new JSONObject(), 123, 9));
        assertFalse(PlaylistCache.validTrackSnapshot(empty, 456, 9));
        assertFalse(PlaylistCache.validTrackSnapshot(empty, 123, 10));
        assertFalse(PlaylistCache.validTrackSnapshot(new JSONObject().put("userId", 123).put("playlistId", 9).put("tracks", "invalid"), 123, 9));
    }
    @Test public void deletedOfflinePlaylistStaysHiddenAfterRestartAndAnOnlineListRefresh() throws Exception {
        JSONObject document = new JSONObject().put("playlists",new JSONArray().put(playlist(1,"One")).put(playlist(2,"Two")))
                .put("visitedPlaylists",new JSONArray().put(playlist(1,"Visited"))).put("unrelated","retained");
        PlaylistCache.exclude(document,1,true);
        JSONObject restarted = new JSONObject(document.toString());
        restarted.put("playlists",new JSONArray().put(playlist(1,"Refreshed")).put(playlist(2,"Two")));
        JSONArray visible = PlaylistCache.visiblePlaylists(restarted);
        assertEquals(1,visible.length()); assertEquals(2,visible.getJSONObject(0).getLong("id"));
        assertTrue(PlaylistCache.excluded(restarted,1)); assertEquals("retained",restarted.getString("unrelated"));
        PlaylistCache.exclude(restarted,1,false);
        assertEquals(2,PlaylistCache.visiblePlaylists(new JSONObject(restarted.toString())).length());
    }
    @Test public void exclusionIsIdempotentAndDoesNotRemoveAnotherPlaylistOrItsTrackReferences() throws Exception {
        JSONObject document = new JSONObject().put("tracks",new JSONObject().put("7",new JSONArray().put(91)));
        PlaylistCache.exclude(document,7,true); PlaylistCache.exclude(document,7,true); PlaylistCache.exclude(document,8,true);
        assertEquals(2,document.getJSONArray("excludedPlaylistIds").length());
        PlaylistCache.exclude(document,7,false);
        assertFalse(PlaylistCache.excluded(document,7)); assertTrue(PlaylistCache.excluded(document,8));
        assertEquals(91,document.getJSONObject("tracks").getJSONArray("7").getLong(0));
    }
}
