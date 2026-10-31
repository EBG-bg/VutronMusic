package io.github.vutronmusic.personal;

/** A sampled media clock, projected at display cadence without polling or seeking the player. */
final class LyricFrameClock {
    private long trackId = Long.MIN_VALUE;
    private long sampledPosition = Long.MIN_VALUE;
    private long sampledAt = Long.MIN_VALUE;
    private long anchorPosition;
    private long anchorElapsed;
    private long duration;
    private boolean playing;
    private float rate = 1f;

    void update(long id, long position, long sampleElapsed, boolean advancing, long length, long now) {
        update(id,position,sampleElapsed,advancing,length,now,1f);
    }
    void update(long id, long position, long sampleElapsed, boolean advancing, long length, long now, float speed) {
        float nextRate = Float.isFinite(speed) && speed > 0 ? speed : 1f;
        long positionMs = Math.max(0, position);
        // Old snapshots and post-reboot monotonic timestamps use the observation time once.
        long observed = sampleElapsed > 0 && sampleElapsed <= now ? sampleElapsed : now;
        if (id != trackId || positionMs != sampledPosition || sampleElapsed != sampledAt || advancing != playing || rate != nextRate) {
            trackId = id;
            sampledPosition = positionMs;
            sampledAt = sampleElapsed;
            anchorPosition = positionMs;
            anchorElapsed = observed;
        }
        playing = advancing;
        rate = nextRate;
        duration = Math.max(0, length);
    }

    long positionAt(long elapsed) {
        long position = anchorPosition + (playing ? Math.round(Math.max(0, elapsed - anchorElapsed) * (double)rate) : 0);
        return duration > 0 ? Math.min(duration, position) : position;
    }
}
