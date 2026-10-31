package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;

/** Music network preference shared by the UI bridge and background services. */
final class LocalOnlyMode {
    private static final String PREFERENCES = "vutron_music_network";
    private static final String KEY = "local_only";
    private static final String MANUAL_KEY = "manual_local_only";

    private LocalOnlyMode() { }

    static boolean enabled(Context context) { return enabled(preferences(context)); }

    static boolean manual(Context context) { return manual(preferences(context)); }
    static boolean manual(SharedPreferences preferences) { return preferences.getBoolean(MANUAL_KEY, preferences.getBoolean(KEY, false)); }

    static boolean enabled(SharedPreferences preferences) { return preferences.getBoolean(KEY, false); }

    static void save(SharedPreferences preferences, boolean enabled) throws IOException {
        if (!preferences.edit().putBoolean(KEY, enabled).commit()) throw new IOException("无法保存本地模式设置");
    }
    static void save(SharedPreferences preferences, boolean enabled, boolean manual) throws IOException {
        if (!preferences.edit().putBoolean(KEY, enabled).putBoolean(MANUAL_KEY, manual).commit()) throw new IOException("无法保存离线模式设置");
    }

    static void setEnabled(Context context, boolean enabled) throws IOException {
        setEnabled(context, enabled, enabled);
    }

    static synchronized void setEnabled(Context context, boolean enabled, boolean manual) throws IOException {
        SharedPreferences preferences = preferences(context);
        boolean previous = enabled(preferences);
        if (previous == enabled && preferences.getBoolean(MANUAL_KEY, !manual) == manual) return;
        save(preferences, enabled, manual);
        AndroidPlaybackService.invalidatePrefetchForPolicyChange();
        if (enabled && !previous) {
            AudioCache.cancelNetworkDownloads();
            ArtworkCache.cancelNetworkDownloads();
            // A temporary outage must not release a buffered player or its cursor.
            // An explicit offline choice still stops remote playback immediately.
            if (manual) AndroidPlaybackService.enforceLocalOnly();
        }
    }

    static synchronized void updateConnectivity(Context context, boolean connected) throws IOException {
        boolean manual = manual(context);
        boolean enabled = effective(manual, connected, !SessionStore.load(context).isEmpty());
        setEnabled(context, enabled, manual);
    }

    static boolean effective(boolean manual, boolean connected, boolean sessionSaved) { return manual || !connected || !sessionSaved; }

    static void requireOnline(Context context) throws IOException {
        if (enabled(context)) throw new IOException("当前为本地模式，仅可使用本地文件和已完成缓存");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
