package com.wazeology.installer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.wazeology.core.BuildPipeline;
import com.wazeology.core.Io;
import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;
import com.wazeology.installer.core.BuildCache;
import com.wazeology.installer.core.JobState;
import com.wazeology.installer.core.SourceCache;
import com.wazeology.installer.core.UiModel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The process-wide source of truth the screen, the service and the receivers share. It holds only
 * what cannot be re-read: the running job ({@link JobState}), the install confirmation Android handed
 * over, and which activity is on screen. Everything durable is on disk (the caches) or in the "state"
 * preferences (outcome banner, committed install session, pending Settings round trip), so a process
 * death loses nothing the rider cares about.
 *
 * Listeners always run on the main thread, throttled to one update per 250 ms while bytes flow; a
 * phase change or the end of a job is delivered at once.
 */
final class JobStore {

    interface Listener {
        void onChanged();
    }

    private static JobStore instance;

    static synchronized JobStore get(Context context) {
        if (instance == null) {
            instance = new JobStore(context.getApplicationContext());
        }
        return instance;
    }

    // prefs keys
    static final String K_OUTCOME = "outcome";
    static final String K_OUTCOME_DETAIL = "outcome.detail";
    static final String K_OUTCOME_BYTES = "outcome.bytes";
    static final String K_INFLIGHT = "job.inflight";
    static final String K_SESSION = "install.session";
    static final String K_SESSION_PHASE = "install.phase";
    static final String K_SESSION_AT = "install.at";
    static final String K_CONTINUE = "continue.after";
    static final String K_CONTINUE_AT = "continue.at";
    static final String K_ASKED_NOTIFICATIONS = "asked.notifications";
    static final String PHASE_WRITING = "writing";
    static final long INSTALL_GRACE_MS = 5 * 60 * 1000L;
    /** How long after returning from Android's confirmation, with no result, before it is offered
     *  again. An approved install of ~190 MB can take well over a minute, and a cancel reports at once. */
    static final long CONFIRM_LEFT_MS = 2 * 60 * 1000L;
    static final String PHASE_COMMITTED = "committed";
    /** Android finished with the session before this process saw its result; the id is kept so the
     *  result (which may be what woke this process) is still matched. */
    static final String PHASE_DONE = "done";

    final Context app;
    final JobState job = new JobState();
    final File root;
    final File work;
    final SourceCache sources;
    final BuildCache builds;
    final SharedPreferences prefs;
    final Handler main = new Handler(Looper.getMainLooper());

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile String assetsDigest;
    private volatile byte[] ourCert;
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile long lastDispatch;
    private volatile long lastSeq = -1;

    // disk facts, refreshed when a job starts, changes phase or ends (never per progress tick)
    private volatile Disk disk = new Disk();
    private long diskSeq = -1;

    // main thread only
    int visible;
    private WeakReference<Activity> resumed = new WeakReference<Activity>(null);
    private Intent confirmIntent;
    private boolean confirmIsUninstall;
    /** The held confirmation was shown over the installer (not only posted as a notification). */
    private boolean confirmLaunched;
    boolean uninstallPending;
    /** This process committed the tracked session, so Android is working on it. */
    volatile boolean committedHere;

    private final ArrayDeque<String> logLines = new ArrayDeque<String>();
    private final File logFile;

    static final class Disk {
        SourceCache.Source source;
        SourceCache.Partial partial;
        List<File> build;
        long bytes;
    }

    private JobStore(Context app) {
        this.app = app;
        this.root = app.getNoBackupFilesDir();
        this.work = new File(root, "work");
        this.sources = new SourceCache(root);
        this.builds = new BuildCache(root);
        this.prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE);
        this.logFile = new File(app.getFilesDir(), "details.log");
        loadLog();
        Notifications.createChannels(app, new Texts(app));

