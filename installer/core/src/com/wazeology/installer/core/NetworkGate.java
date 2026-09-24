package com.wazeology.installer.core;

import com.wazeology.core.CancelToken;

/**
 * Connectivity and waiting, abstracted so the download retry loop runs unchanged on the phone
 * (ConnectivityManager callbacks) and in the host gate (always online, no real sleeping).
 */
public interface NetworkGate {

    boolean isOnline();

    /** Block until a network is available, maxMs passes, or the job is cancelled. */
    void awaitOnline(long maxMs, CancelToken cancel);

    /** Sleep ms, waking early (and throwing) when the job is cancelled. */
    void sleep(long ms, CancelToken cancel);
}
