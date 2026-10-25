package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.ActivityCallback;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.ByteArrayOutputStream;

    /** Native adapter shared by the Capacitor shell. */
@CapacitorPlugin(name = "VutronMusic")
public final class VutronMusicPlugin extends Plugin {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Keep the NetEase cookie jar between QR polling calls. */
    private final Map<String, NeteaseApi> qrSessions = new HashMap<>();

    @PluginMethod
    public void getSession(PluginCall call) {
        worker.execute(() -> {
            try {
                String cookie = SessionStore.load(getContext());
                if (cookie.isEmpty()) { call.resolve(new JSObject().put("loggedIn", false)); return; }
                Context context = getContext();
                JSONObject account;
                try {
                    account = new NeteaseApi(cookie).profile();
                    cacheProfile(context, account);
                } catch (Exception networkUnavailable) {
                    String cached = context.getSharedPreferences("vutron_user", Context.MODE_PRIVATE)
                            .getString("profile", "");
                    if (cached.isEmpty()) throw networkUnavailable;
                    account = new JSONObject(cached);
                }
                JSObject result = new JSObject().put("loggedIn", true).put("profile", profile(account));
                call.resolve(result);
            } catch (Exception error) {
                call.resolve(new JSObject().put("loggedIn", false));
            }
        });
    }

    @PluginMethod
    public void loginWithCookie(PluginCall call) {
        String cookie = call.getString("cookie", "");
        if (cookie.trim().isEmpty()) { call.reject("请输入网易云 Cookie"); return; }
        worker.execute(() -> {
            try {
                Context context = getContext();
                NeteaseApi api = new NeteaseApi(cookie);
                JSONObject account = api.profile();
                SessionStore.save(context, api.getCookie());
                cacheProfile(context, account);
                call.resolve(new JSObject().put("profile", profile(account)));
            } catch (Exception error) {
                call.reject(error.getMessage() == null ? "网易云登录失败" : error.getMessage());
            }
        });
    }

    /** Create a NetEase QR login session and return a ready-to-display PNG. */
    @PluginMethod
    public void createQrLogin(PluginCall call) {
        worker.execute(() -> {
            try {
                NeteaseApi api = new NeteaseApi("");
                String key = api.qrKey();
                String url = "https://music.163.com/login?codekey=" + key;
                String imageData = qrPngData(url);
                synchronized (qrSessions) { qrSessions.put(key, api); }
                call.resolve(new JSObject().put("key", key).put("url", url).put("imageData", imageData));
            } catch (Exception error) {
                call.reject(message(error, "二维码获取失败"));
            }
        });
    }

    /** Poll a QR login session. Codes 801/802 mean waiting/scanned, 803 is success. */
    @PluginMethod
    public void checkQrLogin(PluginCall call) {
        String key = call.getString("key", "").trim();
        if (key.isEmpty()) { call.reject("二维码登录标识无效"); return; }
        worker.execute(() -> {
            NeteaseApi api;
            synchronized (qrSessions) { api = qrSessions.get(key); }
            if (api == null) { call.reject("二维码登录已失效，请重新生成"); return; }
            try {
                JSONObject response = api.qrCheck(key);
                int code = response.optInt("code");
                JSObject result = new JSObject().put("code", code)
                        .put("message", response.optString("message", response.optString("msg", "")))
                        .put("loggedIn", false);
                if (code == 803) {
                    // Some NetEase deployments return the authenticated cookie
                    // in the JSON body instead of a Set-Cookie header.  Feed it
                    // into the same jar before validating the profile so QR
                    // login works across both response variants.
                    String responseCookie = response.optString("cookie", "")
                            .replace(" HTTPOnly", "");
                    if (!responseCookie.trim().isEmpty()) api.setCookie(responseCookie);
                    JSONObject account = api.profile();
                    Context context = getContext();
                    String cookie = api.getCookie();
                    if (cookie.trim().isEmpty()) throw new java.io.IOException("二维码登录未返回有效会话");
                    SessionStore.save(context, cookie);
                    cacheProfile(context, account);
                    result.put("loggedIn", true).put("profile", profile(account));
                    synchronized (qrSessions) { qrSessions.remove(key); }
                } else if (code == 800) {
                    synchronized (qrSessions) { qrSessions.remove(key); }
                }
                call.resolve(result);
            } catch (Exception error) {
                call.reject(message(error, "二维码登录检查失败"));
            }
        });
    }

    @PluginMethod
    public void cancelQrLogin(PluginCall call) {
        String key = call.getString("key", "").trim();
        if (!key.isEmpty()) synchronized (qrSessions) { qrSessions.remove(key); }
        call.resolve();
    }

