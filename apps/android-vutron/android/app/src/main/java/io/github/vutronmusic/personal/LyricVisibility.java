package io.github.vutronmusic.personal;

/** Pure visibility policy shared by the renderer ticker and independent window watchdog. */
final class LyricVisibility {
    static final long PAUSE_GRACE_MS = 3000L;
    private long pausedAt = -1L;
    private long previewUntil;

    static boolean shouldRecoverService(boolean playing, boolean loading) { return playing || loading; }

    void preview(long elapsedNow) { previewUntil = elapsedNow + PAUSE_GRACE_MS; }

    boolean visible(boolean playing, boolean loading, boolean hasTrack, long elapsedNow,
                    long wallNow, long hiddenUntil) {
        if (playing || loading) pausedAt = -1L;
        else if (pausedAt < 0) pausedAt = elapsedNow;
        if (hiddenUntil > wallNow) return false;
        if (previewUntil > elapsedNow) return true;
        return hasTrack && (playing || loading || elapsedNow - pausedAt < PAUSE_GRACE_MS);
    }
}
