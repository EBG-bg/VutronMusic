package io.github.vutronmusic.personal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-file, same-directory CUE. Validation is atomic: callers fall back to the whole recording. */
final class CueParser {
    static final int MAX_BYTES = 256 * 1024, MAX_LINES = 10000, MAX_TRACKS = 500;
    private static final Pattern FILE = Pattern.compile("(?:\"([^\"]+)\"|(\\S+))\\s+\\S+", Pattern.CASE_INSENSITIVE);
    static final class Track {
        final int number;
        final String title, performer;
        final long startMs, durationMs;
        Track(int number, String title, String performer, long start, long duration) {
            this.number = number; this.title = title; this.performer = performer;
            this.startMs = start; this.durationMs = duration;
        }
    }
    static final class Sheet {
        final String file, title, performer;
        final List<Track> tracks;
        Sheet(String file, String title, String performer, List<Track> tracks) {
            this.file = file; this.title = title; this.performer = performer;
            this.tracks = Collections.unmodifiableList(tracks);
        }
    }
    private static final class Entry {
        int number; String title = "", performer = ""; long start = -1, index00 = -1;
    }
    private CueParser() { }

    /** Index only FILE declarations; track/time validation waits for the real audio duration. */
    static String fileNameOnly(String text) {
        if (text == null || text.length() > MAX_BYTES) throw invalid("CUE 文本超限");
        String file = ""; int count = 0, start = 0;
        while (start <= text.length()) {
            if (++count > MAX_LINES) throw invalid("CUE 行数超限");
            int end = text.indexOf('\n',start);
            if (end < 0) end = text.length();
            String line = text.substring(start,end).replace("\uFEFF","").trim();
            int space = 0;
            while (space < line.length() && !Character.isWhitespace(line.charAt(space))) space++;
            if ("FILE".equalsIgnoreCase(line.substring(0,space))) {
                if (!file.isEmpty()) throw invalid("暂不支持多个 FILE，已保留整轨");
                Matcher match = FILE.matcher(line.substring(space).trim());
                if (!match.matches()) throw invalid("FILE 格式无效");
                file = safeFile(match.group(1) == null ? match.group(2) : match.group(1));
            }
            if (end == text.length()) break;
            start = end + 1;
        }
        if (file.isEmpty()) throw invalid("CUE 缺少音频");
        return file;
    }

    /** Identify only related recordings when a sheet is malformed; never tag every sibling. */
    static java.util.Set<String> warningTargets(String cueName, String text) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        if (text != null && text.length() <= MAX_BYTES) {
            for (String raw : text.replace("\uFEFF", "").split("\\r?\\n", MAX_LINES + 1)) {
                String line = raw.trim();
                if (!line.matches("(?i)^FILE\\s+.*")) continue;
                Matcher match = FILE.matcher(line.replaceFirst("(?i)^FILE\\s+", ""));
                if (match.matches()) try {
                    targets.add(safeFile(match.group(1) == null ? match.group(2) : match.group(1)).toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException unsupportedPath) { /* Never infer a sibling from an external path. */ }
            }
        }
        if (targets.isEmpty() && cueName != null) {
            int dot = cueName.lastIndexOf('.');
            if (dot > 0) targets.add("stem:" + cueName.substring(0,dot).toLowerCase(Locale.ROOT));
        }
        return targets;
    }

    static String warningFor(java.util.Map<String,String> warnings, String audioName) {
        String name = audioName.toLowerCase(Locale.ROOT);
        if (warnings.containsKey(name)) return warnings.get(name);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? warnings.getOrDefault("stem:" + name.substring(0,dot), "") : "";
    }

