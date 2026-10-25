package io.github.vutronmusic.personal;

import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * The legacy personal edition's source selection, exposed to the Capacitor
 * adapter. The original embedded runtime owns identity checks and chooses the
 * best validated audio quality; its headers and measured metadata stay intact.
 */
public final class PlaybackSourceResolver {
    private PlaybackSourceResolver() { }

    public static JSONObject resolve(Context context, long trackId) throws IOException {
        return resolve(context, trackId, ignored -> { });
    }

    public static JSONObject resolve(Context context, long trackId, Consumer<String> status) throws IOException {
        if (trackId <= 0) throw new IOException("歌曲 ID 无效");
        Context app = context.getApplicationContext();
        BooleanSupplier onlineAllowed = () -> !LocalOnlyMode.enabled(app);
        JSONObject location = resolveSources(onlineAllowed, () -> {
            status.accept("正在获取网易云播放地址…");
            return fullTrack(new NeteaseApi(SessionStore.load(app), onlineAllowed).songUrl(trackId), "netease");
        }, () -> SourceSettings.autoMatch(app), () -> {
            JSONObject result = EmbeddedSources.songUrl(app, trackId, status);
            return fullTrack(result, result.optString("source", "其它音源"));
        });
        requireOnline(onlineAllowed);
        return EmbeddedSources.playable(app, location);
    }

    interface Source { JSONObject load() throws IOException; }

    /** The gate is re-read between providers; switching mode cannot start a fallback search. */
    static JSONObject resolveSources(BooleanSupplier onlineAllowed, Source official,
            BooleanSupplier autoMatch, Source fallback) throws IOException {
        requireOnline(onlineAllowed);
        IOException officialFailure;
        try {
            JSONObject result = official.load();
            requireOnline(onlineAllowed);
            return result;
        } catch (IOException failure) {
            officialFailure = failure;
        }
        checkInterrupted();
        requireOnline(onlineAllowed);
        if (!autoMatch.getAsBoolean()) throw officialFailure;
        try {
            requireOnline(onlineAllowed);
            JSONObject result = fallback.load();
            requireOnline(onlineAllowed);
            return result;
        } catch (IOException matchingFailure) {
            IOException failure = new IOException("网易云与其它音源均无法提供完整歌曲。网易云："
                    + message(officialFailure) + "；其它音源：" + message(matchingFailure), matchingFailure);
            failure.addSuppressed(officialFailure);
            throw failure;
        }
    }

    private static void requireOnline(BooleanSupplier allowed) throws IOException {
        if (!allowed.getAsBoolean()) throw new IOException("当前为本地模式，仅可使用本地文件和已完成缓存");
    }

    /** Match the full-track rule used by the legacy PlaybackService. */
    static JSONObject fullTrack(JSONObject location, String defaultSource) throws IOException {
        if (location == null) throw new IOException("音源未返回歌曲地址");
        Object trial = location.opt("freeTrialInfo");
        if (trial != null && trial != JSONObject.NULL) throw new IOException("网易云仅返回试听地址");
        String url = validatePlaybackUrl(location.optString("url", ""));
        try {
            location.put("url", url);
            if (location.optString("source", "").trim().isEmpty()) location.put("source", defaultSource);
            return location;
        } catch (JSONException invalid) {
            throw new IOException("音源播放信息无效", invalid);
        }
    }

    /** Only NetEase's legacy CDN URLs have a verified equivalent TLS path. */
    static String validatePlaybackUrl(String value) throws IOException {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) {
            throw new IOException("音源未返回完整播放地址");
        }
        Uri uri = Uri.parse(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) throw new IOException("音源播放地址无效");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return "http".equalsIgnoreCase(uri.getScheme())
                && (host.endsWith(".music.126.net") || host.endsWith(".music.163.com"))
                ? uri.buildUpon().scheme("https").build().toString() : value;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("音源查找已取消");
    }

    private static String message(IOException error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
