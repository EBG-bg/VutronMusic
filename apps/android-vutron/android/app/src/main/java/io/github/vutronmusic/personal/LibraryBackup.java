package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Portable user metadata only. Folder grants, media bytes, account state and paths never leave the app. */
final class LibraryBackup {
    static final String GENERATOR = "vutron-mobile-backup";
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String[] PREFS = {"lyric_overlay", "playback_volume", "audio_cache_settings", "artwork_cache_settings", "audio_sources", "vutron_playback"};
    private static final Set<String> LYRIC_BOOLEANS = new HashSet<>(Arrays.asList("locked", "touch_passthrough_priority", "translation", "wifi_validation", "next_line", "estimated_word_timing", "shadow_enabled"));
    private static final Set<String> LYRIC_INTS = new HashSet<>(Arrays.asList("font_size", "played_color", "unplayed_color", "background_color", "shadow_color", "shadow_radius"));
    private LibraryBackup() { }
    private static File file(Context c) { return new File(c.getFilesDir(), "restored-playlists.json"); }

    static JSONObject read(InputStream input) throws Exception {
        if (input == null) throw new IOException("无法打开备份文件");
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) { if (output.size() + count > MAX_BYTES) throw new IOException("备份文件超过 8 MiB"); output.write(buffer, 0, count); }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }
    static String digest(String text) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    static JSONObject portableTrack(JSONObject row) throws Exception {
        JSONArray artists = row.optJSONArray("artists"); if (artists == null) artists = row.optJSONArray("ar");
        JSONArray names = new JSONArray();
        for (int i = 0; artists != null && i < artists.length(); i++) {
            Object artist = artists.opt(i); String name = artist instanceof JSONObject ? ((JSONObject) artist).optString("name") : String.valueOf(artist);
            if (!name.trim().isEmpty()) names.put(name);
        }
        if (names.length() == 0 && !row.optString("artist").isEmpty()) names.put(row.optString("artist"));
        JSONObject album = row.optJSONObject("al"); if (album == null) album = row.optJSONObject("album");
        String filename = row.optString("localFileName", row.optString("fileName", "")).replace((char) 92, '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        JSONObject result = new JSONObject().put("name", row.optString("name")).put("artists", names)
                .put("album", album == null ? row.optString("album", "") : album.optString("name"))
                .put("duration", Math.max(0, row.optLong("duration", row.optLong("dt", 0)))).put("fileName", filename);
        long remote = row.optLong("neteaseId");
        boolean online = row.optBoolean("onlineOnly");
        if (online && remote == 0) remote = row.optLong("id");
        if (remote > 0) result.put("neteaseId", remote);
        if (online) result.put("onlineOnly", true);
        result.put("key", digest(result.toString()));
        if (row.has("volume")) result.put("volume", row.getInt("volume"));
        if (row.has("lyricOffsetMs")) result.put("lyricOffsetMs", row.getLong("lyricOffsetMs"));
        return result;
    }
    static String fingerprint(JSONObject row) throws Exception {
        JSONObject value = portableTrack(row); value.remove("key"); value.remove("volume"); value.remove("lyricOffsetMs");
        if (!value.optBoolean("onlineOnly")) value.remove("neteaseId");
        return digest(value.toString());
    }
    private static boolean validPreference(String pref, String key, Object value) {
        if ("playback_volume".equals(pref)) return key.matches("track:id:[1-9][0-9]*|track:local:[a-f0-9]{64}|playlist:key:[a-f0-9]{64}") && value instanceof Number && ((Number) value).doubleValue() == ((Number) value).intValue() && ((Number) value).intValue() >= 0 && ((Number) value).intValue() <= 100;
        if ("lyric_overlay".equals(pref)) {
            if (LYRIC_BOOLEANS.contains(key)) return value instanceof Boolean;
            if (key.matches("lyric_offset_ms_[a-f0-9]{64}")) return value instanceof Number && Math.abs(((Number) value).longValue()) <= 30000;
            if (!LYRIC_INTS.contains(key) || !(value instanceof Number)) return false;
            long number = ((Number) value).longValue();
            return "font_size".equals(key) ? number >= 16 && number <= 34 : "shadow_radius".equals(key) ? number >= 0 && number <= 8 : number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
        }
        if ("audio_cache_settings".equals(pref) && key.equals("enabled")) return value instanceof Boolean;
        if ((pref.equals("audio_cache_settings") || pref.equals("artwork_cache_settings")) && key.equals("capacity_mib")) {
            if (!(value instanceof Number)) return false; long number = ((Number) value).longValue();
            return pref.equals("audio_cache_settings") ? number >= 256 && number <= 10240 : number >= 32 && number <= 1024;
        }
        if (pref.equals("audio_sources") && key.equals("auto_match")) return value instanceof Boolean;
        return pref.equals("vutron_playback") && key.equals("mode") && value instanceof Number && ((Number) value).intValue() >= 0 && ((Number) value).intValue() <= 2;
    }
    static JSONObject safePreferences(JSONObject supplied) throws Exception {
        JSONObject result = new JSONObject();
        for (String pref : PREFS) {
            JSONObject values = supplied.optJSONObject(pref), safe = new JSONObject();
            if (values != null) for (Iterator<String> i = values.keys(); i.hasNext();) { String key = i.next(); Object value = values.get(key); if (validPreference(pref, key, value)) safe.put(key, value); }
            result.put(pref, safe);
        }
        return result;
    }
    private static JSONObject load(Context c) throws Exception { return file(c).isFile() ? read(new FileInputStream(file(c))) : new JSONObject().put("collections", new JSONArray()); }
    private static JSONArray allTracks(Context c) throws Exception {
        JSONArray result = LocalTrackStore.loadFolderIndex(c);
        for (JSONArray source : new JSONArray[]{LocalTrackStore.load(c), LocalPlaylistStore.tracks(new File(c.getFilesDir(), "local-bundles"))})
            for (int i = 0; i < source.length(); i++) result.put(source.getJSONObject(i));
        return result;
    }
    static JSONArray list(Context c) throws Exception {
        JSONArray result = new JSONArray(), collections = load(c).getJSONArray("collections");
        for (int i = 0; i < collections.length(); i++) {
            JSONObject item = collections.getJSONObject(i);
            result.put(new JSONObject().put("id", item.getString("id")).put("name", item.getString("name"))
                    .put("trackCount", item.getJSONArray("tracks").length()).put("isLocalBundle", true).put("isRestored", true));
        }
        return result;
    }
    static JSONObject collection(Context c, String id) throws Exception {
        JSONArray rows = load(c).getJSONArray("collections");
        for (int i = 0; i < rows.length(); i++) if (id.equals(rows.getJSONObject(i).optString("id"))) return rows.getJSONObject(i);
        throw new IOException("恢复的歌单不存在");
    }
    static JSONArray tracks(Context c, String id) throws Exception {
        JSONObject collection = collection(c, id); JSONArray saved = collection.getJSONArray("tracks"), source = allTracks(c), result = new JSONArray();
        Map<String, Map<String, JSONObject>> matches = new HashMap<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.getJSONObject(i); String uri = LocalLibraryIds.uri(row); if (uri.isEmpty()) continue;
            matches.computeIfAbsent(fingerprint(row), ignored -> new HashMap<>()).put(uri, row);
        }
        for (int i = 0; i < saved.length(); i++) {
            JSONObject data = saved.getJSONObject(i); String key = data.getString("key");
            Map<String, JSONObject> candidates = matches.get(fingerprint(data));
            JSONObject row = candidates != null && candidates.size() == 1 ? new JSONObject(candidates.values().iterator().next().toString()) : new JSONObject();
            boolean online = data.optBoolean("onlineOnly");
            if (row.length() == 0) row.put("id", online ? data.getLong("neteaseId") : Math.max(1L, Math.abs((long) (id + key).hashCode())))
                    .put("name", data.getString("name")).put("artists", data.getJSONArray("artists")).put("album", data.getString("album"))
                    .put("duration", data.getLong("duration")).put("localFileName", data.optString("fileName"));
            row.remove("localFolderId"); row.remove("trackVolumeKey");
            row.put("localPlaylistId", id).put("localEntryKey", key).put("missingLocal", !online && LocalLibraryIds.uri(row).isEmpty());
            if (row.optBoolean("missingLocal")) row.put("volume", data.optInt("volume", 100)).put("lyricOffsetMs", data.optLong("lyricOffsetMs"));
            if (data.has("neteaseId")) row.put("neteaseId", data.getLong("neteaseId"));
            if (online) row.put("onlineOnly", true);
            SharedPreferences applied = c.getSharedPreferences("backup_linked", Context.MODE_PRIVATE);
            String appliedKey = digest(id + key + PlaybackVolume.trackKey(row) + LyricOverlaySettings.trackIdentity(row));
            if (!row.optBoolean("missingLocal") && !applied.getBoolean(appliedKey, false)) {
                if (data.has("volume") && PlaybackVolume.trackPercent(c, row) != data.getInt("volume")) PlaybackVolume.setTrackPercent(c, row, data.getInt("volume"));
                if (data.has("lyricOffsetMs") && LyricOverlaySettings.lyricOffsetMs(c, row) != data.getLong("lyricOffsetMs")) LyricOverlaySettings.lyricOffsetMs(c, row, data.getLong("lyricOffsetMs"));
                if (!applied.edit().putBoolean(appliedKey, true).commit()) throw new IOException("恢复记录保存失败");
            }
            result.put(row);
        }
        return result;
    }
    private static JSONObject portableCollection(Context c, String id, String name, JSONArray rows, JSONObject orders) throws Exception {
        JSONObject order = orders.optJSONObject(id); String mode = order == null ? "original" : order.optString("mode", "original");
        List<JSONObject> ordered = new ArrayList<>(); for (int i = 0; i < rows.length(); i++) ordered.add(rows.getJSONObject(i));
        if (mode.equals("custom")) {
            Map<String, Integer> ranks = new HashMap<>(); JSONArray keys = order.optJSONArray("keys");
            for (int i = 0; keys != null && i < keys.length(); i++) ranks.put(keys.optString(i), i);
            ordered.sort(Comparator.comparingInt(row -> ranks.getOrDefault(LocalLibraryIds.key(row), Integer.MAX_VALUE)));
        }
        JSONArray portable = new JSONArray();
        for (JSONObject row : ordered) {
            JSONObject track = portableTrack(row).put("volume", row.optBoolean("missingLocal") ? row.optInt("volume", 100) : PlaybackVolume.trackPercent(c, row))
                    .put("lyricOffsetMs", row.optBoolean("missingLocal") ? row.optLong("lyricOffsetMs") : LyricOverlaySettings.lyricOffsetMs(c, row));
            track.put("key", digest(fingerprint(track) + ":" + portable.length()));
            portable.put(track);
        }
        return new JSONObject().put("name", name).put("sortMode", mode).put("volume", PlaybackVolume.playlistPercent(c, id)).put("tracks", portable);
    }
    static JSONObject snapshot(Context c, JSONObject frontend, JSONObject orders) throws Exception {
        JSONObject preferences = new JSONObject();
        for (String pref : PREFS) preferences.put(pref, new JSONObject(c.getSharedPreferences(pref, Context.MODE_PRIVATE).getAll()));
        JSONArray collections = new JSONArray(), folderTracks = LocalTrackStore.loadFolderIndex(c), folders = LocalFolderStore.list(c);
        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.getJSONObject(i); JSONArray rows = new JSONArray();
            for (int j = 0; j < folderTracks.length(); j++) if (LocalLibraryIds.inCollection(folderTracks.getJSONObject(j), folder.getString("id"))) rows.put(folderTracks.getJSONObject(j));
            collections.put(portableCollection(c, folder.getString("id"), folder.getString("name"), rows, orders));
        }
        JSONArray selected = LocalTrackStore.load(c);
        if (selected.length() > 0) collections.put(portableCollection(c, "local:folder", "本地音乐", selected, orders));
        JSONArray bundles = LocalPlaylistStore.list(new File(c.getFilesDir(), "local-bundles"));
        for (int i = 0; i < bundles.length(); i++) { JSONObject item = bundles.getJSONObject(i); collections.put(portableCollection(c, item.getString("id"), item.getString("name"), item.getJSONArray("localTracks"), orders)); }
        JSONArray restored = list(c);
        for (int i = 0; i < restored.length(); i++) { JSONObject item = restored.getJSONObject(i); collections.put(portableCollection(c, item.getString("id"), item.getString("name"), tracks(c, item.getString("id")), orders)); }
        PlaybackQueue.Snapshot queue = PlaybackQueue.load(c);
        if (queue != null) {
            JSONArray rows = new JSONArray(); for (JSONArray lane : new JSONArray[]{queue.songs, queue.upNext}) for (int i = 0; i < lane.length(); i++) rows.put(lane.getJSONObject(i));
            collections.put(portableCollection(c, "backup:queue", "恢复的播放队列", rows, new JSONObject()));
        }
        return new JSONObject().put("format", 1).put("generator", GENERATOR).put("settings", safePreferences(preferences)).put("frontend", safeFrontend(frontend)).put("collections", collections);
    }
    static JSONObject safeFrontend(JSONObject value) throws Exception {
        JSONObject result = new JSONObject();
        Map<String, String> allowed = new HashMap<>();
        allowed.put("vutron:language", "zh|zht|en"); allowed.put("vutron:appearance", "auto|light|dark"); allowed.put("vutron:accent", "#[0-9a-fA-F]{6}");
        allowed.put("vutron:motion-preference", "full|system|reduced"); allowed.put("vutron:playlist-view", "cards|list"); allowed.put("vutron:playlist-columns", "[1-6]");
        allowed.put("vutron:local-track-order", "original|title|artist|album");
        for (Map.Entry<String, String> item : allowed.entrySet()) if (value.opt(item.getKey()) instanceof String && value.getString(item.getKey()).matches(item.getValue())) result.put(item.getKey(), value.getString(item.getKey()));
        return result;
    }
    static JSONObject validate(JSONObject input) throws Exception {
        if (!(input.opt("format") instanceof Number) || ((Number) input.get("format")).doubleValue() != 1.0 || !GENERATOR.equals(input.optString("generator"))) throw new IOException("请选择 Vutron Music 导出的 JSON 备份");
        JSONArray collections = input.optJSONArray("collections"), safe = new JSONArray(); int count = 0;
        if (collections == null || collections.length() > 250) throw new IOException("备份歌单数量无效");
        for (int i = 0; i < collections.length(); i++) {
            JSONObject item = collections.getJSONObject(i); JSONArray tracks = item.getJSONArray("tracks"), cleaned = new JSONArray();
            if ((count += tracks.length()) > 20000 || item.getString("name").length() > 1024) throw new IOException("备份曲目或名称超过限制");
            for (int j = 0; j < tracks.length(); j++) {
                JSONObject row = tracks.getJSONObject(j), track = portableTrack(row);
                track.put("key", digest(fingerprint(track) + ":" + j));
                if (track.optString("name").length() > 2048 || track.optString("album").length() > 2048 || track.optLong("duration") > 86400000
                        || track.optInt("volume", 100) < 0 || track.optInt("volume", 100) > 100 || Math.abs(track.optLong("lyricOffsetMs")) > 30000
                        || track.optBoolean("onlineOnly") && track.optLong("neteaseId") <= 0) throw new IOException("备份曲目资料无效");
                cleaned.put(track);
            }
            String mode = item.optString("sortMode", "original"); if (!Arrays.asList("original", "title", "artist", "album", "custom").contains(mode)) mode = "original";
            safe.put(new JSONObject().put("name", item.getString("name")).put("sortMode", mode).put("tracks", cleaned).put("volume", Math.max(0, Math.min(100, item.optInt("volume", 100)))));
        }
        return new JSONObject().put("collections", safe).put("frontend", safeFrontend(input.optJSONObject("frontend") == null ? new JSONObject() : input.getJSONObject("frontend")))
                .put("settings", safePreferences(input.optJSONObject("settings") == null ? new JSONObject() : input.getJSONObject("settings")));
    }
    static JSONObject restore(Context c, JSONObject input) throws Exception {
        JSONObject safe = validate(input), existing = load(c), orders = new JSONObject(); JSONArray collections = existing.getJSONArray("collections"), incoming = safe.getJSONArray("collections");
        Set<String> hashes = new HashSet<>(); for (int i = 0; i < collections.length(); i++) hashes.add(collections.getJSONObject(i).optString("backupKey"));
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject item = incoming.getJSONObject(i); String hash = digest(item.toString()); if (!hashes.add(hash)) continue;
            String id = "restored:" + UUID.randomUUID(); item.put("id", id).put("backupKey", hash); collections.put(item);
            PlaybackVolume.setPlaylistPercent(c, id, item.getInt("volume"));
            JSONArray keys = new JSONArray(), tracks = item.getJSONArray("tracks");
            for (int j = 0; j < tracks.length(); j++) keys.put(LocalLibraryIds.key(new JSONObject().put("localPlaylistId", id).put("localEntryKey", tracks.getJSONObject(j).getString("key"))));
            orders.put(id, new JSONObject().put("mode", item.getString("sortMode")).put("keys", keys));
        }
        LibraryFiles.writeJson(file(c), existing);
        JSONObject settings = safe.getJSONObject("settings");
        for (String pref : PREFS) {
            SharedPreferences.Editor editor = c.getSharedPreferences(pref, Context.MODE_PRIVATE).edit(); JSONObject values = settings.getJSONObject(pref);
            for (Iterator<String> i = values.keys(); i.hasNext();) {
                String key = i.next(); Object value = values.get(key);
                if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (key.equals("capacity_mib") || key.startsWith("lyric_offset_ms_")) editor.putLong(key, ((Number) value).longValue());
                else editor.putInt(key, ((Number) value).intValue());
            }
            if (!editor.commit()) throw new IOException("备份设置写入失败：" + pref);
        }
        SharedPreferences audio = c.getSharedPreferences("audio_cache_settings", Context.MODE_PRIVATE);
        if (audio.contains("capacity_mib")) AudioCache.setCapacityMiB(c, audio.getLong("capacity_mib", 2048));
        SharedPreferences artwork = c.getSharedPreferences("artwork_cache_settings", Context.MODE_PRIVATE);
        if (artwork.contains("capacity_mib")) ArtworkCache.setCapacityMiB(c, artwork.getLong("capacity_mib", 128));
        AndroidLyricOverlayService.refreshSettings(); AndroidPlaybackService.onVolumeSettingsChanged(c);
        return new JSONObject().put("frontend", safe.getJSONObject("frontend")).put("orders", orders).put("collectionCount", collections.length());
    }
    static void remove(Context c, String id, String entry) throws Exception {
        JSONObject data = load(c); JSONArray kept = new JSONArray(), collections = data.getJSONArray("collections"); boolean found = false;
        for (int i = 0; i < collections.length(); i++) {
            JSONObject item = collections.getJSONObject(i);
            if (!id.equals(item.optString("id"))) { kept.put(item); continue; }
            found = true;
            if (entry == null) continue;
            JSONArray rows = new JSONArray(), tracks = item.getJSONArray("tracks");
            for (int j = 0; j < tracks.length(); j++) if (!entry.equals(tracks.getJSONObject(j).optString("key"))) rows.put(tracks.getJSONObject(j));
            item.put("tracks", rows); kept.put(item);
        }
        if (!found) throw new IOException("恢复的歌单不存在");
        LibraryFiles.writeJson(file(c), data.put("collections", kept));
    }
}