    static Sheet parse(String text, long audioDurationMs) {
        if (text == null || text.length() > MAX_BYTES || audioDurationMs <= 0 || audioDurationMs > Integer.MAX_VALUE)
            throw invalid("CUE 文本或音频时长无效");
        String[] lines = text.replace("\uFEFF", "").split("\\r?\\n", -1);
        if (lines.length > MAX_LINES) throw invalid("CUE 行数超限");
        String file = "", album = "", performer = "";
        List<Entry> entries = new ArrayList<>(); Entry current = null;
        for (String raw : lines) {
            String line = raw.trim(); if (line.isEmpty()) continue;
            int space = 0; while (space < line.length() && !Character.isWhitespace(line.charAt(space))) space++;
            String key = line.substring(0, space).toUpperCase(Locale.ROOT), value = line.substring(space).trim();
            switch (key) {
                case "FILE": {
                    if (!file.isEmpty()) throw invalid("暂不支持多个 FILE，已保留整轨");
                    Matcher match = FILE.matcher(value);
                    if (!match.matches()) throw invalid("FILE 格式无效");
                    file = safeFile(match.group(1) == null ? match.group(2) : match.group(1));
                    break;
                }
                case "TRACK": {
                    String[] parts = value.split("\\s+");
                    if (file.isEmpty() || parts.length != 2 || !"AUDIO".equalsIgnoreCase(parts[1])) throw invalid("仅支持 AUDIO 分轨");
                    int number;
                    try { number = Integer.parseInt(parts[0]); } catch (NumberFormatException e) { throw invalid("轨号无效"); }
                    if (number < 1 || number > 999 || (!entries.isEmpty() && number <= entries.get(entries.size()-1).number)) throw invalid("轨号必须递增");
                    if (entries.size() >= MAX_TRACKS) throw invalid("CUE 曲目数超限");
                    current = new Entry(); current.number = number; entries.add(current); break;
                }
                case "TITLE": if (current == null) album = quoted(value); else current.title = quoted(value); break;
                case "PERFORMER": if (current == null) performer = quoted(value); else current.performer = quoted(value); break;
                case "INDEX": {
                    String[] parts = value.split("\\s+");
                    if (current == null || parts.length != 2) throw invalid("INDEX 格式无效");
                    long time = timeMs(parts[1]);
                    if ("01".equals(parts[0])) { if (current.start >= 0) throw invalid("重复 INDEX 01"); current.start = time; }
                    else if ("00".equals(parts[0])) { if (current.index00 >= 0) throw invalid("重复 INDEX 00"); current.index00 = time; }
                    break;
                }
                case "PREGAP": case "POSTGAP": timeMs(value); break; // Synthetic gaps do not shift the source file.
                case "REM": case "FLAGS": case "CATALOG": case "ISRC": case "CDTEXTFILE": case "SONGWRITER": break;
                default: break;
            }
        }
        if (file.isEmpty() || entries.isEmpty()) throw invalid("CUE 缺少音频或曲目");
        List<Track> result = new ArrayList<>(); long last = -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            long end = i + 1 < entries.size() ? entries.get(i+1).start : audioDurationMs;
            if (entry.start < 0 || entry.start <= last || entry.start >= audioDurationMs || end <= entry.start || end > audioDurationMs
                    || entry.index00 > entry.start || entry.index00 >= 0 && entry.index00 < last) throw invalid("CUE 时间缺失、乱序或超出音频范围");
            result.add(new Track(entry.number, entry.title.isEmpty() ? "Track " + entry.number : entry.title,
                    entry.performer.isEmpty() ? performer : entry.performer, entry.start, end-entry.start));
            last = entry.start;
        }
        return new Sheet(file, album, performer, result);
    }
    static long timeMs(String text) {
        if (text == null || !text.matches("[0-9]{1,7}:[0-9]{2}:[0-9]{2}")) throw invalid("CUE 时间格式无效");
        String[] parts = text.split(":");
        long minutes = Long.parseLong(parts[0]), seconds = Long.parseLong(parts[1]), frames = Long.parseLong(parts[2]);
        long result = (minutes * 60 + seconds) * 1000 + Math.round(frames * 1000.0 / 75);
        if (seconds > 59 || frames > 74 || result > Integer.MAX_VALUE) throw invalid("CUE 时间超限");
        return result;
    }
    static String safeFile(String value) {
        if (value.isEmpty() || value.contains("..") || value.contains(":") || value.contains("/") || value.contains("\\") || value.indexOf('\0') >= 0)
            throw invalid("仅支持当前目录内音频，已保留整轨");
        return value;
    }
    private static String quoted(String value) {
        if (value.startsWith("\"")) { if (value.length() < 2 || !value.endsWith("\"")) throw invalid("CUE 引号未闭合"); return value.substring(1, value.length()-1); }
        return value;
    }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