        // A job marker left behind means the process died mid-job (a new process never has one).
        String inflight = prefs.getString(K_INFLIGHT, null);
        if (inflight != null) {
            prefs.edit().remove(K_INFLIGHT).apply();
            if (JobState.Kind.PREPARE.name().equals(inflight) || JobState.Kind.PICK.name().equals(inflight)) {
                setOutcome(Outcome.INTERRUPTED, "process ended during " + inflight, 0);
            }
            log("previous " + inflight + " job was interrupted");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                init();
            }
        }, "wazeology-init").start();
    }

    /** Startup sweep: drop leftovers of crashed jobs and old versions, hash the baked assets, and
     *  reconcile install sessions. Jobs wait for this before touching the caches. */
    private void init() {
        try {
            Io.deleteTree(new File(app.getCacheDir(), "wazeology")); // the old layout
            Io.deleteTree(work);
            builds.cleanTemp();
            sources.evictOthers();
            try {
                ourCert = loadCert(); // first: the screen needs it to tell our Waze from another
                changedNow();
                assetsDigest = BuildPipeline.assetsDigest(assets());
            } catch (Exception e) {
                log("cannot read the installer's assets: " + e);
            }
            SourceCache.Source s = sources.current();
            builds.evictExcept(s != null && assetsDigest != null
                    ? BuildCache.key(s.id, assetsDigest) : null);
            InstallSession.recover(this);
        } catch (RuntimeException e) {
            log("startup sweep failed: " + e);
        } finally {
            ready.countDown();
            refreshDisk();
            changedNow();
        }
    }

    void awaitReady() {
        try {
            ready.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    BuildPipeline.Assets assets() {
        return new BuildPipeline.Assets() {
            @Override
            public byte[] read(String name) throws IOException {
                try (InputStream in = app.getAssets().open(name)) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[1 << 16];
                    int r;
                    while ((r = in.read(b)) > 0) {
                        buf.write(b, 0, r);
                    }
                    return buf.toByteArray();
                }
            }
        };
    }

    private byte[] loadCert() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(assets().read(Pins.P12_ASSET)), Pins.P12_PASSWORD);
        String alias = ks.containsAlias(Pins.P12_ALIAS) ? Pins.P12_ALIAS : ks.aliases().nextElement();
        Certificate cert = ks.getCertificate(alias);
        return cert == null ? null : cert.getEncoded();
    }

    String assetsDigest() {
        return assetsDigest;
    }

    byte[] ourCert() {
        return ourCert;
    }

    // --- disk facts ---

    void refreshDisk() {
        Disk d = new Disk();
        d.source = sources.current();
        d.partial = d.source == null ? sources.partial() : null;
        String digest = assetsDigest;
        d.build = d.source != null && digest != null
                ? builds.complete(BuildCache.key(d.source.id, digest)) : null;
        d.bytes = sources.bytesOnDisk() + builds.bytesOnDisk();
        disk = d;
    }

    Disk disk() {
        long seq = job.seq();
        if (seq != diskSeq) {
            diskSeq = seq;
            refreshDisk();
        }
        return disk;
    }

    List<File> currentBuild() {
        refreshDisk();
        return disk.build;
    }

    UiModel.Facts facts(WazeProbe.Result waze) {
        UiModel.Facts f = new UiModel.Facts();
        f.sdkOk = Build.VERSION.SDK_INT >= 32;
        f.abiOk = abiOk();
        f.job = job.snapshot();
        Disk d = disk();
        f.sourceReady = d.source != null;
        if (d.partial != null) {
            f.partialBytes = d.partial.have;
            f.partialTotal = d.partial.size;
        }
        f.buildReady = d.build != null;
        f.waze = waze == null ? UiModel.Waze.NONE : waze.waze;
        f.installCommitted = PHASE_COMMITTED.equals(prefs.getString(K_SESSION_PHASE, null));
        f.confirmAvailable = confirmIntent != null && !confirmIsUninstall && !confirmLaunched;
        // Android is busy with the session: it was committed by this process, its confirmation was
        // shown, or it was committed moments before a restart. Only after that does "start again"
        // make sense, so the rider never abandons an install that is actually running.
        f.installWorking = f.installCommitted && (committedHere || (confirmLaunched && !confirmIsUninstall)
                || System.currentTimeMillis() - prefs.getLong(K_SESSION_AT, 0) < INSTALL_GRACE_MS);
        f.uninstallPending = uninstallPending;
        return f;
    }

    static boolean abiOk() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.startsWith("arm64") || abi.startsWith("armeabi")) {
                return true;
            }
        }
        return false;
    }

    // --- outcome banner ---

    void setOutcome(Outcome p, String detail, long bytes) {
        prefs.edit().putString(K_OUTCOME, p.name()).putString(K_OUTCOME_DETAIL, detail)
                .putLong(K_OUTCOME_BYTES, bytes).apply();
        if (detail != null) {
            log(p.name() + ": " + detail);
        }
        changedNow();
    }

    Outcome outcome() {
        String name = prefs.getString(K_OUTCOME, null);
        if (name == null) {
            return null;
        }
        try {
            return Outcome.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    long outcomeBytes() {
        return prefs.getLong(K_OUTCOME_BYTES, 0);
    }

    void clearOutcome() {
        prefs.edit().remove(K_OUTCOME).remove(K_OUTCOME_DETAIL).remove(K_OUTCOME_BYTES).apply();
        changedNow();
    }

    // --- running job bookkeeping (called by JobService) ---

    void markInflight(JobState.Kind kind) {
        prefs.edit().putString(K_INFLIGHT, kind.name()).commit();
    }

    void clearInflight() {
        prefs.edit().remove(K_INFLIGHT).commit();
    }

    // --- install confirmation (main thread) ---

    void onConfirmNeeded(Intent confirm, boolean uninstall) {
        confirmIntent = confirm;
        confirmIsUninstall = uninstall;
        confirmLaunched = false;
        Activity a = resumed.get();
        if (a != null) {
            launchConfirm(a);
        } else {
            Notifications.confirm(app, new Texts(app), confirm, uninstall);
        }
        changedNow();
    }

    /** Show Android's confirmation over activity a, if one is waiting. */
    boolean launchConfirm(Activity a) {
        Intent c = confirmIntent;
        if (c == null) {
            return false;
        }
        Notifications.cancelConfirm(app);
        try {
            a.startActivity(c);
            confirmLaunched = true;
            return true;
        } catch (RuntimeException e) {
            // Without its confirmation the committed session can never finish: drop it and say so,
            // or the screen would wait on it until the process dies.
            String detail = "cannot open the confirmation: " + e;
            log(detail);
            if (confirmIsUninstall) {
                onUninstallResult(Outcome.UNINSTALL_FAILED, detail);
            } else {
                InstallSession.abandonCommitted(this);
                onInstallResult(Outcome.NO_CONFIRM, detail);
            }
            return false;
        }
    }

    /** On return to the screen: re-show a confirmation whose notification is still unanswered. A
     *  confirmation the rider already opened from the notification stays behind the "Continue
     *  installing" button instead of popping up twice. */
    void onActivityResumed(Activity a) {
        resumed = new WeakReference<Activity>(a);
        if (confirmIntent != null && !confirmLaunched) {
            if (Notifications.confirmShowing(app)) {
                launchConfirm(a);
            } else {
                // The notification is gone: the rider opened the confirmation from it (or dismissed
                // it). Treat it as shown, so the screen waits instead of asking again at once.
                confirmLaunched = true;
            }
        }
    }

    /** The rider may have closed Android's confirmation without answering (Home, recents): offer it
     *  again once the installer is back on screen and no result came. */
    void onConfirmMaybeLeft() {
        if (confirmIntent != null && confirmLaunched) {
            confirmLaunched = false;
            changedNow();
        }
    }

    void onActivityPaused(Activity a) {
        if (resumed.get() == a) {
            resumed = new WeakReference<Activity>(null);
        }
    }

    void clearConfirm() {
        confirmIntent = null;
        confirmLaunched = false;
        Notifications.cancelConfirm(app);
    }

    // --- results from InstallResultReceiver (main thread) ---

    void onInstallResult(Outcome p, String detail) {
        clearConfirm();
        committedHere = false;
        prefs.edit().remove(K_SESSION).remove(K_SESSION_PHASE).remove(K_SESSION_AT).commit();
        setOutcome(p, detail, 0);
        if (visible == 0) {
            Notifications.result(app, new Texts(app), p);
        }
    }

    void onUninstallResult(Outcome p, String detail) {
        clearConfirm();
        uninstallPending = false;
        setOutcome(p, detail, 0);
    }

    // --- listeners ---

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private final Runnable dispatch = new Runnable() {
        @Override
        public void run() {
            pending.set(false);
            lastDispatch = SystemClock.uptimeMillis();
            for (Listener l : listeners) {
                l.onChanged();
            }
        }
    };

    private final Runnable dispatchNow = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(dispatch);
            dispatch.run();
        }
    };

    /** A progress tick (any thread): at most one update per 250 ms, except for a phase change. */
    void changed() {
        long seq = job.seq();
        if (seq != lastSeq) {
            lastSeq = seq;
            changedNow();
            return;
        }
        if (pending.compareAndSet(false, true)) {
            long wait = Math.max(0, 250 - (SystemClock.uptimeMillis() - lastDispatch));
            main.postDelayed(dispatch, wait);
        }
    }

    /** Deliver an update right away (any thread). */
    void changedNow() {
        main.post(dispatchNow);
    }

    // --- details log ---

    void log(String line) {
        String stamped = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + " " + line;
        synchronized (logLines) {
            logLines.addLast(stamped);
            while (logLines.size() > 400) {
                logLines.removeFirst();
            }
            try (FileWriter w = new FileWriter(logFile, true)) {
                w.write(stamped);
                w.write('\n');
            } catch (IOException ignored) {
                // the in-memory copy is enough to show
            }
        }
    }

    String logText() {
        synchronized (logLines) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    private void loadLog() {
        if (!logFile.isFile()) {
            return;
        }
        try {
            if (logFile.length() > 512 * 1024) {
                logFile.delete();
                return;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(logFile));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    logLines.addLast(line);
                    if (logLines.size() > 400) {
                        logLines.removeFirst();
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
            // start with an empty log
        }
    }
}
