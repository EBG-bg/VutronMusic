package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runs the desktop source matcher in a single, app-private Node.js Mobile runtime. */
public final class EmbeddedSources {
    private static final Object LOCK = new Object();
    private static final String BUNDLE = "sources-runtime-0280-node18204-v3";
    private static final String ASSETS = "sources-runtime";
    private static final String START_SYMBOL = "_ZN4node5StartEiPPc";
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final long REQUEST_TIMEOUT_MS = 40000;
    private static final Object LOG_LOCK = new Object();
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Vutron-source-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean started;
    private static volatile boolean ready;
    private static volatile IOException startupFailure;
    private static volatile String stage = "尚未启动";
    private static volatile File diagnosticFile;
    private static String token;
    private static int port;

    private EmbeddedSources() { }

    /** Returns the upstream matcher's entire audio object, including source and request headers. */
    public static JSONObject songUrl(Context context, long id) throws IOException {
        return songUrl(context, id, ignored -> { });
    }

    public static JSONObject songUrl(Context context, long id, Consumer<String> status) throws IOException {
        if (id <= 0) throw new IOException("歌曲 ID 无效");
        long deadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS;
        status.accept("正在启动内置音源引擎…");
        ensureStarted(context.getApplicationContext());
        awaitReady(deadline);
        status.accept("正在比较其它音源音质…");
        record("match-start", null);
        JSONObject response = get("/song?id=" + id, deadline, false);
        JSONObject item = response.optJSONObject("data");
        if (response.optInt("code") != 200 || item == null) {
            record("match-failed-" + response.optInt("code"), null);
            throw new IOException(response.optString("message", "暂未找到这首歌的可用音源"));
        }
        String address = item.optString("url");
        if (!address.startsWith("https://") && !address.startsWith("http://")) {
            throw new IOException("音源查找未返回有效播放地址");
        }
        record("match-ready", null);
        return item;
    }

    public static String status() { return stage; }

