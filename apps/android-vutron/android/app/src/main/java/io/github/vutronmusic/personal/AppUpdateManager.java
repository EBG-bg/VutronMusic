package io.github.vutronmusic.personal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit verified updates from the Mobile fork, independent of music login and cookies. */
final class AppUpdateManager {
    static final String RELEASES_URL = "https://github.com/EBG-bg/VutronMusic/releases";
    private static final String API_URL = "https://api.github.com/repos/EBG-bg/VutronMusic/releases?per_page=100";
    static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final int MAX_API_BYTES = 4 * 1024 * 1024;
    private static final long DOWNLOAD_DEADLINE_MS = 20L * 60 * 1000;
    private static final int MAX_REDIRECTS = 5;
    interface Callback { void complete(JSObject state); }
    interface Listener { void changed(JSObject state); }
    private final Context context;
    private final PackageManager packages;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File directory, apk, partial;
    private final String currentVersion;
    private final String comparisonVersion;
    private final long currentVersionCode;
    private volatile HttpURLConnection connection;
    private Listener listener;
    private GithubReleaseUpdate.Release release;
    private String status = "idle", error = "";
    private boolean autoCheck, checking, downloading, installing, closed;
    private volatile boolean manualChecking;
    private int progress;
    private long downloadedBytes, totalBytes, checkedAt, readyVersionCode, lastProgressEvent;

    AppUpdateManager(Context context, Listener listener) throws Exception {
        this.context = context.getApplicationContext();
        this.listener = listener;
        packages = this.context.getPackageManager();
        PackageInfo installed = installedPackage();
        currentVersion = installed.versionName == null ? "" : installed.versionName;
        currentVersionCode = versionCode(installed);
        comparisonVersion = BuildConfig.UPDATE_COMPARISON_VERSION;
        preferences = this.context.getSharedPreferences("mobile_app_updates", Context.MODE_PRIVATE);
        autoCheck = preferences.getBoolean("auto_check", false);
        checkedAt = preferences.getLong("checked_at", 0);
        File cache = this.context.getCacheDir().getCanonicalFile();
        directory = new File(cache, "update");
        if (!directory.getCanonicalFile().equals(new File(cache, "update"))) throw new UpdateException("更新缓存目录无效");
        apk = new File(directory, "release.apk");
        partial = new File(directory, "release.part.apk");
        restore();
        if (!allowsInstall(BuildConfig.APP_EDITION)) {
            clearOwnedCache();
            preferences.edit().remove("ready_version_code").apply();
            readyVersionCode = 0; progress = 0; downloadedBytes = 0;
            if (release != null) status = "available";
        }
    }

    private void restore() {
        try {
            String saved = preferences.getString("release", "");
            if (!saved.isEmpty()) {
                JSONObject json = new JSONObject(saved);
                GithubReleaseUpdate.Release candidate = new GithubReleaseUpdate.Release(json.getString("version"), json.getString("tag"),
                        json.getString("releaseUrl"), json.optString("notes", ""), json.getString("assetName"),
                        json.getString("downloadUrl"), json.getString("sha256"), json.getLong("size"));
                validateRelease(candidate);
                release = candidate;
            }
            readyVersionCode = preferences.getLong("ready_version_code", 0);
            if (readyVersionCode > 0 && readyVersionCode <= currentVersionCode || release != null && !GithubReleaseUpdate.isNewer(release.version, comparisonVersion)) {
                clearOwnedCache(); clearSavedRelease(); release = null; return;
            }
            deleteOwned(partial);
            if (release != null) {
                totalBytes = release.size;
                if (readyVersionCode > currentVersionCode && apk.isFile() && apk.length() == release.size) {
                    status = "ready"; progress = 100; downloadedBytes = release.size;
                } else status = "available";
            }
        } catch (Exception invalidSavedUpdate) {
            release = null; clearSavedRelease(); clearOwnedCache();
        }
    }

