package io.github.vutronmusic.personal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.Gravity;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Floating lyric surface for the Capacitor shell; it follows the native player's persisted clock. */
public final class AndroidLyricOverlayService extends Service {
    static final String ACTION_SHOW = "io.github.vutronmusic.personal.webshell.SHOW_LYRICS";
    static final String ACTION_HIDE = "io.github.vutronmusic.personal.webshell.HIDE_LYRICS";
    static final String ACTION_TEMPORARY_HIDE = "io.github.vutronmusic.personal.webshell.TEMPORARY_HIDE_LYRICS";
    static final String ACTION_RESTORE = "io.github.vutronmusic.personal.webshell.RESTORE_LYRICS";
    private static final String CHANNEL = "vutron_android_lyrics";
    private static final String TAG = "VutronLyricsOverlay";
    private static final String HIDDEN_UNTIL = "web_hidden_until";
    private static final long WINDOW_CHECK_MS = 2000L;
    private static volatile AndroidLyricOverlayService active;
    private static long nextServiceRecoveryAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService lyricWorker = Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private WindowManager.LayoutParams windowParams;
    private LinearLayout root;
    private TimedLyricView original;
    private TimedLyricView translation;
    private TextView nextLine;
    private LyricRepository.Lyrics lyrics;
    private long lyricTrackId;
    private long lyricGeneration;
    private final LyricVisibility visibility = new LyricVisibility();
    private long nextWindowRetryAt;
    private int windowFailures;
    private long nextValidationAt;
    private boolean loadingLyrics;
    private String lyricSongKey = "";
    private final LyricFrameClock clock = new LyricFrameClock();
    private Choreographer choreographer;
    private boolean frameScheduled;
    private long lyricDuration;
    private long lyricOffset;
    private boolean estimateWords;
    private boolean showTranslation;
    private boolean showNextLine;
    private boolean interactive;
    private boolean lastPlaying;
    private boolean destroyed;
    private int appliedStyleHash = Integer.MIN_VALUE;
    private boolean settingsRefreshScheduled;

    /** Apply bridge previews on the next UI frame without waiting for the 100ms state poll. */
    static void refreshSettings() {
        AndroidLyricOverlayService service = active;
        if (service == null || service.destroyed) return;
        service.handler.post(() -> {
            if (service.destroyed || service.settingsRefreshScheduled) return;
            service.settingsRefreshScheduled = true;
            service.choreographer.postFrameCallback(ignored -> {
                service.settingsRefreshScheduled = false;
                if (!service.destroyed) service.updateText();
            });
        });
    }

    private final Choreographer.FrameCallback lyricFrame = frameTimeNanos -> {
        frameScheduled = false;
        if (!canAnimate()) return;
        // Same separation as the original LyricLine animations: metadata is sampled
        // separately; highlighting and long-line motion advance on every display frame.
        renderLyrics(clock.positionAt(SystemClock.elapsedRealtime()));
        scheduleFrame();
    };

    private boolean canAnimate() {
        return !destroyed && lastPlaying && lyrics != null && !lyrics.original.lines.isEmpty()
                && root != null && root.isAttachedToWindow() && root.isShown()
                && interactive;
    }

    private void scheduleFrame() {
        if (choreographer == null || frameScheduled || !canAnimate()) return;
        frameScheduled = true;
        choreographer.postFrameCallback(lyricFrame);
    }

    private void stopFrames() {
        if (choreographer != null && frameScheduled) choreographer.removeFrameCallback(lyricFrame);
        frameScheduled = false;
    }

    static boolean isRunning() {
        AndroidLyricOverlayService service = active;
        return service != null && !service.destroyed;
    }

