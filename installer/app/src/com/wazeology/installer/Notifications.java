package com.wazeology.installer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import com.wazeology.core.Outcome;
import com.wazeology.installer.core.JobState;

/**
 * The installer's notifications: the foreground progress one (with Cancel), a "tap to confirm" one
 * when Android needs the rider's OK while the app is in the background, and a result one when a job
 * ends off-screen. Small icons are framework drawables: an adaptive mipmap as the status-bar icon
 * crashes SystemUI on Android 8.
 */
final class Notifications {

    static final String CH_PROGRESS = "progress";
    static final String CH_ALERTS = "alerts";
    static final int ID_PROGRESS = 1;
    static final int ID_CONFIRM = 2;
    static final int ID_RESULT = 3;

    private Notifications() {}

    static void createChannels(Context c, Texts tx) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel progress = new NotificationChannel(CH_PROGRESS, tx.channelProgress(),
                NotificationManager.IMPORTANCE_LOW);
        progress.setShowBadge(false);
        nm.createNotificationChannel(progress);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERTS, tx.channelAlerts(),
                NotificationManager.IMPORTANCE_HIGH));
    }

    /** Brings the installer's task to the front as it is (never clearing the confirmation above it). */
    static PendingIntent openApp(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(c, InstallerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The notification startForeground needs before the job has a phase. */
    static Notification starting(Context c, Texts tx, JobState.Kind kind) {
        return new Notification.Builder(c, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(tx.notifTitle(kind))
                .setContentIntent(openApp(c))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build();
    }

    static Notification progress(Context c, Texts tx, JobState.Snapshot s) {
        Notification.Builder b = new Notification.Builder(c, CH_PROGRESS)
                .setSmallIcon(s.phase == JobState.Phase.DOWNLOAD
                        ? android.R.drawable.stat_sys_download : android.R.drawable.stat_notify_sync)
                .setContentTitle(tx.notifTitle(s.kind))
                .setContentIntent(openApp(c))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (s.running()) {
            String detail = tx.phaseDetail(s);
            b.setContentText(tx.phaseTitle(s.kind, s.phase) + (detail.isEmpty() ? "" : " · " + detail));
            int pct = s.percent();
            b.setProgress(100, Math.max(0, pct), pct < 0);
            Intent cancel = new Intent(c, CancelReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, cancel,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, tx.cancel(), pi).build());
        }
        return b.build();
    }

    static void notifyProgress(Context c, Texts tx, JobState.Snapshot s) {
        notify(c, ID_PROGRESS, progress(c, tx, s));
    }

    static void confirm(Context c, Texts tx, Intent confirm, boolean uninstall) {
        Intent i = new Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH_ALERTS)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(tx.confirmNotifTitle(uninstall))
                .setContentText(tx.confirmNotifBody())
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        notify(c, ID_CONFIRM, n);
    }

    static boolean confirmShowing(Context c) {
        try {
            for (StatusBarNotification n : c.getSystemService(NotificationManager.class)
                    .getActiveNotifications()) {
                if (n.getId() == ID_CONFIRM) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // treat as not showing
        }
        return false;
    }

    static void cancelConfirm(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID_CONFIRM);
    }

    static void result(Context c, Texts tx, Outcome p) {
        String title = tx.title(p);
        String body = p == Outcome.PREPARED || p == Outcome.INSTALLED ? tx.tapToOpen() : tx.body(p, 0);
        Notification n = new Notification.Builder(c, CH_ALERTS)
                .setSmallIcon(p.success ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .build();
        notify(c, ID_RESULT, n);
    }

    static void cancelResult(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID_RESULT);
    }

    private static void notify(Context c, int id, Notification n) {
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (RuntimeException ignored) {
            // notifications denied: the screen shows the same state when the rider comes back
        }
    }
}
