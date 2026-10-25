package io.github.vutronmusic.personal;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** App-owned JSON writes and directory deletion, never external SAF audio deletion. */
final class LibraryFiles {
    private LibraryFiles() { }
    static void writeJson(File file, JSONObject value) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(value.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    static File child(File root, String name) throws IOException {
        if (name == null || !name.matches("[a-fA-F0-9-]{36}")) throw new IOException("歌单标识无效");
        File target = new File(root, name).getCanonicalFile();
        if (!root.getCanonicalFile().equals(target.getParentFile())) throw new IOException("歌单目录越界");
        return target;
    }
    static void deleteOwnedTree(File root, File target) throws IOException {
        String base = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(base)) throw new IOException("只能删除应用私有歌单副本");
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteOwnedTree(root, child);
        if (target.exists() && !target.delete()) throw new IOException("私有歌单文件清理失败，请重试");
    }
}
