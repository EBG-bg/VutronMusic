package io.github.vutronmusic.personal;

import android.content.Context;
import org.json.JSONObject;

/** Global settings; URL prefetch is explicitly opt-in and restricted to validated Wi-Fi. */
final class PlaybackTuning {
    static final String PREFS = "playback_tuning";
    private PlaybackTuning() { }
    static float speed(Context context) { return context.getSharedPreferences(PREFS,0).getFloat("speed",1f); }
    static float pitch(Context context) { return context.getSharedPreferences(PREFS,0).getFloat("pitch",1f); }
    static boolean prefetch(Context context) { return context.getSharedPreferences(PREFS,0).getBoolean("prefetchWifi",false); }
    static float checked(double value) {
        if (!Double.isFinite(value) || value < .5 || value > 2) throw new IllegalArgumentException("倍率须在 0.5–2.0 之间");
        return Math.round(value*100)/100f;
    }
    static void update(Context context, JSONObject values) {
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences(PREFS,0).edit();
        for (String key : new String[]{"speed","pitch"}) if (values.has(key)) edit.putFloat(key,checked(values.optDouble(key,Double.NaN)));
        if (values.has("prefetchWifi")) {
            if (!(values.opt("prefetchWifi") instanceof Boolean)) throw new IllegalArgumentException("预取设置无效");
            edit.putBoolean("prefetchWifi",values.optBoolean("prefetchWifi"));
        }
        if (!edit.commit()) throw new IllegalStateException("播放设置保存失败");
    }
    static JSONObject read(Context context) throws org.json.JSONException {
        android.content.SharedPreferences state=context.getSharedPreferences("vutron_playback",0);
        return new JSONObject().put("speed",speed(context)).put("pitch",pitch(context)).put("prefetchWifi",prefetch(context))
                .put("actualSpeed",state.getFloat("playbackRate",1f)).put("actualPitch",state.getFloat("playbackPitch",1f))
                .put("warning",state.getString("tuningWarning",""));
    }
}
