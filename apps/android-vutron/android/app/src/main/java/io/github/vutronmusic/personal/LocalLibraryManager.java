package io.github.vutronmusic.personal;

import android.content.Context;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** The sole bridge boundary for local removals; never deletes external original audio. */
final class LocalLibraryManager {
    private LocalLibraryManager() { }
    static JSObject manage(Context context, LibraryTaskRunner tasks, String action, JSONObject supplied, String collectionId, boolean stopConfirmed) throws Exception {
        if ("restoreRemoved".equals(action)) { LocalTrackStore.restoreExcluded(context); return new JSObject().put("changed", true); }
        File root = new File(context.getFilesDir(), "local-bundles");
        Predicate<JSONObject> removed;
        JSONObject target = null;
        if ("removeTrack".equals(action)) {
            target = findTrack(context, root, supplied);
            JSONObject captured = target; removed = row -> LocalLibraryIds.same(captured, row);
        } else if ("deletePlaylist".equals(action)) {
            if (collectionId.startsWith("restored:")) LibraryBackup.collection(context, collectionId);
            else LocalPlaylistStore.directory(root, collectionId);
            removed = row -> LocalLibraryIds.inCollection(row, collectionId);
        } else if ("forgetFolder".equals(action)) {
            boolean found = false; JSONArray trees = LocalFolderStore.loadTreeUris(context);
            for (int i = 0; i < trees.length(); i++) if (collectionId.equals(LocalFolderStore.folderId(trees.optString(i)))) found = true;
            if (!found) throw new IOException("文件夹未授权或已移除");
            removed = row -> LocalLibraryIds.inCollection(row, collectionId);
        } else throw new IOException("本地资料操作无效");
        tasks.cancel();
        if (!AndroidPlaybackService.prepareLibraryRemoval(context, removed, stopConfirmed, "deletePlaylist".equals(action)).get(10, TimeUnit.SECONDS))
            return new JSObject().put("changed", false).put("requiresStop", true);
        if ("removeTrack".equals(action)) {
            if (target.optString("localPlaylistId").startsWith("restored:")) LibraryBackup.remove(context, target.optString("localPlaylistId"), target.getString("localEntryKey"));
            else if (target.optString("localPlaylistId").startsWith("bundle:")) LocalPlaylistStore.removeTrack(root, target.optString("localPlaylistId"), target);
            else LocalTrackStore.exclude(context, target);
        } else if ("deletePlaylist".equals(action)) {
            LocalTrackStore.removeMatching(context, removed);
            if (collectionId.startsWith("restored:")) LibraryBackup.remove(context, collectionId, null);
            else LocalPlaylistStore.delete(root, collectionId);
        } else LocalFolderStore.removeTree(context, collectionId);
        return new JSObject().put("changed", true);
    }
    static JSONObject findTrack(Context context, File root, JSONObject supplied) throws Exception {
        if (supplied == null || (LocalLibraryIds.uri(supplied).isEmpty() && supplied.optString("localEntryKey").isEmpty())) throw new IOException("只能移除已导入的本地曲目");
        String bundle = supplied.optString("localPlaylistId", supplied.optString("playlistVolumeKey"));
        JSONArray rows;
        if (bundle.startsWith("restored:")) rows = LibraryBackup.tracks(context, bundle);
        else if (bundle.startsWith("bundle:")) rows = LocalPlaylistStore.load(LocalPlaylistStore.directory(root, bundle)).getJSONArray("localTracks");
        else {
            rows = LocalTrackStore.loadFolderIndex(context);
            JSONArray selected = LocalTrackStore.load(context);
            for (int i = 0; i < selected.length(); i++) rows.put(selected.optJSONObject(i));
        }
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && LocalLibraryIds.same(supplied, row)) return row; }
        throw new IOException("曲目已移除或不属于本地音乐库");
    }
}
