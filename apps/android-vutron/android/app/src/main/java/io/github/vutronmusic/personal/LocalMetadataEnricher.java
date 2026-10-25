package io.github.vutronmusic.personal;
import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.Normalizer;
import java.util.*;

/** Metadata only: local files and local playback identities are never replaced by a remote URL. */
final class LocalMetadataEnricher {
    private LocalMetadataEnricher() { }
    static String normalized(String value) { return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+", " ").trim().toLowerCase(Locale.ROOT); }
    static JSONObject choose(JSONObject local, JSONArray candidates) {
        Set<String> artists = LocalTrackMatcher.identityArtists(local);
        if (artists.isEmpty()) return null;
        long duration = local.optLong("dt", local.optLong("duration"));
        String album = normalized(local.optJSONObject("al") == null ? local.optString("album") : local.optJSONObject("al").optString("name"));
        JSONObject chosen = null;
        for (int i = 0; candidates != null && i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i); if (candidate == null || candidate.optLong("id") <= 0) continue;
            if (LocalTrackMatcher.match(candidate, Collections.singletonList(local)) == null || !artists.equals(LocalTrackMatcher.identityArtists(candidate))) continue;
            long remoteDuration = candidate.optLong("dt", candidate.optLong("duration"));
            String remoteAlbum = normalized(candidate.optJSONObject("al") == null ? "" : candidate.optJSONObject("al").optString("name"));
            if (!album.isEmpty() && !remoteAlbum.isEmpty() && !album.equals(remoteAlbum)) continue;
            boolean durationMatches = duration > 0 && remoteDuration > 0 && Math.abs(duration - remoteDuration) <= 2000;
            if (duration > 0 && remoteDuration > 0 && !durationMatches) continue;
            if (!durationMatches && (album.isEmpty() || !album.equals(remoteAlbum))) continue;
            if (chosen != null && chosen.optLong("id") != candidate.optLong("id")) return null;
            chosen = candidate;
        }
        return chosen;
    }
    static JSONObject enrich(Context context, File directory) throws Exception {
        JSONObject data = LocalPlaylistStore.load(directory); JSONArray tracks = data.getJSONArray("tracks");
        for (String key : new String[]{"id", "localTracks", "bundleDir", "trackCount", "isLocalBundle"}) data.remove(key);
        NeteaseApi api = new NeteaseApi(SessionStore.load(context), () -> !LocalOnlyMode.enabled(context) && !LibraryTaskRunner.isCancelled());
        int matched = 0, failed = 0; boolean paused = false;
        LibraryTaskRunner.phase("match");
        for (int i = 0; i < tracks.length(); i++) {
            LibraryTaskRunner.checkCancelled();
            if (LocalOnlyMode.enabled(context) || SessionStore.load(context).isEmpty()) { paused = true; break; }
            JSONObject entry = tracks.getJSONObject(i); File file = LocalPlaylistStore.resolveEntry(directory, entry.optString("fileName", entry.optString("file")));
            try {
                JSONObject local = LocalFolderStore.trackWithMetadata(context, Uri.fromFile(file), file.getName(), file.length(), file.lastModified(), new JSONObject());
                LibraryTaskRunner.checkCancelled();
                String query = local.optString("name").replaceFirst("(?i)[.](mp3|flac|m4a|aac|ogg|opus|wav|ape|wma)$", "");
                JSONObject candidate = choose(local, api.search("tracks", query, 1).optJSONArray("items"));
                LibraryTaskRunner.checkCancelled();
                if (candidate != null) {
                    JSONObject album = candidate.optJSONObject("al");
                    entry.put("name", candidate.optString("name")).put("artist", PlaybackRequest.artistText(candidate))
                            .put("album", album == null ? "" : album.optString("name"))
                            .put("duration", local.optLong("dt", local.optLong("duration"))).put("neteaseId", candidate.optLong("id"))
                            .put("picUrl", ArtworkSource.coverUrl(candidate));
                    LibraryFiles.writeJson(new File(directory, "manifest.json"), data); matched++;
                    if (!entry.optString("picUrl").isEmpty()) ArtworkCache.resolve(context, entry.optString("picUrl"));
                    LibraryTaskRunner.checkCancelled();
                    try { LyricRepository.load(context, candidate.optLong("id")); } catch (java.io.IOException lyricUnavailable) { /* Metadata and local audio remain usable. */ }
                }
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (Exception unavailable) { failed++; }
            LibraryTaskRunner.progress(i + 1, tracks.length());
        }
        return new JSONObject().put("matched", matched).put("total", tracks.length()).put("failed", failed).put("matchingPaused", paused);
    }
}
