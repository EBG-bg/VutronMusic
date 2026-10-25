package io.github.vutronmusic.personal;

import org.json.JSONObject;
import java.net.URI;
import java.util.Locale;

/** Shared artwork selection for lists, playback panels and the system media card. */
final class ArtworkSource {
    private ArtworkSource() { }

    static String coverUrl(JSONObject value) {
        if (value == null) return "";
        String cover = firstUrl(value, "coverImgUrl", "picUrl", "cover");
        if (!cover.isEmpty()) return cover;
        for (String key : new String[]{"al", "album"}) {
            cover = firstUrl(value.optJSONObject(key), "picUrl", "coverImgUrl", "cover");
            if (!cover.isEmpty()) return cover;
        }
        return firstUrl(value, "albumPicUrl");
    }

    private static String firstUrl(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object raw = object.opt(key);
            if (raw instanceof String && isHttpUrl(((String) raw).trim())) return ((String) raw).trim();
        }
        return "";
    }

    static boolean isHttpUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    static String requestUrl(String source) {
        String value = source == null ? "" : source.trim();
        if (!isHttpUrl(value)) return "";
        URI uri = URI.create(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        // Resize only the NetEase image CDN. Other servers and signed URLs stay unchanged.
        if (!host.equals("music.126.net") && !host.endsWith(".music.126.net")) return value;
        int fragmentAt = value.indexOf('#');
        String fragment = fragmentAt < 0 ? "" : value.substring(fragmentAt);
        String base = fragmentAt < 0 ? value : value.substring(0, fragmentAt);
        int queryAt = base.indexOf('?');
        String query = queryAt < 0 ? "" : base.substring(queryAt + 1);
        base = queryAt < 0 ? base : base.substring(0, queryAt);
        base = "https" + base.substring(base.indexOf(':'));
        StringBuilder parameters = new StringBuilder();
        for (String part : query.split("&")) {
            String name = part.split("=", 2)[0];
            if (part.isEmpty() || name.equalsIgnoreCase("param")) continue;
            if (parameters.length() > 0) parameters.append('&');
            parameters.append(part);
        }
        if (parameters.length() > 0) parameters.append('&');
        return base + "?" + parameters + "param=512y512" + fragment;
    }
}