    @PluginMethod
    public void logout(PluginCall call) {
        Context context = getContext();
        SessionStore.clear(context);
        context.getSharedPreferences("vutron_user", Context.MODE_PRIVATE).edit().clear().apply();
        if (AndroidPlaybackService.isActive()) {
            ContextCompat.startForegroundService(context,
                    new Intent(context, AndroidPlaybackService.class).setAction(AndroidPlaybackService.ACTION_STOP));
        } else {
            context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE).edit().clear().apply();
        }
        call.resolve();
    }

    @PluginMethod
    public void getPlaylists(PluginCall call) {
        worker.execute(() -> {
            try {
                NeteaseApi api = authenticated();
                long userId = longArg(call, "userId", 0L);
                Context context = getContext();
                List<JSONObject> fetched = api.playlists(userId);
                try { PlaylistCache.savePlaylists(context, userId, fetched); } catch (Exception ignored) { }
                JSONArray values = new JSONArray();
                for (JSONObject item : fetched) values.put(playlist(item, context));
                call.resolve(new JSObject().put("playlists", values));
            } catch (Exception error) {
                long userId = longArg(call, "userId", 0L);
                List<JSONObject> cached = PlaylistCache.readPlaylists(getContext(), userId);
                if (cached.isEmpty()) { call.reject(message(error, "读取歌单失败")); return; }
                JSONArray values = new JSONArray();
                for (JSONObject item : cached) values.put(playlist(item, getContext()));
                call.resolve(new JSObject().put("playlists", values).put("cached", true));
            }
        });
    }

    @PluginMethod
    public void getPlaylistTracks(PluginCall call) {
        worker.execute(() -> {
            try {
                NeteaseApi api = authenticated();
                long playlistId = longArg(call, "playlistId", 0L);
                Context context = getContext();
                List<JSONObject> fetched = api.tracks(playlistId);
                try { PlaylistCache.saveTracks(context, cachedUserId(context), playlistId, fetched, call.getObject("playlist")); }
                catch (Exception cacheFailure) { android.util.Log.w("VutronPlaylistCache", "Unable to persist the opened playlist", cacheFailure); }
                JSONArray values = new JSONArray();
                for (JSONObject item : fetched) values.put(track(item, context));
                call.resolve(new JSObject().put("tracks", values));
            } catch (Exception error) {
                long playlistId = longArg(call, "playlistId", 0L);
                JSONArray values = new JSONArray();
                for (JSONObject item : PlaylistCache.readTracks(getContext(), cachedUserId(getContext()), playlistId)) values.put(track(item, getContext()));
                if (values.length() == 0 && !PlaylistCache.hasTrackSnapshot(getContext(), cachedUserId(getContext()), playlistId)) {
                    call.reject(message(error, "读取歌曲列表失败")); return;
                }
                call.resolve(new JSObject().put("tracks", values).put("cached", true));
            }
        });
    }

    @PluginMethod
    public void getHomeData(PluginCall call) {
        worker.execute(() -> {
            try {
                Context context = getContext();
                JSONObject data = authenticated().homeData(!SessionStore.load(context).isEmpty());
                JSONArray rows = data.optJSONArray("playlists");
                List<JSONObject> snapshots = new ArrayList<>();
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject item = rows.optJSONObject(i);
                    if (item != null) snapshots.add(item);
                }
                if (!snapshots.isEmpty()) try { PlaylistCache.saveRecommendedPlaylists(context, cachedUserId(context), snapshots); } catch (Exception ignored) { }
                JSONArray daily = data.optJSONArray("dailyTracks");
                if (daily != null) {
                    JSONArray standardized = new JSONArray();
                    for (int i = 0; i < daily.length(); i++) {
                        JSONObject item = daily.optJSONObject(i);
                        if (item != null) standardized.put(track(item, context));
                    }
                    data.put("dailyTracks", standardized);
                }
                enqueueHomeArtwork(context, data);
                decorateHomeArtwork(context, data);
                call.resolve(new JSObject(data.toString()));
            } catch (Exception error) {
                Context context = getContext();
                long userId = cachedUserId(context);
                List<JSONObject> cached = PlaylistCache.readRecommendedPlaylists(context, userId);
                if (cached.isEmpty()) cached = PlaylistCache.readPlaylists(context, userId);
                if (cached.isEmpty()) { call.reject(message(error, "首页数据加载失败")); return; }
                JSONArray lists = new JSONArray();
                for (JSONObject item : cached) lists.put(playlist(item, context));
                call.resolve(new JSObject().put("banners", new JSONArray()).put("playlists", lists)
                        .put("artists", new JSONArray()).put("albums", new JSONArray())
                        .put("charts", new JSONArray()).put("dailyTracks", new JSONArray()).put("cached", true));
            }
        });
    }

    @PluginMethod
    public void search(PluginCall call) {
        String category = call.getString("category", "tracks");
        String keywords = call.getString("keywords", "").trim();
        int page = Math.max(1, call.getInt("page", 1));
        if (keywords.isEmpty()) { call.reject("请输入搜索内容"); return; }
        worker.execute(() -> {
            try {
                Context context = getContext();
                JSONObject result = authenticated().search(category, keywords, page);
                JSONArray items = result.optJSONArray("items");
                JSONArray resolved = new JSONArray();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    if ("tracks".equals(category)) resolved.put(track(item, context));
                    else if ("playlists".equals(category)) resolved.put(playlist(item, context));
                    else {
                        String cover = item.optString("picUrl", "");
                        ArtworkCache.enqueue(context, cover);
                        if (!cover.isEmpty()) item.put("picLocalUri", ArtworkCache.resolve(context, cover));
                        resolved.put(item);
                    }
                }
                result.put("items", resolved);
                call.resolve(new JSObject(result.toString()));
            } catch (Exception error) { call.reject(message(error, "歌曲搜索失败")); }
        });
    }

    @PluginMethod
    public void getEntityTracks(PluginCall call) {
        String category = call.getString("category", "artists");
        long id = longArg(call, "id", 0L);
        worker.execute(() -> {
            try {
                Context context = getContext();
                JSONArray rows = new JSONArray();
                for (JSONObject item : authenticated().entityTracks(category, id)) rows.put(track(item, context));
                call.resolve(new JSObject().put("tracks", rows));
            } catch (Exception error) { call.reject(message(error, "读取搜索结果歌曲失败")); }
        });
    }

    @PluginMethod
    public void openMv(PluginCall call) {
        long id = longArg(call, "id", 0L);
        if (id <= 0) { call.reject("MV ID 无效"); return; }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.163.com/#/mv?id=" + id));
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception unavailable) { call.reject(message(unavailable, "无法打开 MV 页面")); }
    }

    @PluginMethod
    public void getCachedPlaylists(PluginCall call) {
        Context context = getContext();
        JSONArray rows = new JSONArray();
        for (JSONObject item : PlaylistCache.readPlaylists(context, cachedUserId(context))) rows.put(playlist(item, context));
        call.resolve(new JSObject().put("playlists", rows));
    }

    @PluginMethod
    public void getCachedPlaylistTracks(PluginCall call) {
        Context context = getContext();
        long id = longArg(call, "playlistId", 0L);
        JSONArray rows = new JSONArray();
        for (JSONObject item : PlaylistCache.readTracks(context, cachedUserId(context), id)) rows.put(track(item, context));
        call.resolve(new JSObject().put("tracks", rows));
    }

    @PluginMethod
    public void cachePlaylist(PluginCall call) {
        long playlistId = longArg(call, "playlistId", 0L);
        if (playlistId <= 0) { call.reject("歌单 ID 无效"); return; }
        worker.execute(() -> {
            try {
                Context context = getContext();
                List<JSONObject> values = authenticated().tracks(playlistId);
                PlaylistCache.saveTracks(context, cachedUserId(context), playlistId, values);
                for (JSONObject song : values) ArtworkCache.enqueue(context, ArtworkSource.coverUrl(song));
                call.resolve(new JSObject().put("count", values.size()));
            } catch (Exception error) { call.reject(message(error, "缓存歌单失败")); }
        });
    }

    @PluginMethod
    public void cacheAllPlaylists(PluginCall call) {
        worker.execute(() -> {
            int cachedPlaylists = 0;
            int cachedTracks = 0;
            int failedPlaylists = 0;
            try {
                Context context = getContext();
                long userId = cachedUserId(context);
                List<JSONObject> playlists = authenticated().playlists(userId);
                try { PlaylistCache.savePlaylists(context, userId, playlists); } catch (Exception ignored) { }
                NeteaseApi api = authenticated();
                for (JSONObject playlist : playlists) {
                    long id = playlist.optLong("id");
                    if (id <= 0) { failedPlaylists++; continue; }
                    try {
                        List<JSONObject> songs = api.tracks(id);
                        try { PlaylistCache.saveTracks(context, userId, id, songs); } catch (Exception ignored) { failedPlaylists++; continue; }
                        for (JSONObject song : songs) ArtworkCache.enqueue(context, ArtworkSource.coverUrl(song));
                        cachedPlaylists++;
                        cachedTracks += songs.size();
                    } catch (Exception unavailable) {
                        failedPlaylists++;
                    }
                }
                int pendingCovers = 0;
                try { pendingCovers = ArtworkCache.awaitPending(30_000L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); pendingCovers = 1; }
                call.resolve(new JSObject().put("cachedPlaylists", cachedPlaylists)
                        .put("cachedTracks", cachedTracks).put("failedPlaylists", failedPlaylists)
                        .put("pendingCovers", pendingCovers));
            } catch (Exception error) { call.reject(message(error, "批量缓存歌单失败")); }
        });
    }

    @PluginMethod
    public void getSleepTimer(PluginCall call) {
        call.resolve(new JSObject().put("remainingMs", AndroidPlaybackService.sleepTimerRemainingMs(getContext())));
    }

    @PluginMethod
    public void setSleepTimer(PluginCall call) {
        long durationMs = longArg(call, "durationMs", 0L);
        try {
            AndroidPlaybackService.setSleepTimer(getContext(), durationMs);
            call.resolve(new JSObject().put("remainingMs", AndroidPlaybackService.sleepTimerRemainingMs(getContext())));
        } catch (Exception error) { call.reject(message(error, "睡眠定时设置失败")); }
    }

    @PluginMethod
    public void clearSleepTimer(PluginCall call) {
        AndroidPlaybackService.clearSleepTimer(getContext());
        call.resolve(new JSObject().put("remainingMs", 0));
    }

    @PluginMethod
    public void getSourceSettings(PluginCall call) {
        call.resolve(new JSObject().put("autoMatch", SourceSettings.autoMatch(getContext())));
    }

    @PluginMethod
    public void updateSourceSettings(PluginCall call) {
        SourceSettings.autoMatch(getContext(), call.getBoolean("autoMatch", true));
        getSourceSettings(call);
    }

    @PluginMethod
    public void getVolumeSettings(PluginCall call) {
        JSONObject song = call.getObject("track");
        if (song == null) song = currentTrack(getContext());
        String playlistKey = call.getString("playlistKey", "");
        int playlistPercent = playlistKey.isEmpty() ? PlaybackVolume.playlistPercent(getContext(), song)
                : PlaybackVolume.playlistPercent(getContext(), playlistKey);
        call.resolve(new JSObject().put("trackPercent", PlaybackVolume.trackPercent(getContext(), song))
                .put("playlistPercent", playlistPercent));
    }

    @PluginMethod
    public void setTrackVolume(PluginCall call) {
        JSONObject song = call.getObject("track");
        if (song == null) song = currentTrack(getContext());
        int percent = (int) longArg(call, "percent", -1L);
        try {
            PlaybackVolume.setTrackPercent(getContext(), song, percent);
            getVolumeSettings(call);
        } catch (Exception invalid) { call.reject(message(invalid, "单曲音量设置失败")); }
    }

    @PluginMethod
    public void setPlaylistVolume(PluginCall call) {
        String key = call.getString("playlistKey", "");
        int percent = (int) longArg(call, "percent", -1L);
        try {
            PlaybackVolume.setPlaylistPercent(getContext(), key, percent);
            getVolumeSettings(call);
        } catch (Exception invalid) { call.reject(message(invalid, "歌单音量设置失败")); }
    }

    @PluginMethod
    public void play(PluginCall call) {
        JSObject track = call.getObject("track");
        if (track == null || track.optLong("id") <= 0) { call.reject("歌曲信息无效"); return; }
        worker.execute(() -> {
            try {
                JSONArray songs = call.getArray("queue");
                if (songs == null || songs.length() == 0) songs = new JSONArray().put(track);
                int index = (int) longArg(call, "index", 0L);
                index = Math.max(0, Math.min(index, songs.length() - 1));
                if (songs.optJSONObject(index) == null || songs.optJSONObject(index).optLong("id") != track.optLong("id")) {
                    throw new IllegalArgumentException("播放队列与所选歌曲不一致");
                }
                PlaybackQueue.Snapshot queue = PlaybackQueue.replace(getContext(), songs, index);
                ContextCompat.startForegroundService(getContext(), new Intent(getContext(), AndroidPlaybackService.class)
                        .setAction(AndroidPlaybackService.ACTION_QUEUE).putExtra("queueToken", queue.token));
                call.resolve();
            } catch (Exception error) { call.reject(message(error, "启动播放失败")); }
        });
    }

    @PluginMethod
    public void getPlaybackQueue(PluginCall call) {
        worker.execute(() -> {
            try { call.resolve(playbackQueue(PlaybackQueue.load(getContext()))); }
            catch (Exception error) { call.reject(message(error, "读取播放队列失败")); }
        });
    }

    @PluginMethod
    public void addToPlayNext(PluginCall call) {
        JSObject value = call.getObject("track");
        if (value == null || value.optLong("id") <= 0) { call.reject("歌曲信息无效"); return; }
        editPlaybackQueue(call, "add", value);
    }

    @PluginMethod
    public void appendToPlaybackQueue(PluginCall call) {
        JSObject value = call.getObject("track");
        if (value == null || value.optLong("id") <= 0) { call.reject("歌曲信息无效"); return; }
        editPlaybackQueue(call, "append", value);
    }

    @PluginMethod
    public void playQueueIndex(PluginCall call) { editPlaybackQueue(call, "select", null); }

    @PluginMethod
    public void removeQueueTrack(PluginCall call) { editPlaybackQueue(call, "remove", null); }

    @PluginMethod
    public void clearPlayNext(PluginCall call) { editPlaybackQueue(call, "clearNext", null); }

    private void editPlaybackQueue(PluginCall call, String operation, JSONObject value) {
        int index = (int) longArg(call, "index", -1L);
        AndroidPlaybackService.editQueue(getContext(), operation, index, call.getBoolean("upNext", false), value,
                new AndroidPlaybackService.QueueResult() {
                    @Override public void success(PlaybackQueue.Snapshot updated) { worker.execute(() -> call.resolve(playbackQueue(updated))); }
                    @Override public void failure(Exception error) { call.reject(message(error, "修改播放队列失败")); }
                });
    }

    private JSObject playbackQueue(PlaybackQueue.Snapshot value) {
        return new JSObject().put("tracks", value == null ? new JSONArray() : localLibraryTracks(value.songs))
                .put("index", value == null ? -1 : value.index)
                .put("upNext", value == null ? new JSONArray() : localLibraryTracks(value.upNext))
                .put("mode", AndroidPlaybackService.getMode(getContext()));
    }

    @PluginMethod
    public void next(PluginCall call) {
        ContextCompat.startForegroundService(getContext(), new Intent(getContext(), AndroidPlaybackService.class)
                .setAction(AndroidPlaybackService.ACTION_NEXT));
        call.resolve();
    }

    @PluginMethod
    public void previous(PluginCall call) {
        ContextCompat.startForegroundService(getContext(), new Intent(getContext(), AndroidPlaybackService.class)
                .setAction(AndroidPlaybackService.ACTION_PREVIOUS));
        call.resolve();
    }

    @PluginMethod
    public void seek(PluginCall call) {
        int position = Math.max(0, call.getInt("position", 0));
        AndroidPlaybackService.seek(getContext(), position);
        call.resolve();
    }

    @PluginMethod
    public void getLyrics(PluginCall call) {
        long trackId = longArg(call, "trackId", 0L);
        worker.execute(() -> {
            try {
                if (trackId <= 0) throw new Exception("歌曲 ID 无效");
                Context context = getContext();
                JSONObject current = currentTrack(context);
                JSONObject song = current != null && current.optLong("id") == trackId ? current : new JSONObject().put("id", trackId);
                long remoteId = LyricOverlaySettings.remoteLyricId(song);
                LyricRepository.Lyrics lyrics = LyricRepository.loadForTrack(context, song);
                if (!lyrics.local && remoteId > 0 && LyricRepository.hasValidationWifi(context)) {
                    try {
                        LyricRepository.Lyrics refreshed = LyricRepository.revalidateOnWifi(context, remoteId);
                        if (refreshed != null) lyrics = refreshed;
                    } catch (java.io.IOException deferred) {
                        android.util.Log.w("VutronLyrics", "Wi-Fi lyric refresh deferred; keeping cached lyrics");
                    }
                }
                call.resolve(lyricsPayload(lyrics));
            } catch (Exception error) { call.reject(message(error, "读取歌词失败")); }
        });
    }

    @PluginMethod
    public void pickMusicFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPicked");
    }

    @ActivityCallback
    private void folderPicked(PluginCall call, ActivityResult result) {
        if (result == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) {
            call.resolve(new JSObject().put("cancelled", true));
            return;
        }
        Uri uri = result.getData().getData();
        if (!LocalFolderStore.saveTree(getContext(), uri, result.getData().getFlags())) {
            call.reject("文件夹授权失败");
            return;
        }
        JSONObject folder = LocalFolderStore.descriptor(getContext(), uri.toString());
        call.resolve(new JSObject().put("id", folder.optString("id"))
                .put("uri", uri.toString()).put("name", folder.optString("name"))
                .put("trackCount", folder.optInt("trackCount")));
    }

    @PluginMethod
    public void getLocalLibrary(PluginCall call) {
        worker.execute(() -> {
            Context context = getContext();
            JSONArray bundles = LocalPlaylistStore.list(localBundleRoot(context));
            JSONArray playlists = new JSONArray();
            for (int index = 0; index < bundles.length(); index++) {
                JSONObject value = bundles.optJSONObject(index);
                playlists.put(new JSObject().put("id", value.optString("id"))
                        .put("name", value.optString("name"))
                        .put("description", value.optString("description"))
                        .put("trackCount", value.optInt("trackCount"))
                        .put("isLocalBundle", true));
            }
            call.resolve(new JSObject().put("folders", LocalFolderStore.list(context)).put("playlists", playlists));
        });
    }

    @PluginMethod
    public void importLocalPlaylist(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"})
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(call, intent, "localPlaylistPicked");
    }

    @ActivityCallback
    private void localPlaylistPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) {
            call.resolve(new JSObject().put("cancelled", true));
            return;
        }
        Uri uri = result.getData().getData();
        worker.execute(() -> {
            try (java.io.InputStream input = getContext().getContentResolver().openInputStream(uri)) {
                JSONObject imported = LocalPlaylistStore.importArchive(localBundleRoot(getContext()), input);
                JSObject playlist = new JSObject().put("id", imported.optString("id"))
                        .put("name", imported.optString("name"))
                        .put("trackCount", imported.optInt("trackCount")).put("isLocalBundle", true);
                call.resolve(new JSObject().put("cancelled", false).put("playlist", playlist));
            } catch (Exception error) { call.reject(message(error, "导入电脑本地歌单失败")); }
        });
    }

    @PluginMethod
    public void getLocalPlaylistTracks(PluginCall call) {
        String playlistId = call.getString("playlistId", "");
        worker.execute(() -> {
            JSONArray playlists = LocalPlaylistStore.list(localBundleRoot(getContext()));
            for (int index = 0; index < playlists.length(); index++) {
                JSONObject playlist = playlists.optJSONObject(index);
                if (playlistId.equals(playlist.optString("id"))) {
                    call.resolve(new JSObject().put("tracks", localLibraryTracks(playlist.optJSONArray("localTracks"))));
                    return;
                }
            }
            call.reject("本地歌单不存在或文件已不可用");
        });
    }

    @PluginMethod
    public void getLocalFolderTracks(PluginCall call) {
        String folderId = call.getString("folderId", "");
        worker.execute(() -> {
            JSONArray rows = LocalTrackStore.loadFolderIndex(getContext());
            JSONArray selected = new JSONArray();
            JSONArray folders = LocalFolderStore.loadTreeUris(getContext());
            boolean legacyFolder = folders.length() == 1
                    && folderId.equals(LocalFolderStore.folderId(folders.optString(0)));
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row != null && (folderId.equals(row.optString("localFolderId"))
                        || legacyFolder && row.optString("localFolderId").isEmpty())) selected.put(row);
            }
            call.resolve(new JSObject().put("tracks", localLibraryTracks(selected)));
        });
    }

    private JSONArray localLibraryTracks(JSONArray rows) {
        JSONArray result = new JSONArray();
        for (int index = 0; rows != null && index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null) result.put(track(row, getContext()));
        }
        return result;
    }

    private static java.io.File localBundleRoot(Context context) {
        return new java.io.File(context.getFilesDir(), "local-bundles");
    }

    @PluginMethod
    public void scanLocalMusic(PluginCall call) {
        worker.execute(() -> {
            try {
                List<JSONObject> values = LocalFolderStore.scanSelection(getContext(), call.getString("folderId", ""));
                JSONArray tracks = new JSONArray();
                for (JSONObject value : values) tracks.put(track(value, getContext()));
                call.resolve(new JSObject().put("tracks", tracks));
            } catch (Exception error) { call.reject(message(error, "本地音乐扫描失败")); }
        });
    }

    @PluginMethod
    public void getCacheStatus(PluginCall call) {
        AudioCache.Stats stats = AudioCache.stats(getContext());
        call.resolve(cacheStatus(stats));
    }

    @PluginMethod
    public void setCacheEnabled(PluginCall call) {
        AudioCache.setEnabled(getContext(), call.getBoolean("enabled", true));
        call.resolve(cacheStatus(AudioCache.stats(getContext())));
    }

    @PluginMethod
    public void setPlaybackMode(PluginCall call) {
        int mode = (int) longArg(call, "mode", -1);
        if (mode < 0 || mode > 2) { call.reject("播放模式无效"); return; }
        AndroidPlaybackService.setMode(getContext(), mode);
        call.resolve(new JSObject().put("mode", mode));
    }

    @PluginMethod
    public void getArtwork(PluginCall call) {
        ArtworkCache.fetch(getContext(), call.getString("url", ""), new ArtworkCache.Result() {
            @Override public void success(String uri) {
                call.resolve(new JSObject().put("uri", uri));
            }

            @Override public void failure(Exception error) {
                call.reject(message(error, "封面下载失败"), "ARTWORK_UNAVAILABLE", error);
            }
        });
    }

    @PluginMethod
    public void setCacheCapacity(PluginCall call) {
        worker.execute(() -> {
            try {
                long capacity = longArg(call, "capacityMb", AudioCache.DEFAULT_CAPACITY_MIB);
                AudioCache.setCapacityMiB(getContext(), capacity);
                call.resolve(cacheStatus(AudioCache.stats(getContext())));
            } catch (Exception error) { call.reject(message(error, "缓存容量设置失败")); }
        });
    }

    @PluginMethod
    public void setArtworkCacheCapacity(PluginCall call) {
        worker.execute(() -> {
            try {
                ArtworkCache.setCapacityMiB(getContext(), longArg(call, "capacityMb", 128L));
                call.resolve(cacheStatus(AudioCache.stats(getContext())));
            } catch (Exception error) { call.reject(message(error, "封面缓存容量设置失败")); }
        });
    }

    @PluginMethod
    public void cacheTrack(PluginCall call) {
        JSObject track = call.getObject("track");
        if (track == null || track.optLong("id") <= 0) { call.reject("歌曲信息无效"); return; }
        if (!track.optString("localUri", "").trim().isEmpty()
                || !track.optString("localPath", "").trim().isEmpty()) {
            call.resolve(new JSObject().put("cached", true).put("local", true));
            return;
        }
        worker.execute(() -> {
            try {
                long id = track.optLong("id");
                JSONObject location = PlaybackSourceResolver.resolve(getContext(), id);
                boolean queued = AudioCache.enqueueManual(getContext(), new JSONObject(track.toString()), location);
                call.resolve(new JSObject().put("cached", queued || !AudioCache.statusFor(getContext(), id).isEmpty()));
            } catch (Exception error) { call.reject(message(error, "歌曲缓存失败")); }
        });
    }

    @PluginMethod
    public void getCachedTracks(PluginCall call) {
        worker.execute(() -> {
            try {
                JSONArray values = new JSONArray();
                for (JSONObject song : AudioCache.list(getContext())) {
                    long id = song.optLong("id");
                    if (song.optString("trackVolumeKey", "").isEmpty()) song.put("trackVolumeKey", PlaybackVolume.trackKey(song));
                    AudioCache.Entry entry = AudioCache.find(getContext(), id);
                    if (entry != null) song.put("localPath", entry.file.getAbsolutePath()).put("localUri", "file://" + entry.file.getAbsolutePath());
                    song.put("source", "cache");
                    values.put(track(song, getContext()));
                }
                call.resolve(new JSObject().put("tracks", values));
            } catch (Exception error) { call.reject(message(error, "读取缓存歌曲失败")); }
        });
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        worker.execute(() -> {
            try { AudioCache.clear(getContext()); PlaylistCache.clear(getContext()); call.resolve(); }
            catch (Exception error) { call.reject(message(error, "缓存清理失败")); }
        });
    }

    @PluginMethod
    public void getLyricOverlaySettings(PluginCall call) { call.resolve(overlaySettings()); }

    @PluginMethod
    public void updateLyricOverlaySettings(PluginCall call) {
        JSObject values = call.getObject("settings");
        if (values != null) {
            applyOverlaySettings(values);
            if (values.has("enabled")) {
                if (values.optBoolean("enabled")) enableLyricOverlay();
                else disableLyricOverlay();
            }
        }
        call.resolve(overlaySettings());
    }

    @PluginMethod
    public void setLyricOverlayEnabled(PluginCall call) {
        if (call.getBoolean("enabled", false)) enableLyricOverlay();
        else disableLyricOverlay();
        call.resolve(overlaySettings());
    }

    @PluginMethod
    public void showLyricOverlay(PluginCall call) {
        enableLyricOverlay();
        call.resolve(overlaySettings());
    }

    @PluginMethod
    public void hideLyricOverlay(PluginCall call) {
        disableLyricOverlay();
        call.resolve(overlaySettings());
    }

    @PluginMethod
    public void temporaryHideLyricOverlay(PluginCall call) {
        AndroidLyricOverlayService.temporaryHide(getContext(), longArg(call, "durationMs", 30_000L));
        call.resolve(overlaySettings());
    }

    @PluginMethod
    public void restoreLyricOverlay(PluginCall call) {
        AndroidLyricOverlayService.restoreNow(getContext());
        enableLyricOverlay();
        call.resolve(overlaySettings());
    }

    private boolean enableLyricOverlay() {
        Context context = getContext();
        if (!Settings.canDrawOverlays(context)) {
            context.getSharedPreferences("lyric_overlay", Context.MODE_PRIVATE).edit()
                    .putBoolean("pending_enable", true).apply();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return false;
        }
        context.getSharedPreferences("lyric_overlay", Context.MODE_PRIVATE).edit()
                .putBoolean("pending_enable", false).apply();
        LyricOverlaySettings.enabled(context, true);
        if (!AndroidLyricOverlayService.isRunning()) {
            ContextCompat.startForegroundService(context,
                    new Intent(context, AndroidLyricOverlayService.class).setAction(AndroidLyricOverlayService.ACTION_SHOW));
        }
        return true;
    }

    private void disableLyricOverlay() {
        Context context = getContext();
        context.getSharedPreferences("lyric_overlay", Context.MODE_PRIVATE).edit()
                .putBoolean("pending_enable", false).remove("web_hidden_until").apply();
        LyricOverlaySettings.enabled(context, false);
        context.stopService(new Intent(context, AndroidLyricOverlayService.class).setAction(AndroidLyricOverlayService.ACTION_HIDE));
    }

    @PluginMethod
    public void setLyricOffset(PluginCall call) {
        long trackId = longArg(call, "trackId", 0L);
        long offset = longArg(call, "offsetMs", 0L);
        try {
            JSONObject song = lyricOffsetTrack(trackId, call.getObject("track"), currentTrack(getContext()));
            LyricOverlaySettings.lyricOffsetMs(getContext(), song, offset);
            AndroidLyricOverlayService.refreshSettings();
            call.resolve();
        }
        catch (Exception error) { call.reject(message(error, "歌词延迟保存失败")); }
    }

    /** A delayed drag write belongs to the captured recording, even after playback changes. */
    static JSONObject lyricOffsetTrack(long trackId, JSONObject supplied, JSONObject current) throws org.json.JSONException {
        if (trackId <= 0 || supplied != null && supplied.optLong("id") != trackId)
            throw new IllegalArgumentException("歌曲信息无效");
        JSONObject selected = supplied != null ? supplied : current;
        if (selected == null || selected.optLong("id") != trackId) return new JSONObject().put("id", trackId);
        return new JSONObject(selected.toString());
    }

    private JSObject cacheStatus(AudioCache.Stats stats) {
        return new JSObject().put("capacityMb", stats.maxBytes / (1024L * 1024L))
                .put("enabled", stats.enabled)
                .put("usedBytes", stats.bytes).put("trackCount", stats.count)
                .put("libraryBytes", PlaylistCache.bytes(getContext()) + ArtworkCache.bytes(getContext()))
                .put("artworkCapacityMb", ArtworkCache.capacityMiB(getContext()))
                .put("queued", stats.queued).put("downloading", stats.downloading)
                .put("downloadedBytes", stats.downloadedBytes).put("downloadTotalBytes", stats.downloadTotalBytes)
                .put("error", stats.error == null ? "" : stats.error);
    }

    private static JSONObject currentTrack(Context context) {
        String value = context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE)
                .getString("current_track", "");
        if (value == null || value.isEmpty()) return null;
        try { return new JSONObject(value); }
        catch (Exception malformed) { return null; }
    }

    private static long cachedUserId(Context context) {
        String profile = context.getSharedPreferences("vutron_user", Context.MODE_PRIVATE).getString("profile", "");
        try { return profile == null || profile.isEmpty() ? 0 : new JSONObject(profile).optLong("userId", 0); }
        catch (Exception invalid) { return 0; }
    }

    /**
     * Capacitor's PluginCall.getLong only accepts an actual java.lang.Long.
     * JSON numbers sent by JavaScript are commonly decoded as Integer or
     * Double, which otherwise silently falls back to zero (for example the
     * logged-in user's id becomes "用户 ID 无效").
     */
    static long longArg(PluginCall call, String name, long fallback) {
        Object value = call.getData().opt(name);
        if (value instanceof Number) {
            try { return new java.math.BigDecimal(value.toString()).longValueExact(); }
            catch (NumberFormatException | ArithmeticException invalid) { return fallback; }
        }
        if (value instanceof String) {
            try { return Long.parseLong((String) value); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private static void enqueueHomeArtwork(Context context, JSONObject data) {
        for (String key : new String[]{"banners", "playlists", "artists", "albums", "charts", "dailyTracks"}) {
            JSONArray rows = data.optJSONArray(key);
            if (rows == null) continue;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) ArtworkCache.enqueue(context, row.optString("picUrl", row.optString("imageUrl", "")));
            }
        }
        JSONArray tracks = data.optJSONArray("dailyTracks");
        if (tracks != null) for (int i = 0; i < tracks.length(); i++) {
            JSONObject row = tracks.optJSONObject(i);
            if (row == null) continue;
            String cover = row.optString("picUrl", "");
            if (!cover.isEmpty()) ArtworkCache.enqueue(context, cover);
            else {
                JSONObject album = row.optJSONObject("al");
                if (album != null) ArtworkCache.enqueue(context, album.optString("picUrl", ""));
            }
        }
    }

    private static void decorateHomeArtwork(Context context, JSONObject data) {
        for (String key : new String[]{"banners", "playlists", "artists", "albums", "charts", "dailyTracks"}) {
            JSONArray rows = data.optJSONArray(key);
            if (rows == null) continue;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String cover = row.optString("picUrl", row.optString("imageUrl", ""));
                if (!cover.isEmpty()) {
                    try {
                        row.put("picLocalUri", ArtworkCache.resolve(context, cover));
                        if (key.equals("playlists") || key.equals("charts")) row.put("coverLocalUri", ArtworkCache.resolve(context, cover));
                    } catch (org.json.JSONException ignored) { }
                }
            }
        }
    }

    private JSObject lyricsPayload(LyricRepository.Lyrics lyrics) throws org.json.JSONException {
        return new JSObject(LyricPayload.encode(lyrics).toString());
    }

    private JSObject overlaySettings() {
        Context context = getContext();
        JSONObject current = currentTrack(context);
        return new JSObject().put("enabled", LyricOverlaySettings.enabled(context))
                .put("locked", LyricOverlaySettings.locked(context))
                .put("translation", LyricOverlaySettings.translation(context))
                .put("nextLine", LyricOverlaySettings.nextLine(context))
                .put("estimatedWordTiming", LyricOverlaySettings.estimatedWordTiming(context))
                .put("wifiValidation", LyricOverlaySettings.wifiValidation(context))
                .put("fontSize", LyricOverlaySettings.fontSize(context))
                .put("playedColor", LyricOverlaySettings.playedColor(context))
                .put("unplayedColor", LyricOverlaySettings.unplayedColor(context))
                .put("backgroundColor", LyricOverlaySettings.backgroundColor(context))
                .put("shadowColor", LyricOverlaySettings.shadowColor(context))
                .put("shadowEnabled", LyricOverlaySettings.shadowEnabled(context))
                .put("shadowRadius", LyricOverlaySettings.shadowRadius(context))
                .put("touchPassthroughPriority", LyricOverlaySettings.touchPassthroughPriority(context))
                .put("temporaryHidden", AndroidLyricOverlayService.temporaryHiddenRemainingMs(context) > 0)
                .put("temporaryHiddenRemainingMs", AndroidLyricOverlayService.temporaryHiddenRemainingMs(context))
                .put("currentLyricOffsetMs", LyricOverlaySettings.lyricOffsetMs(context, current))
                .put("permissionGranted", Settings.canDrawOverlays(context));
    }

    private void applyOverlaySettings(JSObject values) {
        Context context = getContext();
        if (values.has("enabled")) LyricOverlaySettings.enabled(context, values.optBoolean("enabled"));
        if (values.has("locked")) LyricOverlaySettings.locked(context, values.optBoolean("locked"));
        if (values.has("translation")) LyricOverlaySettings.translation(context, values.optBoolean("translation"));
        if (values.has("nextLine")) LyricOverlaySettings.nextLine(context, values.optBoolean("nextLine"));
        if (values.has("estimatedWordTiming")) LyricOverlaySettings.estimatedWordTiming(context, values.optBoolean("estimatedWordTiming"));
        if (values.has("wifiValidation")) LyricOverlaySettings.wifiValidation(context, values.optBoolean("wifiValidation"));
        if (values.has("fontSize")) LyricOverlaySettings.fontSize(context, values.optInt("fontSize"));
        if (values.has("playedColor")) LyricOverlaySettings.playedColor(context, values.optInt("playedColor"));
        if (values.has("unplayedColor")) LyricOverlaySettings.unplayedColor(context, values.optInt("unplayedColor"));
        if (values.has("backgroundColor")) LyricOverlaySettings.backgroundColor(context, values.optInt("backgroundColor"));
        if (values.has("shadowColor")) LyricOverlaySettings.shadowColor(context, values.optInt("shadowColor"));
        if (values.has("shadowEnabled")) LyricOverlaySettings.shadowEnabled(context, values.optBoolean("shadowEnabled"));
        if (values.has("shadowRadius")) LyricOverlaySettings.shadowRadius(context, values.optInt("shadowRadius"));
        if (values.has("touchPassthroughPriority")) LyricOverlaySettings.touchPassthroughPriority(context, values.optBoolean("touchPassthroughPriority"));
        AndroidLyricOverlayService.refreshSettings();
    }

    @PluginMethod
    public void getPlaybackState(PluginCall call) {
        android.content.SharedPreferences state = getContext().getSharedPreferences("vutron_playback", Context.MODE_PRIVATE);
        long trackId = state.getLong("trackId", 0L);
        boolean playing = state.getBoolean("playing", false);
        String title = state.getString("title", "");
        String artist = state.getString("artist", "");
        long duration = state.getLong("duration", 0L);
        int position = state.getInt("position", 0);
        if (trackId <= 0) {
            trackId = state.getLong("lastTrackId", 0L);
            playing = state.getBoolean("lastPlaying", playing);
            title = title.isEmpty() ? state.getString("lastTitle", "") : title;
            artist = artist.isEmpty() ? state.getString("lastArtist", "") : artist;
            duration = duration <= 0 ? state.getLong("lastDuration", 0L) : duration;
            position = state.getInt("lastPosition", position);
        }
        String error = state.getString("error", "");
        if (trackId > 0 && error != null && !error.isEmpty()) playing = false;
        JSONObject current = currentTrack(getContext());
        JSObject snapshot = new JSObject().put("playing", playing).put("trackId", trackId)
                .put("buffering", state.getBoolean("buffering", false))
                .put("title", title).put("artist", artist).put("duration", duration)
                .put("position", position).put("error", error)
                .put("queueIndex", state.getInt("queueIndex", -1)).put("queueCount", state.getInt("queueCount", 0))
                .put("upNextCount", state.getInt("upNextCount", 0))
                .put("mode", AndroidPlaybackService.getMode(getContext()))
                .put("quality", state.getString("playbackQuality", ""))
                .put("trackVolumePercent", state.getInt("trackVolumePercent", 100))
                .put("playlistVolumePercent", state.getInt("playlistVolumePercent", 100));
        if (current != null && current.optLong("id") == trackId) snapshot.put("track", track(current, getContext()));
        call.resolve(snapshot);
    }

    /** Restore the previous queue through the same local/cache/multi-source path. */
    @PluginMethod
    public void restorePlayback(PluginCall call) {
        worker.execute(() -> {
            try {
                Context context = getContext();
                android.content.SharedPreferences state = context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE);
                long trackId = state.getLong("lastTrackId", state.getLong("trackId", 0L));
                if (trackId <= 0) {
                    call.resolve(new JSObject().put("restored", false));
                    return;
                }
                if (AndroidPlaybackService.isActive()) {
                    call.resolve(new JSObject().put("restored", false));
                    return;
                }
                // A cold UI launch restores metadata and cursor, never unexpectedly starts sound.
                // Explicit resume/notification play still resolves the saved queue normally.
                state.edit().putBoolean("playing", false).putBoolean("lastPlaying", false)
                        .putBoolean("loading", false).apply();
                call.resolve(new JSObject().put("restored", true));
            } catch (Exception error) {
                getContext().getSharedPreferences("vutron_playback", Context.MODE_PRIVATE).edit()
                        .putString("error", message(error, "恢复播放失败")).putBoolean("playing", false).apply();
                call.reject(message(error, "恢复播放失败"));
            }
        });
    }

    @PluginMethod
    public void pause(PluginCall call) {
        Context context = getContext();
        if (AndroidPlaybackService.isActive()) {
            ContextCompat.startForegroundService(context,
                    new Intent(context, AndroidPlaybackService.class).setAction(AndroidPlaybackService.ACTION_PAUSE));
        } else {
            android.content.SharedPreferences state = context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE);
            state.edit().putBoolean("lastPlaying", false).putBoolean("playing", false).apply();
        }
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        Context context = getContext();
        android.content.SharedPreferences state = context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE);
        long trackId = state.getLong("lastTrackId", state.getLong("trackId", 0L));
        if (!AndroidPlaybackService.isActive() && trackId <= 0) {
            call.resolve();
            return;
        }
        ContextCompat.startForegroundService(context,
                new Intent(context, AndroidPlaybackService.class).setAction(AndroidPlaybackService.ACTION_RESUME));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context context = getContext();
        if (AndroidPlaybackService.isActive()) {
            ContextCompat.startForegroundService(context,
                    new Intent(context, AndroidPlaybackService.class).setAction(AndroidPlaybackService.ACTION_STOP));
        } else {
            PlaybackQueue.clear(context);
            context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE).edit()
                    .remove("lastTrackId").remove("lastPosition").remove("lastDuration")
                    .remove("lastTitle").remove("lastArtist").remove("current_track").putBoolean("lastPlaying", false)
                    .putLong("trackId", 0L).putInt("position", 0).putBoolean("playing", false).apply();
        }
        call.resolve();
    }

    private NeteaseApi authenticated() throws Exception {
        String cookie = SessionStore.load(getContext());
        if (cookie.isEmpty()) throw new Exception("请先登录网易云");
        return new NeteaseApi(cookie);
    }

    private static void cacheProfile(Context context, JSONObject account) {
        context.getSharedPreferences("vutron_user", Context.MODE_PRIVATE).edit()
                .putString("profile", account.toString()).apply();
    }

    private static JSObject profile(JSONObject value) {
        return new JSObject().put("userId", value.optLong("userId"))
                .put("nickname", value.optString("nickname", value.optString("userName", "网易云用户")))
                .put("avatarUrl", value.optString("avatarUrl", ""))
                .put("isVip", value.optInt("vipType", 0) > 0);
    }

    private static JSObject playlist(JSONObject value) { return playlist(value, null); }

    private static JSObject playlist(JSONObject value, Context context) {
        String cover = value.optString("coverImgUrl", value.optString("picUrl", ""));
        if (context != null) ArtworkCache.enqueue(context, cover);
        String local = context == null ? value.optString("coverLocalUri", "") : ArtworkCache.resolve(context, cover);
        if (local.isEmpty()) local = value.optString("coverLocalUri", "");
        return new JSObject().put("id", value.optLong("id"))
                .put("name", value.optString("name", "未命名歌单"))
                .put("coverImgUrl", cover)
                .put("coverLocalUri", local)
                .put("trackCount", value.optInt("trackCount", 0));
    }

    private static JSObject track(JSONObject value) { return track(value, null); }

    private static JSObject track(JSONObject value, Context context) {
        JSONArray artists = value.optJSONArray("ar");
        if (artists == null) artists = value.optJSONArray("artists");
        JSArray names = new JSArray();
        if (artists != null) for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            String name = artist == null ? artists.optString(i, "") : artist.optString("name", "");
            if (!name.trim().isEmpty()) names.put(name);
        }
        if (names.length() == 0 && !value.optString("artist", "").trim().isEmpty()) names.put(value.optString("artist"));
        JSONObject album = value.optJSONObject("al");
        if (album == null) album = value.optJSONObject("album");
        String cover = ArtworkSource.coverUrl(value);
        String local = context == null ? value.optString("picLocalUri", "") : ArtworkCache.resolve(context, cover);
        if (local.isEmpty()) local = value.optString("picLocalUri", "");
        JSObject result = new JSObject().put("id", value.optLong("id"))
                .put("name", value.optString("name", "未命名歌曲"))
                .put("duration", value.optLong("dt", value.optLong("duration", 0)))
                .put("picUrl", cover).put("picLocalUri", local)
                .put("artists", names)
                .put("album", album == null ? value.optString("album", "") : album.optString("name", ""));
        for (String key : new String[]{"localUri", "localPath", "source", "trackVolumeKey", "playlistVolumeKey", "playlistVolumeName",
                "metadataTitle", "localFileName", "localFolderId", "localPlaylistId", "cacheSource", "cacheFormat"}) {
            if (value.has(key)) result.put(key, value.optString(key, ""));
        }
        for (String key : new String[]{"cacheBitrate", "bytes"}) if (value.has(key)) result.put(key, value.optLong(key));
        if (value.has("cacheLossless")) result.put("cacheLossless", value.optBoolean("cacheLossless"));
        if (value.has("neteaseId")) result.put("neteaseId", value.optLong("neteaseId"));
        if (value.has("cached")) result.put("cached", value.optBoolean("cached"));
        return result;
    }

    private static String message(Exception error, String fallback) {
        return error.getMessage() == null || error.getMessage().isEmpty() ? fallback : error.getMessage();
    }

    private static String qrPngData(String value) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640);
        Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[640 * 640];
        for (int y = 0; y < 640; y++) {
            for (int x = 0; x < 640; x++) pixels[y * 640 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 640);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new java.io.IOException("二维码图片编码失败");
            return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    @Override protected void handleOnDestroy() {
        synchronized (qrSessions) { qrSessions.clear(); }
        worker.shutdownNow();
        super.handleOnDestroy();
    }
}
