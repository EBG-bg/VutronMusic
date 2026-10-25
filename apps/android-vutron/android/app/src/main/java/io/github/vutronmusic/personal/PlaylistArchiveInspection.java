package io.github.vutronmusic.personal;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

final class PlaylistArchiveInspection {
    final boolean desktop;
    final int audioCount;
    private PlaylistArchiveInspection(boolean desktop, int audioCount) { this.desktop = desktop; this.audioCount = audioCount; }
    static PlaylistArchiveInspection inspect(File file) throws Exception {
        if (file.length() > 2L * 1024 * 1024 * 1024) throw new IOException("压缩包超过 2 GiB 限制");
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry manifest = null; int count = 0, audio = 0; Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                LibraryTaskRunner.checkCancelled(); ZipEntry entry = entries.nextElement();
                if (++count > 20000) throw new IOException("压缩包文件数量超过限制");
                String name = entry.getName().replace((char) 92, '/');
                LocalPlaylistStore.resolveEntry(file.getParentFile(), name);
                if (entry.isDirectory()) continue;
                if (!names.add(name)) throw new IOException("压缩包含有重复的文件路径");
                if (name.equalsIgnoreCase("manifest.json")) {
                    if (manifest != null) throw new IOException("压缩包含有多个根目录清单");
                    manifest = entry;
                }
                if (LocalPlaylistStore.isAudioFile(name)) audio++;
            }
            if (manifest != null) {
                try (InputStream in = zip.getInputStream(manifest); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = in.read(buffer)) != -1) { LibraryTaskRunner.checkCancelled(); if (out.size() + read > 4 * 1024 * 1024) throw new IOException("歌单清单超过限制"); out.write(buffer, 0, read); }
                    LocalPlaylistStore.validateNewManifest(new JSONObject(out.toString(StandardCharsets.UTF_8.name())));
                }
                return new PlaylistArchiveInspection(true, audio);
            }
            if (audio == 0) throw new IOException("ZIP 中没有支持的音频文件");
            return new PlaylistArchiveInspection(false, audio);
        }
    }
}
