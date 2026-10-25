package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The personal desktop export's original manifest.json/audio archive format. */
final class LocalPlaylistStore {
    private static final long MAX_TOTAL = 2L * 1024 * 1024 * 1024;
    private static final long MAX_FILE = 4L * 1024 * 1024 * 1024;
    private static final int MAX_MANIFEST = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20000;

    private LocalPlaylistStore() { }

    static JSONObject importArchive(File root, InputStream input) throws Exception {
        return importArchive(root, input, false, "桌面本地歌单");
    }

    static JSONObject importArchive(File root, InputStream input, boolean audioOnly, String title) throws Exception {
        if (input == null) throw new IOException("无法打开压缩包");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建本地歌单目录");
        String bundleId = UUID.randomUUID().toString();
        File directory = new File(root, ".importing-" + bundleId);
        if (!directory.mkdirs()) throw new IOException("无法创建歌单目录");
        boolean complete = false;
        JSONArray audioTracks = new JSONArray();
        java.util.List<String> lyricFiles = new java.util.ArrayList<>();
        java.util.Set<String> extracted = new java.util.HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            long total = 0; int entries = 0;
            byte[] buffer = new byte[32768]; ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                LibraryTaskRunner.checkCancelled();
                if (++entries > MAX_ENTRIES) throw new IOException("压缩包文件数量超过限制");
                File target = resolveEntry(directory, entry.getName());
                String normalized = entry.getName().replace((char) 92, '/');
                if (normalized.equalsIgnoreCase("manifest.json")) target = new File(directory, "manifest.json");
                if (entry.isDirectory()) continue;
                if (!extracted.add(target.getCanonicalPath())) throw new IOException("压缩包含有重复的文件路径");
                boolean audioEntry = isAudioFile(normalized), lyricEntry = isLyricFile(normalized);
                boolean keep = !audioOnly || audioEntry || lyricEntry;
                if (keep) {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建音频目录");
                }
                long written = 0;
                try (FileOutputStream output = keep ? new FileOutputStream(target) : null) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        LibraryTaskRunner.checkCancelled(); written += count; total += count;
                        if (written > MAX_FILE || total > MAX_TOTAL) throw new IOException("解压内容超过限制");
                        if (lyricEntry && written > 256 * 1024) throw new IOException("同名歌词文件超过 256 KiB");
                        if (target.getName().equalsIgnoreCase("manifest.json") && written > MAX_MANIFEST) throw new IOException("歌单清单超过限制");
                        if (output != null) output.write(buffer, 0, count);
                    }
                }
                if (audioOnly && lyricEntry) lyricFiles.add(normalized);
                if (audioOnly && audioEntry) {
                    String name = target.getName(); int dot = name.lastIndexOf('.');
                    audioTracks.put(new JSONObject().put("fileName", normalized).put("name", dot > 0 ? name.substring(0, dot) : name)
                            .put("artist", "").put("album", "").put("duration", 0));
                }
                LibraryTaskRunner.progress(audioOnly ? audioTracks.length() : entries, 0);
            }
            LibraryTaskRunner.checkCancelled();
            if (audioOnly) {
                java.util.Set<String> audioStems = new java.util.HashSet<>();
                for (int i = 0; i < audioTracks.length(); i++) audioStems.add(fileStem(audioTracks.getJSONObject(i).getString("fileName")));
                for (String lyric : lyricFiles) if (!audioStems.contains(fileStem(lyric))) {
                    File orphan = resolveEntry(directory, lyric);
                    if (orphan.isFile() && !orphan.delete()) throw new IOException("无法清理未匹配的歌词文件");
                }
            }
            if (audioOnly) LibraryFiles.writeJson(new File(directory, "manifest.json"), new JSONObject()
                    .put("format", 1).put("generator", "vutron-audio-zip-import").put("name", title).put("tracks", audioTracks));
            JSONObject result = load(directory);
            if (!audioOnly) validateNewManifest(result);
            if (result.optInt("trackCount") == 0) throw new IOException("歌单中没有可用曲目");
            LibraryTaskRunner.checkCancelled();
            File published = new File(root, bundleId);
            if (!directory.renameTo(published)) throw new IOException("无法保存完整歌单副本");
            complete = true; return load(published);
        } finally { if (!complete) removeIncomplete(directory); }
    }

    static boolean isAudioFile(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).matches(".*[.](mp3|flac|m4a|aac|ogg|opus|wav|wave|aif|aiff|ape|wma|alac|amr|mp4)$");
    }
    private static boolean isLyricFile(String name) { return name.toLowerCase(java.util.Locale.ROOT).matches(".*[.](lrc|eslrc)$"); }
    private static String fileStem(String name) { int dot = name.lastIndexOf('.'); return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(java.util.Locale.ROOT); }
    static void validateNewManifest(JSONObject data) throws IOException {
        Object format = data.opt("format");
        if (!(format instanceof Number) || ((Number) format).doubleValue() != 1.0 || !"vutron-desktop-export".equals(data.optString("generator")))
            throw new IOException("请使用桌面版导出工具生成的歌单包");
    }

    static File resolveEntry(File directory, String name) throws IOException {
        String normalized = name == null ? "" : name.replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("../")
                || normalized.matches("^[A-Za-z]:.*")) throw new IOException("压缩包路径无效");
        File target = new File(directory, normalized).getCanonicalFile();
        if (!target.getPath().startsWith(directory.getCanonicalPath() + File.separator)) {
            throw new IOException("压缩包路径越界");
        }
        return target;
    }

    static JSONObject load(File directory) throws Exception {
        File manifest = new File(directory, "manifest.json");
        if (!manifest.isFile()) throw new IOException("缺少 manifest.json");
        JSONObject data;
        try (FileInputStream input = new FileInputStream(manifest);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_MANIFEST) throw new IOException("歌单清单超过限制");
                output.write(buffer, 0, count);
            }
            data = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
        JSONArray raw = data.optJSONArray("tracks");
        if (raw == null) throw new IOException("manifest 缺少 tracks");
        JSONArray tracks = new JSONArray();
        for (int index = 0; index < raw.length(); index++) {
            JSONObject source = raw.optJSONObject(index);
            if (source == null) continue;
            String relative = source.optString("fileName", source.optString("file"));
            boolean onlineOnly = relative.isEmpty() && source.optBoolean("onlineOnly") && source.optLong("neteaseId") > 0;
            File audio = null;
            if (!onlineOnly) {
                try { audio = resolveEntry(directory, relative); } catch (IOException invalidPath) { continue; }
                if (!audio.isFile()) continue;
            }
            String artist = source.optString("artist", "本地文件");
            JSONObject row = new JSONObject()
                    .put("id", onlineOnly ? source.optLong("neteaseId") : Math.max(1L, Math.abs((long) (directory + relative).hashCode())))
                    .put("name", source.optString("name", audio == null ? "网易云歌曲" : audio.getName()))
                    .put("artist", artist)
                    .put("dt", source.optLong("duration", 0))
                    .put("duration", source.optLong("duration", 0))
                    .put("localHasTitle", !source.optString("name", "").trim().isEmpty())
                    .put("ar", new JSONArray().put(new JSONObject().put("name", artist)))
                    .put("al", new JSONObject().put("name", source.optString("album", "")))
                    .put("localPlaylistId", id(directory));
            if (audio != null) row.put("localUri", "file://" + audio.toURI().getRawPath()).put("localFileName", audio.getName());
            else row.put("onlineOnly", true).put("localEntryKey", source.optString("entryKey", "netease:" + source.optLong("neteaseId")));
            // A local database row id is not automatically a NetEase identity.
            if (source.optLong("neteaseId", 0) > 0) row.put("neteaseId", source.optLong("neteaseId"));
            if (!source.optString("picUrl").isEmpty()) row.put("picUrl", source.optString("picUrl"));
            tracks.put(row);
        }
        return data.put("id", id(directory)).put("localTracks", tracks)
                .put("name", data.optString("name", "桌面本地歌单"))
                .put("isLocalBundle", true).put("bundleDir", directory.getPath())
                .put("trackCount", tracks.length());
    }

    static JSONArray list(File root) {
        JSONArray playlists = new JSONArray();
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) return playlists;
        Arrays.sort(directories, Comparator.comparing(File::getName));
        for (File directory : directories) {
            if (directory.getName().startsWith(".")) continue;
            try { playlists.put(load(directory)); } catch (Exception invalidBundle) {
                // Keep unavailable imports on disk; a listing must never remove user data.
            }
        }
        return playlists;
    }

    static JSONArray tracks(File root) {
        JSONArray result = new JSONArray();
        JSONArray playlists = list(root);
        for (int index = 0; index < playlists.length(); index++) {
            JSONArray rows = playlists.optJSONObject(index).optJSONArray("localTracks");
            for (int row = 0; rows != null && row < rows.length(); row++) result.put(rows.optJSONObject(row));
        }
        return result;
    }

    private static String id(File directory) { return "bundle:" + directory.getName(); }

    static File directory(File root, String playlistId) throws IOException {
        if (playlistId == null || !playlistId.startsWith("bundle:")) throw new IOException("本地歌单标识无效");
        File directory = LibraryFiles.child(root, playlistId.substring(7));
        if (!directory.isDirectory()) throw new IOException("本地歌单不存在");
        return directory;
    }
    static void removeTrack(File root, String playlistId, JSONObject track) throws Exception {
        File directory = directory(root, playlistId);
        JSONObject data = load(directory);
        JSONArray original = data.getJSONArray("tracks"), kept = new JSONArray();
        boolean found = false;
        for (int i = 0; i < original.length(); i++) {
            JSONObject row = original.getJSONObject(i);
            if (row.optBoolean("onlineOnly") && row.optString("fileName", row.optString("file")).isEmpty()) {
                if (row.optString("entryKey", "netease:" + row.optLong("neteaseId")).equals(track.optString("localEntryKey"))) found = true;
                else kept.put(row);
                continue;
            }
            File audio = resolveEntry(directory, row.optString("fileName", row.optString("file")));
            String uri = "file://" + audio.toURI().getRawPath();
            if (uri.equals(LocalLibraryIds.uri(track))) found = true;
            else kept.put(row);
        }
        if (!found) throw new IOException("歌单中未找到该本地曲目");
        data.put("tracks", kept); data.remove("localTracks"); data.remove("bundleDir"); data.remove("id"); data.remove("trackCount");
        LibraryFiles.writeJson(new File(directory, "manifest.json"), data);
    }
    static void delete(File root, String playlistId) throws Exception {
        File directory = directory(root, playlistId);
        File retired = new File(root, ".removed-" + directory.getName());
        if (!directory.renameTo(retired)) throw new IOException("歌单副本无法移除，请重试");
        LibraryFiles.deleteOwnedTree(root, retired);
    }

    private static void removeIncomplete(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) removeIncomplete(child);
            else child.delete();
        }
        directory.delete();
    }
}
