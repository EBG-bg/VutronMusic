package io.github.vutronmusic.personal;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Picker grants are used only to stage a private temporary copy; original ZIPs are never edited. */
final class PlaylistImportController {
    private static final long MAX_ZIP = 2L * 1024 * 1024 * 1024;
    private static final class Pending {
        final File file; final String title;
        Pending(File file, String title) { this.file = file; this.title = title; }
    }
    private final Context context;
    private final LibraryTaskRunner tasks;
    private final File temporary, bundles;
    private final Map<String, Pending> pending = new HashMap<>();
    PlaylistImportController(Context context, LibraryTaskRunner tasks) throws IOException {
        this.context = context.getApplicationContext(); this.tasks = tasks;
        temporary = new File(context.getCacheDir(), "library-imports"); bundles = new File(context.getFilesDir(), "local-bundles");
        if (!temporary.isDirectory() && !temporary.mkdirs()) throw new IOException("无法创建临时导入目录");
        File[] old = temporary.listFiles();
        if (old != null) for (File item : old) if (item.isFile() && item.getName().matches("pending-[a-f0-9-]{36}[.]zip")) item.delete();
    }
    void stage(PluginCall call, Uri uri) {
        tasks.execute("importInspect", call, () -> {
            clearPending();
            File file = new File(temporary, "pending-" + UUID.randomUUID() + ".zip"); boolean retained = false;
            try {
                try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new IOException("无法读取选择的 ZIP");
                    byte[] buffer = new byte[65536]; long size = 0; int read;
                    while ((read = in.read(buffer)) != -1) {
                        LibraryTaskRunner.checkCancelled(); size += read;
                        if (size > MAX_ZIP) throw new IOException("压缩包超过 2 GiB 限制");
                        out.write(buffer, 0, read);
                    }
                }
                PlaylistArchiveInspection info = PlaylistArchiveInspection.inspect(file);
                String title = title(uri);
                if (info.desktop) call.resolve(perform(new Pending(file, title), false, false));
                else {
                    String token = UUID.randomUUID().toString();
                    synchronized (this) { pending.put(token, new Pending(file, title)); }
                    retained = true;
                    call.resolve(new JSObject().put("needsMode", true).put("importToken", token).put("audioCount", info.audioCount).put("canMatchOnline", onlineAvailable()));
                }
            } finally { if (!retained) file.delete(); }
        });
    }
    void complete(PluginCall call, String token, boolean matchOnline) {
        Pending item; synchronized (this) { item = pending.get(token); }
        if (item == null || !item.file.isFile()) { call.reject("导入选择已失效，请重新选择 ZIP"); return; }
        if (matchOnline && !onlineAvailable()) {
            call.resolve(new JSObject().put("needsMode", true).put("importToken", token).put("canMatchOnline", false)); return;
        }
        tasks.execute("import", call, () -> {
            synchronized (this) { if (pending.remove(token) != item) throw new IOException("导入选择已失效"); }
            try { call.resolve(perform(item, true, matchOnline)); }
            finally { item.file.delete(); }
        });
    }
    private JSObject perform(Pending item, boolean audioOnly, boolean enrich) throws Exception {
        LibraryTaskRunner.phase("import");
        JSONObject imported;
        try (InputStream input = new FileInputStream(item.file)) { imported = LocalPlaylistStore.importArchive(bundles, input, audioOnly, item.title); }
        JSONObject details = new JSONObject();
        File directory = LocalPlaylistStore.directory(bundles, imported.getString("id"));
        if (enrich) {
            try { details = LocalMetadataEnricher.enrich(context, directory); }
            catch (java.util.concurrent.CancellationException cancelled) { details.put("matchingCancelled", true); }
            catch (Exception unavailable) { details.put("matchingFailed", true); }
        }
        imported = LocalPlaylistStore.load(directory);
        JSObject result = new JSObject().put("cancelled", false).put("playlist", new JSObject().put("id", imported.optString("id"))
                .put("name", imported.optString("name")).put("trackCount", imported.optInt("trackCount")).put("isLocalBundle", true));
        Iterator<String> keys = details.keys(); while (keys.hasNext()) { String key = keys.next(); result.put(key, details.opt(key)); }
        return result;
    }
    static boolean canEnrich(boolean localOnly, boolean authenticated) { return !localOnly && authenticated; }
    private boolean onlineAvailable() { return canEnrich(LocalOnlyMode.enabled(context), !SessionStore.load(context).isEmpty()); }
    private String title(Uri uri) {
        String name = "导入音频";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) name = "导入音频";
        name = name.replaceFirst("(?i)[.]zip$", "");
        return name.length() > 200 ? name.substring(0, 200) : name;
    }
    synchronized void discard(String token) { Pending item = pending.remove(token); if (item != null) item.file.delete(); }
    synchronized void clearPending() { for (Pending item : pending.values()) item.file.delete(); pending.clear(); }
}
