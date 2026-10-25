package io.github.vutronmusic.personal;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Complete, verified audio only. Temporary network credentials never enter the index. */
public final class AudioCache {
    /** Default total cache capacity. Retained for callers that need the default value. */
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long DEFAULT_CAPACITY_MIB = 2048;
    public static final long MIN_CAPACITY_MIB = 256;
    public static final long MAX_CAPACITY_MIB = 10 * 1024;
    public static final int QUALITY_POLICY = 1;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final long TOTAL_TIMEOUT_MS = 12 * 60 * 1000;
    private static final int IDLE_TIMEOUT_MS = 20000;
    private static final int MAX_PENDING = 2;
    private static final String TAG = "VutronAudioCache";
    private static final String DIRECTORY = "audio-cache-v1";
    private static final String PREFS = "audio_cache_settings";
    private static final String OWNED_NAME = "track-[1-9][0-9]*-[a-f0-9]{32}\\.(audio|part)";
    private static final Object LOCK = new Object();
    private static final Map<Long, Entry> entries = new HashMap<>();
    // A clear can retire the song before MediaPlayer has opened its file descriptor.
    private static final Map<File, Long> deferredDeletes = new HashMap<>();
    private static final LinkedHashMap<Long, Download> pending = new LinkedHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "complete-audio-cache");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "audio-cache-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    // Capacity changes must not wait for a download, whose deadline can be twelve minutes.
    // Index mutations still share LOCK with the downloader.
    private static final ScheduledExecutorService MAINTENANCE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "audio-cache-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private static File directory;
    private static Context appContext;
    /** Read and written under LOCK; volatile for Stats snapshots. */
    private static volatile long capacityBytes = MAX_BYTES;
    private static Download active;
    private static boolean pumping;
    private static boolean trimScheduled;
    private static long generation;
    private static String lastError = "";

    public static final class Entry {
        public final long songId;
        public final File file;
        public final JSONObject song;
        public final String source;
        /** Measured file average in bits/second, never an upstream requested bitrate. */
        public final long bitrate;
        public final String format;
        public final long bytes;
        public final long durationMs;
        public final boolean lossless;
        public final long cachedAt;
        public final int qualityPolicy;
        private final String sha256;
        private long lastUsed;

        private Entry(long songId, File file, JSONObject song, String source, long bitrate,
                      String format, long bytes, long durationMs, boolean lossless,
                      long cachedAt, long lastUsed, String sha256, int qualityPolicy) {
            this.songId = songId;
            this.file = file;
            this.song = song;
            this.source = source;
            this.bitrate = bitrate;
            this.format = format;
            this.bytes = bytes;
            this.durationMs = durationMs;
            this.lossless = lossless;
            this.cachedAt = cachedAt;
            this.lastUsed = lastUsed;
            this.sha256 = sha256;
            this.qualityPolicy = qualityPolicy;
        }
    }

    public static final class Stats {
        public final boolean enabled;
        public final int count;
        public final long bytes;
        public final long maxBytes;
        public final int queued;
        public final boolean downloading;
        public final long downloadingSongId;
        public final long downloadedBytes;
        public final long downloadTotalBytes;
        /** Fixed user-facing description, without exception messages or URLs. */
        public final String error;

        private Stats(boolean enabled, int count, long bytes, long maxBytes, int queued, Download active) {
            this.enabled = enabled;
            this.count = count;
            this.bytes = bytes;
            this.maxBytes = maxBytes;
            this.queued = queued;
            this.downloading = active != null;
            this.downloadingSongId = active == null ? 0 : active.songId;
            this.downloadedBytes = active == null ? 0 : active.downloadedBytes;
            this.downloadTotalBytes = active == null ? 0 : active.totalBytes;
            this.error = lastError;
        }
    }

    private static final class Download {
        final Context context;
        final long songId;
        final JSONObject song;
        final String url;
        final Map<String, String> headers;
        final String source;
        final long requestedBitrate;
        final long expectedSize;
        final boolean requestedLossless;
        final long generation;
        boolean manual;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile long downloadedBytes;
        volatile long totalBytes;

        Download(Context context, JSONObject song, JSONObject location) throws IOException, JSONException {
            this.context = context;
            this.song = metadata(song);
            songId = this.song.getLong("id");
            url = location.optString("url", "");
            checkedUrl(url);
            headers = sourceHeaders(location.optJSONObject("headers"));
            source = text(location.optString("source", "网易云"), 80);
            requestedBitrate = Math.max(0, location.optLong("br", location.optLong("bitrate")));
            expectedSize = Math.max(0, location.optLong("size"));
            String type = location.optString("type", "");
            requestedLossless = location.optBoolean("lossless") || "flac".equalsIgnoreCase(type)
                    || "alac".equalsIgnoreCase(type) || "wav".equalsIgnoreCase(type);
            generation = AudioCache.generation;
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection value = connection;
            if (value != null) DEADLINES.execute(value::disconnect);
        }
    }

    private AudioCache() { }

    public static boolean isEnabled(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", true);
    }

    /**
     * Sets total audio-cache capacity in MiB (256 through 10240). The setting is saved
     * immediately, and least-recently-used files are trimmed on the cache worker.
     */
    public static void setCapacityMiB(Context context, long capacityMiB) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        if (capacityMiB < MIN_CAPACITY_MIB || capacityMiB > MAX_CAPACITY_MIB)
            throw new IllegalArgumentException("Audio cache capacity must be between 256 and 10240 MiB");
        Context app = context.getApplicationContext();
        long bytes = capacityMiB * 1024 * 1024;
        synchronized (LOCK) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("capacity_mib", capacityMiB).apply();
            capacityBytes = bytes;
        }
        scheduleCapacityTrim(app);
    }

    /** Existing offline tracks remain available when automatic saving is disabled. */
    public static void setEnabled(Context context, boolean enabled) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply();
        if (!enabled) synchronized (LOCK) {
            generation++;
            pending.clear();
            if (active != null) active.cancel();
        }
    }

    /** Performs a full hash check; call on a playback/address worker, never the UI thread. */
    public static Entry find(Context context, long songId) {
        Entry value;
        synchronized (LOCK) {
            if (!initialize(context)) return null;
            value = entries.get(songId);
        }
        if (value == null) return null;
        try {
            if (!value.file.isFile() || value.file.length() != value.bytes
                    || !value.sha256.equals(hash(value.file, null, Long.MAX_VALUE))) {
                throw new IOException("Offline audio integrity changed");
            }
            synchronized (LOCK) {
                if (entries.get(songId) != value) return null;
                value.lastUsed = System.currentTimeMillis();
                // Persist LRU later, without delaying the start of local playback.
                IO.execute(AudioCache::checkpoint);
                return copy(value);
            }
        } catch (InterruptedIOException cancelled) {
            // A song change interrupts this read; it does not corrupt the cached audio.
            return null;
        } catch (IOException unavailable) {
            synchronized (LOCK) {
                if (entries.get(songId) == value) {
                    entries.remove(songId);
                    try { saveIndex(); } catch (IOException failed) { warn("index-remove", failed); }
                    deleteOwned(value.file);
                }
            }
            return null;
        }
    }

    /** Nonblocking queue offer. Call once online playback has started buffering/playing. */
    public static boolean enqueue(Context context, JSONObject song, JSONObject location) {
        return enqueue(context, song, location, false);
    }

    /** Explicit saving remains possible while automatic playback caching is disabled. */
    public static boolean enqueueManual(Context context, JSONObject song, JSONObject location) {
        return enqueue(context, song, location, true);
    }

    private static boolean enqueue(Context context, JSONObject song, JSONObject location, boolean manual) {
        Context app = context.getApplicationContext();
        if ((!manual && !isEnabled(app)) || song == null || location == null || song.optLong("id") <= 0) return false;
        synchronized (LOCK) {
            if (!initialize(app)) return false;
            final Download job;
            try { job = new Download(app, song, location); }
            catch (IOException | JSONException invalid) { lastError = "缓存地址或歌曲信息无效"; return false; }
            job.manual = manual;
            Entry previous = entries.get(job.songId);
            if (previous != null && previous.file.isFile() && previous.file.length() == previous.bytes
                    && previous.qualityPolicy >= QUALITY_POLICY) {
                if (previous.lossless) return false;
                // The matcher commonly uses 999000 as a request/sentinel, not measured
                // bitrate. Never re-download an existing cache based on that value alone.
                boolean demonstrablyBetter = job.requestedBitrate > previous.bitrate * 105 / 100
                        && job.requestedBitrate < 999000;
                if (!job.requestedLossless && !demonstrablyBetter)
                    return false;
            }
            if (active != null && active.songId == job.songId) return false;
            pending.remove(job.songId);
            while (pending.size() >= MAX_PENDING) pending.remove(pending.keySet().iterator().next());
            pending.put(job.songId, job);
            if (!pumping) {
                pumping = true;
                IO.execute(AudioCache::pump);
            }
            return true;
        }
    }

    /** Pure song metadata, newest first; tracks keep their online id for the normal playback queue. */
    public static List<JSONObject> list(Context context) {
        synchronized (LOCK) {
            List<JSONObject> songs = new ArrayList<>();
            if (!initialize(context)) return songs;
            List<Entry> ordered = new ArrayList<>(entries.values());
            ordered.sort(Comparator.comparingLong((Entry e) -> e.cachedAt).reversed());
            for (Entry entry : ordered) {
                if (!entry.file.isFile() || entry.file.length() != entry.bytes) continue;
                JSONObject song = copyJson(entry.song);
                try {
                    song.put("cached", true).put("offlineOnly", true).put("cacheSource", entry.source)
                            .put("cacheBitrate", entry.bitrate).put("cacheBytes", entry.bytes)
                            .put("cacheFormat", entry.format).put("cacheLossless", entry.lossless);
                } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                songs.add(song);
            }
            return songs;
        }
    }

    public static Stats stats(Context context) {
        synchronized (LOCK) {
            initialize(context);
            long bytes = 0;
            int count = 0;
            for (Entry entry : entries.values()) {
                if (entry.file.isFile() && entry.file.length() == entry.bytes) {
                    bytes += entry.bytes;
                    count++;
                }
            }
            for (File retired : deferredDeletes.keySet()) if (retired.isFile()) bytes += retired.length();
            return new Stats(isEnabled(context), count, bytes, capacityBytes, pending.size(), active);
        }
    }

    /** "缓存中", "等待缓存", "已离线" or an empty string. Metadata lookup only. */
    public static String statusFor(Context context, long songId) {
        synchronized (LOCK) {
            if (!initialize(context)) return "";
            if (active != null && active.songId == songId && !active.cancelled) return "缓存中";
            if (pending.containsKey(songId)) return "等待缓存";
            Entry value = entries.get(songId);
            return value != null && value.file.isFile() && value.file.length() == value.bytes ? "已离线" : "";
        }
    }

    /** Call on a worker. Cancels in-flight jobs before deleting only this module's generated files. */
    public static void clear(Context context) throws IOException {
        synchronized (LOCK) {
            if (!initialize(context)) throw new IOException("Cannot open audio cache");
            generation++;
            pending.clear();
            if (active != null) active.cancel();
            Map<Long, Entry> old = new HashMap<>(entries);
            entries.clear();
            try { saveIndex(); }
            catch (IOException failure) { entries.putAll(old); throw failure; }
            boolean complete = true;
            long protectedSongId = protectedPlaybackSongId();
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Cannot list audio cache");
            for (File file : files) {
                if (!file.getName().matches(OWNED_NAME)) continue;
                if (protectedSongId > 0 && file.getName().startsWith("track-" + protectedSongId + "-")
                        && file.getName().endsWith(".audio")) deferredDeletes.put(file, protectedSongId);
                else if (!deleteOwned(file)) {
                    complete = false;
                    deferredDeletes.put(file, 0L);
                }
            }
            if (!deferredDeletes.isEmpty()) scheduleCapacityTrim(context.getApplicationContext(), 5000);
            lastError = complete ? "" : "部分缓存文件未能清理，请重试";
            if (!complete) throw new IOException("Some audio cache files could not be removed");
        }
    }

    private static void pump() {
        while (true) {
            Download job;
            synchronized (LOCK) {
                if (pending.isEmpty()) { active = null; pumping = false; return; }
                Long id = pending.keySet().iterator().next();
                job = pending.remove(id);
                active = job;
                lastError = "";
            }
            try { download(job); }
            catch (IOException | RuntimeException failure) {
                synchronized (LOCK) {
                    if (!job.cancelled) lastError = "歌曲缓存未完成，可继续在线播放";
                }
                warn("download", failure);
            } finally {
                synchronized (LOCK) { if (active == job) active = null; }
            }
        }
    }

    private static void download(Download job) throws IOException {
        String stem = "track-" + job.songId + "-" + UUID.randomUUID().toString().replace("-", "");
        File part = new File(directory, stem + ".part");
        File complete = new File(directory, stem + ".audio");
        long deadline = SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS;
        ScheduledFuture<?> timeout = DEADLINES.schedule(job::cancel, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        boolean committed = false;
        try {
            check(job, deadline);
            HttpURLConnection connection = open(job, deadline);
            int status = connection.getResponseCode();
            // No range was requested: a partial response is never a complete offline track.
            if (status != HttpURLConnection.HTTP_OK || connection.getHeaderField("Content-Range") != null)
                throw new IOException("Audio server did not return a complete file");
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding))
                throw new IOException("Unexpected encoded audio response");
            long length = connection.getContentLengthLong();
            long fileLimit = Math.min(MAX_FILE_BYTES, capacityBytes);
            if (length == 0 || length > fileLimit || job.expectedSize > fileLimit)
                throw new IOException("Audio file exceeds cache limit or is empty");
            job.totalBytes = length > 0 ? length : job.expectedSize;
            if (directory.getUsableSpace() < (job.totalBytes > 0 ? job.totalBytes : 32 * 1024 * 1024)
                    + 64L * 1024 * 1024) throw new IOException("Not enough storage for audio cache");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    check(job, deadline);
                    if (count == 0) continue;
                    job.downloadedBytes += count;
                    if (job.downloadedBytes > Math.min(MAX_FILE_BYTES, capacityBytes))
                        throw new IOException("Audio file exceeds cache limit");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (job.downloadedBytes < 1024 || (length > 0 && job.downloadedBytes != length)
                    || (job.expectedSize > 0 && job.expectedSize != job.downloadedBytes))
                throw new IOException("Audio download is incomplete");
            check(job, deadline);
            AudioInfo info = inspect(part, duration(job.song), job, deadline);
            String digest = hash(part, job, deadline);
            synchronized (LOCK) {
                check(job, deadline);
                if (job.generation != generation || (!job.manual && !isEnabled(job.context))) throw new InterruptedIOException("Cache cancelled");
                if (part.length() > capacityBytes) throw new IOException("Audio file exceeds cache capacity");
                Entry old = entries.get(job.songId);
                if (old != null && old.file.isFile() && old.file.length() == old.bytes
                        && (old.songId == protectedPlaybackSongId() || old.lossless && !info.lossless
                        || old.lossless == info.lossless && old.bitrate >= info.bitrate * 98 / 100)) return;
                // Rename within one directory: the visible audio name never points at a partial file.
                if (!part.renameTo(complete)) throw new IOException("Cannot commit complete audio");
                long now = System.currentTimeMillis();
                Entry entry = new Entry(job.songId, complete, job.song, job.source, info.bitrate,
                        info.format, complete.length(), info.durationMs, info.lossless, now, now,
                        digest, QUALITY_POLICY);
                Map<Long, Entry> previous = new HashMap<>(entries);
                entries.put(entry.songId, entry);
                List<Entry> evicted = evict();
                try { saveIndex(); }
                catch (IOException failure) { entries.clear(); entries.putAll(previous); throw failure; }
                committed = true;
                if (old != null) deleteOwned(old.file);
                for (Entry victim : evicted) deleteOwned(victim.file);
                lastError = "";
            }
        } finally {
            timeout.cancel(false);
            HttpURLConnection connection = job.connection;
            if (connection != null) connection.disconnect();
            job.connection = null;
            deleteOwned(part);
            if (!committed) deleteOwned(complete);
        }
    }

    private static HttpURLConnection open(Download job, long deadline) throws IOException {
        URL url = checkedUrl(job.url);
        for (int redirects = 0; redirects <= 8; redirects++) {
            check(job, deadline);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            job.connection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout((int) Math.min(12000, Math.max(1, deadline - SystemClock.elapsedRealtime())));
            connection.setReadTimeout(IDLE_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", "audio/*,application/octet-stream;q=0.9,*/*;q=0.1");
            for (Map.Entry<String, String> header : job.headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            int code = connection.getResponseCode();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return connection;
            String target = connection.getHeaderField("Location");
            connection.disconnect();
            if (target == null || redirects == 8) throw new IOException("Invalid audio redirect");
            url = checkedUrl(new URL(url, target).toString());
        }
        throw new IOException("Too many audio redirects");
    }

    private static final class AudioInfo {
        long durationMs;
        long bitrate;
        String format;
        boolean lossless;
    }

    /** Reads every audio sample; the final sample must reach the advertised/full song duration. */
    private static AudioInfo inspect(File file, long expectedDuration, Download job, long deadline) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat track = null;
            int selected = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = candidate; selected = i; break; }
            }
            if (track == null) throw new IOException("No valid audio track");
            String mime = track.getString(MediaFormat.KEY_MIME);
            long declaredUs = track.containsKey(MediaFormat.KEY_DURATION) ? track.getLong(MediaFormat.KEY_DURATION) : 0;
            extractor.selectTrack(selected);
            ByteBuffer sample = ByteBuffer.allocate(2 * 1024 * 1024);
            long firstUs = -1, lastUs = -1, lastDeltaUs = 0, samples = 0;
            while (true) {
                check(job, deadline);
                sample.clear();
                int size = extractor.readSampleData(sample, 0);
                if (size < 0) break;
                if (size == 0 || size > sample.capacity()
                        || (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                    throw new IOException("Invalid or encrypted audio sample");
                long at = extractor.getSampleTime();
                if (at < 0 || lastUs > at) throw new IOException("Invalid audio timestamps");
                if (firstUs < 0) firstUs = at;
                if (at > lastUs && lastUs >= 0) lastDeltaUs = at - lastUs;
                lastUs = at;
                samples++;
                if (!extractor.advance()) break;
            }
            long observedMs = (lastUs - Math.max(0, firstUs) + Math.max(1000, lastDeltaUs)) / 1000;
            long declaredMs = declaredUs / 1000;
            if (samples < 2 || observedMs < 500) throw new IOException("Audio has no complete samples");
            if (declaredMs > 0 && observedMs + Math.max(1500, declaredMs / 100) < declaredMs)
                throw new IOException("Audio ends before its declared duration");
            long durationMs = declaredMs > 0 ? declaredMs : observedMs;
            if (expectedDuration > 0 && Math.abs(durationMs - expectedDuration) > Math.max(10000, expectedDuration / 20))
                throw new IOException("Audio duration does not match complete song");
            AudioInfo info = new AudioInfo();
            info.durationMs = durationMs;
            info.bitrate = file.length() * 8000 / durationMs;
            info.lossless = "audio/flac".equals(mime) || "audio/alac".equals(mime) || "audio/raw".equals(mime);
            info.format = "audio/mpeg".equals(mime) ? "mp3" : "audio/mp4a-latm".equals(mime) ? "aac"
                    : "audio/flac".equals(mime) ? "flac" : "audio/raw".equals(mime) ? "pcm"
                    : mime == null ? "audio" : mime.substring("audio/".length());
            return info;
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot validate complete audio", invalid);
        } finally { extractor.release(); }
    }

    private static boolean initialize(Context context) {
        if (directory != null) return true;
        appContext = context.getApplicationContext();
        capacityBytes = capacityBytesFromPreferences(context.getApplicationContext());
        File root = new File(context.getApplicationContext().getFilesDir(), DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs()) { lastError = "无法创建歌曲缓存目录"; return false; }
        directory = root;
        try {
            AtomicFile index = indexFile();
            if (index.getBaseFile().exists() || new File(root, "index.json.bak").exists()) {
                byte[] raw = index.readFully();
                if (raw.length > 8 * 1024 * 1024) throw new IOException("Audio index exceeds limit");
                JSONObject document = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                if (document.optInt("version") != 1) throw new IOException("Unsupported audio index");
                JSONArray rows = document.getJSONArray("entries");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    try {
                        long id = row.optLong("songId");
                        String name = row.optString("file");
                        String hash = row.optString("sha256");
                        if (id <= 0 || !name.matches("track-" + id + "-[a-f0-9]{32}\\.audio")
                                || !hash.matches("[a-f0-9]{64}")) continue;
                        File file = owned(name);
                        long bytes = row.optLong("bytes");
                        if (!file.isFile() || bytes < 1024 || bytes > MAX_FILE_BYTES || file.length() != bytes
                                || row.optLong("durationMs") <= 0) continue;
                        JSONObject song = metadata(row.getJSONObject("song"));
                        if (song.optLong("id") != id) continue;
                        entries.put(id, new Entry(id, file, song, text(row.optString("source"), 80),
                                Math.max(0, row.optLong("bitrate")), text(row.optString("format"), 40),
                                bytes, row.getLong("durationMs"), row.optBoolean("lossless"),
                                row.optLong("cachedAt"), row.optLong("lastUsed"), hash, row.optInt("qualityPolicy")));
                    } catch (IOException | JSONException badEntry) {
                        // One malformed row cannot make unrelated complete tracks disappear.
                        warn("skip-index-entry", badEntry);
                    }
                }
            }
        } catch (IOException | JSONException invalid) {
            entries.clear();
            lastError = "缓存索引不可用，损坏的缓存不会播放";
            warn("load-index", invalid);
        }
        Set<String> keep = new HashSet<>();
        for (Entry entry : entries.values()) keep.add(entry.file.getName());
        File[] files = root.listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().matches(OWNED_NAME) && !keep.contains(file.getName())) deleteOwned(file);
        }
        long totalBytes = 0;
        for (Entry entry : entries.values()) totalBytes += entry.bytes;
        if (totalBytes > capacityBytes) scheduleCapacityTrim(context.getApplicationContext());
        return true;
    }

    private static long capacityBytesFromPreferences(Context context) {
        long value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("capacity_mib", DEFAULT_CAPACITY_MIB);
        if (value < MIN_CAPACITY_MIB || value > MAX_CAPACITY_MIB) value = DEFAULT_CAPACITY_MIB;
        return value * 1024 * 1024;
    }

    private static void scheduleCapacityTrim(Context context) {
        scheduleCapacityTrim(context, 0);
    }

    private static void scheduleCapacityTrim(Context context, long delayMs) {
        synchronized (LOCK) {
            if (trimScheduled) return;
            trimScheduled = true;
        }
        try {
            MAINTENANCE.schedule(() -> {
                boolean completed = false;
                try { completed = trimToCapacity(context); }
                finally {
                    boolean retry;
                    synchronized (LOCK) {
                        trimScheduled = false;
                        retry = completed && directory != null
                                && (totalCachedBytesLocked() > capacityBytes || !deferredDeletes.isEmpty());
                    }
                    // A file being opened/played may temporarily exceed the new limit.
                    // Retry after the current song changes without spinning the worker.
                    if (retry) scheduleCapacityTrim(context, 5000);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            synchronized (LOCK) { trimScheduled = false; }
            throw rejected;
        }
    }

    /** Must be called under LOCK. A file being opened or played is never evicted. */
    private static List<Entry> evict() {
        List<Entry> oldest = new ArrayList<>(entries.values());
        oldest.sort(Comparator.comparingLong(e -> e.lastUsed));
        long total = totalCachedBytesLocked();
        List<Entry> removed = new ArrayList<>();
        long protectedSongId = protectedPlaybackSongId();
        for (Entry entry : oldest) {
            if (total <= capacityBytes) break;
            if (entry.songId == protectedSongId) continue;
            entries.remove(entry.songId);
            total -= entry.bytes;
            removed.add(entry);
        }
        return removed;
    }

    private static long protectedPlaybackSongId() {
        Context context = appContext;
        if (context == null) return 0;
        return context.getSharedPreferences("vutron_playback", Context.MODE_PRIVATE)
                .getLong("trackId", 0L);
    }

    /** Removes indexed over-capacity entries, then their files, on the maintenance worker. */
    private static boolean trimToCapacity(Context context) {
        while (true) {
            List<Entry> evicted;
            synchronized (LOCK) {
                if (!initialize(context)) return false;
                long protectedSongId = protectedPlaybackSongId();
                Iterator<Map.Entry<File, Long>> retired = deferredDeletes.entrySet().iterator();
                while (retired.hasNext()) {
                    Map.Entry<File, Long> entry = retired.next();
                    if (entry.getValue() == protectedSongId) continue;
                    if (!deleteOwned(entry.getKey())) {
                        lastError = "部分缓存文件未能清理，请重试";
                        return false;
                    }
                    retired.remove();
                }
                Map<Long, Entry> previous = new HashMap<>(entries);
                evicted = evict();
                if (evicted.isEmpty()) return true;
                try { saveIndex(); }
                catch (IOException failure) {
                    entries.clear();
                    entries.putAll(previous);
                    lastError = "缓存索引更新失败";
                    warn("capacity-trim-index", failure);
                    return false;
                }
            }
            boolean complete = true;
            for (Entry entry : evicted) if (!deleteOwned(entry.file)) {
                complete = false;
                synchronized (LOCK) { deferredDeletes.put(entry.file, entry.songId); }
            }
            if (!complete) {
                synchronized (LOCK) { lastError = "部分超出容量的缓存未能删除"; }
                return false;
            }
        }
    }

    /** Must be called under LOCK. */
    private static long totalCachedBytesLocked() {
        long total = 0;
        for (Entry entry : entries.values()) total += entry.bytes;
        for (File retired : deferredDeletes.keySet()) if (retired.isFile()) total += retired.length();
        return total;
    }

    private static void checkpoint() {
        synchronized (LOCK) {
            if (directory == null) return;
            try { saveIndex(); } catch (IOException failure) { warn("checkpoint", failure); }
        }
    }

    private static void saveIndex() throws IOException {
        JSONArray rows = new JSONArray();
        JSONObject document = new JSONObject();
        try {
            for (Entry entry : entries.values()) rows.put(new JSONObject().put("songId", entry.songId)
                    .put("file", entry.file.getName()).put("song", entry.song).put("source", entry.source)
                    .put("bitrate", entry.bitrate).put("format", entry.format).put("bytes", entry.bytes)
                    .put("durationMs", entry.durationMs).put("lossless", entry.lossless)
                    .put("cachedAt", entry.cachedAt).put("lastUsed", entry.lastUsed)
                    .put("sha256", entry.sha256).put("qualityPolicy", entry.qualityPolicy));
            document.put("version", 1).put("entries", rows);
        } catch (JSONException invalid) { throw new IOException("Cannot encode audio index", invalid); }
        AtomicFile file = indexFile();
        FileOutputStream output = file.startWrite();
        try {
            output.write(document.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException failure) { file.failWrite(output); throw failure; }
    }

    private static AtomicFile indexFile() { return new AtomicFile(new File(directory, "index.json")); }

    private static File owned(String name) throws IOException {
        if (!name.matches(OWNED_NAME)) throw new IOException("Invalid audio cache file name");
        File file = new File(directory, name);
        if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
            throw new IOException("Audio cache file escaped its directory");
        return file;
    }

    private static boolean deleteOwned(File file) {
        try { return !file.exists() || owned(file.getName()).delete(); }
        catch (IOException invalid) { warn("delete-file", invalid); return false; }
    }

    static JSONObject metadata(JSONObject song) throws JSONException {
        JSONObject saved = new JSONObject();
        long id = song.optLong("id");
        if (id <= 0) throw new JSONException("Missing song id");
        saved.put("id", id);
        // Derive before the offline browser adds a file URI. Otherwise an online
        // recording receives a new local-file gain key after it is cached.
        String volumeKey = PlaybackVolume.trackKey(song);
        if (!volumeKey.isEmpty()) saved.put("trackVolumeKey", volumeKey);
        if (song.optLong("neteaseId", 0) > 0) saved.put("neteaseId", song.optLong("neteaseId"));
        for (String key : new String[]{"name", "title", "artist", "metadataTitle"}) {
            if (song.opt(key) instanceof String) saved.put(key, text(song.optString(key), 500));
        }
        for (String key : new String[]{"picUrl", "coverImgUrl", "cover", "albumPicUrl"}) {
            if (song.opt(key) instanceof String) saved.put(key, text(song.optString(key), 2048));
        }
        for (String key : new String[]{"dt", "duration"}) {
            long number = song.optLong(key);
            if (number > 0 && number <= 24L * 60 * 60 * 1000) saved.put(key, number);
        }
        for (String key : new String[]{"ar", "artists"}) {
            JSONArray artists = song.optJSONArray(key);
            if (artists == null) continue;
            JSONArray values = new JSONArray();
            for (int i = 0; i < Math.min(40, artists.length()); i++) {
                JSONObject artist = artists.optJSONObject(i);
                values.put(artist == null ? text(artists.optString(i), 200) : namedMetadata(artist));
            }
            saved.put(key, values);
        }
        for (String key : new String[]{"al", "album"}) {
            JSONObject album = song.optJSONObject(key);
            if (album != null) saved.put(key, namedMetadata(album));
            else if (song.opt(key) instanceof String) saved.put(key, text(song.optString(key), 500));
        }
        return saved;
    }

    private static JSONObject namedMetadata(JSONObject value) throws JSONException {
        JSONObject saved = new JSONObject().put("name", text(value.optString("name"), 500));
        if (value.optLong("id") > 0) saved.put("id", value.optLong("id"));
        for (String key : new String[]{"picUrl", "coverImgUrl", "cover"}) {
            if (value.opt(key) instanceof String) saved.put(key, text(value.optString(key), 2048));
        }
        return saved;
    }

    private static String text(String value, int max) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        return clean.substring(0, Math.min(max, clean.length()));
    }

    private static Map<String, String> sourceHeaders(JSONObject values) {
        Map<String, String> result = new HashMap<>();
        result.put("Referer", "https://music.163.com/");
        result.put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
        if (values != null) {
            Iterator<String> names = values.keys();
            while (names.hasNext()) {
                String key = names.next();
                String canonical = "Referer".equalsIgnoreCase(key) ? "Referer"
                        : "User-Agent".equalsIgnoreCase(key) ? "User-Agent" : "Origin".equalsIgnoreCase(key) ? "Origin" : null;
                String value = values.optString(key, "");
                if (canonical != null && value.length() <= 4096 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0)
                    result.put(canonical, value);
            }
        }
        return result;
    }

    private static URL checkedUrl(String value) throws IOException {
        URL url = new URL(value);
        if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))
                || url.getHost().isEmpty() || url.getUserInfo() != null) throw new IOException("Invalid audio URL");
        return url;
    }

    private static long duration(JSONObject song) {
        long duration = song.optLong("dt", song.optLong("duration"));
        return Math.max(0, duration);
    }

    private static void check(Download job, long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || job != null && job.cancelled)
            throw new InterruptedIOException("Audio cache cancelled");
        if (SystemClock.elapsedRealtime() >= deadline) throw new InterruptedIOException("Audio cache timed out");
    }

    private static String hash(File file, Download job, long deadline) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) { check(job, deadline); digest.update(buffer, 0, count); }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    private static Entry copy(Entry entry) {
        return new Entry(entry.songId, entry.file, copyJson(entry.song), entry.source, entry.bitrate,
                entry.format, entry.bytes, entry.durationMs, entry.lossless, entry.cachedAt, entry.lastUsed,
                entry.sha256, entry.qualityPolicy);
    }

    private static JSONObject copyJson(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (JSONException invalid) { throw new IllegalStateException(invalid); }
    }

    /** URLs, headers, cookies and platform exception messages are deliberately excluded. */
    private static void warn(String stage, Throwable failure) {
        Log.w(TAG, stage + ": " + failure.getClass().getSimpleName());
    }
}
