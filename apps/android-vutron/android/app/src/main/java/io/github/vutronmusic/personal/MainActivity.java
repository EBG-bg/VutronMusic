package io.github.vutronmusic.personal;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 7001;

    @Override protected void onCreate(android.os.Bundle state) {
        registerPlugin(VutronMusicPlugin.class);
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        android.content.SharedPreferences preferences = getSharedPreferences("lyric_overlay", MODE_PRIVATE);
        boolean pending = preferences.getBoolean("pending_enable", false);
        if (!pending && !LyricOverlaySettings.enabled(this)) return;
        preferences.edit().putBoolean("pending_enable", false).apply();
        LyricOverlaySettings.enabled(this, true);
        androidx.core.content.ContextCompat.startForegroundService(this,
                new android.content.Intent(this, AndroidLyricOverlayService.class)
                        .setAction(AndroidLyricOverlayService.ACTION_SHOW));
    }
}
