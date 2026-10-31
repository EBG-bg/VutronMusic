package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class GithubMigrationTest {
    private static JSONObject release(String repository, String version) throws Exception {
        String base = "https://github.com/" + repository + "/releases/";
        String tag = "mobile-v" + version;
        String name = "Vutron-Music-Mobile-" + version + "-arm64.apk";
        return new JSONObject().put("tag_name", tag).put("html_url", base + "tag/" + tag)
                .put("draft", false).put("prerelease", false).put("published_at", "2026-10-31T12:00:00Z")
                .put("assets", new JSONArray().put(new JSONObject().put("name", name).put("size", 25000000)
                        .put("digest", "sha256:" + "a".repeat(64)).put("browser_download_url", base + "download/" + tag + "/" + name)));
    }

    @Test public void acceptsOnlyTheStandaloneRepositoryForFutureUpdates() throws Exception {
        assertEquals("EBG-bg/VutronMusic-Android", GithubReleaseUpdate.REPOSITORY);
        assertEquals("https://github.com/EBG-bg/VutronMusic-Android/releases", AppUpdateManager.RELEASES_URL);
        GithubReleaseUpdate.Release selected = GithubReleaseUpdate.selectLatest(new JSONArray().put(release(GithubReleaseUpdate.REPOSITORY, "1.1.0")));
        assertEquals("1.1.0", selected.version);
        assertTrue(GithubReleaseUpdate.isNewer(selected.version, "1.0.2"));
        assertTrue(selected.downloadUrl.startsWith("https://github.com/EBG-bg/VutronMusic-Android/releases/download/"));
        assertTrue(GithubReleaseUpdate.hasValidLocation(selected));
        AppUpdateManager.validateRelease(selected);
    }

    @Test public void emptyOrOldOnlyFeedsDoNotFallBackToTheArchive() throws Exception {
        assertNull(GithubReleaseUpdate.selectLatest(new JSONArray()));
        assertNull(GithubReleaseUpdate.selectLatest(new JSONArray().put(release("EBG-bg/VutronMusic", "9.0.0"))));
        JSONArray mixed = new JSONArray().put(release("EBG-bg/VutronMusic", "9.0.0")).put(release(GithubReleaseUpdate.REPOSITORY, "1.1.0"));
        assertEquals("1.1.0", GithubReleaseUpdate.selectLatest(mixed).version);
    }

    @Test public void oldSavedMetadataIsRejectedEvenWithValidHashAndSize() throws Exception {
        GithubReleaseUpdate.Release current = GithubReleaseUpdate.selectLatest(new JSONArray().put(release(GithubReleaseUpdate.REPOSITORY, "1.1.0")));
        GithubReleaseUpdate.Release old = new GithubReleaseUpdate.Release(current.version, current.tagName,
                current.releaseUrl.replace("/VutronMusic-Android/", "/VutronMusic/"), current.notes, current.assetName,
                current.downloadUrl.replace("/VutronMusic-Android/", "/VutronMusic/"), current.sha256, current.size);
        assertFalse(GithubReleaseUpdate.hasValidLocation(old));
        assertFalse(GithubReleaseUpdate.isTrustedDownloadUrl(old.downloadUrl));
        assertThrows(IOException.class, () -> AppUpdateManager.validateRelease(old));
    }

    @Test public void newPageAndOldAssetCannotBeMixed() throws Exception {
        JSONObject value = release(GithubReleaseUpdate.REPOSITORY, "1.1.0");
        JSONObject asset = value.getJSONArray("assets").getJSONObject(0);
        asset.put("browser_download_url", asset.getString("browser_download_url").replace("/VutronMusic-Android/", "/VutronMusic/"));
        assertNull(GithubReleaseUpdate.selectLatest(new JSONArray().put(value)));
    }

    @Test public void newDownloadPathDoesNotOpenOtherRepositoriesOrUnsafeAddresses() throws Exception {
        String url = release(GithubReleaseUpdate.REPOSITORY, "1.1.0").getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
        assertTrue(GithubReleaseUpdate.isTrustedDownloadUrl(url));
        for (String invalid : new String[]{ url.replace("/EBG-bg/", "/other/"), url.replace("/VutronMusic-Android/", "/VutronMusic/"),
                url + "?download=1", url + "#fragment", url.replace("https://", "http://"), url.replace("mobile-v1.1.0/", "../") }) {
            assertFalse(invalid, GithubReleaseUpdate.isTrustedDownloadUrl(invalid));
        }
    }
}
