package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Adapts the existing personal edition's local matcher to Capacitor track rows. */
final class LocalPlaybackResolver {
    private static final long DURATION_TOLERANCE_MS = 2000;

    private LocalPlaybackResolver() { }

    /**
     * Reads the completed folder index, never rescans a SAF tree during playback.
     * The chosen URI must still be readable using this installation's grants.
     */
    static JSONObject match(Context context, JSONObject online) throws JSONException {
        if (context == null || online == null) return null;
        List<JSONObject> indexed = new ArrayList<>();
        append(indexed, LocalTrackStore.loadFolderIndex(context));
        append(indexed, LocalTrackStore.load(context));
        append(indexed, LocalPlaylistStore.tracks(new java.io.File(context.getFilesDir(), "local-bundles")));
        JSONObject normalized = normalizeTrack(online);
        List<JSONObject> candidates = candidates(normalized, indexed);
        List<JSONObject> readable = new ArrayList<>();
        for (JSONObject local : candidates) {
            if (readable(context, local.optString("localUri", ""))) readable.add(local);
        }
        return LocalTrackMatcher.match(normalized, readable);
    }

    /** Pure metadata entrypoint for identity and ambiguity regression checks. */
    static JSONObject match(JSONObject online, List<JSONObject> indexed) throws JSONException {
        if (online == null) return null;
        JSONObject normalized = normalizeTrack(online);
        return LocalTrackMatcher.match(normalized, candidates(normalized, indexed));
    }

    private static void append(List<JSONObject> target, JSONArray values) {
        for (int index = 0; index < values.length(); index++) {
            JSONObject row = values.optJSONObject(index);
            if (row != null) target.add(row);
        }
    }

    /**
     * The old matcher owns title normalization, version labels, explicit service
     * ids, and duplicate-file ambiguity. Add the missing identity evidence here:
     * an unbound file needs all artists plus a matching album or close duration.
     */
    private static List<JSONObject> candidates(JSONObject online, List<JSONObject> indexed)
            throws JSONException {
        List<JSONObject> result = new ArrayList<>();
        if (indexed == null) return result;
        Set<String> seenUris = new LinkedHashSet<>();
        long onlineId = online.optLong("id", 0);
        for (JSONObject value : indexed) {
            if (value == null) continue;
            JSONObject local = normalizeTrack(value);
            String uri = local.optString("localUri", "").trim();
            if (uri.isEmpty() || !seenUris.add(uri)) continue;
            long boundId = local.optLong("neteaseId", 0);
            if (boundId > 0) {
                if (onlineId > 0 && boundId == onlineId) result.add(local);
                continue;
            }
            if (fullArtistsMatch(online, local) && corroborated(online, local)
                    && LocalTrackMatcher.match(online, Collections.singletonList(local)) != null) result.add(local);
        }
        return result;
    }

    /** Preserve the original row and its online identity while adding native aliases. */
    static JSONObject normalizeTrack(JSONObject input) throws JSONException {
        JSONObject row = new JSONObject(input.toString());
        if (row.optLong("id", 0) <= 0 && row.optLong("neteaseId", 0) > 0) {
            row.put("id", row.optLong("neteaseId"));
        }
        if (row.optString("name", "").trim().isEmpty()) row.put("name", row.optString("title", ""));
        if (row.optJSONArray("ar") == null) {
            JSONArray values = row.optJSONArray("artists");
            if (values != null) row.put("ar", values);
            else if (!row.optString("artist", "").trim().isEmpty()) {
                row.put("ar", new JSONArray().put(new JSONObject().put("name", row.optString("artist"))));
            }
        }
        if (row.optJSONObject("al") == null) {
            JSONObject album = row.optJSONObject("album");
            if (album != null) row.put("al", album);
            else if (row.opt("album") instanceof String) {
                row.put("al", new JSONObject().put("name", row.optString("album")));
            }
        }
        if (row.optLong("dt", 0) <= 0 && row.optLong("duration", 0) > 0) {
            row.put("dt", row.optLong("duration"));
        }
        if (row.optLong("duration", 0) <= 0 && row.optLong("dt", 0) > 0) {
            row.put("duration", row.optLong("dt"));
        }
        return row;
    }

    private static boolean fullArtistsMatch(JSONObject online, JSONObject local) {
        Set<String> expected = artists(online);
        Set<String> actual = LocalTrackMatcher.identityArtists(local);
        if (expected.isEmpty() || actual.isEmpty()) return false;
        if (expected.equals(actual)) return true;
        // Android tags often store a whole performer roster in one field. Only
        // split that field when the online track explicitly has multiple artists;
        // a single artist such as "AC/DC" therefore keeps its original identity.
        if (expected.size() <= 1 || actual.size() != 1) return false;
        Set<String> roster = new LinkedHashSet<>();
        for (String name : actual.iterator().next().split("\\s*(?:;|/|、|\\s&\\s)\\s*")) {
            String normalized = normalize(name);
            if (!normalized.isEmpty()) roster.add(normalized);
        }
        if (!expected.equals(roster)) return false;
        try {
            JSONArray values = new JSONArray();
            for (String name : roster) values.put(new JSONObject().put("name", name));
            local.put("ar", values);
        } catch (JSONException invalid) { return false; }
        return true;
    }

    private static Set<String> artists(JSONObject value) {
        Set<String> names = new LinkedHashSet<>();
        JSONArray values = value.optJSONArray("ar");
        if (values == null) values = value.optJSONArray("artists");
        if (values != null) for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            addArtist(names, item == null ? values.optString(index, "") : item.optString("name", ""));
        }
        if (names.isEmpty()) addArtist(names, value.optString("artist", ""));
        return names;
    }

    private static void addArtist(Set<String> names, String value) {
        String name = normalize(value);
        if (!name.isEmpty() && !name.equals("本地文件") && !name.equals("未知歌手")
                && !name.equals("unknown") && !name.equals("unknown artist")) names.add(name);
    }

    private static boolean corroborated(JSONObject online, JSONObject local) {
        String expectedAlbum = album(online);
        String actualAlbum = album(local);
        boolean albumMatches = !expectedAlbum.isEmpty() && expectedAlbum.equals(actualAlbum);
        long expectedDuration = online.optLong("dt", online.optLong("duration", 0));
        long actualDuration = local.optLong("dt", local.optLong("duration", 0));
        boolean durationMatches = expectedDuration > 0 && actualDuration > 0
                && Math.abs(expectedDuration - actualDuration) <= DURATION_TOLERANCE_MS;
        return albumMatches || durationMatches;
    }

    private static String album(JSONObject row) {
        JSONObject value = row.optJSONObject("al");
        if (value == null) value = row.optJSONObject("album");
        String name = normalize(value == null ? row.optString("album", "") : value.optString("name", ""));
        return name.equals("未知专辑") || name.equals("unknown album") ? "" : name;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean readable(Context context, String address) {
        if (address.isEmpty()) return false;
        Uri uri = Uri.parse(address);
        if (!"content".equalsIgnoreCase(uri.getScheme()) && !"file".equalsIgnoreCase(uri.getScheme())) return false;
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (Exception inaccessible) {
            return false;
        }
    }
}
