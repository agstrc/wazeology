package com.wazeology.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import com.wazeology.core.Outcome;
import com.wazeology.installer.core.InstallStatus;

/**
 * Receives PackageInstaller results for installs and uninstalls. Declared in the manifest (not
 * registered by the activity) so a result that arrives after the process died, or while the rider
 * is elsewhere, is still recorded and shown the next time the installer opens.
 */
public class InstallResultReceiver extends BroadcastReceiver {

    /** PackageManager.EXTRA_LEGACY_STATUS, hidden from android.jar since API 26. */
    private static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        JobStore store = JobStore.get(context);
        boolean uninstall = InstallSession.ACTION_UNINSTALL.equals(intent.getAction());
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        int legacy = intent.getIntExtra(EXTRA_LEGACY_STATUS, 0);
        String detail = (uninstall ? "uninstall" : "install") + " status " + status + ", legacy "
                + legacy + (message != null ? ", " + message : "");
        store.log(detail);

        // An abandoned earlier session still reports (as aborted); only the session this installer is
        // tracking may change the screen, or a stale cancel would wipe a live install's state.
        if (!uninstall) {
            int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            int tracked = store.prefs.getInt(JobStore.K_SESSION, -1);
            if (id != tracked) {
                store.log("ignoring the result of old session " + id + " (tracking " + tracked + ")");
                return;
            }
        }

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                store.onConfirmNeeded(confirm, uninstall);
                return;
            }
            if (uninstall) {
                store.onUninstallResult(Outcome.UNINSTALL_FAILED, detail);
            } else {
                InstallSession.abandonCommitted(store);
                store.onInstallResult(Outcome.NO_CONFIRM, detail);
            }
            return;
        }
        if (uninstall) {
            store.onUninstallResult(InstallStatus.classifyUninstall(status), detail);
        } else {
            store.onInstallResult(InstallStatus.classify(status, legacy, message), detail);
        }
    }
}