    static long temporaryHiddenRemainingMs(Context context) {
        long deadline = context.getSharedPreferences("lyric_overlay", MODE_PRIVATE).getLong(HIDDEN_UNTIL, 0);
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    static void temporaryHide(Context context, long durationMs) {
        context.getSharedPreferences("lyric_overlay", MODE_PRIVATE).edit()
                .putLong(HIDDEN_UNTIL, System.currentTimeMillis() + Math.max(1000L, Math.min(300_000L, durationMs))).apply();
        AndroidLyricOverlayService service = active;
        if (service != null && !service.destroyed) service.handler.post(() -> { service.detachWindow(); service.updateNotification(); });
    }

    static void restoreNow(Context context) {
        context.getSharedPreferences("lyric_overlay", MODE_PRIVATE).edit().remove(HIDDEN_UNTIL).apply();
        AndroidLyricOverlayService service = active;
        if (service != null && !service.destroyed) service.handler.post(() -> {
            service.visibility.preview(SystemClock.elapsedRealtime());
            service.nextWindowRetryAt = 0;
            service.updateText();
            service.updateNotification();
        });
    }

    /** Playback service restores an unexpectedly lost overlay, without resetting manual hiding. */
    static void recoverIfEnabled(Context context) {
        android.content.SharedPreferences state = context.getSharedPreferences("vutron_playback", MODE_PRIVATE);
        if (!LyricVisibility.shouldRecoverService(state.getBoolean("playing", false), state.getBoolean("loading", false))) return;
        if (isRunning() || !LyricOverlaySettings.enabled(context) || !Settings.canDrawOverlays(context)) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextServiceRecoveryAt) return;
        nextServiceRecoveryAt = now + 10_000L;
        try { androidx.core.content.ContextCompat.startForegroundService(context,
                new Intent(context, AndroidLyricOverlayService.class).setAction(ACTION_SHOW)); }
        catch (RuntimeException unavailable) { Log.w(TAG, "Overlay service recovery deferred", unavailable); }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            updateText();
            handler.postDelayed(this, 100L);
        }
    };

    private final Runnable windowCheck = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            updateText();
            if (root != null && root.isAttachedToWindow() && shouldDisplay()
                    && SystemClock.elapsedRealtime() >= nextWindowRetryAt) {
                try {
                    windows.updateViewLayout(root, windowParams);
                    root.requestLayout(); root.invalidate();
                    windowFailures = 0; nextWindowRetryAt = 0;
                } catch (RuntimeException unavailable) { retryWindow(unavailable); }
            }
            updateNotification();
            handler.postDelayed(this, WINDOW_CHECK_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, BuildConfig.APP_DISPLAY_NAME + " 歌词", NotificationManager.IMPORTANCE_LOW));
            startForeground(1907, notification());
        }
        windows = getSystemService(WindowManager.class);
        choreographer = Choreographer.getInstance();
        handler.post(ticker);
        handler.post(windowCheck);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            android.content.SharedPreferences state = getSharedPreferences("vutron_playback", MODE_PRIVATE);
            if (!LyricVisibility.shouldRecoverService(state.getBoolean("playing", false), state.getBoolean("loading", false))) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            LyricOverlaySettings.enabled(this, false);
            getSharedPreferences("lyric_overlay", MODE_PRIVATE).edit().remove(HIDDEN_UNTIL).apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!LyricOverlaySettings.enabled(this)) { stopSelf(startId); return START_NOT_STICKY; }
        if (intent != null && ACTION_TEMPORARY_HIDE.equals(intent.getAction())) temporaryHide(this, 30_000L);
        else if (intent != null && ACTION_RESTORE.equals(intent.getAction())) restoreNow(this);
        updateText();
        return START_STICKY;
    }

    private Notification notification() {
        boolean hidden = temporaryHiddenRemainingMs(this) > 0;
        String status;
        if (hidden) status = "歌词已临时隐藏，播放中到时恢复";
        else if (!LyricOverlaySettings.locked(this)) status = "拖动调整位置；锁定后默认允许点击下方";
        else if (LyricOverlaySettings.touchPassthroughPriority(this)) status = "已锁定并优先下方点击；仍无法点击时请临时隐藏";
        else status = "已锁定并保留完整不透明度；可能阻止下方点击";
        return (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this)).setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(BuildConfig.APP_DISPLAY_NAME + " 桌面歌词").setContentText(status)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(android.R.drawable.ic_menu_view, hidden ? "立即恢复" : "恢复显示", notificationAction(ACTION_RESTORE, 11))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "无法点击？隐藏 30 秒", notificationAction(ACTION_TEMPORARY_HIDE, 12))
                .addAction(android.R.drawable.ic_delete, "关闭歌词", notificationAction(ACTION_HIDE, 13)).build();
    }

    private PendingIntent notificationAction(String action, int request) {
        return PendingIntent.getService(this, request, new Intent(this, AndroidLyricOverlayService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification() {
        if (!destroyed) getSystemService(NotificationManager.class).notify(1907, notification());
    }

    private boolean shouldDisplay() {
        android.content.SharedPreferences state = getSharedPreferences("vutron_playback", MODE_PRIVATE);
        long id = state.getLong("trackId", 0L);
        if (id <= 0) id = state.getLong("lastTrackId", 0L);
        long deadline = getSharedPreferences("lyric_overlay", MODE_PRIVATE).getLong(HIDDEN_UNTIL, 0);
        return visibility.visible(state.getBoolean("playing", false), state.getBoolean("loading", false), id > 0,
                SystemClock.elapsedRealtime(), System.currentTimeMillis(), deadline);
    }

    private void detachWindow() {
        stopFrames();
        if (root == null || windows == null || !root.isAttachedToWindow() && root.getParent() == null) return;
        try { windows.removeViewImmediate(root); }
        catch (RuntimeException alreadyRemoved) { Log.d(TAG, "Lyric window already detached"); }
    }

    private void retryWindow(RuntimeException unavailable) {
        windowFailures = Math.min(5, windowFailures + 1);
        nextWindowRetryAt = SystemClock.elapsedRealtime() + windowFailures * WINDOW_CHECK_MS;
        Log.w(TAG, "Lyric window temporarily unavailable; retry scheduled", unavailable);
    }

    private void ensureWindow() {
        if (SystemClock.elapsedRealtime() < nextWindowRetryAt) return;
        if (root == null) { createWindow(); return; }
        try {
            if (!root.isAttachedToWindow()) windows.addView(root, windowParams);
            root.setVisibility(View.VISIBLE);
        } catch (RuntimeException unavailable) {
            retryWindow(unavailable);
        }
    }

    private void createWindow() {
        if (root != null || windows == null) return;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        original = new TimedLyricView(this);
        translation = new TimedLyricView(this);
        nextLine = new TextView(this);
        nextLine.setGravity(Gravity.CENTER);
        nextLine.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        nextLine.setTextSize(Math.max(13, LyricOverlaySettings.fontSize(this) - 5));
        root.addView(original, new LinearLayout.LayoutParams(-1, -2));
        root.addView(translation, new LinearLayout.LayoutParams(-1, -2));
        root.addView(nextLine, new LinearLayout.LayoutParams(-1, -2));
        root.setOnTouchListener(new DragWindow());

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        windowParams = new WindowManager.LayoutParams(Math.min(dp(360), Math.max(dp(180), screenWidth - dp(24))),
                -2, type, flags, PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        windowParams.y = getSharedPreferences("lyric_overlay", MODE_PRIVATE).getInt("web_y", dp(112));
        windowParams.x = getSharedPreferences("lyric_overlay", MODE_PRIVATE).getInt("web_x", 0);
        applyOverlayStyle(true);
        try { windows.addView(root, windowParams); }
        catch (RuntimeException unavailable) { retryWindow(unavailable); }
    }

    private void applyOverlayStyle(boolean force) {
        if (root == null || original == null || translation == null || nextLine == null) return;
        boolean locked = LyricOverlaySettings.locked(this);
        boolean touchPriority = LyricOverlaySettings.touchPassthroughPriority(this);
        int fontSize = LyricOverlaySettings.fontSize(this);
        int playedColor = LyricOverlaySettings.playedColor(this);
        int unplayedColor = LyricOverlaySettings.unplayedColor(this);
        int backgroundColor = LyricOverlaySettings.backgroundColor(this);
        boolean shadow = LyricOverlaySettings.shadowEnabled(this);
        int shadowColor = LyricOverlaySettings.shadowColor(this);
        int shadowRadius = LyricOverlaySettings.shadowRadius(this);
        int styleHash = Objects.hash(locked, touchPriority, fontSize, playedColor, unplayedColor,
                backgroundColor, shadow, shadowColor, shadowRadius);
        if (!force && styleHash == appliedStyleHash) return;
        appliedStyleHash = styleHash;

        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(18));
        root.setBackground(background);
        original.setLyricSize(fontSize);
        translation.setLyricSize(Math.max(12, fontSize - 7));
        original.setColors(playedColor, unplayedColor);
        translation.setColors(playedColor, unplayedColor);
        original.setLyricShadow(shadow, dpFloat(shadowRadius), shadowColor);
        translation.setLyricShadow(shadow, dpFloat(shadowRadius), shadowColor);
        nextLine.setTextSize(Math.max(13, fontSize - 5));
        nextLine.setTextColor(unplayedColor);
        if (shadow && shadowRadius > 0) nextLine.setShadowLayer(dpFloat(shadowRadius), 0, dp(1), shadowColor);
        else nextLine.getPaint().clearShadowLayer();
        windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (locked) windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windowParams.alpha = locked && touchPriority ? lockedWindowOpacity() : 1f;
        if (root.isAttachedToWindow()) {
            try { windows.updateViewLayout(root, windowParams); } catch (RuntimeException ignored) { }
        }
    }

    private float lockedWindowOpacity() {
        if (Build.VERSION.SDK_INT < 31) return 1f;
        InputManager input = getSystemService(InputManager.class);
        if (input == null) {
            Log.w(TAG, "Touch opacity limit unavailable; hiding locked surface until unlocked");
            return 0f;
        }
        final float limit;
        try { limit = input.getMaximumObscuringOpacityForTouch(); }
        catch (RuntimeException unavailable) {
            Log.w(TAG, "Touch opacity limit could not be read; hiding locked surface until unlocked", unavailable);
            return 0f;
        }
        if (!Float.isFinite(limit) || limit < 0f || limit > 1f) {
            Log.w(TAG, "Invalid touch opacity limit; hiding locked surface until unlocked");
            return 0f;
        }
        return LyricOverlaySettings.windowOpacity(true, true, limit);
    }

    private void updateText() {
        if (!LyricOverlaySettings.enabled(this) || !Settings.canDrawOverlays(this)) { stopSelf(); return; }
        if (!shouldDisplay()) { detachWindow(); return; }
        ensureWindow();
        if (root == null || !root.isAttachedToWindow()) return;
        applyOverlayStyle(false);
        android.content.SharedPreferences state = getSharedPreferences("vutron_playback", MODE_PRIVATE);
        boolean playing = state.getBoolean("playing", false) && !state.getBoolean("buffering", false);
        long id = state.getLong("trackId", 0L);
        if (id <= 0) id = state.getLong("lastTrackId", 0L);
        long storedPosition = state.getInt("position", state.getInt("lastPosition", 0));
        long duration = state.getLong("duration", state.getLong("lastDuration", 0L));
        long now = SystemClock.elapsedRealtime();
        PowerManager power = getSystemService(PowerManager.class);
        interactive = power == null || power.isInteractive();
        clock.update(id, storedPosition, state.getLong("positionElapsedRealtime", 0L), playing, duration, now);
        lastPlaying = playing;
        lyricDuration = duration;

        root.setVisibility(View.VISIBLE);

        JSONObject song = currentSong(state);
        lyricOffset = LyricOverlaySettings.lyricOffsetMs(this, song);
        estimateWords = LyricOverlaySettings.estimatedWordTiming(this);
        showTranslation = LyricOverlaySettings.translation(this);
        showNextLine = LyricOverlaySettings.nextLine(this);
        String songKey = LyricOverlaySettings.trackIdentity(song) + "|" + LyricOverlaySettings.remoteLyricId(song);
        if (id != lyricTrackId || !songKey.equals(lyricSongKey)) {
            lyricSongKey = songKey;
            nextValidationAt = 0;
            loadLyricsForTrack(id, state);
        } else if (!loadingLyrics && now >= nextValidationAt) {
            nextValidationAt = now + 30_000;
            validateLyrics(song, lyricGeneration);
        }
        if (lyrics == null || lyrics.original.lines.isEmpty()) {
            original.setLyricText(state.getString("title", ""));
            original.setProgress(0);
            translation.setLyricText("");
            setNextText("");
            stopFrames();
            return;
        }
        renderLyrics(clock.positionAt(now));
        if (canAnimate()) scheduleFrame();
        else stopFrames();
    }

    private void setNextText(String value) {
        if (!android.text.TextUtils.equals(nextLine.getText(), value)) nextLine.setText(value);
    }

    private void renderLyrics(long position) {
        if (lyrics == null || lyrics.original.lines.isEmpty()) return;

        long effectiveTime = LyricTimeline.adjustedTime(position, lyricOffset);
        int index = lyrics.original.indexAt(effectiveTime);
        if (index < 0 || index >= lyrics.original.lines.size()) {
            original.setLyricText("");
            translation.setLyricText("");
            String first = lyrics.original.lines.get(0).text;
            setNextText(first);
            nextLine.setVisibility(showNextLine ? View.VISIBLE : View.GONE);
            return;
        }

        LyricTimeline.Line line = lyrics.original.lines.get(index);
        long nextTime = index + 1 < lyrics.original.lines.size()
                ? lyrics.original.lines.get(index + 1).time : lyricDuration;
        if (line.duration > 0) nextTime = line.time + line.duration;
        if (nextTime <= line.time) nextTime = line.time + 3000;
        original.setLyricText(line.text);
        boolean estimate = estimateWords;
        original.setProgress(line.displayCharacters(effectiveTime, nextTime, estimate));
        boolean progressive = !line.words.isEmpty() || estimate;

        String translatedText = showTranslation ? lyrics.translationAt(index) : "";
        translation.setLyricText(translatedText);
        if (translatedText.isEmpty()) {
            translation.setVisibility(View.GONE);
        } else {
            translation.setVisibility(View.VISIBLE);
            int translatedIndex = lyrics.translation.indexAt(line.time);
            if (translatedIndex >= 0 && translatedIndex < lyrics.translation.lines.size()
                    && lyrics.translation.lines.get(translatedIndex).time == line.time
                    && lyrics.translation.lines.get(translatedIndex).text.equals(translatedText)) {
                LyricTimeline.Line translatedLine = lyrics.translation.lines.get(translatedIndex);
                long translatedEnd = translatedIndex + 1 < lyrics.translation.lines.size()
                        ? lyrics.translation.lines.get(translatedIndex + 1).time : nextTime;
                if (translatedEnd <= translatedLine.time) translatedEnd = nextTime;
                translation.setProgress(translatedLine.words.isEmpty()
                        ? progressive ? translatedText.length() * clampFraction(effectiveTime, line.time, nextTime) : translatedText.length()
                        : translatedLine.highlightedCharacters(effectiveTime, translatedEnd));
            } else {
                translation.setProgress(progressive ? translatedText.length() * clampFraction(effectiveTime, line.time, nextTime)
                        : translatedText.length());
            }
        }
        String nextText = index + 1 < lyrics.original.lines.size() ? lyrics.original.lines.get(index + 1).text : "";
        setNextText(nextText);
        nextLine.setVisibility(!nextText.isEmpty() && showNextLine ? View.VISIBLE : View.GONE);
    }

    private void loadLyricsForTrack(long id, android.content.SharedPreferences state) {
        lyricTrackId = id;
        lyrics = null;
        long generation = ++lyricGeneration;
        JSONObject song = currentSong(state);
        loadingLyrics = true;
        nextValidationAt = SystemClock.elapsedRealtime() + 30_000;
        lyricWorker.execute(() -> {
            try {
                LyricRepository.Lyrics loaded = LyricRepository.loadForTrack(this, song);
                long remoteId = LyricOverlaySettings.remoteLyricId(song);
                if (!loaded.local && remoteId > 0 && LyricRepository.hasValidationWifi(this)) {
                    try {
                        LyricRepository.Lyrics refreshed = LyricRepository.revalidateOnWifi(this, remoteId);
                        if (refreshed != null) loaded = refreshed;
                    } catch (java.io.IOException deferred) { Log.d(TAG, "Wi-Fi lyric refresh deferred; keeping cached lyrics"); }
                }
                LyricRepository.Lyrics result = loaded;
                handler.post(() -> {
                    if (!destroyed && generation == lyricGeneration) { lyrics = result; loadingLyrics = false; }
                });
            } catch (Exception unavailable) {
                Log.w(TAG, "Lyrics unavailable: " + unavailable.getClass().getSimpleName());
                handler.post(() -> { if (generation == lyricGeneration) loadingLyrics = false; });
            }
        });
    }

    private void validateLyrics(JSONObject song, long generation) {
        long id = LyricOverlaySettings.remoteLyricId(song);
        if (id <= 0 || lyrics != null && lyrics.local || !LyricOverlaySettings.wifiValidation(this) || !LyricRepository.hasValidationWifi(this)) return;
        loadingLyrics = true;
        lyricWorker.execute(() -> {
            try {
                LyricRepository.Lyrics refreshed = LyricRepository.revalidateOnWifi(this, id);
                handler.post(() -> {
                    if (!destroyed && generation == lyricGeneration) {
                        if (refreshed != null) lyrics = refreshed;
                        loadingLyrics = false;
                    }
                });
            } catch (java.io.IOException unavailable) {
                Log.d(TAG, "Wi-Fi lyric validation deferred; keeping offline copy");
                handler.post(() -> { if (generation == lyricGeneration) loadingLyrics = false; });
            }
        });
    }

    private static float clampFraction(long position, long start, long end) {
        if (end <= start) return 0f;
        return Math.max(0f, Math.min(1f, (float) (position - start) / (end - start)));
    }

    private JSONObject currentSong(android.content.SharedPreferences state) {
        try { return new JSONObject(state.getString("current_track", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopFrames();
        lyricGeneration++;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(windowCheck);
        lyricWorker.shutdownNow();
        if (root != null && windows != null) try { windows.removeView(root); } catch (RuntimeException ignored) { }
        root = null;
        if (active == this) active = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private float dpFloat(int value) { return value * getResources().getDisplayMetrics().density; }

    private final class DragWindow implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (LyricOverlaySettings.locked(AndroidLyricOverlayService.this)) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = windowParams.x; startY = windowParams.y; moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(4)) moved = true;
                    if (moved) {
                        windowParams.x = startX + Math.round(dx);
                        windowParams.y = Math.max(0, startY + Math.round(dy));
                        try { windows.updateViewLayout(root, windowParams); } catch (RuntimeException ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) getSharedPreferences("lyric_overlay", MODE_PRIVATE).edit()
                            .putInt("web_x", windowParams.x).putInt("web_y", windowParams.y).apply();
                    return true;
                default: return true;
            }
        }
    }

    /** Smoothly reveals timed text and scrolls long lines to keep the active words visible. */
    private final class TimedLyricView extends View {
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private String text = "";
        private StaticLayout layout;
        private float progress;
        private float textSizePx;
        private int playedColor = LyricOverlaySettings.DEFAULT_PLAYED_COLOR;
        private int unplayedColor = LyricOverlaySettings.DEFAULT_UNPLAYED_COLOR;
        private int lastContentWidth = -1;
        private final Path reveal = new Path();

        TimedLyricView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            setPadding(0, dp(3), 0, dp(3));
            setWillNotDraw(false);
        }

        void setLyricText(String value) {
            String next = value == null ? "" : value;
            if (text.equals(next)) return;
            text = next;
            setContentDescription(next);
            progress = 0;
            layout = null;
            lastContentWidth = -1;
            requestLayout();
            invalidate();
        }

        void setLyricSize(int sp) {
            float next = sp * getResources().getDisplayMetrics().scaledDensity;
            if (textSizePx == next) return;
            textSizePx = next;
            textPaint.setTextSize(next);
            layout = null;
            lastContentWidth = -1;
            requestLayout();
            invalidate();
        }

        void setColors(int played, int unplayed) {
            playedColor = played;
            unplayedColor = unplayed;
            invalidate();
        }

        void setLyricShadow(boolean enabled, float radius, int color) {
            if (enabled && radius > 0) textPaint.setShadowLayer(radius, 0, dp(1), color);
            else textPaint.clearShadowLayer();
            invalidate();
        }

        void setProgress(float value) {
            float next = Math.max(0f, Math.min(text.length(), value));
            if (Math.abs(progress - next) < .0001f) return;
            progress = next;
            invalidate();
        }

        private void ensureLayout(int contentWidth) {
            if (contentWidth <= 0 || text.isEmpty()) { layout = null; return; }
            if (layout != null && contentWidth == lastContentWidth) return;
            int layoutWidth = contentWidth;
            if (text.indexOf('\n') < 0) layoutWidth = Math.max(contentWidth, (int) Math.ceil(textPaint.measureText(text)));
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, layoutWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .build();
            lastContentWidth = contentWidth;
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) width = dp(320);
            int contentWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            ensureLayout(contentWidth);
            int height = (layout == null ? Math.max(1, (int) textSizePx) : layout.getHeight())
                    + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int viewportWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            ensureLayout(viewportWidth);
            if (layout == null) return;
            int save = canvas.save();
            canvas.clipRect(getPaddingLeft(), 0, getWidth() - getPaddingRight(), getHeight());
            float scroll = 0;
            if (layout.getWidth() > viewportWidth && !text.isEmpty()) {
                float focus = horizontalAt(layout, progress);
                scroll = Math.max(0f, Math.min(layout.getWidth() - viewportWidth, focus - viewportWidth / 2f));
            }
            float vertical = getPaddingTop() + Math.max(0f,
                    (getHeight() - getPaddingTop() - getPaddingBottom() - layout.getHeight()) / 2f);
            canvas.translate(getPaddingLeft() - scroll, vertical);
            textPaint.setColor(unplayedColor);
            layout.draw(canvas);
            Path reveal = revealPath(layout, progress);
            if (reveal != null && !reveal.isEmpty()) {
                int revealSave = canvas.save();
                canvas.clipPath(reveal);
                textPaint.setColor(playedColor);
                layout.draw(canvas);
                canvas.restoreToCount(revealSave);
            }
            canvas.restoreToCount(save);
        }

        private Path revealPath(StaticLayout textLayout, float characterPosition) {
            int length = text.length();
            if (length == 0 || characterPosition <= 0) return null;
            Path path = reveal;
            path.rewind();
            if (characterPosition >= length) {
                path.addRect(0, 0, textLayout.getWidth(), textLayout.getHeight(), Path.Direction.CW);
                return path;
            }
            int index = (int) characterPosition;
            if (index > 0 && index < length && Character.isHighSurrogate(text.charAt(index - 1))
                    && Character.isLowSurrogate(text.charAt(index))) index--;
            int line = textLayout.getLineForOffset(index);
            for (int i = 0; i < line; i++) path.addRect(0, textLayout.getLineTop(i),
                    textLayout.getWidth(), textLayout.getLineBottom(i), Path.Direction.CW);
            float edge = horizontalAt(textLayout, characterPosition);
            float top = textLayout.getLineTop(line), bottom = textLayout.getLineBottom(line);
            if (textLayout.getParagraphDirection(line) >= 0) path.addRect(0, top, Math.max(0, edge), bottom, Path.Direction.CW);
            else path.addRect(Math.min(textLayout.getWidth(), edge), top, textLayout.getWidth(), bottom, Path.Direction.CW);
            return path;
        }

        private float horizontalAt(StaticLayout textLayout, float characterPosition) {
            float bounded = Math.max(0f, Math.min(text.length(), characterPosition));
            int first = (int) bounded;
            if (first >= text.length()) return textLayout.getPrimaryHorizontal(text.length());
            if (first > 0 && Character.isLowSurrogate(text.charAt(first)) && Character.isHighSurrogate(text.charAt(first - 1))) first--;
            int end = first + Character.charCount(Character.codePointAt(text, first));
            float fraction = (bounded - first) / (end - first);
            float startX = textLayout.getPrimaryHorizontal(first), endX = textLayout.getPrimaryHorizontal(end);
            if (textLayout.getLineForOffset(first) != textLayout.getLineForOffset(end)) {
                int line = textLayout.getLineForOffset(first);
                endX = textLayout.getParagraphDirection(line) >= 0 ? textLayout.getLineRight(line) : textLayout.getLineLeft(line);
            }
            return startX + (endX - startX) * fraction;
        }
    }
}
