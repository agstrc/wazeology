package com.wazeology.installer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.wazeology.core.CancelToken;
import com.wazeology.installer.core.NetworkGate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** {@link NetworkGate} over ConnectivityManager: a dropped connection parks the download until the
 *  default network comes back, instead of burning retries. */
final class AndroidNetworkGate implements NetworkGate {

    private final ConnectivityManager cm;

    AndroidNetworkGate(Context c) {
        cm = c.getSystemService(ConnectivityManager.class);
    }

    @Override
    public boolean isOnline() {
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    boolean isMetered() {
        return cm.isActiveNetworkMetered();
    }

    @Override
    public void awaitOnline(long maxMs, CancelToken cancel) {
        final CountDownLatch up = new CountDownLatch(1);
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                up.countDown();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(cb);
        } catch (RuntimeException e) {
            sleep(Math.min(maxMs, 10000), cancel);
            return;
        }
        try {
            long end = System.currentTimeMillis() + maxMs;
            while (!isOnline() && System.currentTimeMillis() < end) {
                cancel.check();
                if (up.await(500, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    @Override
    public void sleep(long ms, CancelToken cancel) {
        long end = System.currentTimeMillis() + ms;
        try {
            while (System.currentTimeMillis() < end) {
                cancel.check();
                Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