    synchronized JSObject getState() {
        if (("ready".equals(status) || "permissionRequired".equals(status)) && !apk.isFile()) {
            status = release == null ? "idle" : "available"; readyVersionCode = 0; downloadedBytes = 0; progress = 0;
        }
        boolean canInstall = canInstallPackages();
        if ("permissionRequired".equals(status) && canInstall) { status = "ready"; error = ""; }
        JSObject state = new JSObject().put("currentVersion", currentVersion).put("comparisonVersion", comparisonVersion).put("currentVersionCode", currentVersionCode)
                .put("autoCheck", autoCheck).put("status", status).put("releaseUrl", release == null ? RELEASES_URL : release.releaseUrl)
                .put("progress", progress).put("downloadedBytes", downloadedBytes).put("totalBytes", totalBytes)
                .put("error", error).put("checkedAt", checkedAt).put("canInstall", canInstall)
                .put("installAllowed", allowsInstall(BuildConfig.APP_EDITION)).put("autoCheckPaused", LocalOnlyMode.enabled(context));
        state.put("latestVersion", release == null ? "" : release.version).put("notes", release == null ? "" : release.notes);
        return state;
    }

    JSObject setAutoCheck(boolean enabled) {
        synchronized (this) { autoCheck = enabled; preferences.edit().putBoolean("auto_check", enabled).apply(); }
        publish(); return getState();
    }
    static boolean shouldCheck(boolean manual, boolean enabled, long lastCheck, long now, boolean busy) {
        return !busy && (manual || enabled && (lastCheck <= 0 || now < lastCheck || now - lastCheck >= AUTO_INTERVAL_MS));
    }
    static boolean allowsInstall(String edition) { return "mobile".equals(edition); }
    static boolean allowsCheck(boolean manual, boolean localOnly) { return manual || !localOnly; }

    synchronized void onNetworkModeChanged() {
        if (LocalOnlyMode.enabled(context) && checking && !manualChecking) {
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
        }
        publish();
    }

    void check(boolean manual, Callback callback) {
        final long previousCheckedAt;
        synchronized (this) {
            if (!allowsCheck(manual, LocalOnlyMode.enabled(context))) { callback.complete(getState()); return; }
            if (closed || !shouldCheck(manual, autoCheck, checkedAt, System.currentTimeMillis(), checking || downloading || installing)) { callback.complete(getState()); return; }
            previousCheckedAt = checkedAt;
            checking = true; manualChecking = manual; status = "checking"; error = ""; checkedAt = System.currentTimeMillis();
            preferences.edit().putLong("checked_at", checkedAt).apply();
        }
        publish();
        worker.execute(() -> {
            try {
                if (!allowsCheck(manual, LocalOnlyMode.enabled(context))) return;
                GithubReleaseUpdate.Release latest = GithubReleaseUpdate.selectLatest(fetchReleases());
                if (!allowsCheck(manual, LocalOnlyMode.enabled(context))) return;
                if (latest == null) throw new UpdateException("GitHub 暂无可验证的稳定 ARM64 安装包，请稍后再检查");
                synchronized (this) {
                    if (latest == null || !GithubReleaseUpdate.isNewer(latest.version, comparisonVersion)) {
                        release = latest; status = "upToDate"; progress = 0; downloadedBytes = 0; totalBytes = latest == null ? 0 : latest.size;
                        clearSavedRelease(); clearOwnedCache();
                    } else {
                        validateRelease(latest);
                        boolean same = release != null && latest.sha256.equals(release.sha256) && latest.size == release.size;
                        release = latest; totalBytes = latest.size;
                        if (!same) { readyVersionCode = 0; progress = 0; downloadedBytes = 0; }
                        status = allowsInstall(BuildConfig.APP_EDITION) && same && readyVersionCode > currentVersionCode && apk.isFile() && apk.length() == latest.size ? "ready" : "available";
                        saveRelease();
                    }
                }
            } catch (Exception failure) {
                if (allowsCheck(manual, LocalOnlyMode.enabled(context))) fail(failure, "检查 GitHub 更新失败");
            } finally {
                synchronized (this) {
                    checking = false;
                    if (!allowsCheck(manual, LocalOnlyMode.enabled(context))) {
                        status = release == null ? "idle" : "available"; error = ""; checkedAt = previousCheckedAt;
                        preferences.edit().putLong("checked_at", checkedAt).apply();
                    }
                }
                publish(); callback.complete(getState());
            }
        });
    }

