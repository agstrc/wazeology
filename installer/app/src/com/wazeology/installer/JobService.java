package com.wazeology.installer;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;

import com.wazeology.core.BuildPipeline;
import com.wazeology.core.CancelToken;
import com.wazeology.core.Io;
import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;
import com.wazeology.installer.core.ApkPure;
import com.wazeology.installer.core.JobState;
import com.wazeology.installer.core.NetworkGate;
import com.wazeology.installer.core.Preparer;
import com.wazeology.installer.core.SourceCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs every long job (prepare, pick, install write, export, clear) on one worker thread inside a
 * foreground service, so turning the screen off, switching apps or swiping the installer away does not
 * stop a 180 MB download halfway. The activity only observes {@link JobStore}; it never binds.
 *
 * startForeground is the first thing onStartCommand does (Android kills the app when a foreground
 * service start is not followed by it within seconds), and only then is the job gate consulted.
 */
public class JobService extends Service {

    static final String EXTRA_KIND = "kind";
    private static final long WAKE_MS = 60 * 60 * 1000L;
    static final String EXTRA_URIS = "uris";
    static final String EXTRA_TREE = "tree";
    /** PREPARE only: "rebuild" drops the finished build first, "redownload" the download too. */
    static final String EXTRA_RESET = "reset";

    private JobStore store;
    private ExecutorService worker;
    private PowerManager.WakeLock wakeLock;
    private int lastStartId;
    private long lastNotify;
    private long lastNotifySeq = -1;
    private final JobStore.Listener notifier = new JobStore.Listener() {
        @Override
        public void onChanged() {
            JobState.Snapshot s = store.job.snapshot();
            long now = SystemClock.uptimeMillis();
            // Byte ticks at most once a second (Android drops faster updates); a new phase or a
            // cancel always shows at once.
            if (s.running() && (s.seq != lastNotifySeq || now - lastNotify >= 1000)) {
                lastNotify = now;
                lastNotifySeq = s.seq;
                Notifications.notifyProgress(JobService.this, new Texts(JobService.this), s);
            }
        }
    };

