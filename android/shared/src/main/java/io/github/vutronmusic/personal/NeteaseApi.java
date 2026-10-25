package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal native transport for VutronMusic's NetEase playlist flow.
 * Protocol source: @neteasecloudmusicapienhanced/api 4.36.1,
 * module/{login_status,login_qr_key,login_qr_check,user_playlist,
 * playlist_detail,song_detail,song_url}.js and util/{request,crypto}.js.
 * Cookie values remain in memory; the Android host owns persistence.
 */
public final class NeteaseApi {
    private static final String WEB = "https://music.163.com";
    private static final String API = "https://interface.music.163.com";
    private static final String WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String APP_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    private static final String RSA_PUBLIC = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_RESPONSE = 16 * 1024 * 1024;
    private final LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
    private final BooleanSupplier onlineAllowed;
    interface Transport { JSONObject request(String path, JSONObject payload, boolean weapi) throws IOException; }
    private final Transport transport;
    private volatile long verifiedUserId;
    private final String deviceId = randomHex(16);
    private final String nuid = randomHex(32);
    private final String wnmcid = randomHex(3) + "." + System.currentTimeMillis() + ".01.0";

    public NeteaseApi(String cookie) {
        this(cookie, () -> true);
    }

    /** The old constructor remains available for explicit login and other hosts. */
    public NeteaseApi(String cookie, BooleanSupplier onlineAllowed) {
        this(cookie, onlineAllowed, null);
    }

    NeteaseApi(String cookie, BooleanSupplier onlineAllowed, Transport transport) {
        if (onlineAllowed == null) throw new IllegalArgumentException("Missing music network policy");
        this.onlineAllowed = onlineAllowed;
        this.transport = transport;
        setCookie(cookie);
    }

    private void requireOnline() throws IOException {
        if (!onlineAllowed.getAsBoolean()) throw new IOException("当前为本地模式，音乐网络请求已关闭");
    }

    public synchronized String getCookie() {
        return cookieHeader(cookies);
    }

