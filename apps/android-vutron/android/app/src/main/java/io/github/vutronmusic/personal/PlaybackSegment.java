package io.github.vutronmusic.personal;

import org.json.JSONObject;

/** A bounded virtual track; all public positions are relative to its start. */
final class PlaybackSegment {
    final long start, length;
    final boolean cue;
    private PlaybackSegment(long start, long length, boolean cue) { this.start = start; this.length = length; this.cue = cue; }
    static PlaybackSegment of(JSONObject row, long sourceDuration) {
        boolean cue = row != null && row.optInt("cueTrackNo") > 0;
        if (!cue) return new PlaybackSegment(0, Math.max(0, sourceDuration), false);
        long start = row.optLong("cueOffsetMs", -1), length = row.optLong("cueDurationMs", -1);
        if (start < 0 || length <= 0 || start >= sourceDuration || sourceDuration > Integer.MAX_VALUE)
            throw new IllegalArgumentException("CUE 分轨已失效，请重新扫描文件夹");
        return new PlaybackSegment(start, Math.min(length, sourceDuration-start), true);
    }
    int relative(long absolute) { return (int)Math.max(0, Math.min(length, absolute-start)); }
    boolean needsInitialSeek(int relative) { return start > 0 || relative > 0; }
    int absolute(long relative) { return (int)(start + Math.max(0, Math.min(Math.max(0,length-1), relative))); }
    static String suffix(JSONObject row) { return row != null && row.optInt("cueTrackNo") > 0
            ? "\ncue:" + row.optLong("cueOffsetMs") + ":" + row.optInt("cueTrackNo") : ""; }
}
