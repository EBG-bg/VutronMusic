package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

/**
 * One playback path for Vue, notification resume and process restoration.
 * Selection follows the original personal edition: selected/local matched
 * file, verified offline audio, then the official/embedded source resolver.
 */
final class PlaybackRequest {
    private PlaybackRequest() { }

    static Intent prepare(Context context, JSONObject supplied, boolean autoPlay) throws Exception {
        if (supplied == null || supplied.optLong("id") <= 0) throw new IOException("歌曲信息无效");
        JSONObject song = new JSONObject(supplied.toString());
        if (song.optString("trackVolumeKey", "").isEmpty()) {
            song.put("trackVolumeKey", PlaybackVolume.trackKey(song));
        }
        long id = song.optLong("id");
        String source = localSource(song);
        if (source.isEmpty()) {
            JSONObject local = LocalPlaybackResolver.match(context, song);
            if (local != null) {
                song = local;
                song.put("neteaseId", id);
                source = localSource(song);
            }
        }
        JSONObject location = null;
        if (source.isEmpty()) {
            AudioCache.Entry cached = AudioCache.find(context, id);
            if (cached != null) {
                source = Uri.fromFile(cached.file).toString();
                song.put("playbackSource", cached.source).put("playbackBitrate", cached.bitrate)
                        .put("playbackFormat", cached.format).put("playbackLossless", cached.lossless)
                        .put("playbackCached", true);
            } else {
                location = PlaybackSourceResolver.resolve(context, id);
                source = location.getString("url");
                song.put("playbackSource", location.optString("source", "netease"))
                        .put("playbackBitrate", location.optLong("br", location.optLong("bitrate", 0)))
                        .put("playbackFormat", location.optString("type", ""))
                        .put("playbackLossless", location.optBoolean("lossless")
                                || "flac".equalsIgnoreCase(location.optString("type", "")))
                        .put("playbackCached", false);
            }
        } else if (song.optBoolean("cached") || "cache".equals(song.optString("source"))) {
            song.put("playbackSource", song.optString("cacheSource", "离线缓存"))
                    .put("playbackBitrate", song.optLong("cacheBitrate"))
                    .put("playbackFormat", song.optString("cacheFormat", ""))
                    .put("playbackLossless", song.optBoolean("cacheLossless"))
                    .put("playbackCached", true);
        } else {
            song.put("playbackSource", "本地文件").put("playbackBitrate", 0)
                    .put("playbackFormat", "").put("playbackLossless", false).put("playbackCached", false);
        }
        String cover = first(song, "picLocalUri", "coverLocalUri", "picUrl", "coverImgUrl");
        if (cover.isEmpty()) cover = ArtworkSource.coverUrl(song);
        JSONObject headers = location == null ? null : location.optJSONObject("headers");
        return new Intent(context, AndroidPlaybackService.class).setAction(AndroidPlaybackService.ACTION_PLAY)
                .putExtra("url", source).putExtra("trackId", id)
                .putExtra("title", song.optString("name", BuildConfig.APP_DISPLAY_NAME))
                .putExtra("artist", artistText(song)).putExtra("coverUrl", cover)
                .putExtra("track", song.toString()).putExtra("autoPlay", autoPlay)
                .putExtra("cacheLocation", location == null ? "" : location.toString())
                .putExtra("sourceHeaders", headers == null ? "{}" : headers.toString());
    }

    static JSONObject remembered(Context context) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE);
        String raw = prefs.getString("current_track", "");
        if (raw != null && !raw.isEmpty()) return new JSONObject(raw);
        return new JSONObject().put("id", prefs.getLong("lastTrackId", prefs.getLong("trackId", 0L)))
                .put("name", prefs.getString("lastTitle", prefs.getString("title", BuildConfig.APP_DISPLAY_NAME)))
                .put("artist", prefs.getString("lastArtist", prefs.getString("artist", "")));
    }

    private static String localSource(JSONObject song) throws IOException {
        if (song.optBoolean("missingLocal")) throw new IOException("备份未包含音频，请重新授权音乐文件夹或导入原歌单包后播放");
        String value = first(song, "localUri", "localPath");
        if (value.isEmpty()) return "";
        if (value.startsWith("content://")) return value;
        File file = value.startsWith("file://") ? new File(Uri.parse(value).getPath()) : new File(value);
        if (file.isFile()) return Uri.fromFile(file).toString();
        // A cache can be evicted between displaying the list and playback.
        // Resolve the online identity again; never treat a missing local
        // user's recording as an unrelated online song with a generated id.
        if (song.optBoolean("cached") || "cache".equals(song.optString("source"))) return "";
        throw new IOException("本地音频不存在，请重新扫描音乐文件夹");
    }

    static String artistText(JSONObject song) {
        JSONArray artists = song.optJSONArray("artists");
        if (artists == null) artists = song.optJSONArray("ar");
        StringBuilder text = new StringBuilder();
        if (artists != null) for (int i = 0; i < artists.length(); i++) {
            Object value = artists.opt(i);
            String name = value instanceof JSONObject ? ((JSONObject) value).optString("name", "")
                    : value instanceof String ? (String) value : "";
            if (!name.trim().isEmpty()) { if (text.length() > 0) text.append(" / "); text.append(name.trim()); }
        }
        return text.length() > 0 ? text.toString() : song.optString("artist", "");
    }

    private static String first(JSONObject value, String... keys) {
        for (String key : keys) {
            String text = value.optString(key, "").trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }
}
