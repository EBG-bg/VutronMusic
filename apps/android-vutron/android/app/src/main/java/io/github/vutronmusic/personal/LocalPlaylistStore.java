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
        if (input == null) throw new IOException("无法打开压缩包");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建本地歌单目录");
        File directory = new File(root, UUID.randomUUID().toString());
        if (!directory.mkdirs()) throw new IOException("无法创建歌单目录");
        boolean complete = false;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            long total = 0;
            int entries = 0;
            byte[] buffer = new byte[32768];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("压缩包文件数量超过限制");
                if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消");
                File target = resolveEntry(directory, entry.getName());
                if (entry.getName().equalsIgnoreCase("manifest.json")) target = new File(directory, "manifest.json");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法创建音频目录");
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建音频目录");
                long written = 0;
                try (FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        written += count;
                        total += count;
                        if (written > MAX_FILE || total > MAX_TOTAL) throw new IOException("解压内容超过限制");
                        if (target.getName().equalsIgnoreCase("manifest.json") && written > MAX_MANIFEST) {
                            throw new IOException("歌单清单超过限制");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
            JSONObject result = load(directory);
            complete = true;
            return result;
        } finally {
            // Only a newly allocated incomplete import may be removed.
            if (!complete) removeIncomplete(directory);
        }
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
            File audio;
            try { audio = resolveEntry(directory, relative); } catch (IOException invalidPath) { continue; }
            if (!audio.isFile()) continue;
            String artist = source.optString("artist", "本地文件");
            JSONObject row = new JSONObject()
                    .put("id", Math.max(1, Math.abs((directory + relative).hashCode())))
                    .put("name", source.optString("name", audio.getName()))
                    .put("artist", artist)
                    .put("dt", source.optLong("duration", 0))
                    .put("duration", source.optLong("duration", 0))
                    .put("localUri", "file://" + audio.toURI().getRawPath())
                    .put("localFileName", audio.getName())
                    .put("localHasTitle", !source.optString("name", "").trim().isEmpty())
                    .put("ar", new JSONArray().put(new JSONObject().put("name", artist)))
                    .put("al", new JSONObject().put("name", source.optString("album", "")))
                    .put("localPlaylistId", id(directory));
            // A local database row id is not automatically a NetEase identity.
            if (source.optLong("neteaseId", 0) > 0) row.put("neteaseId", source.optLong("neteaseId"));
            tracks.put(row);
        }
        if (tracks.length() == 0) throw new IOException("歌单中没有可用音频");
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

    private static void removeIncomplete(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) removeIncomplete(child);
            else child.delete();
        }
        directory.delete();
    }
}
