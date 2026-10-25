package io.github.vutronmusic.personal;

import android.content.Context;
import android.util.Log;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Native NetEase writes and authoritative refreshes. No Node/plugin runtime is involved. */
final class OnlineLibraryManager {
    private OnlineLibraryManager() { }
    private static NeteaseApi api(Context context, String cookie) throws IOException {
        LocalOnlyMode.requireOnline(context);
        if (cookie.isEmpty()) throw new IOException("请先登录网易云");
        return new NeteaseApi(cookie, () -> !LocalOnlyMode.enabled(context) && cookie.equals(SessionStore.load(context)));
    }
    private static void sameSession(Context context, String cookie) throws IOException {
        LocalOnlyMode.requireOnline(context);
        if (!cookie.equals(SessionStore.load(context))) throw new IOException("登录状态已变化，请刷新后重试");
    }
    static JSONObject liked(Context context) throws Exception {
        String cookie = SessionStore.load(context); NeteaseApi api = api(context, cookie);
        long uid = api.profile().getLong("userId");
        JSONArray ids = new JSONArray(new ArrayList<>(api.likedTracks(uid)));
        sameSession(context, cookie);
        return new JSONObject().put("userId", uid).put("likedIds", ids);
    }
    static JSONObject read(Context context, long playlistId) throws Exception {
        String cookie = SessionStore.load(context); NeteaseApi api = api(context, cookie);
        long uid = api.profile().getLong("userId");
        JSONObject result = snapshot(api, uid, playlistId);
        sameSession(context, cookie); cache(context, result);
        return result;
    }
    static JSONObject mutate(Context context, JSONObject options) throws Exception {
        String cookie = SessionStore.load(context); NeteaseApi api = api(context, cookie);
        long uid = api.profile().getLong("userId"), pid = options.optLong("playlistId"), tid = options.optLong("trackId");
        String action = options.optString("action");
        long deleted = 0, created = 0;
        switch (action) {
            case "like":
                if (!(options.opt("liked") instanceof Boolean)) throw new IOException("喜欢操作参数无效");
                api.likeTrack(tid, options.getBoolean("liked")); break;
            case "create": created = api.createPlaylist(options.optString("name"), options.optInt("privacy")).getLong("id"); break;
            case "edit": api.editPlaylist(pid, options.optString("name"), options.optString("description"), options.optJSONArray("tags")); break;
            case "delete": api.deletePlaylist(pid); deleted = pid; break;
            case "add": api.changePlaylistTrack(pid, tid, true); break;
            case "remove": api.changePlaylistTrack(pid, tid, false); break;
            default: throw new IOException("歌单操作无效");
        }
        sameSession(context, cookie);
        if (deleted > 0) try { PlaylistCache.removePlaylist(context, uid, deleted); }
        catch (IOException error) { Log.w("VutronOnlineLibrary", "Deleted playlist cache cleanup failed", error); }
        long refreshId = options.optLong("refreshPlaylistId");
        if (created > 0) refreshId = created;
        if (refreshId == deleted) refreshId = 0;
        try {
            JSONObject result = snapshot(api, uid, refreshId);
            sameSession(context, cookie); cache(context, result);
            return result.put("committed", true).put("deletedPlaylistId", deleted).put("createdPlaylistId", created);
        } catch (Exception refreshError) {
            sameSession(context, cookie);
            return new JSONObject().put("userId", uid).put("committed", true).put("refreshFailed", true)
                    .put("deletedPlaylistId", deleted).put("createdPlaylistId", created)
                    .put("message", "操作已提交，但服务端数据刷新失败，请先重新同步，避免重复操作。" + String.valueOf(refreshError.getMessage()));
        }
    }
    private static JSONObject snapshot(NeteaseApi api, long uid, long playlistId) throws Exception {
        JSONArray playlists = new JSONArray(api.playlists(uid)), ids = new JSONArray(new ArrayList<>(api.likedTracks(uid)));
        JSONObject result = new JSONObject().put("userId", uid).put("playlists", playlists).put("likedIds", ids);
        if (playlistId > 0) {
            JSONObject metadata = api.playlistDetail(playlistId);
            result.put("playlist", metadata).put("tracks", new JSONArray(api.tracks(metadata)));
        }
        return result;
    }
    private static void cache(Context context, JSONObject snapshot) {
        try {
            long uid = snapshot.getLong("userId");
            Set<Long> current = new HashSet<>(); JSONArray fresh = snapshot.getJSONArray("playlists");
            for (int i = 0; i < fresh.length(); i++) current.add(fresh.getJSONObject(i).optLong("id"));
            for (JSONObject old : PlaylistCache.readPlaylists(context, uid)) {
                long owner = old.optLong("creatorUserId", old.optJSONObject("creator") == null ? 0 : old.optJSONObject("creator").optLong("userId"));
                if (owner == uid && old.optLong("id") > 0 && !current.contains(old.optLong("id"))) PlaylistCache.removePlaylist(context, uid, old.optLong("id"));
            }
            PlaylistCache.savePlaylists(context, uid, list(snapshot.getJSONArray("playlists")));
            JSONObject playlist = snapshot.optJSONObject("playlist");
            if (playlist != null) PlaylistCache.saveTracks(context, uid, playlist.getLong("id"), list(snapshot.getJSONArray("tracks")), playlist);
        } catch (Exception error) { Log.w("VutronOnlineLibrary", "Authoritative online snapshot cache failed", error); }
    }
    private static List<JSONObject> list(JSONArray rows) throws JSONException {
        List<JSONObject> result = new ArrayList<>(); for (int i = 0; i < rows.length(); i++) result.add(rows.getJSONObject(i)); return result;
    }
}
