package io.github.vutronmusic.personal;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LocalOnlyModeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private SharedPreferences preferences(Map<String, Object> saved, boolean commitWorks) {
        return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { SharedPreferences.class }, (proxy, method, arguments) -> {
                    if ("getBoolean".equals(method.getName())) {
                        return saved.getOrDefault((String) arguments[0], arguments[1]);
                    }
                    if ("edit".equals(method.getName())) {
                        Map<String, Object> pending = new HashMap<>();
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] { SharedPreferences.Editor.class }, (editor, action, values) -> {
                                    if ("putBoolean".equals(action.getName())) {
                                        pending.put((String) values[0], values[1]); return editor;
                                    }
                                    if ("commit".equals(action.getName())) {
                                        if (commitWorks) saved.putAll(pending);
                                        return commitWorks;
                                    }
                                    throw new UnsupportedOperationException(action.getName());
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test public void modePersistsForFreshReadersWithoutClearingOtherSettings() throws Exception {
        Map<String, Object> saved = new HashMap<>();
        saved.put("unrelated_cache_setting", true);
        assertFalse(LocalOnlyMode.enabled(preferences(saved, true)));
        LocalOnlyMode.save(preferences(saved, true), true);
        assertTrue(LocalOnlyMode.enabled(preferences(saved, true)));
        assertEquals(true, saved.get("unrelated_cache_setting"));
        LocalOnlyMode.save(preferences(saved, true), false);
        assertFalse(LocalOnlyMode.enabled(preferences(saved, true)));
        assertEquals(true, saved.get("unrelated_cache_setting"));
    }

    @Test public void failedPersistenceIsReportedInsteadOfPretendingItSurvivesRestart() {
        assertThrows(IOException.class, () -> LocalOnlyMode.save(preferences(new HashMap<>(), false), true));
    }

    @Test public void localModeCannotInvokeOfficialOrEmbeddedProviders() {
        AtomicInteger official = new AtomicInteger();
        AtomicInteger embedded = new AtomicInteger();
        assertThrows(IOException.class, () -> PlaybackSourceResolver.resolveSources(() -> false,
                () -> { official.incrementAndGet(); return new JSONObject(); }, () -> true,
                () -> { embedded.incrementAndGet(); return new JSONObject(); }));
        assertEquals(0, official.get());
        assertEquals(0, embedded.get());
    }

    @Test public void enteringLocalModeDuringOfficialFailureCannotStartEmbeddedFallback() {
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicInteger embedded = new AtomicInteger();
        assertThrows(IOException.class, () -> PlaybackSourceResolver.resolveSources(online::get,
                () -> { online.set(false); throw new IOException("official unavailable"); }, () -> true,
                () -> { embedded.incrementAndGet(); return new JSONObject(); }));
        assertEquals(0, embedded.get());
    }

    @Test public void onlineProviderOrderingStillAllowsConfiguredFallback() throws Exception {
        JSONObject location = new JSONObject().put("url", "https://example.invalid/audio");
        AtomicInteger embedded = new AtomicInteger();
        assertSame(location, PlaybackSourceResolver.resolveSources(() -> true,
                () -> { throw new IOException("official unavailable"); }, () -> true,
                () -> { embedded.incrementAndGet(); return location; }));
        assertEquals(1, embedded.get());
        assertThrows(IOException.class, () -> PlaybackSourceResolver.resolveSources(() -> true,
                () -> { throw new IOException("official unavailable"); }, () -> false,
                () -> { embedded.incrementAndGet(); return location; }));
        assertEquals(1, embedded.get());
    }

    @Test public void providerResultArrivingAfterModeChangeCannotStartRemotePlayback() {
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicInteger embedded = new AtomicInteger();
        assertThrows(IOException.class, () -> PlaybackSourceResolver.resolveSources(online::get,
                () -> { online.set(false); return new JSONObject(); }, () -> true,
                () -> { embedded.incrementAndGet(); return new JSONObject(); }));
        assertEquals(0, embedded.get());
    }

    @Test public void existingMusicApiRechecksThePolicyForEveryEndpointBeforeNetworkWork() {
        AtomicInteger checks = new AtomicInteger();
        NeteaseApi api = new NeteaseApi("", () -> { checks.incrementAndGet(); return false; });
        assertThrows(IOException.class, api::profile);
        assertThrows(IOException.class, () -> api.songUrl(123));
        assertThrows(IOException.class, () -> api.lyricData(123));
        assertEquals(3, checks.get());
    }

    @Test public void homeRefreshFailsWhenAllSectionsAreUnavailableInsteadOfReturningAnEmptySuccess() {
        NeteaseApi api = new NeteaseApi("", () -> false);
        IOException error = assertThrows(IOException.class, () -> api.homeData(true));
        assertTrue(error.getMessage().contains("首页"));
    }

    @Test public void localArtworkUsesCachedFileAndNeverReturnsRemoteFallback() throws Exception {
        File cover = temporary.newFile("cover.jpg");
        String remote = "https://example.invalid/cover.jpg";
        assertEquals("file://" + cover.getAbsolutePath(), ArtworkCache.resolvedUri(cover, remote, true));
        assertEquals("", ArtworkCache.resolvedUri(null, remote, true));
        assertEquals(remote, ArtworkCache.resolvedUri(null, remote, false));
    }

    @Test public void cacheOwnerCanBeRecoveredFromSnapshotsAfterSessionRemoval() throws Exception {
        File root = temporary.newFolder("playlist-cache-v1");
        File old = new File(root, "user-111.json");
        File current = new File(root, "playlist-222-1234.json");
        Files.write(old.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(current.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(old.setLastModified(1000));
        assertTrue(current.setLastModified(2000));
        assertEquals(222, PlaylistCache.cachedOwnerId(root));
        assertTrue(old.exists());
        assertTrue(current.exists());
    }

    @Test public void cacheOwnerRecoveryUsesBackupsAndIgnoresInvalidNamesOrDirectories() throws Exception {
        File root = temporary.newFolder("owner-recovery");
        assertEquals(0, PlaylistCache.cachedOwnerId(root));
        Files.write(new File(root, "user-333.json.bak").toPath(), new byte[0]);
        Files.write(new File(root, "user-0.json").toPath(), new byte[0]);
        Files.write(new File(root, "user-999999999999999999999999.json").toPath(), new byte[0]);
        assertTrue(new File(root, "user-999.json").mkdir());
        Files.write(new File(root, "account.json").toPath(), new byte[0]);
        assertEquals(333, PlaylistCache.cachedOwnerId(root));
    }
}
