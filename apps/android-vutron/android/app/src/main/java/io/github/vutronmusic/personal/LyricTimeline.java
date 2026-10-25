package io.github.vutronmusic.personal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Timed lyrics with a binary-search cursor; the caller always supplies player time. */
final class LyricTimeline {
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern LEADING = Pattern.compile("^(?:\\[\\d{1,4}:\\d{1,2}(?:[.:]\\d{1,3})?\\])+");
    private static final Pattern ENHANCED = Pattern.compile("<(\\d{1,4}:\\d{1,2}(?:[.:]\\d{1,3})?)>");
    private static final Pattern OFFSET = Pattern.compile("(?im)\\[offset:([+-]?\\d{1,9})\\]");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d{1,10}),(\\d{1,9})\\](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d{1,10}),(\\d{1,9}),\\d+\\)(.*?)(?=\\(\\d{1,10},\\d{1,9},\\d+\\)|$)");
    private static final int MAX_LINES = 12000;

    static final class Word {
        final long start, duration;
        final int first, end;

        Word(long start, long duration, int first, int end) {
            this.start = start; this.duration = duration; this.first = first; this.end = end;
        }
    }

    static final class Line {
        final long time;
        final long duration;
        final String text;
        final List<Word> words;
        private List<Integer> estimatedBoundaries;

        Line(long time, long duration, String text, List<Word> words) {
            this.time = time; this.duration = Math.max(0, duration); this.text = text; this.words = words;
        }

        /** Estimates are a display projection and never replace the provider's word list. */
        float displayCharacters(long position, long fallbackEnd, boolean estimate) {
            if (!words.isEmpty()) return highlightedCharacters(position, fallbackEnd);
            if (!estimate) return position < time ? 0 : text.length();
            long end = duration > 0 ? time + duration : fallbackEnd;
            if (end <= time) end = time + 3000;
            if (position <= time || text.isEmpty()) return 0;
            if (position >= end) return text.length();
            if (estimatedBoundaries == null) estimatedBoundaries = graphemeEnds(text);
            double unit = (double) (position - time) / (end - time) * estimatedBoundaries.size();
            int index = Math.min(estimatedBoundaries.size() - 1, (int) unit);
            int first = index == 0 ? 0 : estimatedBoundaries.get(index - 1);
            return first + (float) (unit - index) * (estimatedBoundaries.get(index) - first);
        }

        /** Fractional UTF-16 character position reached by the current lyric clock. */
        float highlightedCharacters(long position, long fallbackEnd) {
            if (words.isEmpty()) {
                long end = duration > 0 ? time + duration : fallbackEnd;
                if (end <= time) end = time + 3000;
                float fraction = Math.max(0f, Math.min(1f, (float) (position - time) / (end - time)));
                return text.length() * fraction;
            }
            float count = 0;
            for (int index = 0; index < words.size(); index++) {
                Word word = words.get(index);
                if (position < word.start) break;
                if (word.duration <= 0 || position >= word.start + word.duration) count = word.end;
                else {
                    float fraction = (float) (position - word.start) / word.duration;
                    count = word.first + Math.max(0f, Math.min(1f, fraction)) * (word.end - word.first);
                    break;
                }
            }
            return Math.max(0f, Math.min(text.length(), count));
        }
    }

    final List<Line> lines;

    private LyricTimeline(List<Line> lines) {
        lines.sort(Comparator.comparingLong(line -> line.time));
        this.lines = Collections.unmodifiableList(lines);
    }

    static LyricTimeline empty() { return new LyricTimeline(new ArrayList<>()); }

    /** Negative clocks intentionally select no line, so a zero-time first row can be delayed. */
    static long adjustedTime(long playerPosition, long offsetMs) {
        return playerPosition - offsetMs;
    }

    /** Same display grouping as lyricPlayback.ts: code points, marks, flags and joined emoji. */
    static List<Integer> graphemeEnds(String text) {
        List<Integer> ends = new ArrayList<>();
        int previous = -1, regionalCount = 0;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            int type = Character.getType(point);
            boolean regional = point >= 0x1f1e6 && point <= 0x1f1ff;
            boolean extender = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK || point == 0x200d
                    || point >= 0xfe00 && point <= 0xfe0f || point >= 0xe0100 && point <= 0xe01ef
                    || point >= 0x1f3fb && point <= 0x1f3ff || point >= 0xe0020 && point <= 0xe007f;
            if (!ends.isEmpty() && (extender || previous == 0x200d || regional && regionalCount % 2 == 1
                    || previous == 0x0d && point == 0x0a)) ends.set(ends.size() - 1, offset);
            else ends.add(offset);
            regionalCount = regional ? regionalCount + 1 : 0;
            previous = point;
        }
        return ends;
    }

    static LyricTimeline parse(String lrc) {
        List<Line> result = new ArrayList<>();
        if (lrc == null || lrc.length() > 256 * 1024) return new LyricTimeline(result);
        long offset = 0;
        Matcher offsetMatcher = OFFSET.matcher(lrc);
        if (offsetMatcher.find()) offset = Long.parseLong(offsetMatcher.group(1));
        for (String raw : lrc.split("\\r?\\n")) {
            raw = trimEnd(trimStart(raw));
            Matcher leading = LEADING.matcher(raw);
            if (!leading.find()) continue;
            Matcher stamp = STAMP.matcher(leading.group());
            List<Long> times = new ArrayList<>();
            while (stamp.find()) {
                long time = stampTime(stamp, offset);
                if (time >= 0) times.add(time);
            }
            if (times.isEmpty()) continue;
            String content = ENHANCED.matcher(raw.substring(leading.end())).replaceAll("[$1]");
            Matcher inline = STAMP.matcher(content);
            boolean timed = false;
            while (inline.find()) if (stampTime(inline, offset) >= 0) { timed = true; break; }
            if (timed) result.add(timedLine(times.get(0), times.get(times.size() - 1), content, offset));
            else for (long time : times) {
                result.add(new Line(time, 0, content.trim(), Collections.emptyList()));
                if (result.size() >= MAX_LINES) break;
            }
            if (result.size() >= MAX_LINES) break;
        }
        result.sort(Comparator.comparingLong(line -> line.time));
        List<Line> finalized = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            Line line = result.get(i);
            if (line.words.isEmpty()) { finalized.add(line); continue; }
            long end = i + 1 < result.size() ? result.get(i + 1).time : line.time + 3000;
            List<Word> words = new ArrayList<>();
            for (Word word : line.words) {
                long duration = word.duration < 0 ? (end > word.start ? end - word.start : 3000) : word.duration;
                words.add(new Word(word.start, duration, word.first, word.end));
                end = Math.max(end, word.start + duration);
            }
            finalized.add(new Line(line.time, end - line.time, line.text, Collections.unmodifiableList(words)));
        }
        return new LyricTimeline(finalized);
    }

    private static long stampTime(Matcher stamp, long offset) {
        int seconds = Integer.parseInt(stamp.group(2));
        if (seconds >= 60) return -1;
        String fraction = stamp.group(3);
        long millis = fraction == null ? 0 : Integer.parseInt(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
        return Math.max(0, Long.parseLong(stamp.group(1)) * 60000 + seconds * 1000L + millis - offset);
    }

    private static Line timedLine(long start, long initial, String content, long offset) {
        Matcher stamp = STAMP.matcher(content); StringBuilder text = new StringBuilder();
        List<Word> words = new ArrayList<>(); long current = initial; int cursor = 0; boolean valid = true;
        while (stamp.find()) {
            long next = stampTime(stamp, offset); if (next < 0) continue;
            String word = content.substring(cursor, stamp.start()); if (text.length() == 0) word = trimStart(word);
            if (next < current) valid = false;
            if (!word.isEmpty()) { int first = text.length(); text.append(word); words.add(new Word(current, next - current, first, text.length())); }
            current = next; cursor = stamp.end();
        }
        String tail = trimEnd(content.substring(cursor)); if (text.length() == 0) tail = trimStart(tail);
        if (!tail.isEmpty()) { int first = text.length(); text.append(tail); words.add(new Word(current, -1, first, text.length())); }
        else if (!words.isEmpty()) {
            String trimmed = trimEnd(text.toString()); text.setLength(trimmed.length());
            Word last = words.remove(words.size() - 1);
            if (last.first < text.length()) words.add(new Word(last.start, last.duration, last.first, text.length()));
        }
        return new Line(start, 0, text.toString(), valid ? Collections.unmodifiableList(words) : Collections.emptyList());
    }

    private static boolean space(int point) { return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xfeff; }
    private static String trimStart(String text) {
        int index = 0; while (index < text.length() && space(text.codePointAt(index))) index += Character.charCount(text.codePointAt(index));
        return text.substring(index);
    }
    private static String trimEnd(String text) {
        int index = text.length(); while (index > 0 && space(text.codePointBefore(index))) index -= Character.charCount(text.codePointBefore(index));
        return text.substring(0, index);
    }

    static LyricTimeline parseWords(String yrc) {
        List<Line> result = new ArrayList<>();
        if (yrc == null || yrc.length() > 256 * 1024) return new LyricTimeline(result);
        for (String raw : yrc.split("\\r?\\n")) {
            Matcher line = YRC_LINE.matcher(raw);
            if (!line.matches()) continue;
            Matcher word = YRC_WORD.matcher(line.group(3));
            StringBuilder text = new StringBuilder();
            List<Word> words = new ArrayList<>();
            while (word.find()) {
                int first = text.length();
                text.append(word.group(3));
                words.add(new Word(Long.parseLong(word.group(1)), Long.parseLong(word.group(2)), first, text.length()));
            }
            if (text.toString().trim().isEmpty()) continue;
            result.add(new Line(Long.parseLong(line.group(1)), Long.parseLong(line.group(2)),
                    text.toString(), Collections.unmodifiableList(words)));
            if (result.size() >= MAX_LINES) break;
        }
        return new LyricTimeline(result);
    }

    int indexAt(long position) {
        int low = 0, high = lines.size() - 1, result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).time <= position) { result = middle; low = middle + 1; }
            else high = middle - 1;
        }
        return result;
    }

    /** Reject partial word timing instead of letting a short fragment replace full plain lyrics. */
    boolean hasComparableCoverage(LyricTimeline reference) {
        long[] candidate = coverage(), full = reference.coverage();
        if (candidate[0] == 0) return false;
        if (full[0] == 0) return true;
        // Character count survives split/merged lines; time span catches isolated late fragments.
        // Require at least 80% of plain content and span, allowing credits and timing differences.
        return candidate[0] * 5 >= full[0] * 4 && candidate[1] * 5 >= full[1] * 4;
    }

    private long[] coverage() {
        long characters = 0, first = -1, end = 0;
        for (Line line : lines) {
            int content = 0;
            for (int offset = 0; offset < line.text.length();) {
                int point = line.text.codePointAt(offset);
                if (Character.isLetterOrDigit(point)) content++;
                offset += Character.charCount(point);
            }
            if (content == 0) continue;
            characters += content;
            if (first < 0) first = line.time;
            end = Math.max(end, line.time + line.duration);
        }
        return new long[]{characters, first < 0 ? 0 : Math.max(0, end - first)};
    }

    /** Pair once within the same lyric family; never borrow or reuse a nearby row. */
    List<String> pairTranslations(LyricTimeline translations) {
        List<String> paired = new ArrayList<>(Collections.nCopies(lines.size(), ""));
        int source = 0, translated = 0;
        while (source < lines.size() && translated < translations.lines.size()) {
            Line original = lines.get(source), candidate = translations.lines.get(translated);
            if (original.time < candidate.time) { source++; continue; }
            if (candidate.time < original.time) { translated++; continue; }
            int sourceEnd = source + 1, translatedEnd = translated + 1;
            while (sourceEnd < lines.size() && lines.get(sourceEnd).time == original.time) sourceEnd++;
            while (translatedEnd < translations.lines.size()
                    && translations.lines.get(translatedEnd).time == original.time) translatedEnd++;
            // Duplicate timestamps are ambiguous. A missing or ambiguous translation stays blank.
            if (sourceEnd == source + 1 && translatedEnd == translated + 1
                    && !original.text.isEmpty() && !candidate.text.equals(original.text)) {
                paired.set(source, candidate.text);
            }
            source = sourceEnd; translated = translatedEnd;
        }
        return Collections.unmodifiableList(paired);
    }
}
