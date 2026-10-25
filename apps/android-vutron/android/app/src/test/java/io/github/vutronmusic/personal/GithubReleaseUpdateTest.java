package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class GithubReleaseUpdateTest {
    private static final String REPOSITORY = "https://github.com/EBG-bg/VutronMusic";
    private static final String SHA256 =
            "c443a1cf39291cba09e024fb3f70e044f95f61e3e6906cc4e9d4e3f74b526207";

    private JSONObject release(String version) throws Exception {
        String tag = "mobile-v" + version;
        String name = "Vutron-Music-Mobile-" + version + "-arm64.apk";
        JSONObject asset = new JSONObject().put("name", name).put("size", 24535301L)
                .put("digest", "sha256:" + SHA256)
                .put("browser_download_url", REPOSITORY + "/releases/download/" + tag + "/" + name);
        return new JSONObject().put("tag_name", tag)
                .put("html_url", REPOSITORY + "/releases/tag/" + tag)
                .put("draft", false).put("prerelease", false)
                .put("published_at", "2026-10-25T12:23:29Z")
                .put("body", "Mobile release notes")
                .put("assets", new JSONArray().put(asset));
    }

    private JSONObject asset(JSONObject release) throws Exception {
        return release.getJSONArray("assets").getJSONObject(0);
    }

    private void assertRejected(JSONObject release) {
        assertNull(GithubReleaseUpdate.selectLatest(new JSONArray().put(release)));
    }

    @Test public void comparesThreeNumericPartsAndEqualVersionDoesNotUpgrade() {
        assertTrue(GithubReleaseUpdate.isNewer("1.0.10", "1.0.9"));
        assertFalse(GithubReleaseUpdate.isNewer("1.0.9", "1.0.10"));
        assertTrue(GithubReleaseUpdate.isNewer("1.10.0", "1.9.999"));
        assertTrue(GithubReleaseUpdate.isNewer("2.0.0", "1.999.999"));
        assertFalse(GithubReleaseUpdate.isNewer("1.0.0", "1.0.0"));
        assertTrue(GithubReleaseUpdate.isNewer("2147483648.0.0", "2147483647.999.999"));
    }

    @Test public void rejectsMalformedOrNonStableVersionStrings() {
        String[] invalid = { null, "", "v1.0.0", "mobile-v1.0.0", "1.0", "1.0.0.0",
                "1.0.0-beta", "1.0.0+build", "01.0.0", "1.-1.0", " 1.0.0" };
        for (String value : invalid) {
            assertFalse(String.valueOf(value), GithubReleaseUpdate.isNewer(value, "1.0.0"));
            assertFalse(String.valueOf(value), GithubReleaseUpdate.isNewer("2.0.0", value));
        }
    }

    @Test public void selectsHighestVersionRatherThanFirstArrayEntry() throws Exception {
        JSONArray releases = new JSONArray().put(release("1.0.9")).put(release("1.0.1"))
                .put(release("1.0.10")).put(release("1.0.2"));
        GithubReleaseUpdate.Release selected = GithubReleaseUpdate.selectLatest(releases);
        assertNotNull(selected);
        assertEquals("1.0.10", selected.version);
        assertFalse(GithubReleaseUpdate.isNewer(selected.version, "1.0.10"));
    }

    @Test public void retainsOnlyVerifiedReleaseMetadataAndNormalizesDigest() throws Exception {
        JSONObject data = release("1.0.0");
        asset(data).put("digest", "sha256:" + SHA256.toUpperCase(java.util.Locale.ROOT));
        GithubReleaseUpdate.Release selected = GithubReleaseUpdate.selectLatest(new JSONArray().put(data));
        assertNotNull(selected);
        assertEquals("1.0.0", selected.version);
        assertEquals("mobile-v1.0.0", selected.tagName);
        assertEquals(REPOSITORY + "/releases/tag/mobile-v1.0.0", selected.releaseUrl);
        assertEquals("Mobile release notes", selected.notes);
        assertEquals("Vutron-Music-Mobile-1.0.0-arm64.apk", selected.assetName);
        assertEquals(REPOSITORY + "/releases/download/mobile-v1.0.0/" + selected.assetName, selected.downloadUrl);
        assertEquals(SHA256, selected.sha256);
        assertEquals(24535301L, selected.size);
    }

    @Test public void ignoresDesktopDraftPrereleaseAndUnpublishedReleases() throws Exception {
        JSONObject desktop = release("90.0.0").put("tag_name", "v90.0.0");
        JSONObject draft = release("8.0.0").put("draft", true);
        JSONObject prerelease = release("7.0.0").put("prerelease", true);
        JSONObject unpublished = release("6.0.0").put("published_at", JSONObject.NULL);
        JSONObject invalidDate = release("5.0.0").put("published_at", "2026-02-30T12:00:00Z");
        JSONArray input = new JSONArray().put(desktop).put(draft).put(prerelease).put(unpublished)
                .put(invalidDate).put(release("1.0.0"));
        assertEquals("1.0.0", GithubReleaseUpdate.selectLatest(input).version);
    }

    @Test public void rejectsOtherArchitecturesWrongNamesAndMismatchedAssetVersions() throws Exception {
        for (String name : new String[] { "Vutron-Music-Mobile-1.0.0-x86.apk",
                "Vutron-Music-Mobile-1.0.0-arm64-v8a.apk", "Vutron-Music-Mobile-2.0.0-arm64.apk",
                "Vutron-Music-Mobile-1.0.0-arm64.apk.sha256", "VutronMusic-1.0.0-arm64.apk" }) {
            JSONObject input = release("1.0.0");
            asset(input).put("name", name);
            assertRejected(input);
        }
    }

    @Test public void rejectsMissingOrIncorrectDigest() throws Exception {
        for (Object digest : new Object[] { JSONObject.NULL, "", "sha256:1234",
                "sha512:" + SHA256, "sha256:" + SHA256 + "0", "sha256:" + SHA256.replace('c', 'g'), 123 }) {
            JSONObject input = release("1.0.0");
            asset(input).put("digest", digest);
            assertRejected(input);
        }
        JSONObject missing = release("1.0.0");
        asset(missing).remove("digest");
        assertRejected(missing);
    }

    @Test public void rejectsMissingZeroNegativeOversizeOrFractionalAssetSize() throws Exception {
        for (Object size : new Object[] { JSONObject.NULL, 0, -1, 200L * 1024 * 1024 + 1, 1.5, "12" }) {
            JSONObject input = release("1.0.0");
            asset(input).put("size", size);
            assertRejected(input);
        }
        JSONObject maximum = release("1.0.0");
        asset(maximum).put("size", 200L * 1024 * 1024);
        assertNotNull(GithubReleaseUpdate.selectLatest(new JSONArray().put(maximum)));
    }

    @Test public void requiresMatchingRepositoryAndTagReleasePage() throws Exception {
        String[] invalid = { "https://github.com/other/VutronMusic/releases/tag/mobile-v1.0.0",
                REPOSITORY + "/releases/tag/mobile-v2.0.0", REPOSITORY + "/releases",
                REPOSITORY + "/releases/tag/mobile-v1.0.0?redirect=1",
                "https://github.com.evil.test/EBG-bg/VutronMusic/releases/tag/mobile-v1.0.0",
                "https://user@github.com/EBG-bg/VutronMusic/releases/tag/mobile-v1.0.0",
                "http://github.com/EBG-bg/VutronMusic/releases/tag/mobile-v1.0.0" };
        for (String value : invalid) assertRejected(release("1.0.0").put("html_url", value));
    }

    @Test public void initialAssetAddressMustExactlyMatchRepositoryTagAndName() throws Exception {
        String valid = asset(release("1.0.0")).getString("browser_download_url");
        String[] invalid = { valid.replace("/EBG-bg/", "/other/"),
                valid.replace("mobile-v1.0.0/", "mobile-v2.0.0/"),
                valid.replace("Vutron-Music-Mobile-1.0.0-", "Vutron-Music-Mobile-2.0.0-"),
                valid + "?download=1", valid + "#asset", valid.replace("https://", "http://"),
                valid.replace("github.com", "github.com.evil.test"),
                valid.replace("github.com", "user@github.com"),
                valid.replace("github.com", "github.com:444"),
                "https://release-assets.githubusercontent.com/release/asset.apk" };
        for (String value : invalid) {
            JSONObject input = release("1.0.0");
            asset(input).put("browser_download_url", value);
            assertRejected(input);
        }
    }

    @Test public void permitsOnlyHttpsDefaultPortAndExplicitTrustedRedirectHosts() throws Exception {
        String url = asset(release("1.0.0")).getString("browser_download_url");
        assertTrue(GithubReleaseUpdate.isTrustedDownloadUrl(url));
        assertTrue(GithubReleaseUpdate.isTrustedDownloadUrl(url.replace("github.com", "github.com:443")));
        for (String host : new String[] { "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "github-releases.githubusercontent.com" }) {
            assertTrue(host, GithubReleaseUpdate.isTrustedDownloadUrl("https://" + host + "/asset?token=signed%2Fvalue"));
            assertTrue(host, GithubReleaseUpdate.isTrustedDownloadUrl("https://" + host + ":443/asset"));
        }
    }

    @Test public void rejectsUntrustedHostsCredentialsInUrlAndIncorrectPorts() throws Exception {
        String valid = asset(release("1.0.0")).getString("browser_download_url");
        String[] invalid = { null, "", "not a URL", valid.replace("https://", "http://"),
                valid.replace("github.com", "github.com.evil.test"),
                valid.replace("github.com", "evilgithub.com"), valid.replace("github.com", "github.com."),
                valid.replace("github.com", "github.com@evil.test"),
                valid.replace("github.com", "user:password@github.com"),
                valid.replace("github.com", "github.com:80"), valid.replace("github.com", "github.com:444"),
                valid.replace("github.com", "github.com:"), valid.replace("/EBG-bg/", "/other/"),
                valid.replace("/EBG-bg/", "/%45BG-bg/"), valid + "#fragment",
                "https://github.com/EBG-bg/VutronMusic/releases/download/../evil.apk",
                "https://github.com/EBG-bg/VutronMusic/releases/download/mobile-v1.0.0/",
                "https://release-assets.githubusercontent.com.evil.test/asset",
                "https://user@objects.githubusercontent.com/asset",
                "http://github-releases.githubusercontent.com/asset",
                "https://objects.githubusercontent.com:8443/asset" };
        for (String value : invalid) assertFalse(String.valueOf(value), GithubReleaseUpdate.isTrustedDownloadUrl(value));
    }

    @Test public void skipsMalformedEntriesAndKeepsLookingForValidAsset() throws Exception {
        JSONObject valid = release("1.0.0");
        JSONObject validAsset = asset(valid);
        JSONObject badAsset = new JSONObject(validAsset.toString()).put("digest", "sha256:invalid");
        valid.put("assets", new JSONArray().put(JSONObject.NULL).put(42).put(badAsset).put(validAsset));
        JSONArray input = new JSONArray().put(JSONObject.NULL).put(42).put("release")
                .put(release("9.0.0").put("draft", "false"))
                .put(release("8.0.0").put("prerelease", JSONObject.NULL)).put(valid);
        assertEquals("1.0.0", GithubReleaseUpdate.selectLatest(input).version);
    }

    @Test public void returnsNullWhenNoValidReleaseExists() throws Exception {
        assertNull(GithubReleaseUpdate.selectLatest(null));
        assertNull(GithubReleaseUpdate.selectLatest(new JSONArray()));
        assertNull(GithubReleaseUpdate.selectLatest(new JSONArray().put(new JSONObject()).put("invalid")));
        assertRejected(release("1.0.0").put("assets", new JSONArray()));
        assertRejected(release("1.0.0-beta"));
        assertRejected(release("01.0.0"));
    }
}
