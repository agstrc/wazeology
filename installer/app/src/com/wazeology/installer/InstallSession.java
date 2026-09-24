package com.wazeology.installer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Build;

import com.wazeology.core.CancelToken;
import com.wazeology.core.Pins;
import com.wazeology.installer.core.JobState;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * PackageInstaller plumbing. The session id and its phase are persisted before each step, so after a
 * process death the startup sweep can tell a session still waiting on the rider's confirmation (kept)
 * from one left half-written (abandoned; otherwise its ~190 MB copy lingers). Results go to the
 * manifest-declared {@link InstallResultReceiver}, which wakes the process if it died meanwhile.
 */
final class InstallSession {

    static final String ACTION_INSTALL = "com.wazeology.installer.INSTALL_RESULT";
    static final String ACTION_UNINSTALL = "com.wazeology.installer.UNINSTALL_RESULT";

    /** PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED (API 31). */
    private static final int USER_ACTION_NOT_REQUIRED = 2;

    private InstallSession() {}

    /** Write the build into a new session and commit it. Android answers through the receiver. */
    static void install(JobStore store, List<File> apks, JobState.Listener l, CancelToken cancel)
            throws IOException {
        PackageInstaller pi = store.app.getPackageManager().getPackageInstaller();
        abandonOwnSessions(store, pi, -1);
        long total = 0;
        for (File f : apks) {
            total += f.length();
        }
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(Pins.WAZE_PACKAGE);
        params.setSize(total); // lets Android free cache up front, or fail early on storage
        if (Build.VERSION.SDK_INT >= 31) {
            // Reinstalling over our own earlier install then needs no extra tap.
            params.setRequireUserAction(USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                // The Play Store must then ask the rider before replacing this Waze with its own.
                params.setRequestUpdateOwnership(true);
            } catch (RuntimeException e) {
                l.log("update ownership unavailable: " + e);
            }
        }
        int id = pi.createSession(params);
        store.prefs.edit().putInt(JobStore.K_SESSION, id)
                .putString(JobStore.K_SESSION_PHASE, JobStore.PHASE_WRITING).commit();
        boolean committed = false;
        try (PackageInstaller.Session session = pi.openSession(id)) {
            l.onPhase(JobState.Phase.WRITE);
            long done = 0;
            byte[] buf = new byte[1 << 16];
            for (File apk : apks) {
                l.log("writing " + apk.getName() + " (" + apk.length() + " bytes)");
                try (InputStream in = new FileInputStream(apk);
                        OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        cancel.check();
                        out.write(buf, 0, r);
                        done += r;
                        l.onProgress(done, total);
                    }
                    session.fsync(out);
                }
            }
            cancel.check();
            // The grace period after a restart counts from here: writing can take minutes.
            store.prefs.edit().putString(JobStore.K_SESSION_PHASE, JobStore.PHASE_COMMITTED)
                    .putLong(JobStore.K_SESSION_AT, System.currentTimeMillis()).commit();
            // Set before commit: a fast result may reach the main thread before commit returns.
            store.committedHere = true;
            session.commit(sender(store.app, ACTION_INSTALL));
            committed = true;
            l.log("handed to Android (session " + id + ")");
        } finally {
            if (!committed) {
                store.committedHere = false;
                try {
                    pi.abandonSession(id);
                } catch (RuntimeException ignored) {
                    // already gone
                }
                store.prefs.edit().remove(JobStore.K_SESSION).remove(JobStore.K_SESSION_PHASE)
                        .remove(JobStore.K_SESSION_AT).commit();
            }
        }
    }

    /** Ask Android to remove the installed Waze; the confirmation comes back through the receiver. */
    static void uninstall(Context c) {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        pi.uninstall(new VersionedPackage(Pins.WAZE_PACKAGE, PackageManager.VERSION_CODE_HIGHEST),
                sender(c, ACTION_UNINSTALL));
    }

    /**
     * Startup reconciliation: abandon every session of ours except a committed one Android still
     * holds (it waits on the rider). A committed session Android no longer knows means its result
     * was delivered or lost; the next screen probe shows the true state either way.
     */
    static void recover(JobStore store) {
        PackageInstaller pi = store.app.getPackageManager().getPackageInstaller();
        int id = store.prefs.getInt(JobStore.K_SESSION, -1);
        boolean committed = JobStore.PHASE_COMMITTED.equals(
                store.prefs.getString(JobStore.K_SESSION_PHASE, null));
        boolean alive = id >= 0 && pi.getSessionInfo(id) != null;
        if (committed && !alive) {
            store.log("install session " + id + " finished while the app was closed");
            store.prefs.edit().putString(JobStore.K_SESSION_PHASE, JobStore.PHASE_DONE).commit();
        } else if (!committed && id >= 0 && !JobStore.PHASE_DONE.equals(
                store.prefs.getString(JobStore.K_SESSION_PHASE, null))) {
            store.log("clearing unfinished install session " + id);
            store.prefs.edit().remove(JobStore.K_SESSION).remove(JobStore.K_SESSION_PHASE)
                    .remove(JobStore.K_SESSION_AT).commit();
        }
        abandonOwnSessions(store, pi, committed && alive ? id : -1);
    }

    /** Drop a committed session the rider never confirmed, so a fresh install can start. */
    static void abandonCommitted(JobStore store) {
        PackageInstaller pi = store.app.getPackageManager().getPackageInstaller();
        abandonOwnSessions(store, pi, -1);
        store.prefs.edit().remove(JobStore.K_SESSION).remove(JobStore.K_SESSION_PHASE)
                .remove(JobStore.K_SESSION_AT).commit();
        store.clearConfirm();
        store.committedHere = false;
    }

    private static void abandonOwnSessions(JobStore store, PackageInstaller pi, int keep) {
        for (PackageInstaller.SessionInfo info : pi.getMySessions()) {
            if (info.getSessionId() == keep) {
                continue;
            }
            try {
                pi.abandonSession(info.getSessionId());
                store.log("abandoned leftover install session " + info.getSessionId());
            } catch (RuntimeException ignored) {
                // it finished in the meantime
            }
        }
    }

    /** Explicit component, so the status broadcast reaches the manifest receiver (and may be mutable:
     *  the installer fills in the status extras). */
    private static IntentSender sender(Context c, String action) {
        Intent i = new Intent(c, InstallResultReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        int request = ACTION_INSTALL.equals(action) ? 10 : 11;
        return PendingIntent.getBroadcast(c, request, i, flags).getIntentSender();
    }
}
