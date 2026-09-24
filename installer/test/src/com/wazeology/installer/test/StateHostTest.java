package com.wazeology.installer.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.wazeology.core.BuildPipeline;
import com.wazeology.core.CancelToken;
import com.wazeology.core.Io;
import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;
import com.wazeology.core.Progress;
import com.wazeology.installer.core.ApkPure;
import com.wazeology.installer.core.BuildCache;
import com.wazeology.installer.core.Downloader;
import com.wazeology.installer.core.InstallStatus;
import com.wazeology.installer.core.JobState;
import com.wazeology.installer.core.NetworkGate;
import com.wazeology.installer.core.Preparer;
import com.wazeology.installer.core.SourceCache;
import com.wazeology.installer.core.UiModel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-JVM checks of the installer's decision logic and caches, none of which need the pinned apk:
 * the install-result classifier, the derived screen state, the resumable downloader against a local
 * HTTP server (range resume, redirects, a stream cut mid-way, ETag change, SHA-1 mismatch, cancel),
 * the source and build caches, and the job gate. scripts/test.sh runs it after the engine gate.
 */
final class StateHostTest {

    private static int checks = 0;

    private StateHostTest() {}

    public static void main(String[] args) throws Exception {
        File repo = new File(args.length > 0 ? args[0] : ".");
        File scratch = new File(repo, "build/gen/test/state");
        Io.deleteTree(scratch);
        scratch.mkdirs();
        apkPure();
        installStatus();
        uiModel();
        jobGate();
        buildCache(new File(scratch, "bc"));
        download(new File(scratch, "dl"));
        System.out.println("PASS: " + checks + " installer checks (apkpure, install outcomes, screen state, "
                + "caches, resumable download)");
    }

    // --- ApkPure ---

    private static void apkPure() throws Exception {
        // The APKPure response parser the app uses to fetch the pinned bundle (offline, on a
        //    synthetic record shaped like the live protobuf: sha1, size, flag, type, url).
        String sha1 = "4591a7f63f6fa1843383d999fc1afcdd40fc3afe";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        apkPureAsset(body, "0000000000000000000000000000000000000000", "XAPK",
                Pins.WAZE_PACKAGE + "_" + (Pins.WAZE_VERSION_CODE + 5) + "_a9fc6fee", 189598117L);
        apkPureAsset(body, sha1, "XAPK", Pins.WAZE_PACKAGE + "_" + Pins.WAZE_VERSION_CODE + "_4418e662",
                189598117L);
        apkPureAsset(body, sha1, "XAPK", Pins.WAZE_PACKAGE + "_" + Pins.WAZE_VERSION_CODE + "_4418e662",
                189598117L);
        List<ApkPure.Candidate> cands =
                ApkPure.parse(body.toByteArray(), Pins.WAZE_PACKAGE, Pins.WAZE_VERSION_CODE);
        eq(1, cands.size(), "apkpure: one deduplicated candidate for the pinned versionCode");
        eq("XAPK", cands.get(0).type, "apkpure: candidate type");
        eq(sha1, cands.get(0).sha1, "apkpure: advertised sha1 read");
        eq(189598117L, cands.get(0).size, "apkpure: advertised size read");

    }

    // --- InstallStatus ---

