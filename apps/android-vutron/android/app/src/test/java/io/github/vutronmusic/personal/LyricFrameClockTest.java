package io.github.vutronmusic.personal;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricFrameClockTest {
    @Test public void framesAdvanceBetweenThePlayersOneSecondSnapshots() {
        LyricFrameClock clock = new LyricFrameClock();
        clock.update(7, 1000, 2000, true, 10000, 2090);
        LyricTimeline.Line line = LyricTimeline.parseWords("[0,10000](0,10000,0)Smooth lyrics").lines.get(0);
        float previous = -1;
        for (int frame = 0; frame < 60; frame++) {
            long now = 2090 + Math.round(frame * 1000d / 60);
            float progress = line.displayCharacters(clock.positionAt(now), 10000, false);
            assertTrue("progress advances at every sampled display frame", progress > previous);
            previous = progress;
        }
        assertEquals(1090, clock.positionAt(2090));
    }

    @Test public void metadataPollingDoesNotResetTheMediaAnchor() {
        LyricFrameClock clock = new LyricFrameClock();
        clock.update(7, 1000, 0, true, 10000, 2000);
        clock.update(7, 1000, 0, true, 10000, 2100);
        assertEquals(1250, clock.positionAt(2250));
        clock.update(7, 2000, 3000, true, 10000, 3090);
        assertEquals(2090, clock.positionAt(3090));
    }

    @Test public void pauseSeekResumeAndTrackChangeTakeEffectAtTheirSamples() {
        LyricFrameClock clock = new LyricFrameClock();
        clock.update(7, 1000, 2000, true, 10000, 2000);
        clock.update(7, 1500, 2500, false, 10000, 2510);
        assertEquals(1500, clock.positionAt(5000));
        clock.update(7, 8000, 5000, false, 10000, 5000);
        assertEquals(8000, clock.positionAt(6000));
        clock.update(7, 8000, 6000, true, 10000, 6000);
        assertEquals(8100, clock.positionAt(6100));
        clock.update(8, 0, 6200, true, 20000, 6200);
        assertEquals(100, clock.positionAt(6300));
    }

    @Test public void bufferingSnapshotsHoldTheClockUntilPlaybackContinues() {
        LyricFrameClock clock = new LyricFrameClock();
        clock.update(7, 1000, 2000, true, 10000, 2000);
        clock.update(7, 1100, 2100, false, 10000, 2100);
        assertEquals(1100, clock.positionAt(2990));
        clock.update(7, 1100, 3000, false, 10000, 3000);
        assertEquals(1100, clock.positionAt(3990));
        clock.update(7, 1100, 4000, true, 10000, 4000);
        assertEquals(1117, clock.positionAt(4017));
    }

    @Test public void durationAndPostRebootMonotonicBoundsAreRespected() {
        LyricFrameClock clock = new LyricFrameClock();
        clock.update(7, 9990, 2000, true, 10000, 2000);
        assertEquals(10000, clock.positionAt(2050));
        clock.update(7, 100, 999999, true, 10000, 3000);
        assertEquals(120, clock.positionAt(3020));
    }
}
