package io.github.vutronmusic.personal;

import org.json.JSONObject;

import java.util.Locale;

/** Describes measured source metadata without presenting the matcher's FLAC marker as a bitrate. */
final class PlaybackQuality {
    private PlaybackQuality() { }

    static String describe(JSONObject song) {
        if (song == null) return "";
        String source = sourceName(song.optString("playbackSource", ""));
        if (source.isEmpty()) return "";
        String prefix = song.optBoolean("playbackCached") ? "离线 · " : "";
        String format = song.optString("playbackFormat", "").toLowerCase(Locale.ROOT);
        if ("flac".equals(format)) return prefix + source + " · FLAC 无损";
        if (song.optBoolean("playbackLossless")) return prefix + source + " · "
                + (format.isEmpty() || "unknown".equals(format) ? "无损" : format.toUpperCase(Locale.ROOT) + " 无损");
        long bitrate = song.optLong("playbackBitrate");
        if (bitrate > 0) return prefix + source + " · " + Math.round(bitrate / 1000d) + " kbps";
        return prefix + source + ("本地文件".equals(source) ? "" : " · 音质未识别");
    }

    private static String sourceName(String source) {
        switch (source) {
            case "bodian": return "波点";
            case "kuwo": return "酷我";
            case "kugou": return "酷狗";
            case "qq": return "QQ 音乐";
            case "bilibili": return "哔哩哔哩";
            case "migu": return "咪咕";
            case "pyncmd": return "云音乐备用源";
            default: return source;
        }
    }
}
