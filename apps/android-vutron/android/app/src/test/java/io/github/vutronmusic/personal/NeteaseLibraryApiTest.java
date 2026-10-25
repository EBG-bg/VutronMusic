package io.github.vutronmusic.personal;
import org.json.*;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class NeteaseLibraryApiTest {
    private static JSONObject value(String text) { try { return new JSONObject(text); } catch (JSONException error) { throw new AssertionError(error); } }
    private static JSONObject ok() { return value("{\"code\":200}"); }
    private static JSONObject owned() { return value("{\"code\":200,\"playlist\":{\"id\":42,\"creator\":{\"userId\":7},\"tracks\":[],\"trackIds\":[]}}"); }
    private static JSONObject profile() { return value("{\"code\":200,\"profile\":{\"userId\":7}}"); }
    @Test public void likedStateComesFromLikeListAndLikeUsesWeapi() throws Exception {
        List<String> calls = new ArrayList<>();
        NeteaseApi api = new NeteaseApi("", () -> true, (path, data, weapi) -> {
            calls.add(path);
            if (path.equals("/api/song/like/get")) { assertFalse(weapi); assertEquals(7, data.optLong("uid")); return value("{\"code\":200,\"ids\":[2,3,3],\"liked\":true}"); }
            assertEquals("/api/radio/like", path); assertTrue(weapi); assertEquals(2, data.optLong("trackId")); assertFalse(data.optBoolean("like")); return ok();
        });
        assertEquals(new LinkedHashSet<>(Arrays.asList(2L,3L)), api.likedTracks(7)); api.likeTrack(2,false);
        assertEquals(Arrays.asList("/api/song/like/get","/api/radio/like"),calls);
    }
    @Test public void createUsesPrivacyAndReturnsServerPlaylist() throws Exception {
        NeteaseApi api = new NeteaseApi("", () -> true, (path, data, weapi) -> {
            assertEquals("/api/playlist/create",path); assertTrue(weapi); assertEquals("10",data.optString("privacy")); assertEquals("NORMAL",data.optString("type"));
            assertEquals("A \"quoted\" name",data.optString("name")); return owned();
        });
        assertEquals(42,api.createPlaylist("A \"quoted\" name",10).getLong("id"));
    }
    @Test public void anotherOwnersPlaylistCannotBeMutated() throws Exception {
        List<String> paths = new ArrayList<>();
        NeteaseApi api = new NeteaseApi("", () -> true, (path,data,weapi) -> {
            paths.add(path); if(path.equals("/api/w/nuser/account/get"))return profile();
            return value("{\"code\":200,\"playlist\":{\"id\":42,\"creator\":{\"userId\":8}}}");
        });
        try { api.deletePlaylist(42); fail(); } catch(IOException expected) { assertTrue(expected.getMessage().contains("自己的歌单")); }
        assertEquals(2,paths.size()); assertFalse(paths.contains("/api/playlist/remove"));
    }
    @Test public void excessiveNameIsRejectedBeforeNetworkInsteadOfKnownServer400() throws Exception {
        NeteaseApi api = new NeteaseApi("", () -> true, (path,data,weapi) -> { fail("No request for invalid name"); return ok(); });
        try { api.createPlaylist(new String(new char[41]).replace('\0','x'), 10); fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("40")); }
    }
    @Test public void deleteUsesOnlyConfirmedOwnedPlaylistId() throws Exception {
        NeteaseApi api = new NeteaseApi("", () -> true, (path,data,weapi) -> {
            if(path.equals("/api/w/nuser/account/get"))return profile(); if(path.equals("/api/v6/playlist/detail"))return owned();
            assertEquals("/api/playlist/remove",path); assertTrue(weapi); assertEquals("[42]",data.optString("ids")); return ok();
        }); api.deletePlaylist(42);
    }
    @Test public void editBatchEscapesStringsAndRequiresEveryFieldToSucceed() throws Exception {
        List<JSONObject> writes = new ArrayList<>();
        NeteaseApi api = new NeteaseApi("", () -> true, (path,data,weapi) -> {
            if(path.equals("/api/w/nuser/account/get"))return profile(); if(path.equals("/api/v6/playlist/detail"))return owned();
            assertEquals("/api/batch",path); assertFalse(weapi); writes.add(data);
            return value("{\"code\":200,\"/api/playlist/desc/update\":{\"code\":200},\"/api/playlist/tags/update\":{\"code\":200},\"/api/playlist/update/name\":{\"code\":403}}");
        });
        try { api.editPlaylist(42,"quoted \"name\"","line1\nline2",new JSONArray().put("流行").put("华语")); fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("部分字段")); }
        assertEquals("quoted \"name\"",new JSONObject(writes.get(0).getString("/api/playlist/update/name")).getString("name"));
        assertEquals("line1\nline2",new JSONObject(writes.get(0).getString("/api/playlist/desc/update")).getString("desc"));
        assertEquals("流行;华语",new JSONObject(writes.get(0).getString("/api/playlist/tags/update")).getString("tags"));
    }
    @Test public void membershipRetriesOnlyTheKnown512PathOnce() throws Exception {
        List<JSONObject> writes = new ArrayList<>();
        NeteaseApi api = new NeteaseApi("", () -> true, (path,data,weapi) -> {
            if(path.equals("/api/w/nuser/account/get"))return profile(); if(path.equals("/api/v6/playlist/detail"))return owned();
            assertEquals("/api/playlist/manipulate/tracks",path); assertFalse(weapi); writes.add(data); return writes.size()==1?value("{\"code\":512}"):ok();
        }); api.changePlaylistTrack(42,3,true);
        assertEquals(2,writes.size()); assertEquals("[\"3\"]",writes.get(0).getString("trackIds")); assertEquals("[\"3\",\"3\"]",writes.get(1).getString("trackIds"));
    }
    @Test public void localModeRejectsWritesBeforeAnyTransportCall() throws Exception {
        NeteaseApi api = new NeteaseApi("", () -> false, (path,data,weapi) -> { fail("No network in local mode"); return ok(); });
        try { api.likeTrack(3,true); fail(); } catch(IOException expected) { }
        try { api.createPlaylist("Test",0); fail(); } catch(IOException expected) { }
        try { api.changePlaylistTrack(42,3,false); fail(); } catch(IOException expected) { }
    }
}
