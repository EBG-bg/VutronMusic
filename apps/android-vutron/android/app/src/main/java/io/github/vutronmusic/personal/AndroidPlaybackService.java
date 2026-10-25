package io.github.vutronmusic.personal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Android platform adapter for media playback while the Vue WebView is backgrounded. */
public final class AndroidPlaybackService extends Service {
    static final String ACTION_PLAY = "io.github.vutronmusic.personal.webshell.PLAY";
    static final String ACTION_PAUSE = "io.github.vutronmusic.personal.webshell.PAUSE";
    static final String ACTION_RESUME = "io.github.vutronmusic.personal.webshell.RESUME";
    static final String ACTION_STOP = "io.github.vutronmusic.personal.webshell.STOP";
    static final String ACTION_QUEUE = "io.github.vutronmusic.personal.webshell.QUEUE";
    static final String ACTION_NEXT = "io.github.vutronmusic.personal.webshell.NEXT";
    static final String ACTION_PREVIOUS = "io.github.vutronmusic.personal.webshell.PREVIOUS";
    private static final String ACTION_TIMER_WAKE = "io.github.vutronmusic.personal.webshell.TIMER_WAKE";
    private static final String CHANNEL = "vutron_android_playback";
    private static final String PLAYBACK_PREFS = "vutron_playback";
    private static final String LAST_TRACK_ID = "lastTrackId";
    private static final String LAST_POSITION = "lastPosition";
    private static final String LAST_DURATION = "lastDuration";
    private static final String LAST_TITLE = "lastTitle";
    private static final String LAST_ARTIST = "lastArtist";
    private static final String LAST_PLAYING = "lastPlaying";
    private static final String SLEEP_TIMER_DEADLINE = "sleepTimerDeadlineEpochMs";
    private static final long MAX_SLEEP_TIMER_MS = 24L * 60 * 60 * 1000;
    private static final int NOTIFICATION_ID = 1201;
    private static volatile AndroidPlaybackService activeInstance;

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private String title = "Vutron Music - Mobile";
    private String artist = "";
    private long trackId;
    private long duration;
    private int generation;
    private boolean playing;
    private boolean desiredPlayback;
    private boolean playerPrepared;
    private boolean buffering;
    private boolean destroyed;
    private boolean urlLookupPending;
    private boolean resumeOnFocusGain;
    private float focusVolume = 1f;
    private JSONObject trackMetadata;
    // Resolved URL and provider headers remain memory-only until actual playback.
    private JSONObject cacheLocation;
    private int cacheEnqueuedGeneration = -1;
    private PlaybackQueue.Snapshot queue;
    private Future<?> urlTask;
    private Bitmap artwork;
    private String artworkUrl = "";
    private int artworkGeneration;
    private Handler progressHandler;
    private final ExecutorService urlWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService artworkWorker = Executors.newSingleThreadExecutor();
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            persistPosition();
            checkSleepTimer();
            AndroidLyricOverlayService.recoverIfEnabled(AndroidPlaybackService.this);
            if (playing || player != null) publish();
            if (progressHandler != null) progressHandler.postDelayed(this, 1000L);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusVolume = 1f;
            if (!applyEffectiveVolume()) return;
            boolean shouldResume = resumeOnFocusGain;
            resumeOnFocusGain = false;
            if (shouldResume) resume();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusVolume = .2f;
            applyEffectiveVolume();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            boolean wasPlaying = playing || desiredPlayback;
            pause();
            resumeOnFocusGain = wasPlaying;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            pause();
        }
    };

    static boolean isActive() {
        AndroidPlaybackService service = activeInstance;
        return service != null && !service.destroyed;
    }

    static int getMode(Context context) {
        return context.getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).getInt("mode", 0);
    }

    static void setMode(Context context, int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("播放模式无效");
        context.getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit().putInt("mode", mode).apply();
    }

    interface QueueResult {
        void success(PlaybackQueue.Snapshot value);
        void failure(Exception error);
    }

    /** Serialize queue edits with native completion/skip callbacks on the media thread. */
    static void editQueue(Context context, String operation, int index, boolean upNext, JSONObject track, QueueResult result) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                PlaybackQueue.Snapshot current = PlaybackQueue.load(context);
                AndroidPlaybackService service = activeInstance;
                boolean active = service != null && !service.destroyed;
                if (current == null) {
                    if (!"add".equals(operation) && !"append".equals(operation)) throw new IllegalArgumentException("播放队列为空");
                    current = new PlaybackQueue.Snapshot(java.util.UUID.randomUUID().toString(), new org.json.JSONArray(), -1);
                }
                PlaybackQueue.Snapshot updated;
                boolean select = false;
                if ("add".equals(operation)) updated = current.appendNext(track);
                else if ("append".equals(operation)) updated = current.appendTail(track);
                else if ("clearNext".equals(operation)) updated = current.clearNext();
                else if ("select".equals(operation)) {
                    if (upNext) updated = current.consumeNext(index);
                    else {
                        if (index < 0 || index >= current.count()) throw new IllegalArgumentException("播放队列索引无效");
                        updated = current.at(index);
                    }
                    select = true;
                } else if ("remove".equals(operation)) {
                    select = !upNext && index == current.index;
                    updated = current.remove(index, upNext);
                } else throw new IllegalArgumentException("队列操作无效");
                PlaybackQueue.persist(context, updated);
                if (active) service.queue = updated;
                if (select) {
                    if (updated.count() == 0) {
                        if (active) service.stopPlayback(true);
                        else {
                            PlaybackQueue.clear(context);
                            context.getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit()
                                    .remove(LAST_TRACK_ID).remove(LAST_POSITION).remove(LAST_DURATION)
                                    .remove(LAST_TITLE).remove(LAST_ARTIST).remove("current_track").remove("error")
                                    .putLong("trackId", 0L).putInt("position", 0).putLong("duration", 0L)
                                    .putString("title", "").putString("artist", "").putBoolean("playing", false)
                                    .putBoolean("loading", false).putBoolean(LAST_PLAYING, false).apply();
                        }
                    } else if (active) {
                        boolean autoPlay = !"remove".equals(operation) || service.desiredPlayback;
                        service.selectQueueTrack(false, autoPlay);
                    } else if ("select".equals(operation)) {
                        ContextCompat.startForegroundService(context, new Intent(context, AndroidPlaybackService.class)
                                .setAction(ACTION_QUEUE).putExtra("queueToken", updated.token).putExtra("restorePosition", false));
                    } else {
                        context.getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit()
                                .putString("current_track", updated.current().toString())
                                .putLong(LAST_TRACK_ID, updated.current().optLong("id"))
                                .putLong("trackId", updated.current().optLong("id"))
                                .putString("title", updated.current().optString("name"))
                                .putString("artist", PlaybackRequest.artistText(updated.current()))
                                .putLong("duration", updated.current().optLong("duration", updated.current().optLong("dt", 0)))
                                .putString(LAST_TITLE, updated.current().optString("name"))
                                .putString(LAST_ARTIST, PlaybackRequest.artistText(updated.current()))
                                .putLong(LAST_DURATION, updated.current().optLong("duration", updated.current().optLong("dt", 0)))
                                .putInt(LAST_POSITION, 0).putInt("position", 0).putBoolean("playing", false)
                                .putBoolean(LAST_PLAYING, false).apply();
                    }
                }
                result.success(updated);
            } catch (Exception failure) { result.failure(failure); }
        });
    }

    /** Apply the old personal edition's persistent track and playlist gains immediately. */
    public static void onVolumeSettingsChanged(Context context) {
        AndroidPlaybackService service = activeInstance;
        if (service == null || service.destroyed || service.progressHandler == null) return;
        service.progressHandler.post(() -> {
            if (!service.destroyed && activeInstance == service && service.applyEffectiveVolume()) service.publish();
        });
    }

    /** Starts or replaces a sleep timer. Durations from one second through 24 hours are accepted. */
    static long setSleepTimer(Context context, long durationMs) {
        if (durationMs < 1_000L || durationMs > MAX_SLEEP_TIMER_MS)
            throw new IllegalArgumentException("Sleep timer must be between one second and 24 hours");
        long deadline = System.currentTimeMillis() + durationMs;
        context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(SLEEP_TIMER_DEADLINE, deadline).apply();
        ContextCompat.startForegroundService(context,
                new Intent(context, AndroidPlaybackService.class).setAction(ACTION_TIMER_WAKE));
        return deadline;
    }

    /** Removes the persisted deadline. It does not affect playback. */
    static void clearSleepTimer(Context context) {
        context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE).edit()
                .remove(SLEEP_TIMER_DEADLINE).apply();
        AndroidPlaybackService service = activeInstance;
        if (service != null && !service.destroyed) service.progressHandler.post(service::onSleepTimerCleared);
    }

    private void onSleepTimerCleared() {
        if (player == null && !urlLookupPending) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    /** Returns remaining milliseconds and triggers expiry if the saved deadline has passed. */
    static long sleepTimerRemainingMs(Context context) {
        long deadline = context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE)
                .getLong(SLEEP_TIMER_DEADLINE, 0L);
        if (deadline <= 0) return 0L;
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            AndroidPlaybackService service = activeInstance;
            if (service != null && !service.destroyed) service.progressHandler.post(service::expireSleepTimer);
            else ContextCompat.startForegroundService(context,
                    new Intent(context, AndroidPlaybackService.class).setAction(ACTION_TIMER_WAKE));
            return 0L;
        }
        if (!isActive()) {
            try {
                ContextCompat.startForegroundService(context,
                        new Intent(context, AndroidPlaybackService.class).setAction(ACTION_TIMER_WAKE));
            } catch (RuntimeException ignored) { }
        }
        return remaining;
    }

    static void seek(Context context, int positionMs) {
        AndroidPlaybackService service = activeInstance;
        int target = Math.max(0, positionMs);
        if (service != null && !service.destroyed) {
            service.progressHandler.post(() -> {
                if (service.player != null && service.playerPrepared) {
                    try {
                        int bounded = service.duration > 0 ? Math.min(target, (int) service.duration) : target;
                        service.player.seekTo(bounded);
                        service.persistPosition();
                        service.publish();
                    } catch (IllegalStateException ignored) { }
                }
            });
            return;
        }
        context.getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit()
                .putInt(LAST_POSITION, target).putInt("position", target).apply();
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        progressHandler = new Handler(Looper.getMainLooper());
        try { queue = PlaybackQueue.load(this); }
        catch (Exception failure) { Log.w("VutronPlayback", "Could not restore the playback queue", failure); }
        progressHandler.post(progressTicker);
        audio = getSystemService(AudioManager.class);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Vutron Music - Mobile 播放", NotificationManager.IMPORTANCE_LOW));
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        session = new MediaSession(this, "VutronMusicAndroid");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setPlaybackToLocal(attributes);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resumeOrRestore(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { changeSong(1); }
            @Override public void onSkipToPrevious() { changeSong(-1); }
            @Override public void onSeekTo(long value) { seek(AndroidPlaybackService.this, (int) Math.min(Integer.MAX_VALUE, Math.max(0, value))); }
            @Override public void onStop() { stopPlayback(true); stopSelf(); }
        });
        session.setActive(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (!ACTION_STOP.equals(action)) startForeground(NOTIFICATION_ID, notification());
        if (ACTION_QUEUE.equals(action)) {
            try {
                PlaybackQueue.Snapshot requested = PlaybackQueue.load(this);
                if (requested != null && requested.token.equals(intent.getStringExtra("queueToken"))) {
                    queue = requested;
                    selectQueueTrack(intent.getBooleanExtra("restorePosition", true));
                }
            } catch (Exception failure) {
                Log.w("VutronPlayback", "Could not load the selected queue", failure);
                publishError("无法读取播放队列");
            }
        }
        else if (ACTION_PLAY.equals(action)) startTrack(intent);
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_RESUME.equals(action)) resumeOrRestore();
        else if (ACTION_NEXT.equals(action)) changeSong(1);
        else if (ACTION_PREVIOUS.equals(action)) changeSong(-1);
        else if (ACTION_STOP.equals(action)) { stopPlayback(true); stopSelf(startId); }
        else if (ACTION_TIMER_WAKE.equals(action)) checkSleepTimer();
        return START_NOT_STICKY;
    }

    private void changeSong(int direction) {
        if (queue == null || queue.count() == 0 && queue.upNext.length() == 0) return;
        if (direction > 0) advanceQueue(false);
        else {
            queue = queue.move(-1);
            selectQueueTrack(false);
        }
    }

    private void advanceQueue(boolean automatic) {
        if (queue == null || queue.count() == 0 && queue.upNext.length() == 0) return;
        try {
            PlaybackQueue.Snapshot latest = PlaybackQueue.load(this);
            if (latest == null || !latest.token.equals(queue.token)) return;
            queue = latest;
            int random = queue.count() > 1 ? java.util.concurrent.ThreadLocalRandom.current().nextInt(queue.count() - 1) : 0;
            PlaybackQueue.Snapshot next = queue.next(getMode(this), automatic, random);
            // Consuming inserts changes the durable history and pending list together.
            if (next.songs != queue.songs || next.upNext != queue.upNext) PlaybackQueue.persist(this, next);
            queue = next;
            selectQueueTrack(false);
        } catch (Exception failure) {
            Log.w("VutronPlayback", "Could not persist the next-up queue", failure);
            publishError("无法保存插队播放状态");
        }
    }

    /** Keep queue ownership in this service so screen-off completion needs no WebView. */
    private void selectQueueTrack(boolean restorePosition) {
        selectQueueTrack(restorePosition, true);
    }

    private void selectQueueTrack(boolean restorePosition, boolean autoPlay) {
        if (queue == null || queue.current() == null) return;
        persistPosition();
        android.content.SharedPreferences saved = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE);
        JSONObject selected = queue.current();
        long id = selected.optLong("id");
        int position = restorePosition && saved.getLong(LAST_TRACK_ID, 0L) == id
                ? saved.getInt(LAST_POSITION, 0) : 0;
        cancelUrlTask();
        releasePlayer();
        final int token = ++generation;
        playing = false;
        desiredPlayback = autoPlay;
        resumeOnFocusGain = false;
        urlLookupPending = true;
        trackId = id;
        trackMetadata = selected;
        title = selected.optString("name", "Vutron Music - Mobile");
        artist = PlaybackRequest.artistText(selected);
        duration = selected.optLong("duration", selected.optLong("dt", 0));
        PlaybackQueue.cursor(this, queue);
        saved.edit().putString("current_track", selected.toString()).putLong(LAST_TRACK_ID, id)
                .putInt(LAST_POSITION, position).putInt("position", position).putLong(LAST_DURATION, duration)
                .putString(LAST_TITLE, title).putString(LAST_ARTIST, artist).putBoolean(LAST_PLAYING, autoPlay)
                .remove("error").apply();
        loadArtwork(rememberedArtwork());
        publish();
        urlTask = urlWorker.submit(() -> {
            try {
                Intent prepared = PlaybackRequest.prepare(this, selected, autoPlay).putExtra("resumePosition", position);
                progressHandler.post(() -> {
                    if (destroyed || token != generation) return;
                    urlLookupPending = false;
                    urlTask = null;
                    startTrack(prepared);
                });
            } catch (Exception failure) {
                Log.w("VutronPlayback", "Could not resolve the selected queue track", failure);
                progressHandler.post(() -> {
                    if (destroyed || token != generation) return;
                    urlLookupPending = false;
                    urlTask = null;
                    publishError(failure.getMessage() == null ? "获取播放地址失败" : failure.getMessage());
                });
            }
        });
    }

    private void cancelUrlTask() {
        if (urlTask != null) urlTask.cancel(true);
        urlTask = null;
        urlLookupPending = false;
    }

    private void checkSleepTimer() {
        long deadline = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE)
                .getLong(SLEEP_TIMER_DEADLINE, 0L);
        if (deadline <= 0) {
            if (player == null && !urlLookupPending) onSleepTimerCleared();
            return;
        }
        if (deadline <= System.currentTimeMillis()) expireSleepTimer();
    }

    private void expireSleepTimer() {
        android.content.SharedPreferences preferences = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE);
        long deadline = preferences.getLong(SLEEP_TIMER_DEADLINE, 0L);
        if (deadline <= 0 || deadline > System.currentTimeMillis()) return;
        preferences.edit().remove(SLEEP_TIMER_DEADLINE).apply();
        if (player != null || urlLookupPending) {
            pause();
        } else {
            preferences.edit().putBoolean(LAST_PLAYING, false).putBoolean("playing", false).apply();
            playing = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void startTrack(Intent intent) {
        String url = intent.getStringExtra("url");
        if (!isSupportedSource(url)) {
            publishError("播放地址无效"); stopPlayback(); return;
        }
        // Keep the old song's position before replacing its player. The next
        // start can use this value when the requested track is the same.
        persistPosition();
        android.content.SharedPreferences saved = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE);
        long requestedTrackId = intent.getLongExtra("trackId", 0L);
        long savedTrackId = saved.getLong(LAST_TRACK_ID, saved.getLong("trackId", 0L));
        int savedPosition = saved.getInt(LAST_POSITION, saved.getInt("position", 0));
        final int resumePosition = intent.hasExtra("resumePosition") ? intent.getIntExtra("resumePosition", 0)
                : savedTrackId == requestedTrackId && savedPosition > 0 ? savedPosition : 0;

        // A previous decode/network failure must not remain visible after the
        // user starts a different track.
        saved.edit().remove("error").apply();
        releasePlayer();
        playing = false;
        int token = ++generation;
        title = intent.getStringExtra("title"); if (title == null) title = "Vutron Music - Mobile";
        artist = intent.getStringExtra("artist"); if (artist == null) artist = "";
        trackId = requestedTrackId;
        trackMetadata = readTrackMetadata(intent, requestedTrackId);
        cacheLocation = readCacheLocation(intent);
        String requestedArtwork = intent.getStringExtra("coverUrl");
        if (requestedArtwork == null || requestedArtwork.isBlank()) requestedArtwork = rememberedArtwork();
        loadArtwork(requestedArtwork);
        desiredPlayback = intent.getBooleanExtra("autoPlay", true);
        saved.edit().putString("current_track", trackMetadata.toString())
                .putLong(LAST_TRACK_ID, trackId).putInt(LAST_POSITION, resumePosition)
                .putLong(LAST_DURATION, duration).putString(LAST_TITLE, title).putString(LAST_ARTIST, artist)
                .putBoolean(LAST_PLAYING, desiredPlayback).apply();
        playerPrepared = false;
        MediaPlayer candidate = new MediaPlayer(); player = candidate;
        try {
            candidate.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            candidate.setOnPreparedListener(prepared -> {
                if (destroyed || player != prepared || token != generation) return;
                playerPrepared = true;
                duration = Math.max(0, prepared.getDuration());
                if (resumePosition > 0 && duration > 0) {
                    int seekPosition = Math.min(resumePosition, Math.max(0, (int) duration - 1000));
                    if (seekPosition > 0) {
                        try { prepared.seekTo(seekPosition); } catch (IllegalStateException ignored) { }
                    }
                }
                if (!requestFocus()) { publishError("系统暂不允许音频播放"); return; }
                if (!applyEffectiveVolume()) return;
                if (!desiredPlayback) { playing = false; persistPosition(); publish(); return; }
                prepared.start(); playing = true; enqueueStartedAudio(token); publish();
            });
            candidate.setOnCompletionListener(completed -> {
                if (!destroyed && player == completed && token == generation) {
                    playing = false;
                    clearResumePosition();
                    if (desiredPlayback && queue != null && queue.count() > 0) {
                        advanceQueue(true);
                    }
                    else { desiredPlayback = false; publish(); }
                }
            });
            candidate.setOnInfoListener((reporting, what, extra) -> {
                if (destroyed || player != reporting || token != generation) return false;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    buffering = what == MediaPlayer.MEDIA_INFO_BUFFERING_START;
                    persistPosition();
                    publish();
                }
                return false;
            });
            candidate.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) publishError("音频解码或网络播放失败");
                return true;
            });
            if (url.startsWith("content://") || url.startsWith("file://")) {
                candidate.setDataSource(this, Uri.parse(url));
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                candidate.setDataSource(this, Uri.parse(url), sourceHeaders(intent));
            } else {
                candidate.setDataSource(url);
            }
            publish();
            candidate.prepareAsync();
        } catch (Exception error) {
            Log.w("VutronPlayback", "Could not prepare the selected media", error);
            publishError("歌曲准备失败");
        }
    }

    private static boolean isSupportedSource(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        return url.startsWith("https://") || url.startsWith("http://")
                || url.startsWith("content://") || url.startsWith("file://")
                || new File(url).isFile();
    }

    private static JSONObject readCacheLocation(Intent intent) {
        String raw = intent.getStringExtra("cacheLocation");
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); }
        catch (org.json.JSONException invalid) {
            Log.w("VutronPlayback", "Could not read the resolved automatic cache location");
            return null;
        }
    }

    /** Offer the already resolved source once, only after this player really starts. */
    private void enqueueStartedAudio(int token) {
        if (destroyed || !playing || token != generation || cacheLocation == null
                || cacheEnqueuedGeneration == token) return;
        cacheEnqueuedGeneration = token;
        AudioCache.enqueue(this, trackMetadata, cacheLocation);
    }

    /** Keep the selected queue row's stable volume keys when its URI is resolved elsewhere. */
    private JSONObject readTrackMetadata(Intent intent, long requestedTrackId) {
        String raw = intent.getStringExtra("track");
        if (raw == null || raw.isEmpty()) raw = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE)
                .getString("current_track", "");
        try {
            JSONObject metadata = new JSONObject(raw == null ? "{}" : raw);
            if (metadata.optLong("id", requestedTrackId) == requestedTrackId) {
                metadata.put("id", requestedTrackId);
                return metadata;
            }
        } catch (org.json.JSONException invalid) {
            Log.w("VutronPlayback", "Could not read the current track's saved metadata", invalid);
        }
        JSONObject metadata = new JSONObject();
        try { metadata.put("id", requestedTrackId); }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return metadata;
    }

    /** Retain provider-required request headers such as Referer and User-Agent. */
    private static Map<String, String> sourceHeaders(Intent intent) throws org.json.JSONException {
        String raw = intent.getStringExtra("sourceHeaders");
        Map<String, String> headers = new HashMap<>();
        if (raw == null || raw.trim().isEmpty()) return headers;
        JSONObject values = new JSONObject(raw);
        Iterator<String> names = values.keys();
        while (names.hasNext()) {
            String name = names.next();
            headers.put(name, values.getString(name));
        }
        return headers;
    }

    private boolean applyEffectiveVolume() {
        if (player == null || !playerPrepared) return true;
        float gain = PlaybackVolume.trackPercent(this, trackMetadata)
                * PlaybackVolume.playlistPercent(this, trackMetadata) / 10000f * focusVolume;
        try {
            player.setVolume(gain, gain);
            return true;
        } catch (IllegalStateException expired) {
            Log.w("VutronPlayback", "Could not update the current playback volume", expired);
            publishError("播放器状态已失效，请重新播放歌曲");
            return false;
        }
    }

    private boolean requestFocus() {
        focusVolume = 1f;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focusListener).build();
            return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void pause() {
        resumeOnFocusGain = false;
        if (player == null) {
            cancelUrlTask();
            generation++;
            desiredPlayback = false;
            getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit()
                    .putBoolean(LAST_PLAYING, false).putBoolean("playing", false).apply();
            playing = false;
            publish();
            if (sleepTimerRemainingMs(this) <= 0L) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return;
        }
        if (playerPrepared) {
            try { if (player.isPlaying()) player.pause(); }
            catch (IllegalStateException expired) { Log.w("VutronPlayback", "Could not pause the prepared player", expired); }
        }
        playing = false;
        desiredPlayback = false;
        persistPosition(); publish();
    }

    private void resumeOrRestore() {
        if (player != null) {
            // resume() also records intent while prepareAsync is pending.
            // Otherwise pause -> play during preparation stays paused forever.
            resume();
            return;
        }
        if (urlLookupPending || destroyed) return;
        if (queue != null && queue.count() > 0) { selectQueueTrack(true); return; }
        android.content.SharedPreferences state = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE);
        long savedTrackId = state.getLong(LAST_TRACK_ID, state.getLong("trackId", 0L));
        if (savedTrackId <= 0) return;
        desiredPlayback = true;
        state.edit().putBoolean(LAST_PLAYING, true).apply();
        urlLookupPending = true;
        int token = ++generation;
        urlTask = urlWorker.submit(() -> {
            try {
                Intent intent = PlaybackRequest.prepare(this, PlaybackRequest.remembered(this), true);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (destroyed || token != generation) return;
                    urlLookupPending = false;
                    urlTask = null;
                    startTrack(intent);
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (destroyed || token != generation) return;
                    urlLookupPending = false;
                    urlTask = null;
                    publishError(error.getMessage() == null
                            ? "恢复播放失败" : error.getMessage());
                });
            }
        });
    }

    private void resume() {
        resumeOnFocusGain = false;
        desiredPlayback = true;
        if (player == null) { resumeOrRestore(); return; }
        if (!playerPrepared) { persistPosition(); return; }
        if (!requestFocus()) return;
        if (!applyEffectiveVolume()) return;
        try { player.start(); playing = true; enqueueStartedAudio(generation); persistPosition(); publish(); }
        catch (IllegalStateException ignored) { publishError("播放器尚未准备完成"); }
    }

    private void stopPlayback() {
        stopPlayback(false);
    }

    private void stopPlayback(boolean clearRemembered) {
        generation++;
        cancelUrlTask();
        artworkGeneration++;
        if (clearRemembered) clearSleepTimer(this);
        persistPosition();
        releasePlayer(); playing = false; desiredPlayback = false; trackId = 0; duration = 0;
        resumeOnFocusGain = false;
        focusVolume = 1f;
        artwork = null; artworkUrl = "";
        if (audio != null) {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            else audio.abandonAudioFocus(focusListener);
        }
        publish();
        if (clearRemembered) clearRememberedPlayback();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void releasePlayer() {
        MediaPlayer old = player; player = null;
        playerPrepared = false;
        buffering = false;
        cacheLocation = null;
        if (old != null) try { old.release(); } catch (RuntimeException ignored) { }
    }

    private void publishError(String message) {
        playing = false;
        desiredPlayback = false;
        persistPosition();
        releasePlayer();
        getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit().putString("error", message).apply();
        publish();
    }

    /** Persist the current media position without requiring a UI event. */
    private void persistPosition() {
        android.content.SharedPreferences preferences = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE);
        int position = 0;
        if (player != null && playerPrepared) {
            try { position = Math.max(0, player.getCurrentPosition()); } catch (IllegalStateException ignored) { }
        } else if (trackId == preferences.getLong(LAST_TRACK_ID, 0L)) {
            position = preferences.getInt(LAST_POSITION, 0);
        }
        if (trackId > 0) {
            preferences.edit().putLong(LAST_TRACK_ID, trackId).putInt(LAST_POSITION, position)
                    .putLong(LAST_DURATION, duration).putString(LAST_TITLE, title)
                    .putString(LAST_ARTIST, artist).putBoolean(LAST_PLAYING, desiredPlayback).apply();
        }
        preferences.edit().putInt("position", position)
                .putLong("positionElapsedRealtime", SystemClock.elapsedRealtime()).apply();
    }

    private void clearResumePosition() {
        getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit()
                .putInt(LAST_POSITION, 0).putBoolean(LAST_PLAYING, false).apply();
    }

    private void clearRememberedPlayback() {
        PlaybackQueue.clear(this);
        queue = null;
        getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit()
                .remove(LAST_TRACK_ID).remove(LAST_POSITION).remove(LAST_DURATION)
                .remove(LAST_TITLE).remove(LAST_ARTIST).remove("error").remove("current_track").putBoolean(LAST_PLAYING, false)
                .putLong("trackId", 0L).putInt("position", 0).putBoolean("playing", false)
                .apply();
    }


    private void publish() {
        int position = 0;
        if (player != null && playerPrepared) {
            try { position = Math.max(0, player.getCurrentPosition()); } catch (IllegalStateException ignored) { }
        } else if (trackId > 0) {
            position = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).getInt(LAST_POSITION, 0);
        }
        if (session != null) {
            MediaMetadata.Builder metadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist).putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            if (artwork != null && !artwork.isRecycled()) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            }
            session.setMetadata(metadata.build());
            int state = urlLookupPending || buffering ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING
                    : trackId > 0 ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
            session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                    .setState(state, position, playing && !buffering ? 1f : 0f, SystemClock.elapsedRealtime()).build());
        }
        getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit().putBoolean("playing", playing)
                .putBoolean("loading", urlLookupPending || player != null && !playerPrepared && desiredPlayback)
                .putBoolean("buffering", buffering && playing)
                .putLong("trackId", trackId).putString("title", title).putString("artist", artist)
                .putLong("duration", duration).putInt("position", position)
                .putLong("positionElapsedRealtime", SystemClock.elapsedRealtime())
                .putString("playbackQuality", PlaybackQuality.describe(trackMetadata))
                .putInt("trackVolumePercent", PlaybackVolume.trackPercent(this, trackMetadata))
                .putInt("playlistVolumePercent", PlaybackVolume.playlistPercent(this, trackMetadata)).apply();
        if (playing || player != null || urlLookupPending) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private Notification notification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).setContentText(artist.isEmpty() ? "Vutron Music - Mobile" : artist)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(playing || urlLookupPending).setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "上一首", action(ACTION_PREVIOUS, 1))
                .addAction(playing || desiredPlayback ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing || desiredPlayback ? "暂停" : "播放", action(playing || desiredPlayback ? ACTION_PAUSE : ACTION_RESUME, 2))
                .addAction(android.R.drawable.ic_media_next, "下一首", action(ACTION_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 4));
        if (artwork != null && !artwork.isRecycled()) builder.setLargeIcon(artwork);
        return builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2)).build();
    }

    private String rememberedArtwork() {
        String raw = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).getString("current_track", "");
        if (raw == null || raw.isEmpty()) return "";
        try {
            JSONObject metadata = new JSONObject(raw);
            String local = metadata.optString("picLocalUri", "").trim();
            if (local.isEmpty()) local = metadata.optString("coverLocalUri", "").trim();
            return local.isEmpty() ? ArtworkSource.coverUrl(metadata) : local;
        } catch (Exception ignored) { return ""; }
    }

    private void loadArtwork(String source) {
        artworkGeneration++;
        int token = artworkGeneration;
        artworkUrl = source == null ? "" : source.trim();
        artwork = null;
        if (artworkUrl.isEmpty()) { publish(); return; }
        final String requested = artworkUrl;
        final String fallback = ArtworkSource.coverUrl(trackMetadata);
        ArtworkCache.enqueue(this, fallback.isEmpty() ? requested : fallback);
        artworkWorker.execute(() -> {
            Bitmap decoded = decodeArtwork(requested);
            if (decoded == null && !fallback.isEmpty() && !fallback.equals(requested)) decoded = decodeArtwork(fallback);
            final Bitmap loaded = decoded;
            if (loaded == null || destroyed) return;
            progressHandler.post(() -> {
                if (destroyed || token != artworkGeneration) { loaded.recycle(); return; }
                artwork = loaded;
                publish();
            });
        });
    }

    private Bitmap decodeArtwork(String source) {
        HttpURLConnection connection = null;
        try {
            byte[] bytes;
            if (source.startsWith("file://") || source.startsWith("content://")) {
                InputStream sourceInput = source.startsWith("file://")
                        ? new java.io.FileInputStream(new File(Uri.parse(source).getPath()))
                        : getContentResolver().openInputStream(Uri.parse(source));
                try (InputStream input = sourceInput; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (input == null) return null;
                    copyImage(input, output);
                    bytes = output.toByteArray();
                }
            } else if (source.startsWith("https://") || source.startsWith("http://")) {
                connection = (HttpURLConnection) new URL(ArtworkSource.requestUrl(source)).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
                connection.setRequestProperty("Referer", "https://music.163.com/");
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return null;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    copyImage(input, output);
                    bytes = output.toByteArray();
                }
            } else return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 512 || bounds.outHeight / options.inSampleSize > 512)
                options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static void copyImage(InputStream input, ByteArrayOutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > 8 * 1024 * 1024) throw new java.io.IOException("Artwork exceeds its size limit");
            output.write(buffer, 0, count);
        }
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, AndroidPlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, requestCode, intent, flags);
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (progressHandler != null) progressHandler.removeCallbacks(progressTicker);
        persistPosition();
        stopPlayback();
        urlWorker.shutdownNow();
        artworkWorker.shutdownNow();
        if (session != null) { session.setActive(false); session.release(); }
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
