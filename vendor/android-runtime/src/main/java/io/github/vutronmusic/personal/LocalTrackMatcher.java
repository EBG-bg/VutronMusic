package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative matching between an online queue row and user-selected files. */
final class LocalTrackMatcher {
    private static final Pattern AUDIO_EXTENSION = Pattern.compile(
            "(?i)\\.(mp3|flac|m4a|mp4|aac|ogg|oga|opus|wav|aiff|aif|alac|ape|amr|mid|midi|wma|webm|3gp)$");
    private static final Pattern TRACK_NUMBER = Pattern.compile(
            "^(?:[0-9]{1,3}\\s*[.、)_-]\\s*|[0-9]{1,3}\\s+)(?=\\S)");
    // Android always uses Unicode character classes and rejects the redundant
    // UNICODE_CHARACTER_CLASS flag during static initialization.
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("\\s+[-–—]\\s+");

    private LocalTrackMatcher() { }

    /** Returns an online row carrying a local URI, or null if the match is ambiguous. */
    static JSONObject match(JSONObject online, List<JSONObject> locals) {
        if (online == null || locals == null || locals.isEmpty()) return null;
        long onlineId = online.optLong("id", 0);
        JSONObject explicit = null;
        boolean explicitConflict = false;
        if (onlineId > 0) {
            for (JSONObject local : locals) {
                if (!playable(local) || local.optLong("neteaseId", 0) != onlineId) continue;
                if (explicit != null && !sameFile(explicit, local)) explicitConflict = true;
                else explicit = local;
            }
            if (explicitConflict) return null;
            if (explicit != null) return merge(online, explicit);
        }

        String title = normalize(online.optString("name"));
        if (title.isEmpty()) return null;
        Set<String> onlineArtists = artists(online);
        JSONObject selected = null;
        for (JSONObject local : locals) {
            if (!playable(local)) continue;
            // A file carrying a different explicit service ID is not the same song.
            long localId = local.optLong("neteaseId", 0);
            if (localId > 0 && localId != onlineId) continue;
            LocalIdentity identity = identity(local);
            if (!title.equals(identity.title) || artistsConflict(onlineArtists, identity.artists)) continue;
            if (selected != null && !sameFile(selected, local)) return null;
            selected = local;
        }
        return selected == null ? null : merge(online, selected);
    }

    private static boolean playable(JSONObject local) {
        return local != null && !local.optString("localUri", "").trim().isEmpty();
    }

    private static boolean sameFile(JSONObject first, JSONObject second) {
        return first.optString("localUri").equals(second.optString("localUri"));
    }

    /** Reuse tagged/filename performer identity in the Android platform adapter. */
    static Set<String> identityArtists(JSONObject local) {
        return identity(local).artists;
    }

    private static LocalIdentity identity(JSONObject local) {
        Set<String> localArtists = artists(local);
        String taggedTitle = local.optString("title", local.optString("metadataTitle", "")).trim();
        String name = local.optString("name", "").trim();
        String filename = local.optString("localFileName", "").trim();
        if (taggedTitle.isEmpty() && local.optBoolean("localHasTitle")) taggedTitle = name;
        if (taggedTitle.isEmpty() && !name.isEmpty() && !AUDIO_EXTENSION.matcher(name).find()
                && (filename.isEmpty() || !normalize(name).equals(normalize(filename)))) {
            taggedTitle = name;
        }
        if (!taggedTitle.isEmpty()) return new LocalIdentity(normalize(taggedTitle), localArtists);

        String raw = filename.isEmpty() ? name : filename;
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        if (slash >= 0) raw = raw.substring(slash + 1);
        raw = AUDIO_EXTENSION.matcher(raw).replaceFirst("");
        raw = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
        raw = TRACK_NUMBER.matcher(raw).replaceFirst("").trim();
        String[] parts = ARTIST_SEPARATOR.split(raw, 2);
        if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
            Set<String> filenameArtists = new LinkedHashSet<>();
            addArtist(filenameArtists, parts[0]);
            if (localArtists.isEmpty()) localArtists = filenameArtists;
            raw = parts[1];
        }
        return new LocalIdentity(normalize(raw), localArtists);
    }

    private static Set<String> artists(JSONObject value) {
        Set<String> result = new LinkedHashSet<>();
        JSONArray array = value.optJSONArray("ar");
        if (array == null) array = value.optJSONArray("artists");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject artist = array.optJSONObject(i);
                addArtist(result, artist == null ? array.optString(i, "") : artist.optString("name"));
            }
        }
        if (result.isEmpty()) addArtist(result, value.optString("artist", ""));
        return result;
    }

    private static void addArtist(Set<String> artists, String name) {
        String normalized = normalize(name);
        if (!normalized.isEmpty() && !normalized.equals("本地文件") && !normalized.equals("未知歌手")
                && !normalized.equals("unknown") && !normalized.equals("unknown artist")) {
            artists.add(normalized);
        }
    }

    private static boolean artistsConflict(Set<String> online, Set<String> local) {
        if (online.isEmpty() || local.isEmpty()) return false;
        for (String artist : online) if (local.contains(artist)) return false;
        return true;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static JSONObject merge(JSONObject online, JSONObject local) {
        try {
            JSONObject result = new JSONObject(online.toString());
            result.put("localUri", local.optString("localUri"));
            if (!local.optString("localFileName").isEmpty()) {
                result.put("localFileName", local.optString("localFileName"));
            }
            long onlineDuration = online.optLong("dt", 0);
            if (onlineDuration <= 0) onlineDuration = online.optLong("duration", 0);
            long localDuration = local.optLong("dt", 0);
            if (localDuration <= 0) localDuration = local.optLong("duration", 0);
            long duration = onlineDuration > 0 ? onlineDuration : localDuration;
            if (duration > 0) {
                if (result.optLong("dt", 0) <= 0) result.put("dt", duration);
                if (result.optLong("duration", 0) <= 0) result.put("duration", duration);
            }
            return result;
        } catch (Exception malformedRow) {
            return null;
        }
    }

    private static final class LocalIdentity {
        final String title;
        final Set<String> artists;

        LocalIdentity(String title, Set<String> artists) {
            this.title = title;
            this.artists = artists;
        }
    }
}
