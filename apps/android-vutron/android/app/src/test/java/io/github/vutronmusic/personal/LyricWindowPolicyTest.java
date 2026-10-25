package io.github.vutronmusic.personal;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricWindowPolicyTest {
    @Test public void touchPriorityUsesTheDevicesActualOpacityLimit() {
        assertEquals(.475f, LyricOverlaySettings.windowOpacity(true, true, .5f), .0001f);
        assertEquals(.76f, LyricOverlaySettings.windowOpacity(true, true, .8f), .0001f);
        assertEquals(0f, LyricOverlaySettings.windowOpacity(true, true, 0f), 0f);
        assertEquals(0f, LyricOverlaySettings.windowOpacity(true, true, Float.NaN), 0f);
        assertEquals(0f, LyricOverlaySettings.windowOpacity(true, true, -1f), 0f);
        assertEquals(0f, LyricOverlaySettings.windowOpacity(true, true, 2f), 0f);
    }

    @Test public void clickThroughOpacityStaysBelowEverySupportedSystemLimit() {
        for (int value = 1; value <= 1000; value++) {
            float limit = value / 1000f;
            float opacity = LyricOverlaySettings.windowOpacity(true, true, limit);
            assertTrue(opacity >= 0f && opacity < limit);
        }
    }

    @Test public void normalDisplayKeepsFullWindowOpacity() {
        assertEquals(1f, LyricOverlaySettings.windowOpacity(true, false, .5f), 0f);
        assertEquals(1f, LyricOverlaySettings.windowOpacity(false, true, .5f), 0f);
    }

    @Test public void automaticRecoveryDoesNotRecreateAPausedOverlay() {
        assertFalse(LyricVisibility.shouldRecoverService(false, false));
        assertTrue(LyricVisibility.shouldRecoverService(true, false));
        assertTrue(LyricVisibility.shouldRecoverService(false, true));
        assertTrue(LyricVisibility.shouldRecoverService(true, true));
    }

    @Test public void explicitPausedPreviewRemainsAvailableWithoutAutomaticRecovery() {
        LyricVisibility state = new LyricVisibility();
        state.visible(false, false, true, 0, 1000, 0);
        assertFalse(state.visible(false, false, true, 3000, 4000, 0));
        assertFalse(LyricVisibility.shouldRecoverService(false, false));
        state.preview(3000);
        assertTrue(state.visible(false, false, true, 3001, 4001, 0));
        assertFalse(state.visible(false, false, true, 6000, 7000, 0));
    }
}