    private static void installStatus() {
        eq(Outcome.INSTALLED, InstallStatus.classify(0, 1, null), "install: success");
        eq(Outcome.NEWER_INSTALLED, InstallStatus.classify(InstallStatus.STATUS_FAILURE_INVALID, -25,
                "INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected"), "install: newer Play Waze (-25)");
        eq(Outcome.NEWER_INSTALLED, InstallStatus.classify(InstallStatus.STATUS_FAILURE_INVALID, 0,
                "INSTALL_FAILED_VERSION_DOWNGRADE: x"), "install: downgrade by message");
        eq(Outcome.REMOVE_CURRENT, InstallStatus.classify(InstallStatus.STATUS_FAILURE_CONFLICT, -7,
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"), "install: other signer (-7)");
        eq(Outcome.REMOVE_CURRENT, InstallStatus.classify(InstallStatus.STATUS_FAILURE_CONFLICT, 0, null),
                "install: conflict by status");
        eq(Outcome.INSTALL_CANCELLED, InstallStatus.classify(InstallStatus.STATUS_FAILURE_ABORTED, -115, null),
                "install: rider cancelled");
        eq(Outcome.PLAY_PROTECT, InstallStatus.classify(InstallStatus.STATUS_FAILURE_ABORTED, -22, null),
                "install: Play Protect shares ABORTED with a cancel");
        eq(Outcome.STORAGE, InstallStatus.classify(InstallStatus.STATUS_FAILURE_STORAGE, -4, null),
                "install: storage");
        eq(Outcome.MISSING_PART, InstallStatus.classify(InstallStatus.STATUS_FAILURE_INCOMPATIBLE, -28, null),
                "install: missing split");
        eq(Outcome.WRONG_DEVICE, InstallStatus.classify(InstallStatus.STATUS_FAILURE_INCOMPATIBLE, -113, null),
                "install: no matching ABIs");
        eq(Outcome.ANDROID_TOO_OLD, InstallStatus.classify(InstallStatus.STATUS_FAILURE_INCOMPATIBLE, -12, null),
                "install: SDK too old");
        eq(Outcome.RESTRICTED, InstallStatus.classify(InstallStatus.STATUS_FAILURE_INCOMPATIBLE, -111, null),
                "install: user restricted");
        eq(Outcome.DAMAGED_BUILD, InstallStatus.classify(InstallStatus.STATUS_FAILURE_INVALID, -103, null),
                "install: parse failure");
        eq(Outcome.INSTALL_BLOCKED, InstallStatus.classify(InstallStatus.STATUS_FAILURE_BLOCKED, 0, null),
                "install: blocked");
        eq(Outcome.INSTALL_FAILED, InstallStatus.classify(InstallStatus.STATUS_FAILURE_TIMEOUT, 0, null),
                "install: timeout");
        eq(Outcome.UNINSTALLED, InstallStatus.classifyUninstall(0), "uninstall: success");
        eq(Outcome.UNINSTALL_CANCELLED, InstallStatus.classifyUninstall(InstallStatus.STATUS_FAILURE_ABORTED),
                "uninstall: cancelled");
        eq(Outcome.UNINSTALL_FAILED, InstallStatus.classifyUninstall(InstallStatus.STATUS_FAILURE), "uninstall: failed");
        eq(Outcome.STORAGE, Outcome.of(new IOException("write failed: ENOSPC (No space left on device)")),
                "outcome: out of space recognized");
    }

    // --- UiModel ---

    private static void uiModel() {
        UiModel.Facts f = new UiModel.Facts();
        UiModel m = UiModel.derive(f);
        eq(UiModel.PrepButton.PREPARE, m.prepButton, "ui: fresh phone offers Prepare");
        eq(false, m.installEnabled, "ui: install disabled before preparing");
        eq(UiModel.InstallBlock.NOT_READY, m.installBlock, "ui: install blocked until prepared");
        eq(false, m.showWazeCard, "ui: no Waze card without Waze");

        f.waze = UiModel.Waze.OTHER_SIGNER;
        m = UiModel.derive(f);
        eq(true, m.showWazeCard && m.uninstallVisible && m.uninstallEmphasized,
                "ui: Play Waze shown with an emphasized Remove before any download");

        f.buildReady = true;
        m = UiModel.derive(f);
        eq(UiModel.InstallBlock.REMOVE_CURRENT, m.installBlock, "ui: Play Waze blocks install");
        eq(false, m.installEnabled, "ui: install disabled while Play Waze is present");

        f.waze = UiModel.Waze.NONE;
        m = UiModel.derive(f);
        eq(true, m.installEnabled, "ui: install enabled once prepared and nothing conflicts");
        eq(UiModel.PrepButton.NONE, m.prepButton, "ui: prepared hides the prepare button");

        f.waze = UiModel.Waze.OURS_CURRENT;
        m = UiModel.derive(f);
        eq(UiModel.InstallButton.REINSTALL, m.installButton, "ui: installed offers reinstall");
        eq(true, m.showOpenWazeology, "ui: installed offers Open Wazeology");

        f.waze = UiModel.Waze.SYSTEM_IMAGE;
        m = UiModel.derive(f);
        eq(false, m.uninstallVisible, "ui: preinstalled Waze cannot be removed");
        eq(UiModel.InstallBlock.SYSTEM_WAZE, m.installBlock, "ui: preinstalled Waze blocks install");

        f.waze = UiModel.Waze.NONE;
        f.job = snapshot(JobState.Kind.PREPARE);
        m = UiModel.derive(f);
        eq(UiModel.PrepButton.CANCEL, m.prepButton, "ui: running prepare offers Cancel");
        eq(false, m.installEnabled || m.pickEnabled || m.clearEnabled || m.exportEnabled,
                "ui: nothing else starts while a job runs");
        eq(true, m.keepScreenOn, "ui: screen stays on while working");

        f.job = snapshot(JobState.Kind.INSTALL);
        m = UiModel.derive(f);
        eq(UiModel.InstallButton.CANCEL, m.installButton, "ui: writing install offers Cancel");
        eq(false, m.prepEnabled, "ui: prepare disabled while installing");

        f.job = JobState.IDLE;
        f.installCommitted = true;
        m = UiModel.derive(f);
        eq(UiModel.InstallButton.RESTART, m.installButton,
                "ui: committed session without its confirmation restarts the install");
        f.installWorking = true;
        m = UiModel.derive(f);
        eq(UiModel.InstallButton.WAITING, m.installButton, "ui: Android installing shows a wait, not a restart");
        eq(false, m.installEnabled, "ui: nothing to tap while Android installs");
        f.installWorking = false;
        f.confirmAvailable = true;
        m = UiModel.derive(f);
        eq(UiModel.InstallButton.CONFIRM, m.installButton, "ui: held confirmation can be re-shown");
        eq(false, m.clearEnabled, "ui: files cannot be deleted mid-install");

        f = new UiModel.Facts();
        f.partialBytes = 10;
        f.partialTotal = 100;
        eq(UiModel.PrepButton.CONTINUE, UiModel.derive(f).prepButton, "ui: partial download offers Continue");
        f.sdkOk = false;
        m = UiModel.derive(f);
        eq(false, m.supported || m.prepEnabled, "ui: old Android cannot prepare");
    }

    private static JobState.Snapshot snapshot(JobState.Kind kind) {
        JobState js = new JobState();
        js.tryBegin(kind, new CancelToken());
        return js.snapshot();
    }

    private static void jobGate() {
        JobState js = new JobState();
        CancelToken t = new CancelToken();
        eq(true, js.tryBegin(JobState.Kind.PREPARE, t), "job: first start accepted");
        eq(false, js.tryBegin(JobState.Kind.INSTALL, new CancelToken()), "job: second start refused");
        eq(true, js.cancel() && t.isCancelled() && js.snapshot().cancelling, "job: cancel reaches the token");
        js.end();
        eq(false, js.snapshot().running(), "job: idle after end");
        eq(true, js.tryBegin(JobState.Kind.INSTALL, new CancelToken()), "job: next start accepted");
    }

    // --- BuildCache ---

    private static void buildCache(File root) throws Exception {
        BuildCache bc = new BuildCache(root);
        String key = BuildCache.key("src", "assets");
        eq(key, BuildCache.key("src", "assets"), "build key: stable");
        eq(false, key.equals(BuildCache.key("src", "assets2")), "build key: new assets rebuild");
        eq(false, key.equals(BuildCache.key("src2", "assets")), "build key: new source rebuilds");

        File tmp = bc.begin(key);
        File base = write(new File(tmp, "base.apk"), 1000);
        File split = write(new File(tmp, "split.apk"), 10);
        eq(null, bc.complete(key), "build: an unsealed build is never complete");
        bc.commit(key, Arrays.asList(base, split));
        List<File> done = bc.complete(key);
        eq(2, done == null ? 0 : done.size(), "build: sealed build complete");
        eq("base.apk", done.get(0).getName(), "build: base first");
        write(done.get(1), 11);
        eq(null, bc.complete(key), "build: a truncated or altered apk voids the build");

        String other = BuildCache.key("x", "y");
        File tmp2 = bc.begin(other);
        write(new File(tmp2, "base.apk"), 5);
        bc.cleanTemp();
        eq(false, tmp2.exists(), "build: startup sweep removes a crashed build");
        bc.evictExcept(other);
        eq(false, new File(new File(root, "builds"), key).exists(), "build: old key evicted");
    }

    // --- Downloader + SourceCache + Preparer download path ---

    private static void download(File root) throws Exception {
        byte[] payload = new byte[3 * 1024 * 1024 + 123];
        new Random(7).nextBytes(payload);
        final byte[] data = payload;
        final String sha1 = hex(MessageDigest.getInstance("SHA-1").digest(data));
        final String[] etag = {"\"v1\""};
        final AtomicInteger cutAfter = new AtomicInteger(-1); // cut the next full response after n bytes
        final AtomicInteger requests = new AtomicInteger();
        final List<String> ranges = new ArrayList<String>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/r", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Location", "/f");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            }
        });
        server.createContext("/f", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                requests.incrementAndGet();
                String range = ex.getRequestHeaders().getFirst("Range");
                String ifRange = ex.getRequestHeaders().getFirst("If-Range");
                synchronized (ranges) {
                    ranges.add(String.valueOf(range));
                }
                int start = 0;
                int end = data.length - 1;
                boolean partial = false;
                if (range != null && (ifRange == null || ifRange.equals(etag[0]))) {
                    String spec = range.substring("bytes=".length());
                    if (spec.startsWith("-")) {
                        start = Math.max(0, data.length - Integer.parseInt(spec.substring(1)));
                    } else {
                        start = Integer.parseInt(spec.substring(0, spec.indexOf('-')));
                    }
                    if (start >= data.length) {
                        ex.sendResponseHeaders(416, -1);
                        ex.close();
                        return;
                    }
                    partial = true;
                }
                ex.getResponseHeaders().add("ETag", etag[0]);
                if (partial) {
                    ex.getResponseHeaders().add("Content-Range",
                            "bytes " + start + "-" + end + "/" + data.length);
                }
                int len = end - start + 1;
                ex.sendResponseHeaders(partial ? 206 : 200, len);
                OutputStream out = ex.getResponseBody();
                int cut = cutAfter.getAndSet(-1);
                try {
                    if (cut >= 0 && cut < len) {
                        out.write(data, start, cut);
                        out.flush();
                        throw new IOException("cut"); // closing short of the length drops the connection
                    }
                    out.write(data, start, len);
                } catch (IOException e) {
                    // client went away, or our deliberate cut
                } finally {
                    ex.close();
                }
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            // plain range resume through a redirect
            File part = new File(root, "manual.part");
            root.mkdirs();
            Files.write(part.toPath(), Arrays.copyOf(data, 1000));
            Downloader.fetch(base + "/r", part, etag[0], null, CancelToken.NONE);
            eq(sha1, Io.sha1Hex(part, CancelToken.NONE, null), "download: resumed via redirect");
            eq(true, ranges.contains("bytes=1000-"), "download: Range header survived the redirect");

            // a changed file (new ETag) answers 200 and restarts from zero
            Files.write(part.toPath(), new byte[500]);
            Downloader.fetch(base + "/f", part, "\"stale\"", null, CancelToken.NONE);
            eq(sha1, Io.sha1Hex(part, CancelToken.NONE, null), "download: ETag change restarts cleanly");

            // tail read for the ABI probe
            byte[] tail = Downloader.tail(base + "/f", 4096, CancelToken.NONE);
            eq(true, tail != null && tail.length == 4096
                    && Arrays.equals(tail, Arrays.copyOfRange(data, data.length - 4096, data.length)),
                    "download: tail range read");

            // cancel mid-stream keeps the part
            part.delete();
            final CancelToken token = new CancelToken();
            boolean cancelled = false;
            try {
                Downloader.fetch(base + "/f", part, null, new Progress() {
                    @Override
                    public void onBytes(long done, long total) {
                        if (done > 256 * 1024) {
                            token.cancel();
                        }
                    }
                }, token);
            } catch (CancelToken.Cancelled e) {
                cancelled = true;
            }
            eq(true, cancelled && part.length() > 0 && part.length() < data.length,
                    "download: cancel stops and keeps the partial file");

            // the full Preparer download path: a cut stream, automatic resume, verify, promote
            SourceCache sources = new SourceCache(new File(root, "cache"));
            final ApkPure.Candidate cand = candidate(base + "/r", sha1, data.length);
            cutAfter.set(1024 * 1024);
            synchronized (ranges) {
                ranges.clear();
            }
            final List<JobState.Phase> phases = new ArrayList<JobState.Phase>();
            Preparer.Env env = env(cand);
            JobState.Listener l = listener(phases);
            List<File> none = null;
            try {
                Preparer.prepare(env, sources, new BuildCache(new File(root, "cache")), new File(root, "work"),
                        "digest", l, CancelToken.NONE);
            } catch (IOException e) {
                none = new ArrayList<File>(); // the build fails on random bytes; the download is what counts
            }
            SourceCache.Source src = sources.current();
            boolean resumed = false;
            synchronized (ranges) {
                for (String r : ranges) {
                    resumed |= r.matches("bytes=[1-9][0-9]*-");
                }
            }
            eq(true, resumed, "prepare: a cut stream resumed with a Range request");
            eq(true, src != null && src.id.equals(sha1), "prepare: resumed download verified and promoted");
            eq(true, none != null, "prepare: random bytes are refused by the build step");
            eq(true, phases.contains(JobState.Phase.VERIFY), "prepare: download verified before use");
            eq(null, sources.partial(), "prepare: partial state cleared after promotion");

            int before = requests.get();
            try {
                Preparer.prepare(env, sources, new BuildCache(new File(root, "cache")), new File(root, "work"),
                        "digest", l, CancelToken.NONE);
            } catch (IOException expected) {
                // same build failure; no download
            }
            eq(before, requests.get(), "prepare: a cached download is never fetched again");

            // SHA-1 mismatch discards the part and reports a damaged download
            SourceCache bad = new SourceCache(new File(root, "bad"));
            Outcome got = null;
            try {
                Preparer.prepare(env(candidate(base + "/f", "0000000000000000000000000000000000000000",
                        data.length)), bad, new BuildCache(new File(root, "bad")), new File(root, "work2"),
                        "digest", l, CancelToken.NONE);
            } catch (Outcome.Failure e) {
                got = e.outcome;
            }
            eq(Outcome.CORRUPT_DOWNLOAD, got, "prepare: SHA-1 mismatch reported as a damaged download");
            eq(null, bad.partial(), "prepare: a damaged download is discarded");

            // a refused link (expired token) gets one fresh lookup; a link refused again is final
            final AtomicInteger refusals = new AtomicInteger(1);
            server.createContext("/expired", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    boolean refuse = refusals.getAndDecrement() > 0;
                    if (!refuse) {
                        ex.getResponseHeaders().add("Location", "/f");
                    }
                    ex.sendResponseHeaders(refuse ? 403 : 302, -1);
                    ex.close();
                }
            });
            SourceCache relook = new SourceCache(new File(root, "relook"));
            try {
                Preparer.prepare(env(candidate(base + "/expired", sha1, data.length)), relook,
                        new BuildCache(new File(root, "relook")), new File(root, "work3"), "digest", l,
                        CancelToken.NONE);
            } catch (IOException expected) {
                // the build fails on random bytes; the download is what counts
            }
            eq(true, relook.current() != null, "prepare: a refused link is looked up again and downloads");
            server.createContext("/refused", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    ex.sendResponseHeaders(403, -1);
                    ex.close();
                }
            });
            got = null;
            try {
                Preparer.prepare(env(candidate(base + "/refused", sha1, data.length)),
                        new SourceCache(new File(root, "refused")), new BuildCache(new File(root, "refused")),
                        new File(root, "work4"), "digest", l, CancelToken.NONE);
            } catch (Outcome.Failure e) {
                got = e.outcome;
            }
            eq(Outcome.SERVER, got, "prepare: a link refused twice is a server failure");

            // no SHA-1 advertised: a cut download still resumes (same size, guarded by the ETag)
            SourceCache nosha = new SourceCache(new File(root, "nosha"));
            cutAfter.set(1024 * 1024);
            synchronized (ranges) {
                ranges.clear();
            }
            try {
                Preparer.prepare(env(rawCandidate(base + "/r", null, data.length)), nosha,
                        new BuildCache(new File(root, "nosha")), new File(root, "work5"), "digest", l,
                        CancelToken.NONE);
            } catch (IOException expected) {
                // the build fails on random bytes; the download is what counts
            }
            resumed = false;
            synchronized (ranges) {
                for (String r : ranges) {
                    resumed |= r.matches("bytes=[1-9][0-9]*-");
                }
            }
            eq(true, resumed, "prepare: without a SHA-1 a cut stream still resumes");
            eq(true, nosha.current() != null && nosha.current().bytes == data.length,
                    "prepare: without a SHA-1 the resumed download is complete");

            // the remote ABI probe reads the zip listing only, never file data that names the split
            final byte[][] zip = new byte[1][];
            server.createContext("/zip", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] z = zip[0];
                    int n = Integer.parseInt(ex.getRequestHeaders().getFirst("Range").substring("bytes=-".length()));
                    int start = Math.max(0, z.length - n);
                    ex.getResponseHeaders().add("Content-Range",
                            "bytes " + start + "-" + (z.length - 1) + "/" + z.length);
                    ex.sendResponseHeaders(206, z.length - start);
                    ex.getResponseBody().write(z, start, z.length - start);
                    ex.close();
                }
            });
            String split = ApkPure.nativeSplitName("arm64-v8a");
            ApkPure.Candidate zipCand = rawCandidate(base + "/zip", null, 0);
            zip[0] = zipOf("notes.txt", "see " + split, "config.x86.apk", "x");
            eq(Boolean.FALSE, ApkPure.remoteHasNativeSplit(zipCand, "arm64-v8a", CancelToken.NONE),
                    "abi probe: a name inside file data is not a listed split");
            zip[0] = zipOf("notes.txt", "hello", split, "x");
            eq(Boolean.TRUE, ApkPure.remoteHasNativeSplit(zipCand, "arm64-v8a", CancelToken.NONE),
                    "abi probe: a listed split is found");
        } finally {
            server.stop(0);
        }
    }

    /** A candidate shaped by the real parser (sha1 + size fields), then pointed at the local server
     *  (the parser only accepts APKPure's https URLs). */
    private static ApkPure.Candidate candidate(String url, String sha1, long size) throws Exception {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        apkPureAsset(body, sha1, "APK",
                Pins.WAZE_PACKAGE + "_" + Pins.WAZE_VERSION_CODE + "_test", size);
        ApkPure.Candidate c = ApkPure.parse(body.toByteArray(), Pins.WAZE_PACKAGE,
                Pins.WAZE_VERSION_CODE).get(0);
        eq(size, c.size, "apkpure: advertised size read");
        java.lang.reflect.Constructor<ApkPure.Candidate> ctor = ApkPure.Candidate.class.getDeclaredConstructor(
                String.class, String.class, int.class, String.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(c.type, url, c.versionCode, c.sha1, c.size);
    }

    /** A candidate built directly, for fields the parser cannot express (no SHA-1). */
    private static ApkPure.Candidate rawCandidate(String url, String sha1, long size) throws Exception {
        java.lang.reflect.Constructor<ApkPure.Candidate> ctor = ApkPure.Candidate.class.getDeclaredConstructor(
                String.class, String.class, int.class, String.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance("APK", url, Pins.WAZE_VERSION_CODE, sha1, size);
    }

    /** A stored zip of name/content pairs. */
    private static byte[] zipOf(String... entries) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bytes)) {
            for (int i = 0; i < entries.length; i += 2) {
                z.putNextEntry(new java.util.zip.ZipEntry(entries[i]));
                z.write(entries[i + 1].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Preparer.Env env(final ApkPure.Candidate cand) {
        return new Preparer.Env() {
            @Override
            public BuildPipeline.Assets assets() {
                return new BuildPipeline.Assets() {
                    @Override
                    public byte[] read(String name) {
                        return new byte[0];
                    }
                };
            }

            @Override
            public String[] abis() {
                return new String[] {"arm64-v8a"};
            }

            @Override
            public NetworkGate network() {
                return new NetworkGate() {
                    @Override
                    public boolean isOnline() {
                        return true;
                    }

                    @Override
                    public void awaitOnline(long maxMs, CancelToken cancel) {
                    }

                    @Override
                    public void sleep(long ms, CancelToken cancel) {
                    }
                };
            }

            @Override
            public List<ApkPure.Candidate> lookup() {
                List<ApkPure.Candidate> l = new ArrayList<ApkPure.Candidate>();
                l.add(cand);
                return l;
            }
        };
    }

    private static JobState.Listener listener(final List<JobState.Phase> phases) {
        return new JobState.Listener() {
            @Override
            public void onPhase(JobState.Phase phase) {
                phases.add(phase);
            }

            @Override
            public void onProgress(long done, long total) {
            }

            @Override
            public void log(String line) {
            }
        };
    }

    // --- helpers ---

    private static File write(File f, int n) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[n]);
        }
        return f;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) {
            System.out.println("  ok  " + what);
            return;
        }
        throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }

    private static void apkPureAsset(ByteArrayOutputStream out, String sha1, String type, String id, long size)
            throws Exception {
        String url = "https://download.pureapk.com/b/" + type + "/"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8))
                + "?_fn=x&k=y";
        out.write(new byte[] {0x1a, 40});
        out.write(sha1.getBytes(StandardCharsets.US_ASCII));
        out.write(0x20); // size, a varint
        varint(out, size);
        out.write(new byte[] {0x28, 0x01, 'B', (byte) type.length()});
        out.write((type + "J").getBytes(StandardCharsets.US_ASCII));
        byte[] u = url.getBytes(StandardCharsets.US_ASCII);
        varint(out, u.length); // protobuf length prefix
        out.write(u);
    }

    private static void varint(ByteArrayOutputStream out, long v) {
        while (v >= 0x80) {
            out.write((int) (v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write((int) v);
    }
}