    void download(Callback callback) {
        if (!allowsInstall(BuildConfig.APP_EDITION)) { fail(new UpdateException("当前构建只查看公开更新，不允许跨通道下载安装"), "更新通道不同"); callback.complete(getState()); return; }
        final GithubReleaseUpdate.Release selected;
        synchronized (this) {
            if (closed || checking || downloading || installing) { callback.complete(getState()); return; }
            if (release == null || !GithubReleaseUpdate.isNewer(release.version, comparisonVersion)) { fail(new UpdateException("请先检查并选择可用更新"), "更新不可用"); callback.complete(getState()); return; }
            if (readyVersionCode > currentVersionCode && apk.isFile() && apk.length() == release.size) { status = "ready"; error = ""; callback.complete(getState()); return; }
            selected = release; downloading = true; status = "downloading"; error = ""; progress = 0;
            downloadedBytes = 0; totalBytes = selected.size; lastProgressEvent = 0;
        }
        publish();
        worker.execute(() -> {
            try {
                validateRelease(selected);
                if (!directory.isDirectory() && !directory.mkdirs()) throw new UpdateException("无法创建更新缓存目录");
                downloadFile(selected);
                verifyDownloadedFile(partial, selected.sha256, selected.size);
                PackageInfo archive = verifyArchive(partial, selected);
                if (apk.exists() && !deleteOwned(apk)) throw new UpdateException("无法替换旧更新安装包");
                if (!partial.renameTo(apk)) throw new UpdateException("无法保存已校验的更新安装包");
                synchronized (this) {
                    readyVersionCode = versionCode(archive); progress = 100; downloadedBytes = selected.size; status = "ready"; saveRelease();
                }
            } catch (Exception failure) { deleteOwned(partial); fail(failure, "下载更新失败"); }
            finally { synchronized (this) { downloading = false; } publish(); callback.complete(getState()); }
        });
    }

