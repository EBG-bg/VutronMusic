package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the public GitHub releases response without Android or account state. */
final class GithubReleaseUpdate {
    private static final String REPOSITORY_PATH = "/EBG-bg/VutronMusic";
    private static final String DOWNLOAD_PATH = REPOSITORY_PATH + "/releases/download/";
    private static final String TAG_PREFIX = "mobile-v";
    private static final long MAX_ASSET_SIZE = 200L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)");
    private static final Pattern DIGEST = Pattern.compile("sha256:([0-9a-fA-F]{64})");
    private static final Pattern PUBLISHED_AT = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");

    private GithubReleaseUpdate() {}

    static final class Release {
        final String version;
        final String tagName;
        final String releaseUrl;
        final String notes;
        final String assetName;
        final String downloadUrl;
        /** Lowercase hexadecimal SHA-256, without the API's algorithm prefix. */
        final String sha256;
        final long size;

        Release(String version, String tagName, String releaseUrl, String notes,
                String assetName, String downloadUrl, String sha256, long size) {
            this.version = version;
            this.tagName = tagName;
            this.releaseUrl = releaseUrl;
            this.notes = notes;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    static Release selectLatest(JSONArray releases) {
        if (releases == null) return null;
        Release latest = null;
        for (int index = 0; index < releases.length(); index++) {
            Release candidate = parseRelease(releases.optJSONObject(index));
            if (candidate != null && (latest == null || isNewer(candidate.version, latest.version))) {
                latest = candidate;
            }
        }
        return latest;
    }

    static boolean isNewer(String candidate, String current) {
        BigInteger[] next = parseVersion(candidate);
        BigInteger[] installed = parseVersion(current);
        if (next == null || installed == null) return false;
        for (int part = 0; part < next.length; part++) {
            int compared = next[part].compareTo(installed[part]);
            if (compared != 0) return compared > 0;
        }
        return false;
    }

    /** Allows the repository's download URLs and GitHub's asset redirect hosts. */
    static boolean isTrustedDownloadUrl(String url) {
        URI uri = trustedHttpsUri(url);
        if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!"github.com".equals(host)) return true;
        String path = uri.getRawPath();
        if (path == null || !path.startsWith(DOWNLOAD_PATH)) return false;
        String[] suffix = path.substring(DOWNLOAD_PATH.length()).split("/", -1);
        return suffix.length == 2 && safeDownloadSegment(suffix[0]) && safeDownloadSegment(suffix[1]);
    }

    private static Release parseRelease(JSONObject release) {
        if (release == null || !Boolean.FALSE.equals(release.opt("draft"))
                || !Boolean.FALSE.equals(release.opt("prerelease"))
                || !isPublished(string(release, "published_at"))) return null;
        String tag = string(release, "tag_name");
        if (!tag.startsWith(TAG_PREFIX)) return null;
        String version = tag.substring(TAG_PREFIX.length());
        if (parseVersion(version) == null) return null;
        String releaseUrl = string(release, "html_url");
        if (!isRepositoryUrl(releaseUrl, REPOSITORY_PATH + "/releases/tag/" + tag)) return null;
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        String expectedName = "Vutron-Music-Mobile-" + version + "-arm64.apk";
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null || !expectedName.equals(string(asset, "name"))) continue;
            String downloadUrl = string(asset, "browser_download_url");
            if (!isRepositoryUrl(downloadUrl, DOWNLOAD_PATH + tag + "/" + expectedName)) continue;
            Matcher digest = DIGEST.matcher(string(asset, "digest"));
            if (!digest.matches()) continue;
            Object rawSize = asset.opt("size");
            if (!(rawSize instanceof Number)) continue;
            double size = ((Number) rawSize).doubleValue();
            if (!(size > 0 && size <= MAX_ASSET_SIZE) || size != Math.floor(size)) continue;
            return new Release(version, tag, releaseUrl, string(release, "body"), expectedName,
                    downloadUrl, digest.group(1).toLowerCase(Locale.ROOT), (long) size);
        }
        return null;
    }

    private static boolean isRepositoryUrl(String value, String expectedPath) {
        URI uri = trustedHttpsUri(value);
        return uri != null && "github.com".equalsIgnoreCase(uri.getHost())
                && expectedPath.equals(uri.getRawPath()) && uri.getRawQuery() == null;
    }

    private static URI trustedHttpsUri(String value) {
        if (value == null || value.isEmpty()) return null;
        final URI uri;
        try { uri = new URI(value); }
        catch (URISyntaxException invalid) { return null; }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
        String authority = uri.getRawAuthority();
        if (!host.equalsIgnoreCase(authority)
                && !(host + ":443").equalsIgnoreCase(authority)) return null;
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!"github.com".equals(normalizedHost)
                && !"release-assets.githubusercontent.com".equals(normalizedHost)
                && !"objects.githubusercontent.com".equals(normalizedHost)
                && !"github-releases.githubusercontent.com".equals(normalizedHost)) return null;
        return uri;
    }

    private static boolean safeDownloadSegment(String value) {
        return !value.isEmpty() && !".".equals(value) && !"..".equals(value)
                && value.indexOf('%') == -1;
    }

    private static BigInteger[] parseVersion(String value) {
        if (value == null) return null;
        Matcher matcher = VERSION.matcher(value);
        if (!matcher.matches()) return null;
        return new BigInteger[] { new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2)), new BigInteger(matcher.group(3)) };
    }

    private static boolean isPublished(String value) {
        if (!PUBLISHED_AT.matcher(value).matches()) return false;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0);
        return format.parse(value, position) != null && position.getIndex() == value.length();
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }
}
