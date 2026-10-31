package io.github.vutronmusic.personal;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrefetchedSourceRetryTest {
    @Test public void expiredPrefetchCanResolveFreshExactlyOnceAndFreshFailureStops() {
        PrefetchedSourceRetry retry=new PrefetchedSourceRetry();
        assertTrue(retry.claim(10,10,true,true));
        assertFalse(retry.claim(10,10,true,true)); // Duplicate error from the same released player.
        assertFalse(retry.claim(12,12,false,true)); // Fresh resolution never retries recursively.
        assertTrue(retry.claim(13,13,true,true)); // A later selection gets an independent attempt.
    }
    @Test public void switchingTrackOrGoingOfflineDoesNotStartAResolution() {
        PrefetchedSourceRetry retry=new PrefetchedSourceRetry();
        assertFalse(retry.claim(11,10,true,true));
        assertFalse(retry.claim(11,11,true,false));
        assertFalse(retry.claim(11,11,false,true));
        assertTrue(retry.claim(11,11,true,true)); // Rejected checks did not consume the attempt.
    }
}