    void install(Activity activity, Callback callback) {
        if (!allowsInstall(BuildConfig.APP_EDITION)) { fail(new UpdateException("当前构建不允许安装公开通道更新"), "更新通道不同"); callback.complete(getState()); return; }
        final GithubReleaseUpdate.Release selected;
        synchronized (this) {
            if (closed || checking || downloading || installing) { callback.complete(getState()); return; }
            if (release == null || !apk.isFile()) { fail(new UpdateException("请先下载更新安装包"), "更新尚未下载"); callback.complete(getState()); return; }
            installing = true; selected = release;
        }
        worker.execute(() -> {
            try {
                verifyDownloadedFile(apk, selected.sha256, selected.size);
                verifyArchive(apk, selected);
                main.post(() -> {
                    try {
                        synchronized (this) { if (closed) return; }
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) throw new UpdateException("应用窗口不可用，请返回应用后重试");
                        if (!canInstallPackages()) {
                            synchronized (this) { status = "permissionRequired"; error = ""; }
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())));
                        } else {
                            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
                            Intent installer = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivity(installer);
                            synchronized (this) { status = "ready"; error = ""; }
                        }
                    } catch (Exception failure) { fail(failure, "无法打开系统安装器"); }
                    finally { synchronized (this) { installing = false; } publish(); callback.complete(getState()); }
                });
            } catch (Exception failure) {
                synchronized (this) {
                    // A failed integrity/package check must not reuse a same-sized corrupt APK.
                    readyVersionCode = 0; progress = 0; downloadedBytes = 0;
                    preferences.edit().putLong("ready_version_code", 0).apply();
                    deleteOwned(apk);
                }
                fail(failure, "更新安装包校验失败");
                synchronized (this) { installing = false; }
                publish(); callback.complete(getState());
            }
        });
    }

    private JSONArray fetchReleases() throws Exception {
        if (!allowsCheck(manualChecking, LocalOnlyMode.enabled(context))) throw new UpdateException("本地模式已暂停自动检查");
        HttpURLConnection request = request(new URL(API_URL), "application/vnd.github+json");
        connection = request;
        if (!allowsCheck(manualChecking, LocalOnlyMode.enabled(context))) { request.disconnect(); throw new UpdateException("本地模式已暂停自动检查"); }
        try {
            int code = request.getResponseCode();
            if (code == 403 || code == 429) throw new UpdateException("GitHub 请求受限，请稍后手动重试（HTTP " + code + "）");
            if (code != 200) throw new UpdateException("GitHub 更新检查返回 HTTP " + code);
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new UpdateException("更新检查已中止");
                    if (bytes.size() + count > MAX_API_BYTES) throw new UpdateException("GitHub 更新信息超出大小限制");
                    bytes.write(buffer, 0, count);
                }
                return new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { request.disconnect(); connection = null; }
    }

    private void downloadFile(GithubReleaseUpdate.Release selected) throws Exception {
        URL url = new URL(selected.downloadUrl);
        long deadline = SystemClock.elapsedRealtime() + DOWNLOAD_DEADLINE_MS;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!GithubReleaseUpdate.isTrustedDownloadUrl(url.toString())) throw new UpdateException("GitHub 安装包重定向来源不受信任");
            HttpURLConnection request = request(url, "application/octet-stream");
            connection = request;
            try {
                int code = request.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = request.getHeaderField("Location");
                    if (redirect == MAX_REDIRECTS || location == null) throw new UpdateException("GitHub 安装包重定向次数超限");
                    url = new URL(url, location); continue;
                }
                if (code == 403 || code == 429) throw new UpdateException("GitHub 下载请求受限，请稍后重试（HTTP " + code + "）");
                if (code != 200 || request.getHeaderField("Content-Range") != null) throw new UpdateException("GitHub 安装包下载返回 HTTP " + code);
                long length = request.getContentLength();
                if (length > 0 && length != selected.size) throw new UpdateException("安装包下载大小与 GitHub 公布值不一致");
                try (InputStream input = request.getInputStream(); FileOutputStream output = new FileOutputStream(partial, false)) {
                    byte[] buffer = new byte[65536]; long bytes = 0; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > deadline) throw new UpdateException("更新下载已中止或超时，请重新下载");
                        bytes += count;
                        if (bytes > selected.size || bytes > MAX_APK_BYTES) throw new UpdateException("安装包下载超出公布大小或安全上限");
                        output.write(buffer, 0, count); progress(bytes, selected.size);
                    }
                    output.getFD().sync();
                }
                return;
            } finally { request.disconnect(); connection = null; }
        }
        throw new UpdateException("GitHub 安装包重定向次数超限");
    }
    private HttpURLConnection request(URL url, String accept) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new UpdateException("更新仅允许 HTTPS 连接");
        HttpURLConnection request = (HttpURLConnection) url.openConnection();
        request.setInstanceFollowRedirects(false); request.setConnectTimeout(15000); request.setReadTimeout(20000); request.setUseCaches(false);
        request.setRequestProperty("User-Agent", "VutronMusic-Mobile/" + currentVersion);
        request.setRequestProperty("Accept", accept); request.setRequestProperty("Accept-Encoding", "identity");
        request.setRequestProperty("Cookie", "");
        return request;
    }
    private synchronized void progress(long bytes, long total) {
        downloadedBytes = bytes; totalBytes = total; progress = Math.min(99, (int) (bytes * 100 / total));
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgressEvent >= 250) { lastProgressEvent = now; publish(); }
    }
    static void validateRelease(GithubReleaseUpdate.Release release) throws Exception {
        if (release == null || release.size <= 0 || release.size > MAX_APK_BYTES || !release.sha256.matches("[a-f0-9]{64}")) throw new UpdateException("GitHub 安装包缺少有效的 SHA-256 或大小信息");
        URI uri = new URI(release.downloadUrl);
        if (!GithubReleaseUpdate.isTrustedDownloadUrl(release.downloadUrl) || !"github.com".equalsIgnoreCase(uri.getHost())
                || !uri.getRawPath().startsWith("/EBG-bg/VutronMusic/releases/download/") || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new UpdateException("更新安装包必须来自本项目的 GitHub Release");
    }
    static void verifyDownloadedFile(File file, String expectedSha256, long expectedSize) throws Exception {
        if (expectedSize <= 0 || expectedSize > MAX_APK_BYTES || expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) throw new UpdateException("更新校验信息无效");
        if (!file.isFile() || file.length() != expectedSize) throw new UpdateException("安装包实际大小与 GitHub 公布值不一致，请重新下载");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new UpdateException("更新校验已中止");
                digest.update(buffer, 0, count);
            }
        }
        if (!hex(digest.digest()).equals(expectedSha256)) throw new UpdateException("安装包 SHA-256 校验失败，请重新下载");
    }
    private PackageInfo verifyArchive(File file, GithubReleaseUpdate.Release selected) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = packages.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = installedPackage();
        if (archive == null || !context.getPackageName().equals(archive.packageName)) throw new UpdateException("安装包包名与当前应用不一致");
        if (versionCode(archive) <= versionCode(installed)) throw new UpdateException("安装包版本号未高于当前应用");
        if (!selected.version.equals(archive.versionName)) throw new UpdateException("安装包版本与 GitHub Release 标记不一致");
        if (!sameSignerDigests(signers(archive), signers(installed))) throw new UpdateException("安装包签名与当前应用不一致，已阻止安装");
        return archive;
    }
    private PackageInfo installedPackage() throws PackageManager.NameNotFoundException {
        return packages.getPackageInfo(context.getPackageName(), Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    }
    private static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static String[] signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        if (signatures == null) return new String[0];
        String[] result = new String[signatures.length];
        for (int i = 0; i < signatures.length; i++) result[i] = hex(MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray()));
        return result;
    }
    static boolean sameSignerDigests(String[] first, String[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        String[] left = first.clone(), right = second.clone();
        for (String value : left) if (value == null || value.isEmpty()) return false;
        for (String value : right) if (value == null || value.isEmpty()) return false;
        Arrays.sort(left); Arrays.sort(right); return Arrays.equals(left, right);
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }
    private boolean canInstallPackages() { return Build.VERSION.SDK_INT < 26 || packages.canRequestPackageInstalls(); }
    private void saveRelease() throws Exception {
        if (release == null) return;
        JSONObject value = new JSONObject().put("version", release.version).put("tag", release.tagName)
                .put("releaseUrl", release.releaseUrl).put("notes", release.notes).put("assetName", release.assetName)
                .put("downloadUrl", release.downloadUrl).put("sha256", release.sha256).put("size", release.size);
        if (!preferences.edit().putString("release", value.toString()).putLong("ready_version_code", readyVersionCode).commit()) throw new UpdateException("无法保存更新状态，请重试");
    }
    private void clearSavedRelease() { readyVersionCode = 0; preferences.edit().remove("release").remove("ready_version_code").apply(); }
    private boolean deleteOwned(File file) {
        try { return file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) && (!file.exists() || file.delete()); }
        catch (IOException invalidPath) { return false; }
    }
    private void clearOwnedCache() { deleteOwned(apk); deleteOwned(partial); }
    private synchronized void fail(Exception failure, String fallback) {
        status = "error";
        // Network exception messages may contain signed redirect URLs. Do not expose or persist them.
        error = failure instanceof UpdateException ? failure.getMessage() : fallback + "（" + failure.getClass().getSimpleName() + "）";
    }
    private void publish() {
        Listener target; synchronized (this) { target = closed ? null : listener; }
        if (target != null) target.changed(getState());
    }
    void close() {
        synchronized (this) { closed = true; listener = null; }
        HttpURLConnection active = connection; if (active != null) active.disconnect(); worker.shutdownNow();
    }
    static final class UpdateException extends IOException { UpdateException(String message) { super(message); } }
}