    /** App-private diagnostic text, excluding account credentials and signed media URLs. */
    public static String diagnostics(Context context) {
        File file = new File(context.getFilesDir(), "source-runtime.log");
        StringBuilder result = new StringBuilder("内置音源阶段：").append(stage).append('\n');
        if (!file.isFile()) return result.append("尚无音源引擎诊断记录\n").toString();
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = Math.max(0, file.length() - 65536);
            while (skip > 0) {
                long count = input.skip(skip);
                if (count <= 0) break;
                skip -= count;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1 && output.size() < 65536) output.write(buffer, 0, Math.min(count, 65536 - output.size()));
            return result.append(new String(output.toByteArray(), StandardCharsets.UTF_8)).toString();
        } catch (IOException failure) {
            return result.append("读取诊断失败：").append(failure.getClass().getSimpleName()).append('\n').toString();
        }
    }

    private static void ensureStarted(Context context) throws IOException {
        synchronized (LOCK) {
            if (startupFailure != null) throw startupFailure;
            if (started) return;
            try {
                diagnosticFile = new File(context.getFilesDir(), "source-runtime.log");
                byte[] secret = new byte[32];
                SecureRandom random = new SecureRandom();
                random.nextBytes(secret);
                port = 49152 + random.nextInt(100);
                StringBuilder value = new StringBuilder(64);
                for (byte item : secret) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
                token = value.toString();
                String library = new File(context.getApplicationInfo().nativeLibraryDir, "libnode.so").getAbsolutePath();
                // Extraction and native initialization belong to the runtime thread, so
                // callers can enforce their deadline even on slow device storage.
                Thread node = new Thread(() -> prepareAndStart(context, library), "Vutron-source-matcher");
                node.setDaemon(true);
                started = true;
                node.start();
            } catch (RuntimeException failure) {
                startupFailure = new IOException("无法准备手机内置音源服务", failure);
                record("prepare-failed", failure);
                throw startupFailure;
            }
        }
    }

    private static void prepareAndStart(Context context, String library) {
        File runtime = new File(context.getFilesDir(), BUNDLE);
        try {
            stage = "解包内置音源资源";
            record("extract-start", null);
            File marker = new File(runtime, ".ready");
            String fingerprint = bundleFingerprint(context.getAssets());
            boolean installed = false;
            if (marker.isFile()) {
                try (FileInputStream input = new FileInputStream(marker)) {
                    byte[] contents = new byte[256];
                    int count = input.read(contents);
                    installed = count > 0 && fingerprint.equals(new String(contents, 0, count, StandardCharsets.UTF_8));
                }
            }
            if (!installed) {
                copyAssets(context.getAssets(), ASSETS, runtime);
                if (!new File(runtime, "server.js").isFile()
                        || !new File(runtime, "node_modules/@unblockneteasemusic/server/src/provider/match.js").isFile()) {
                    throw new IOException("内置音源资源不完整");
                }
                try (FileOutputStream output = new FileOutputStream(marker)) {
                    output.write(fingerprint.getBytes(StandardCharsets.UTF_8));
                }
            }
            record("extract-ready", null);
            startNode(library, new File(runtime, "server.js"));
        } catch (IOException | RuntimeException failure) {
            stage = "内置音源资源准备失败";
            startupFailure = new IOException("无法准备手机内置音源资源（" + failure.getClass().getSimpleName() + "）", failure);
            record("extract-failed", failure);
        }
    }

    private static String bundleFingerprint(AssetManager assets) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(BUNDLE.getBytes(StandardCharsets.UTF_8));
            for (String source : new String[]{"server.js", "quality-selection.cjs", "strict-identity.cjs"}) {
                try (InputStream input = assets.open(ASSETS + "/" + source)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
            }
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("设备缺少 SHA-256，无法核对音源资源", failure);
        }
    }

    private static void startNode(String library, File script) {
        try {
            stage = "加载 Node 运行库";
            record("native-load-start", null);
            System.loadLibrary("c++_shared");
            System.loadLibrary("node");
            NativeLibrary node = NativeLibrary.getInstance(library);
            Function start = node.getFunction(START_SYMBOL);
            // Node/libuv expects argv strings in one contiguous allocation.
            String[] arguments = {"node", "--no-warnings", script.getAbsolutePath(), Integer.toString(port), token, diagnosticFile.getAbsolutePath()};
            byte[][] encoded = new byte[arguments.length][];
            long size = 0;
            for (int i = 0; i < arguments.length; i++) {
                encoded[i] = arguments[i].getBytes(StandardCharsets.UTF_8);
                size += encoded[i].length + 1;
            }
            try (Memory text = new Memory(size); Memory argv = new Memory((arguments.length + 1L) * Native.POINTER_SIZE)) {
                long offset = 0;
                for (int i = 0; i < encoded.length; i++) {
                    text.write(offset, encoded[i], 0, encoded[i].length);
                    text.setByte(offset + encoded[i].length, (byte) 0);
                    argv.setPointer((long) i * Native.POINTER_SIZE, text.share(offset));
                    offset += encoded[i].length + 1;
                }
                argv.setPointer((long) arguments.length * Native.POINTER_SIZE, null);
                stage = "启动内置音源脚本";
                record("node-start", null);
                int status = start.invokeInt(new Object[]{arguments.length, argv});
                record("node-exit-" + status, null);
                startupFailure = new IOException("手机内置音源服务已停止（" + status + "），请重新打开应用");
            }
        } catch (Throwable failure) {
            startupFailure = new IOException("手机内置音源服务无法启动（" + failure.getClass().getSimpleName() + "）", failure);
            record("node-failed", failure);
        } finally {
            ready = false;
            stage = "内置音源服务已停止";
        }
    }

    private static void awaitReady(long deadline) throws IOException {
        if (ready) return;
        long startupDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 15000);
        IOException lastFailure = null;
        while (SystemClock.elapsedRealtime() < startupDeadline) {
            checkInterrupted();
            if (startupFailure != null) throw startupFailure;
            try {
                JSONObject health = get("/health", Math.min(startupDeadline, SystemClock.elapsedRealtime() + 700), true);
                if (health.optInt("code") == 200 && health.optBoolean("ready")) {
                    ready = true;
                    stage = "内置音源服务就绪";
                    record("health-ready", null);
                    return;
                }
            } catch (IOException pending) {
                lastFailure = pending;
                if (startupFailure != null) throw startupFailure;
            }
            SystemClock.sleep(80);
        }
        record("health-timeout", lastFailure);
        throw new IOException("手机内置音源服务启动超时（" + stage + "），可在菜单导出播放诊断", lastFailure);
    }

    private static JSONObject get(String path, long deadline, boolean health) throws IOException {
        checkInterrupted();
        int remaining = (int) Math.min(Integer.MAX_VALUE, deadline - SystemClock.elapsedRealtime());
        if (remaining <= 0) throw new IOException("音源查找超时，请重试或选择本地文件");
        HttpURLConnection connection = null;
        ScheduledFuture<?> timeout = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            connection.setConnectTimeout(Math.min(1000, remaining));
            connection.setReadTimeout(remaining);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");
            HttpURLConnection active = connection;
            timeout = DEADLINES.schedule(active::disconnect, remaining, TimeUnit.MILLISECONDS);
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new IOException("手机内置音源服务未返回结果");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream stream = input) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    checkInterrupted();
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("音源查找超时");
                    if (output.size() + count > MAX_RESPONSE) throw new IOException("音源结果超过大小限制");
                    output.write(buffer, 0, count);
                }
            }
            JSONObject result = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            if (status != 200 && health) throw new IOException("手机内置音源服务尚未就绪");
            return result;
        } catch (JSONException invalid) {
            throw new IOException("手机内置音源服务返回了无效结果", invalid);
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (connection != null) connection.disconnect();
        }
    }

    private static void record(String event, Throwable failure) {
        File file = diagnosticFile;
        if (file == null) return;
        synchronized (LOG_LOCK) {
            try {
                JSONObject entry = new JSONObject().put("time", System.currentTimeMillis()).put("event", event);
                if (failure != null) {
                    StringBuilder causes = new StringBuilder();
                    StringBuilder details = new StringBuilder();
                    Throwable cause = failure;
                    for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
                        if (depth > 0) causes.append(" <- ");
                        causes.append(cause.getClass().getSimpleName());
                        String message = cause.getMessage();
                        if (message != null) {
                            if (token != null) message = message.replace(token, "[token]");
                            message = message.replaceAll("(?i)https?://[^\\s]+", "[url]");
                            if (details.length() > 0) details.append(" <- ");
                            details.append(message, 0, Math.min(message.length(), 600));
                        }
                    }
                    entry.put("errorTypes", causes.toString());
                    entry.put("detail", details.toString());
                }
                if (file.length() > 262144) {
                    File previous = new File(file.getParentFile(), "source-runtime.previous.log");
                    if (previous.isFile() && !previous.delete()) throw new IOException("无法轮转音源诊断");
                    if (!file.renameTo(previous)) throw new IOException("无法轮转音源诊断");
                }
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write((entry + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException | JSONException loggingFailure) {
                Log.w("VutronSources", "Cannot write source diagnostic: " + loggingFailure.getClass().getSimpleName());
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("音源查找已取消");
    }

    private static void copyAssets(AssetManager assets, String source, File target) throws IOException {
        String[] entries = assets.list(source);
        if (entries == null) throw new IOException("缺少内置音源资源");
        if (entries.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法创建音源服务目录");
            for (String name : entries) copyAssets(assets, source + "/" + name, new File(target, name));
        } else {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建音源资源目录");
            try (InputStream input = assets.open(source); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
    }
}
