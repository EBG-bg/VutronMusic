package io.github.vutronmusic.personal;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

/** Observes Android's validated default network; never probes a remote endpoint. */
final class MusicConnectivity implements AutoCloseable {
    private final ConnectivityManager manager;
    private final ConnectivityManager.NetworkCallback callback;
    MusicConnectivity(Context context, Runnable changed) {
        manager = context.getSystemService(ConnectivityManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { changed.run(); }
            @Override public void onLost(Network network) { changed.run(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { changed.run(); }
        };
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback);
            else manager.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback);
        }
    }
    static boolean available(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && usable(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    }
    static boolean usable(boolean internet, boolean validated) { return internet && validated; }
    static boolean wifi(Context context) {
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps=manager==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && usable(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    }
    @Override public void close() { if (manager != null) manager.unregisterNetworkCallback(callback); }
}
