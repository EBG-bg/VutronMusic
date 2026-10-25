package io.github.vutronmusic.personal;

import org.json.JSONObject;
import java.util.Locale;

/** Serializes the selected coherent lyric family without losing millisecond or word timing. */
final class LyricPayload {
    private LyricPayload() { }

    static JSONObject encode(LyricRepository.Lyrics lyrics) throws org.json.JSONException {
        boolean wordTimed = false, allTimed = true;
        for (LyricTimeline.Line line : lyrics.original.lines) {
            if (!line.words.isEmpty()) wordTimed = true;
            else if (!line.text.isEmpty()) allTimed = false;
        }
        String original = wordTimed && !allTimed ? enhancedLrc(lyrics.original) : originalLrc(lyrics.original);
        wordTimed = wordTimed && allTimed;
        String translation = translationLrc(lyrics);
        return new JSONObject().put("lrc", wordTimed ? "" : original)
                .put("tlyric", wordTimed ? "" : translation)
                .put("yrc", wordTimed ? wordYrc(lyrics.original) : "")
                .put("ytlrc", wordTimed ? translation : "");
    }

    static String originalLrc(LyricTimeline timeline) {
        StringBuilder result = new StringBuilder();
        for (LyricTimeline.Line line : timeline.lines) result.append(stamp(line.time)).append(line.text).append('\n');
        return result.toString();
    }

    private static String enhancedLrc(LyricTimeline timeline) {
        StringBuilder result = new StringBuilder();
        for (LyricTimeline.Line line : timeline.lines) {
            result.append(stamp(line.time));
            if (line.words.isEmpty()) result.append(line.text);
            else for (LyricTimeline.Word word : line.words) {
                result.append(stamp(word.start).replace('[', '<').replace(']', '>'))
                        .append(line.text, word.first, word.end)
                        .append(stamp(word.start + word.duration).replace('[', '<').replace(']', '>'));
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static String translationLrc(LyricRepository.Lyrics lyrics) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lyrics.original.lines.size(); i++) {
            String text = lyrics.translationAt(i);
            if (!text.isEmpty()) result.append(stamp(lyrics.original.lines.get(i).time)).append(text).append('\n');
        }
        return result.toString();
    }

    private static String wordYrc(LyricTimeline timeline) {
        StringBuilder result = new StringBuilder();
        for (LyricTimeline.Line line : timeline.lines) {
            result.append('[').append(line.time).append(',').append(line.duration).append(']');
            for (LyricTimeline.Word word : line.words) {
                result.append('(').append(word.start).append(',').append(word.duration).append(",0)")
                        .append(line.text, word.first, word.end);
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static String stamp(long time) {
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", time / 60000, (time % 60000) / 1000, time % 1000);
    }
}
