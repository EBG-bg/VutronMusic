package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Original personal-edition queue semantics, persisted outside Binder intents. */
final class PlaybackQueue {
    private static final String PREFS = "vutron_playback";
    private static Snapshot cached;
    private PlaybackQueue() { }

    static final class Snapshot {
        final String token;
        final JSONArray songs;
        final JSONArray upNext;
        final boolean insertedPlaying;
        final int index;
        Snapshot(String token, JSONArray songs, int index) {
            this(token, songs, index, new JSONArray());
        }
        Snapshot(String token, JSONArray songs, int index, JSONArray upNext) {
            this(token, songs, index, upNext, false);
        }
        Snapshot(String token, JSONArray songs, int index, JSONArray upNext, boolean insertedPlaying) {
            this.token = token;
            this.songs = songs;
            this.upNext = upNext;
            this.insertedPlaying = insertedPlaying;
            this.index = songs.length() == 0 ? -1 : Math.max(0, Math.min(index, songs.length() - 1));
        }
        int count() { return songs.length(); }
        JSONObject current() { return songs.optJSONObject(index); }
        Snapshot move(int direction) {
            if (count() == 0) return this;
            return at(Math.floorMod(index + direction, count()));
        }
        Snapshot at(int position) { return new Snapshot(token, songs, position, upNext, position == index && insertedPlaying); }
        Snapshot appendNext(JSONObject supplied) throws Exception {
            JSONArray pending = new JSONArray(upNext.toString());
            pending.put(copyTrack(supplied));
            return new Snapshot(token, songs, index, pending, insertedPlaying);
        }
        Snapshot appendTail(JSONObject supplied) throws Exception {
            JSONArray updated = new JSONArray(songs.toString());
            updated.put(copyTrack(supplied));
            return new Snapshot(token, updated, index, upNext, insertedPlaying);
        }
        Snapshot consumeNext(int selected) throws Exception {
            if (selected < 0 || selected >= upNext.length()) throw new IllegalArgumentException("插队歌曲索引无效");
            JSONArray updated = new JSONArray();
            int target = index + 1;
            for (int row = 0; row < songs.length(); row++) {
                if (row == target) updated.put(safeCopy(upNext.getJSONObject(selected)));
                updated.put(safeCopy(songs.getJSONObject(row)));
            }
            if (target >= songs.length()) updated.put(safeCopy(upNext.getJSONObject(selected)));
            return new Snapshot(token, updated, target, without(upNext, selected), true);
        }
        Snapshot next(int mode, boolean automatic, int randomChoice) throws Exception {
            if (upNext.length() > 0) return consumeNext(0);
            if (automatic && mode == 1 && !insertedPlaying) return this;
            if (mode == 2 && count() > 1) {
                int chosen = Math.floorMod(randomChoice, count() - 1);
                return at(chosen >= index ? chosen + 1 : chosen);
            }
            return move(1);
        }
        Snapshot remove(int selected, boolean inserted) throws Exception {
            JSONArray source = inserted ? upNext : songs;
            if (selected < 0 || selected >= source.length()) throw new IllegalArgumentException("播放队列索引无效");
            if (inserted) return new Snapshot(token, songs, index, without(upNext, selected), insertedPlaying);
            JSONArray updated = without(songs, selected);
            int cursor = selected < index ? index - 1 : index;
            // Deleting the last current row continues with the first remaining row.
            if (cursor >= updated.length()) cursor = 0;
            Snapshot result = new Snapshot(token, updated, cursor, upNext, selected != index && insertedPlaying);
            if (updated.length() == 0 && upNext.length() > 0) return result.consumeNext(0);
            return result;
        }
        Snapshot clearNext() { return new Snapshot(token, songs, index, new JSONArray(), insertedPlaying); }
        Snapshot withoutMatching(java.util.function.Predicate<JSONObject> removed) {
            JSONArray kept = new JSONArray(), pending = new JSONArray(); int cursor = index;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject row = songs.optJSONObject(i);
                if (row != null && removed.test(row)) { if (i < index) cursor--; }
                else if (row != null) kept.put(row);
            }
            for (int i = 0; i < upNext.length(); i++) { JSONObject row = upNext.optJSONObject(i); if (row != null && !removed.test(row)) pending.put(row); }
            return new Snapshot(UUID.randomUUID().toString(), kept, cursor, pending, insertedPlaying && current() != null && !removed.test(current()));
        }
        Snapshot reorderCollection(String collectionId, JSONArray orderedKeys) throws Exception {
            java.util.Map<String, Integer> ranks = new java.util.HashMap<>();
            for (int i = 0; i < orderedKeys.length(); i++) ranks.put(orderedKeys.getString(i), i);
            java.util.List<JSONObject> rows = new java.util.ArrayList<>();
            JSONObject anchor = index >= 0 && index < songs.length() ? songs.optJSONObject(index) : null;
            for (int i = 0; i < songs.length(); i++) rows.add(songs.getJSONObject(i));
            rows.sort(java.util.Comparator.comparingInt(row -> {
                boolean belongs = "local:folder".equals(collectionId) ? !row.optString("localFolderId").isEmpty() : LocalLibraryIds.inCollection(row, collectionId);
                return belongs ? ranks.getOrDefault(LocalLibraryIds.key(row), Integer.MAX_VALUE) : Integer.MAX_VALUE;
            }));
            JSONArray sorted = new JSONArray(); int cursor = index;
            for (int i = 0; i < rows.size(); i++) { sorted.put(rows.get(i)); if (rows.get(i) == anchor) cursor = i; }
            boolean changed = false;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i) != songs.optJSONObject(i)) { changed = true; break; }
            if (!changed) return this;
            return new Snapshot(UUID.randomUUID().toString(), sorted, cursor, upNext, insertedPlaying);
        }
        String encode() throws Exception {
            return new JSONObject().put("token", token).put("songs", songs).put("upNext", upNext)
                    .put("index", index).put("insertedPlaying", insertedPlaying).toString();
        }
    }

    static Snapshot create(JSONArray supplied, int index) throws Exception {
        if (supplied == null || supplied.length() == 0) throw new IllegalArgumentException("播放队列为空");
        JSONArray songs = new JSONArray();
        for (int i = 0; i < supplied.length(); i++) {
            songs.put(copyTrack(supplied.optJSONObject(i)));
        }
        return new Snapshot(UUID.randomUUID().toString(), songs, index);
    }

    static Snapshot decode(String text, String token, int index) throws Exception {
        JSONObject data = new JSONObject(text);
        JSONArray songs = data.getJSONArray("songs");
        if (!token.equals(data.getString("token"))) return null;
        JSONArray pending = data.optJSONArray("upNext");
        return new Snapshot(token, songs, index, pending == null ? new JSONArray() : pending, data.optBoolean("insertedPlaying"));
    }

    static synchronized Snapshot replace(Context context, JSONArray songs, int index) throws Exception {
        Snapshot next = create(songs, index);
        persist(context, next);
        return next;
    }

    static synchronized void persist(Context context, Snapshot next) throws Exception {
        AtomicFile file = file(context);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(next.encode().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
        if (!preferences(context).edit().putString("queueToken", next.token).putInt("queueIndex", next.index)
                .putInt("queueCount", next.count()).putInt("upNextCount", next.upNext.length()).commit())
            throw new java.io.IOException("播放队列索引保存失败");
        cached = next;
    }

    static synchronized Snapshot load(Context context) throws Exception {
        SharedPreferences prefs = preferences(context);
        String token = prefs.getString("queueToken", "");
        if (token.isEmpty()) return null;
        int index = prefs.getInt("queueIndex", 0);
        if (cached != null && token.equals(cached.token)) return cached.at(index);
        cached = decode(new String(file(context).readFully(), StandardCharsets.UTF_8), token, index);
        return cached;
    }

    static synchronized void cursor(Context context, Snapshot value) {
        if (value == null || !value.token.equals(preferences(context).getString("queueToken", ""))) return;
        cached = value;
        preferences(context).edit().putInt("queueIndex", value.index).putInt("queueCount", value.count())
                .putInt("upNextCount", value.upNext.length()).apply();
    }

    static synchronized void clear(Context context) {
        cached = null;
        preferences(context).edit().remove("queueToken").remove("queueIndex").remove("queueCount")
                .remove("upNextCount").apply();
        file(context).delete();
    }

    private static JSONObject copyTrack(JSONObject input) throws Exception {
        if (input == null || input.optLong("id") <= 0) throw new IllegalArgumentException("歌曲信息无效");
        JSONObject row = safeCopy(input);
        if (row.optString("trackVolumeKey", "").isEmpty()) row.put("trackVolumeKey", PlaybackVolume.trackKey(row));
        return row;
    }

    private static JSONObject safeCopy(JSONObject input) throws Exception { return new JSONObject(input.toString()); }

    private static JSONArray without(JSONArray source, int selected) throws Exception {
        JSONArray result = new JSONArray();
        for (int row = 0; row < source.length(); row++) if (row != selected) result.put(safeCopy(source.getJSONObject(row)));
        return result;
    }

    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getFilesDir(), "playback-queue.json")); }
}
