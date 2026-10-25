package io.github.vutronmusic.personal;

import android.content.Context;

final class SourceSettings {
    private SourceSettings() { }

    static boolean autoMatch(Context context) {
        return context.getSharedPreferences("audio_sources", Context.MODE_PRIVATE).getBoolean("auto_match", true);
    }

    static void autoMatch(Context context, boolean enabled) {
        context.getSharedPreferences("audio_sources", Context.MODE_PRIVATE).edit().putBoolean("auto_match", enabled).apply();
    }
}