    static void start(Context c, JobState.Kind kind, ArrayList<Uri> uris, Uri tree) {
        Intent i = new Intent(c, JobService.class).putExtra(EXTRA_KIND, kind.name());
        if (uris != null) {
            i.putParcelableArrayListExtra(EXTRA_URIS, uris);
        }
        if (tree != null) {
            i.putExtra(EXTRA_TREE, tree);
        }
        c.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = JobStore.get(this);
        worker = Executors.newSingleThreadExecutor();
        store.addListener(notifier);
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wazeology:job");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        JobState.Kind kind = null;
        try {
            kind = intent == null ? null : JobState.Kind.valueOf(intent.getStringExtra(EXTRA_KIND));
        } catch (RuntimeException ignored) {
            // unknown command
        }
        JobState.Snapshot now = store.job.snapshot();
        android.app.Notification n = Notifications.starting(this, new Texts(this),
                now.running() ? now.kind : kind);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notifications.ID_PROGRESS, n);
        }
        final CancelToken token = new CancelToken();
        if (kind == null || !store.job.tryBegin(kind, token)) {
            releaseUris(intent); // the activity took persistable grants for a job that never runs
            stopIfIdle();
            return START_NOT_STICKY;
        }
        final JobState.Kind k = kind;
        final Intent cmd = intent;
        store.markInflight(k);
        Notifications.cancelResult(this);
        store.clearOutcome();
        wakeLock.acquire(WAKE_MS);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runJob(k, cmd, token);
            }
        });
        return START_NOT_STICKY;
    }

    private void runJob(JobState.Kind kind, Intent cmd, CancelToken token) {
        Outcome result = null;
        String detail = null;
        long bytes = 0;
        try {
            store.awaitReady();
            store.log("--- " + kind.name().toLowerCase() + " ---");
            switch (kind) {
                case PREPARE:
                    String reset = cmd.getStringExtra(EXTRA_RESET);
                    if ("redownload".equals(reset)) {
                        store.sources.clear();
                    }
                    if (reset != null) {
                        store.builds.clear();
                        store.log("starting over (" + reset + ")");
                    }
                    prepare(token);
                    result = Outcome.PREPARED;
                    break;
                case PICK:
                    preparePicked(cmd.<Uri>getParcelableArrayListExtra(EXTRA_URIS), token);
                    result = Outcome.PREPARED;
                    break;
                case INSTALL:
                    install(token);
                    break;
                case EXPORT:
                    export(cmd.<Uri>getParcelableExtra(EXTRA_TREE), token);
                    result = Outcome.EXPORTED;
                    break;
                case CLEAR:
                    store.sources.clear();
                    store.builds.clear();
                    Io.deleteTree(store.work);
                    store.log("deleted the downloaded files");
                    break;
                default:
                    break;
            }
        } catch (CancelToken.Cancelled e) {
            store.log(kind.name().toLowerCase() + " cancelled");
        } catch (Throwable t) {
            result = kind == JobState.Kind.EXPORT && !(t instanceof Outcome.Failure)
                    ? Outcome.EXPORT_FAILED : Outcome.of(t);
            detail = String.valueOf(t);
            if (result == Outcome.STORAGE) {
                bytes = 400L << 20;
            }
        } finally {
            if (kind != JobState.Kind.INSTALL) {
                Io.deleteTree(store.work);
            }
            store.refreshDisk();
            store.clearInflight();
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            final Outcome r = result;
            final String d = detail;
            final long b = bytes;
            // The job ends on the main thread together with its outcome, so a job started right
            // after (tryBegin also runs there) never sees this one's late outcome or wake-lock release.
            store.main.post(new Runnable() {
                @Override
                public void run() {
                    store.job.end();
                    finish(r, d, b);
                }
            });
        }
    }

    private void finish(Outcome result, String detail, long bytes) {
        if (result != null && result != Outcome.PREPARED) {
            store.setOutcome(result, detail, bytes);
        }
        if (result != null && store.visible == 0) {
            Notifications.result(this, new Texts(this), result);
        }
        store.changedNow();
        stopIfIdle();
    }

    private void stopIfIdle() {
        if (!store.job.snapshot().running()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(lastStartId);
        }
    }

    @Override
    public void onDestroy() {
        store.removeListener(notifier);
        store.job.cancel();
        worker.shutdown();
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    /** Android 15 caps dataSync services at 6 hours; far beyond any job, but stop cleanly anyway.
     *  No @Override: this compiles against android-34, where the callback does not exist yet. */
    public void onTimeout(int startId, int fgsType) {
        store.job.cancel();
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- jobs ---

    private JobState.Listener listener() {
        return new JobState.Listener() {
            @Override
            public void onPhase(JobState.Phase phase) {
                store.job.phase(phase);
                store.changedNow();
                wakeLock.acquire(WAKE_MS); // renewed per phase: a slow download may outlast one hour
            }

            @Override
            public void onProgress(long done, long total) {
                store.job.progress(done, total);
                store.changed();
            }

            @Override
            public void log(String line) {
                store.log(line);
            }
        };
    }

    private Preparer.Env env() {
        final AndroidNetworkGate net = new AndroidNetworkGate(this);
        return new Preparer.Env() {
            @Override
            public BuildPipeline.Assets assets() {
                return store.assets();
            }

            @Override
            public String[] abis() {
                return Build.SUPPORTED_ABIS;
            }

            @Override
            public NetworkGate network() {
                return net;
            }

            @Override
            public List<ApkPure.Candidate> lookup() throws IOException {
                return ApkPure.find(Pins.WAZE_PACKAGE, Pins.WAZE_VERSION_CODE, Build.SUPPORTED_ABIS);
            }
        };
    }

    private String digest() throws IOException {
        String d = store.assetsDigest();
        if (d == null) {
            throw new Outcome.Failure(Outcome.INTERNAL, "the installer's own files are unreadable");
        }
        return d;
    }

    private void prepare(CancelToken token) throws IOException {
        Preparer.prepare(env(), store.sources, store.builds, store.work, digest(), listener(), token);
    }

    /** Copy the picked files in, check them, then build. A bad pick leaves the cache untouched. */
    private void preparePicked(List<Uri> uris, CancelToken token) throws IOException {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        JobState.Listener l = listener();
        File picked = new File(store.work, "picked");
        Io.deleteTree(picked);
        picked.mkdirs();
        l.onPhase(JobState.Phase.COPY);
        long total = 0;
        for (Uri u : uris) {
            long s = querySize(u);
            total = s > 0 && total >= 0 ? total + s : -1;
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        List<File> files = new ArrayList<File>();
        long done = 0;
        byte[] buf = new byte[1 << 16];
        try {
            for (Uri u : uris) {
                String name = queryName(u);
                if (name == null) {
                    name = "picked-" + files.size() + ".apk";
                }
                File dst = new File(picked, new File(name).getName());
                if (dst.exists()) {
                    // Two picks with one name (e.g. from two folders) would overwrite each other.
                    throw new Outcome.Failure(Outcome.INCOMPLETE_FILES, "two picked files are named "
                            + dst.getName());
                }
                InputStream opened;
                try {
                    opened = getContentResolver().openInputStream(u);
                } catch (SecurityException e) {
                    throw new Outcome.Failure(Outcome.INCOMPLETE_FILES, "no access to " + u, e);
                }
                try (InputStream in = opened;
                        OutputStream out = new FileOutputStream(dst)) {
                    if (in == null) {
                        throw new Outcome.Failure(Outcome.INCOMPLETE_FILES, "cannot open " + u);
                    }
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        token.check();
                        out.write(buf, 0, r);
                        md.update(buf, 0, r);
                        done += r;
                        l.onProgress(done, total);
                    }
                }
                files.add(dst);
                l.log("picked " + dst.getName() + " (" + dst.length() + " bytes)");
            }
        } finally {
            for (Uri u : uris) {
                releaseUri(u);
            }
        }
        String id = "picked-" + bytesHex(md.digest());
        SourceCache.Source source = Preparer.acceptPicked(env(), files, id, store.sources, store.work,
                l, token);
        Preparer.build(env(), source, store.builds, store.work, digest(), l, token);
    }

    private void install(CancelToken token) throws IOException {
        List<File> apks = store.currentBuild();
        if (apks == null) {
            throw new Outcome.Failure(Outcome.DAMAGED_BUILD, "no complete build to install");
        }
        InstallSession.install(store, apks, listener(), token);
    }

    private void export(Uri tree, CancelToken token) throws IOException {
        List<File> apks = store.currentBuild();
        if (apks == null || tree == null) {
            throw new Outcome.Failure(Outcome.EXPORT_FAILED, "nothing to export");
        }
        JobState.Listener l = listener();
        l.onPhase(JobState.Phase.COPY);
        Texts tx = new Texts(this);
        Uri dir = null;
        boolean ok = false;
        try {
            Uri treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            dir = DocumentsContract.createDocument(getContentResolver(), treeDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, tx.exportFolder());
            if (dir == null) {
                throw new IOException("cannot create the folder");
            }
            long total = 0;
            for (File f : apks) {
                total += f.length();
            }
            long done = 0;
            byte[] buf = new byte[1 << 16];
            for (File apk : apks) {
                Uri doc = DocumentsContract.createDocument(getContentResolver(), dir,
                        "application/vnd.android.package-archive", apk.getName());
                if (doc == null) {
                    throw new IOException("cannot create " + apk.getName());
                }
                try (InputStream in = new FileInputStream(apk);
                        OutputStream out = getContentResolver().openOutputStream(doc)) {
                    if (out == null) {
                        throw new IOException("cannot write " + apk.getName());
                    }
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        token.check();
                        out.write(buf, 0, r);
                        done += r;
                        l.onProgress(done, total);
                    }
                }
            }
            Uri readme = DocumentsContract.createDocument(getContentResolver(), dir, "text/plain",
                    "README.txt");
            if (readme != null) {
                try (OutputStream out = getContentResolver().openOutputStream(readme)) {
                    if (out != null) {
                        out.write(tx.exportReadme().getBytes("UTF-8"));
                    }
                }
            }
            ok = true;
            l.log("exported " + apks.size() + " apks");
        } finally {
            if (!ok && dir != null) {
                try {
                    DocumentsContract.deleteDocument(getContentResolver(), dir);
                } catch (Exception ignored) {
                    // leave the partial folder; the rider can delete it
                }
            }
            releaseUri(tree);
        }
    }

    // --- content helpers ---

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall back to a generated name
        }
        return null;
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (RuntimeException ignored) {
            // unknown size
        }
        return -1;
    }

    private void releaseUris(Intent cmd) {
        if (cmd == null) {
            return;
        }
        List<Uri> uris = cmd.getParcelableArrayListExtra(EXTRA_URIS);
        if (uris != null) {
            for (Uri u : uris) {
                releaseUri(u);
            }
        }
        Uri tree = cmd.getParcelableExtra(EXTRA_TREE);
        if (tree != null) {
            releaseUri(tree);
        }
    }

    private void releaseUri(Uri uri) {
        try {
            getContentResolver().releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // not persisted (or read-only): nothing to release
        }
        try {
            getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // not persisted
        }
    }

    private static String bytesHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