    public synchronized void setCookie(String cookie) {
        LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
        if (cookie != null) {
            for (String part : cookie.trim().split(";")) {
                int equals = part.indexOf('=');
                if (equals <= 0) continue;
                String name = part.substring(0, equals).trim();
                String value = part.substring(equals + 1).trim();
                if (name.equalsIgnoreCase("Cookie")) continue;
                if (name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]+") && !isCookieAttribute(name)) {
                    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                        throw new IllegalArgumentException("Cookie 包含无效换行");
                    }
                    parsed.put(name, value);
                }
            }
        }
        cookies.clear();
        cookies.putAll(parsed);
        verifiedUserId = 0;
    }

    /** A verified login profile, not the login/status wrapper returned by the Node API. */
    public JSONObject profile() throws IOException {
        JSONObject response = request("/api/w/nuser/account/get", json(), true);
        requireSuccess(response, "验证登录");
        JSONObject profile = response.optJSONObject("profile");
        if (profile == null || profile.optLong("userId") <= 0) {
            throw new IOException("登录已失效，请重新扫码或导入 Cookie");
        }
        verifiedUserId = profile.optLong("userId");
        return profile;
    }

    public List<JSONObject> playlists(long userId) throws IOException {
        if (userId <= 0) throw new IOException("用户 ID 无效");
        List<JSONObject> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        int offset = 0;
        final int pageSize = 1000;
        while (true) {
            JSONObject response = request("/api/user/playlist", json("uid", userId,
                    "limit", pageSize, "offset", offset, "includeVideo", true), true);
            requireSuccess(response, "读取歌单");
            JSONArray page = response.optJSONArray("playlist");
            if (page == null) throw new IOException("歌单接口未返回歌单列表");
            int added = 0;
            for (int i = 0; i < page.length(); i++) {
                JSONObject playlist = page.optJSONObject(i);
                if (playlist != null && seen.add(playlist.optLong("id"))) {
                    result.add(playlist);
                    added++;
                }
            }
            if (!response.optBoolean("more", false) || page.length() == 0) return result;
            if (added == 0) throw new IOException("歌单分页未前进，请稍后刷新");
            offset += page.length();
        }
    }

    public JSONObject playlistDetail(long playlistId) throws IOException {
        if (playlistId <= 0) throw new IOException("歌单 ID 无效");
        JSONObject response = request("/api/v6/playlist/detail", json("id", playlistId,
                "n", 100000, "s", 0), false);
        requireSuccess(response, "读取歌单歌曲");
        JSONObject playlist = response.optJSONObject("playlist");
        if (playlist == null) throw new IOException("歌单不可访问或已被删除");
        return playlist;
    }

    /** Preserve the playlist order and load tracks omitted from the detail response. */
    public List<JSONObject> tracks(long playlistId) throws IOException { return tracks(playlistDetail(playlistId)); }

    public List<JSONObject> tracks(JSONObject playlist) throws IOException {
        LinkedHashMap<Long, JSONObject> songs = new LinkedHashMap<>();
        addSongs(songs, playlist.optJSONArray("tracks"));
        JSONArray trackIds = playlist.optJSONArray("trackIds");
        if (trackIds == null) return new ArrayList<>(songs.values());
        List<Long> order = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (int i = 0; i < trackIds.length(); i++) {
            JSONObject track = trackIds.optJSONObject(i);
            long id = track == null ? 0 : track.optLong("id");
            if (id > 0) {
                order.add(id);
                if (!songs.containsKey(id)) missing.add(id);
            }
        }
        for (int start = 0; start < missing.size(); start += 500) {
            JSONArray ids = new JSONArray();
            for (int i = start; i < Math.min(start + 500, missing.size()); i++) {
                ids.put(json("id", missing.get(i)));
            }
            JSONObject details = request("/api/v3/song/detail", json("c", ids.toString()), true);
            requireSuccess(details, "读取歌曲详情");
            JSONArray loaded = details.optJSONArray("songs");
            if (loaded == null) throw new IOException("歌曲详情接口未返回歌曲列表");
            addSongs(songs, loaded);
        }
        List<JSONObject> result = new ArrayList<>();
        for (long id : order) {
            JSONObject song = songs.get(id);
            if (song != null) result.add(song);
        }
        return result;
    }

    /** liked is an account relation, not a song/detail field. */
    public Set<Long> likedTracks(long userId) throws IOException {
        if (userId <= 0) throw new IOException("用户 ID 无效");
        JSONObject response = request("/api/song/like/get", json("uid", userId), false);
        requireSuccess(response, "读取喜欢的歌曲");
        JSONArray ids = response.optJSONArray("ids");
        if (ids == null) throw new IOException("喜欢列表接口未返回歌曲 ID");
        Set<Long> result = new LinkedHashSet<>();
        for (int i = 0; i < ids.length(); i++) if (ids.optLong(i) > 0) result.add(ids.optLong(i));
        return result;
    }

    public void likeTrack(long trackId, boolean liked) throws IOException {
        if (trackId <= 0) throw new IOException("歌曲 ID 无效");
        requireSuccess(request("/api/radio/like", json("alg", "itembased", "trackId", trackId,
                "like", liked, "time", "3"), true), liked ? "喜欢歌曲" : "取消喜欢");
    }

    public JSONObject createPlaylist(String name, int privacy) throws IOException {
        name = playlistName(name);
        if (privacy != 0 && privacy != 10) throw new IOException("歌单隐私设置无效");
        JSONObject response = request("/api/playlist/create", json("name", name, "privacy", Integer.toString(privacy), "type", "NORMAL"), true);
        requireSuccess(response, "新建歌单");
        JSONObject playlist = response.optJSONObject("playlist");
        if (playlist == null || playlist.optLong("id") <= 0) throw new IOException("歌单可能已创建，但接口未返回歌单 ID，请先刷新确认");
        return playlist;
    }

    private JSONObject ownPlaylist(long playlistId) throws IOException {
        long userId = verifiedUserId > 0 ? verifiedUserId : profile().optLong("userId");
        JSONObject playlist = playlistDetail(playlistId), creator = playlist.optJSONObject("creator");
        if (creator == null || userId <= 0 || creator.optLong("userId") != userId) throw new IOException("只能管理自己的歌单");
        if (playlist.optInt("specialType") == 5) throw new IOException("默认喜欢歌单请使用歌曲的“喜欢／取消喜欢”管理");
        return playlist;
    }

    public void deletePlaylist(long playlistId) throws IOException {
        ownPlaylist(playlistId);
        requireSuccess(request("/api/playlist/remove", json("ids", new JSONArray().put(playlistId).toString()), true), "删除歌单");
    }

    public void changePlaylistTrack(long playlistId, long trackId, boolean add) throws IOException {
        if (trackId <= 0) throw new IOException("歌曲 ID 无效");
        ownPlaylist(playlistId);
        JSONArray ids = new JSONArray().put(Long.toString(trackId));
        JSONObject payload = json("op", add ? "add" : "del", "pid", playlistId, "trackIds", ids.toString(), "imme", "true");
        JSONObject response = request("/api/playlist/manipulate/tracks", payload, false);
        // Mirror the upstream module's one specific 512 compatibility path.
        if (response.optInt("code") == 512) {
            ids.put(Long.toString(trackId));
            response = request("/api/playlist/manipulate/tracks", json("op", add ? "add" : "del", "pid", playlistId, "trackIds", ids.toString(), "imme", "true"), false);
        }
        requireSuccess(response, add ? "添加到歌单" : "从歌单移除歌曲");
    }

    public void editPlaylist(long playlistId, String name, String description, JSONArray tags) throws IOException {
        name = playlistName(name);
        if (description == null) description = "";
        if (description.length() > 10000 || tags == null || tags.length() > 3) throw new IOException("歌单简介或标签超过限制");
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i).trim();
            if (tag.isEmpty() || tag.length() > 64 || tag.indexOf(';') >= 0) throw new IOException("歌单标签无效");
            if (joined.length() > 0) joined.append(';'); joined.append(tag);
        }
        ownPlaylist(playlistId);
        String descPath = "/api/playlist/desc/update", tagPath = "/api/playlist/tags/update", namePath = "/api/playlist/update/name";
        JSONObject result = request("/api/batch", json(descPath, json("id", playlistId, "desc", description).toString(),
                tagPath, json("id", playlistId, "tags", joined.toString()).toString(), namePath, json("id", playlistId, "name", name).toString()), false);
        requireSuccess(result, "编辑歌单");
        for (String path : new String[]{descPath, tagPath, namePath}) {
            JSONObject item = result.optJSONObject(path);
            if (item == null || item.optInt("code") != 200) throw new IOException("歌单信息未全部更新，部分字段可能已生效，请刷新确认");
        }
    }

    private static String playlistName(String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.length() > 40) throw new IOException("请输入 1–40 个字符的歌单名称");
        return name.trim();
    }

    /** Desktop-style recommendation snapshot used by the Android landing page. */
    public JSONObject homeData(boolean loggedIn) throws IOException {
        JSONObject result = json();
        JSONArray banners = new JSONArray();
        JSONArray playlists = new JSONArray();
        JSONArray artists = new JSONArray();
        JSONArray albums = new JSONArray();
        JSONArray charts = new JSONArray();
        JSONArray dailyTracks = new JSONArray();

        JSONObject bannerResponse = requestOptional("/api/v2/banner/get", json("clientType", "pc"), false);
        JSONArray bannerRows = bannerResponse.optJSONArray("banners");
        if (bannerRows != null) for (int i = 0; i < bannerRows.length(); i++) {
            JSONObject item = bannerRows.optJSONObject(i);
            if (item == null || "广告".equals(item.optString("typeTitle"))) continue;
            String url = item.optString("url", "");
            long id = item.optLong("targetId");
            String typeTitle = item.optString("typeTitle", "推荐");
            String type = typeTitle.contains("歌单") ? "playlist"
                    : typeTitle.contains("专辑") ? "album"
                    : typeTitle.contains("歌曲") || typeTitle.contains("新歌") ? "track"
                    : typeTitle.contains("MV") ? "mv" : "activity";
            banners.put(json("id", id, "picUrl", item.optString("imageUrl", ""), "typeTitle", typeTitle,
                    "type", type, "url", url, "sourceContext", json("id", id)));
        }

        LinkedHashMap<Long, JSONObject> uniquePlaylists = new LinkedHashMap<>();
        JSONObject personalized = requestOptional("/api/personalized/playlist", json("limit", 10, "total", true, "n", 1000), true);
        JSONArray personalizedRows = personalized.optJSONArray("result");
        addPlaylists(uniquePlaylists, personalizedRows);
        if (loggedIn) {
            JSONObject recommended = requestOptional("/api/v1/discovery/recommend/resource", json(), true);
            addPlaylists(uniquePlaylists, recommended.optJSONArray("recommend"));
        }
        for (JSONObject item : uniquePlaylists.values()) playlists.put(playlistSummary(item));

        JSONObject artistResponse = requestOptional("/api/artist/top", json("limit", 30, "offset", 0, "total", true), true);
        JSONArray artistRows = artistResponse.optJSONArray("artists");
        if (artistRows != null) for (int i = 0; i < Math.min(artistRows.length(), 30); i++) {
            JSONObject item = artistRows.optJSONObject(i);
            if (item != null) artists.put(json("id", item.optLong("id"), "name", item.optString("name", ""),
                    "picUrl", first(item, "picUrl", "img1v1Url")));
        }

        JSONObject albumResponse = requestOptional("/api/album/new", json("area", "ALL", "limit", 10), false);
        JSONArray albumRows = albumResponse.optJSONArray("albums");
        if (albumRows != null) for (int i = 0; i < albumRows.length(); i++) albums.put(albumSummary(albumRows.optJSONObject(i)));

        JSONObject chartResponse = requestOptional("/api/toplist/detail", json(), true);
        JSONArray chartRows = chartResponse.optJSONArray("list");
        if (chartRows != null) for (int i = 0; i < Math.min(chartRows.length(), 5); i++) charts.put(playlistSummary(chartRows.optJSONObject(i)));

        if (loggedIn) {
            JSONObject daily = requestOptional("/api/v3/discovery/recommend/songs", json(), true);
            JSONObject data = daily.optJSONObject("data");
            JSONArray rows = data == null ? null : data.optJSONArray("dailySongs");
            if (rows != null) for (int i = 0; i < rows.length(); i++) dailyTracks.put(rows.optJSONObject(i));
        }
        try {
            if (banners.length() + playlists.length() + artists.length() + albums.length() + charts.length() + dailyTracks.length() == 0) {
                throw new IOException("首页接口暂未返回可用内容，请稍后重试");
            }
            return result.put("banners", banners).put("playlists", playlists).put("artists", artists)
                    .put("albums", albums).put("charts", charts).put("dailyTracks", dailyTracks);
        } catch (JSONException impossible) { throw new IOException("Cannot encode home data", impossible); }
    }

    /** Search follows the desktop type IDs and page size so the Vue screen can retain native pagination. */
    public JSONObject search(String category, String keywords, int page) throws IOException {
        int type;
        switch (category) {
            case "tracks": type = 1; break;
            case "albums": type = 10; break;
            case "artists": type = 100; break;
            case "playlists": type = 1000; break;
            case "mvs": type = 1004; break;
            default: throw new IOException("Unsupported search category");
        }
        int safePage = Math.max(1, Math.min(1000, page));
        int offset = (safePage - 1) * 50;
        JSONObject response = request("/api/search/get", json("s", keywords, "type", type, "limit", 50, "offset", offset), false);
        JSONObject body = response.optJSONObject("result");
        if (body == null) throw new IOException("Search response is missing result data");
        JSONArray items;
        int count;
        if ("tracks".equals(category)) {
            JSONArray searchRows = body.optJSONArray("songs");
            JSONArray ids = new JSONArray();
            if (searchRows != null) for (int i = 0; i < searchRows.length(); i++) {
                JSONObject item = searchRows.optJSONObject(i);
                if (item != null && item.optLong("id") > 0) ids.put(json("id", item.optLong("id")));
            }
            JSONObject detail = request("/api/v3/song/detail", json("c", ids.toString()), true);
            items = detail.optJSONArray("songs");
            count = body.optInt("songCount");
        } else if ("albums".equals(category)) {
            JSONArray rows = body.optJSONArray("albums"); items = new JSONArray(); count = body.optInt("albumCount");
            if (rows != null) for (int i = 0; i < rows.length(); i++) items.put(albumSummary(rows.optJSONObject(i)));
        } else if ("artists".equals(category)) {
            JSONArray rows = body.optJSONArray("artists"); items = new JSONArray(); count = body.optInt("artistCount");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item != null) items.put(json("id", item.optLong("id"), "name", item.optString("name", ""),
                        "picUrl", first(item, "picUrl", "img1v1Url")));
            }
        } else if ("playlists".equals(category)) {
            JSONArray rows = body.optJSONArray("playlists"); items = new JSONArray(); count = body.optInt("playlistCount");
            if (rows != null) for (int i = 0; i < rows.length(); i++) items.put(playlistSummary(rows.optJSONObject(i)));
        } else {
            JSONArray rows = body.optJSONArray("mvs"); items = new JSONArray(); count = body.optInt("mvCount");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item != null) items.put(json("id", item.optLong("id"), "name", item.optString("name", ""),
                        "picUrl", item.optString("cover", item.optString("imgurl", "")), "duration", item.optLong("duration")));
            }
        }
        return json("items", items == null ? new JSONArray() : items, "count", count, "page", safePage,
                "nextPage", (long) safePage * 50 < count ? safePage + 1 : 0);
    }

    /** Detail-page track list for an album or artist search result. */
    public List<JSONObject> entityTracks(String category, long id) throws IOException {
        if (id <= 0) throw new IOException("Invalid search result ID");
        JSONObject response;
        String field;
        if ("albums".equals(category)) {
            response = request("/api/v1/album/" + id, json(), true);
            field = "songs";
        } else if ("artists".equals(category)) {
            response = request("/api/v1/artist/" + id, json(), true);
            field = "hotSongs";
        } else throw new IOException("Unsupported detail category");
        JSONArray songs = response.optJSONArray(field);
        if (songs == null) throw new IOException("Search result has no songs");
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song != null && song.optLong("id") > 0) result.add(song);
        }
        return result;
    }

    private static void addPlaylists(Map<Long, JSONObject> target, JSONArray rows) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item != null && item.optLong("id") > 0) target.putIfAbsent(item.optLong("id"), item);
        }
    }

    private static JSONObject playlistSummary(JSONObject item) throws IOException {
        if (item == null) return new JSONObject();
        return json("id", item.optLong("id"), "name", item.optString("name", ""),
                "picUrl", first(item, "coverImgUrl", "picUrl"), "coverImgUrl", first(item, "coverImgUrl", "picUrl"),
                "trackCount", item.optInt("trackCount"), "copywriter", item.optString("copywriter", ""),
                "creatorUserId", item.optLong("creatorUserId", item.optJSONObject("creator") == null ? 0 : item.optJSONObject("creator").optLong("userId")),
                "subscribed", item.optBoolean("subscribed"), "privacy", item.optInt("privacy"), "description", item.optString("description"),
                "tags", item.optJSONArray("tags") == null ? new JSONArray() : item.optJSONArray("tags"), "specialType", item.optInt("specialType"));
    }

    private static JSONObject albumSummary(JSONObject item) throws IOException {
        if (item == null) return new JSONObject();
        JSONArray artists = new JSONArray();
        JSONArray source = item.optJSONArray("artists");
        if (source != null) for (int i = 0; i < source.length(); i++) {
            JSONObject artist = source.optJSONObject(i);
            if (artist != null) artists.put(json("id", artist.optLong("id"), "name", artist.optString("name", "")));
        }
        return json("id", item.optLong("id"), "name", item.optString("name", ""),
                "picUrl", first(item, "picUrl", "blurPicUrl"), "artists", artists);
    }

    private static String first(JSONObject value, String one, String two) {
        String result = value.optString(one, "");
        return result.isEmpty() ? value.optString(two, "") : result;
    }

    /** A failing home section must not hide unrelated recommendations. */
    private JSONObject requestOptional(String path, JSONObject payload, boolean weapi) {
        try { return request(path, payload, weapi); }
        catch (IOException unavailable) { return new JSONObject(); }
    }

    public JSONObject songUrl(long id) throws IOException {
        if (id <= 0) throw new IOException("歌曲 ID 无效");
        // Keep the official request aligned with the desktop plugin.  The
        // desktop path asks the NetEase endpoint for its highest available
        // bitrate (999000) before it decides whether the account is allowed
        // to play the returned item.  This does not bypass membership checks:
        // the server still returns `freeTrialInfo`/a null URL when the account
        // lacks the entitlement.
        JSONObject response = request("/api/song/enhance/player/url",
                json("ids", "[\"" + id + "\"]", "br", 999000), false);
        requireSuccess(response, "获取播放地址");
        JSONArray data = response.optJSONArray("data");
        JSONObject item = data == null ? null : data.optJSONObject(0);
        if (item == null || item.isNull("url") || item.optString("url").trim().isEmpty()) {
            throw new IOException("此歌曲当前无法播放，可能需要会员、购买或已下架");
        }
        String url = item.optString("url");
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new IOException("音乐平台返回了无效播放地址");
        }
        // The official legacy endpoint still returns http CDN URLs. Android 9+
        // blocks them under this app's cleartext policy; the same CDN path supports TLS.
        if (url.startsWith("http://")) {
            try {
                item.put("url", "https://" + url.substring(7));
            } catch (JSONException exception) {
                throw new IOException("播放地址编码失败", exception);
            }
        }
        return item;
    }

    public String qrKey() throws IOException {
        JSONObject response = request("/api/login/qrcode/unikey", json("type", 3), false);
        requireSuccess(response, "创建登录二维码");
        String key = response.optString("unikey");
        if (key.isEmpty()) throw new IOException("二维码接口未返回登录标识");
        return key;
    }

    public JSONObject qrCheck(String key) throws IOException {
        if (key == null || key.trim().isEmpty()) throw new IOException("二维码已失效，请重新生成");
        JSONObject response = request("/api/login/qrcode/client/login", json("key", key, "type", 3), false);
        int code = response.optInt("code");
        if (code < 800 || code > 803) requireSuccess(response, "检查扫码登录");
        return response;
    }

    public String lyrics(long id) throws IOException {
        JSONObject response = lyricData(id);
        JSONObject lrc = response.optJSONObject("lrc");
        return lrc == null ? "" : lrc.optString("lyric");
    }

    /** Returns the authorized LRC, translation, and word-timed lyric payload. */
    public JSONObject lyricData(long id) throws IOException {
        JSONObject response = request("/api/song/lyric/v1", json("id", id, "cp", false,
                "tv", 0, "lv", 0, "rv", 0, "kv", 0, "yv", 0, "ytv", 0, "yrv", 0), false);
        requireSuccess(response, "读取歌词");
        return response;
    }

    private JSONObject request(String path, JSONObject payload, boolean weapi) throws IOException {
        requireOnline();
        if (transport != null) return transport.request(path, payload, weapi);
        HttpURLConnection connection = null;
        try {
            LinkedHashMap<String, String> effective;
            synchronized (this) {
                effective = new LinkedHashMap<>(cookies);
            }
            putDefault(effective, "os", "pc");
            putDefault(effective, "osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
            putDefault(effective, "appver", "3.1.17.204416");
            putDefault(effective, "channel", "netease");
            putDefault(effective, "deviceId", deviceId);
            putDefault(effective, "__remember_me", "true");
            putDefault(effective, "_ntes_nuid", nuid);
            putDefault(effective, "_ntes_nnid", nuid + "," + System.currentTimeMillis());
            putDefault(effective, "WNMCID", wnmcid);
            putDefault(effective, "WEVNSM", "1.0.0");
            payload.put("e_r", false);
            String form;
            String requestCookies;
            if (weapi) {
                payload.put("csrf_token", decode(effective.getOrDefault("__csrf", "")));
                byte[] key = new byte[16];
                String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                for (int i = 0; i < key.length; i++) key[i] = (byte) alphabet.charAt(RANDOM.nextInt(alphabet.length()));
                String first = Base64.getEncoder().encodeToString(aes(payload.toString().getBytes(StandardCharsets.UTF_8),
                        "AES/CBC/PKCS5Padding", "0CoJUm6Qyw8W8jud".getBytes(StandardCharsets.UTF_8), true));
                String params = Base64.getEncoder().encodeToString(aes(first.getBytes(StandardCharsets.UTF_8),
                        "AES/CBC/PKCS5Padding", key, true));
                byte[] reversed = new byte[key.length];
                for (int i = 0; i < key.length; i++) reversed[i] = key[key.length - 1 - i];
                Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
                rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(RSA_PUBLIC))));
                form = "params=" + encode(params) + "&encSecKey=" + hex(rsa.doFinal(reversed));
                requestCookies = cookieHeader(effective);
            } else {
                JSONObject header = json();
                // Match @neteasecloudmusicapienhanced/api's eapi header.  NMTID
                // may exist in an imported desktop cookie, but the desktop
                // implementation deliberately does not copy it into this
                // signed header; keeping it out avoids making the Android
                // request differ because of a stale tracking cookie.
                String[] fields = {"osver", "deviceId", "os", "appver", "channel", "MUSIC_U", "MUSIC_A"};
                for (String field : fields) {
                    if (effective.containsKey(field)) header.put(field, decode(effective.get(field)));
                }
                header.put("versioncode", "140");
                header.put("mobilename", "");
                header.put("buildver", Long.toString(System.currentTimeMillis() / 1000));
                header.put("resolution", "1920x1080");
                header.put("__csrf", decode(effective.getOrDefault("__csrf", "")));
                header.put("requestId", System.currentTimeMillis() + "_" + RANDOM.nextInt(10000));
                payload.put("header", header);
                String text = payload.toString();
                java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
                String digest = hex(md5.digest(("nobody" + path + "use" + text + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
                String input = path + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest;
                form = "params=" + hex(aes(input.getBytes(StandardCharsets.UTF_8), "AES/ECB/PKCS5Padding",
                        "e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8), false)).toUpperCase(Locale.ROOT);
                LinkedHashMap<String, String> headerCookies = new LinkedHashMap<>();
                java.util.Iterator<String> names = header.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    headerCookies.put(name, encode(header.optString(name)));
                }
                requestCookies = cookieHeader(headerCookies);
            }
            String address = (weapi ? WEB + "/weapi/" : API + "/eapi/") + path.substring(5);
            requireOnline();
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            connection.setRequestProperty("User-Agent", weapi ? WEB_UA : APP_UA);
            connection.setRequestProperty("Referer", WEB + "/");
            connection.setRequestProperty("Cookie", requestCookies);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setDoOutput(true);
            byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            requireOnline();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            mergeResponseCookies(connection.getHeaderFields());
            if (status >= 300 && status < 400) throw new IOException("音乐接口发生重定向，请稍后重试（HTTP " + status + "）");
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (raw == null) throw new IOException("音乐接口返回空响应（HTTP " + status + "）");
            String body;
            try (InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_RESPONSE) throw new IOException("音乐接口响应超过大小限制");
                    output.write(buffer, 0, count);
                }
                body = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject result;
            try {
                result = new JSONObject(body);
            } catch (JSONException malformed) {
                throw new IOException("音乐接口未返回有效数据（HTTP " + status + "）", malformed);
            }
            if (status >= 400) throw new IOException(apiMessage(result, "音乐接口请求失败") + "（HTTP " + status + "）");
            if (result.optInt("code") == 301) throw new IOException("登录已失效，请重新扫码或导入 Cookie");
            return result;
        } catch (JSONException | GeneralSecurityException exception) {
            throw new IOException("音乐请求编码失败", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void mergeResponseCookies(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(header.getKey()) || header.getValue() == null) continue;
            for (String line : header.getValue()) {
                String first = line.split(";", 2)[0];
                int equals = first.indexOf('=');
                if (equals <= 0) continue;
                String name = first.substring(0, equals).trim();
                String value = first.substring(equals + 1).trim();
                if (value.isEmpty() || line.toLowerCase(Locale.ROOT).contains("max-age=0")) cookies.remove(name);
                else cookies.put(name, value);
            }
        }
    }

    private static JSONObject json(Object... pairs) throws IOException {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        } catch (JSONException exception) {
            throw new IOException("请求参数无效", exception);
        }
        return result;
    }

    private static void addSongs(Map<Long, JSONObject> target, JSONArray songs) {
        if (songs == null) return;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song != null && song.optLong("id") > 0) target.put(song.optLong("id"), song);
        }
    }

    private static void requireSuccess(JSONObject response, String action) throws IOException {
        int code = response.optInt("code");
        if (code != 200) throw new IOException(apiMessage(response, action + "失败") + "（" + code + "）");
    }

    private static String apiMessage(JSONObject response, String fallback) {
        String message = response.optString("message");
        if (message.isEmpty()) message = response.optString("msg");
        return message.isEmpty() ? fallback : message;
    }

    private static byte[] aes(byte[] input, String transformation, byte[] key, boolean withIv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(transformation);
        SecretKeySpec secret = new SecretKeySpec(key, "AES");
        if (withIv) cipher.init(Cipher.ENCRYPT_MODE, secret,
                new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.UTF_8)));
        else cipher.init(Cipher.ENCRYPT_MODE, secret);
        return cipher.doFinal(input);
    }

    private static void putDefault(Map<String, String> target, String key, String value) {
        if (!target.containsKey(key) || target.get(key).isEmpty()) target.put(key, value);
    }

    private static String cookieHeader(Map<String, String> source) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> item : source.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(item.getKey()).append('=').append(item.getValue());
        }
        return result.toString();
    }

    private static boolean isCookieAttribute(String name) {
        return name.equalsIgnoreCase("Path") || name.equalsIgnoreCase("Domain")
                || name.equalsIgnoreCase("Expires") || name.equalsIgnoreCase("Max-Age")
                || name.equalsIgnoreCase("SameSite");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String decode(String value) {
        if (value.indexOf('%') < 0) return value;
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException invalid) {
            return value;
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return hex(value);
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }
}
