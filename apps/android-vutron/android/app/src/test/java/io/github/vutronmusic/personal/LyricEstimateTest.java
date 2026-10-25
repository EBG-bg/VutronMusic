package io.github.vutronmusic.personal;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class LyricEstimateTest {
    @Test public void unicodeDisplayUnitsKeepMarksEmojiAndFlagsTogether() {
        String text = "A\u0301😀👩‍👩‍👧‍👦🇨🇳中";
        assertEquals(5, LyricTimeline.graphemeEnds(text).size());
        assertEquals(Arrays.asList(2, 4, 15, 19, 20), LyricTimeline.graphemeEnds(text));
        LyricTimeline.Line line = new LyricTimeline.Line(1000, 0, text, Collections.emptyList());
        assertEquals(4f, line.displayCharacters(3000, 6000, true), .0001f);
        assertEquals(20f, line.displayCharacters(6000, 6000, true), .0001f);
        assertTrue(line.words.isEmpty());
    }

    @Test public void disabledEstimateUsesWholeLineAndTogglingIsImmediate() {
        LyricTimeline.Line line = new LyricTimeline.Line(1000, 0, "ABCD", Collections.emptyList());
        assertEquals(0f, line.displayCharacters(999, 5000, false), 0f);
        assertEquals(4f, line.displayCharacters(2000, 5000, false), 0f);
        assertEquals(1f, line.displayCharacters(2000, 5000, true), 0f);
        assertEquals(4f, line.displayCharacters(2000, 5000, false), 0f);
    }

    @Test public void realProviderWordsAreNeverEstimatedOrReplaced() {
        LyricTimeline.Line line = new LyricTimeline.Line(1000, 4000, "AB",
                Collections.singletonList(new LyricTimeline.Word(2000, 1000, 0, 2)));
        assertEquals(0f, line.displayCharacters(1500, 9000, true), 0f);
        assertEquals(1f, line.displayCharacters(2500, 9000, true), 0f);
        assertEquals(line.highlightedCharacters(2500, 9000), line.displayCharacters(2500, 9000, false), 0f);
        assertEquals(1, line.words.size());
        assertEquals(2000, line.words.get(0).start);
    }

    @Test public void lastLineUsesTrackEndAndBadEndUsesThreeSecondFallback() {
        LyricTimeline.Line line = new LyricTimeline.Line(1000, 0, "ABCD", Collections.emptyList());
        assertEquals(2f, line.displayCharacters(5000, 9000, true), .0001f);
        assertEquals(2f, line.displayCharacters(2500, 1000, true), .0001f);
        assertEquals(0f, line.displayCharacters(-100, 9000, true), 0f);
        assertEquals(4f, line.displayCharacters(10000, 9000, true), 0f);
    }
}
