package io.github.vutronmusic.personal;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;

/** Music network preference shared by the UI bridge and background services. */
final class LocalOnlyMode {
    private static final String PREFERENCES = "vutron_music_network";
    private static final String KEY = "local_only";

    private LocalOnlyMode() { }

    static boolean enabled(Context context) { return enabled(preferences(context)); }

    static boolean enabled(SharedPreferences preferences) { return preferences.getBoolean(KEY, false); }

    static void save(SharedPreferences preferences, boolean enabled) throws IOException {
        if (!preferences.edit().putBoolean(KEY, enabled).commit()) throw new IOException("无法保存本地模式设置");
    }

    static void setEnabled(Context context, boolean enabled) throws IOException {
        save(preferences(context), enabled);
        if (enabled) {
            AudioCache.cancelNetworkDownloads();
            ArtworkCache.cancelNetworkDownloads();
            AndroidPlaybackService.enforceLocalOnly();
        }
    }

    static void requireOnline(Context context) throws IOException {
        if (enabled(context)) throw new IOException("当前为本地模式，仅可使用本地文件和已完成缓存");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
