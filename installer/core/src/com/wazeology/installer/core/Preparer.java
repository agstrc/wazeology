package com.wazeology.installer.core;

import com.wazeology.core.BuildPipeline;
import com.wazeology.core.CancelToken;
import com.wazeology.core.Io;
import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;
import com.wazeology.core.Progress;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * "Prepare Waze with Wazeology" end to end: make sure the pinned Waze is in the {@link SourceCache}
 * (resuming or starting the APKPure download, with retries across network drops), then make sure
 * the {@link BuildCache} holds the signed build for it. Each step is skipped when its cached result
 * is already complete, so running it again after any interruption continues where it stopped.
 */
public final class Preparer {

    /** Consecutive failed attempts (with no new bytes in between) before giving up. */
    static final int MAX_ATTEMPTS = 6;
    private static final long[] BACKOFF_MS = {2000, 4000, 8000, 16000, 30000, 30000};

    /** Everything the pipeline needs from its host. */
    public interface Env {
        BuildPipeline.Assets assets();

        /** Device ABIs, most preferred first (Build.SUPPORTED_ABIS). */
        String[] abis();

        NetworkGate network();

        /** Lookup of the pinned build's download candidates (ApkPure.find on the phone). */
        List<ApkPure.Candidate> lookup() throws IOException;
    }

    private Preparer() {}

    /** Download (if needed) and build (if needed); returns the signed apks, base first. */
    public static List<File> prepare(Env env, SourceCache sources, BuildCache builds, File work,
            String assetsDigest, JobState.Listener l, CancelToken cancel) throws IOException {
        SourceCache.Source source = sources.current();
        if (source == null) {
            source = download(env, sources, l, cancel);
        } else {
            l.log("using the cached Waze (" + source.origin + ", " + source.bytes + " bytes)");
        }
        return build(env, source, builds, work, assetsDigest, l, cancel);
    }

    /** Build the cached source unless a complete build for it already exists. */
    public static List<File> build(Env env, SourceCache.Source source, BuildCache builds, File work,
            String assetsDigest, JobState.Listener l, CancelToken cancel) throws IOException {
        String key = BuildCache.key(source.id, assetsDigest);
        List<File> ready = builds.complete(key);
        if (ready != null) {
            l.log("using the cached build " + key);
            return ready;
        }
        File tmp = builds.begin(key);
        boolean committed = false;
        try {
            List<File> out = BuildPipeline.build(source.files, work, tmp, env.assets(), env.abis(),
                    JobState.steps(l), cancel);
            l.onPhase(JobState.Phase.FINALIZE);
            builds.commit(key, out);
            committed = true;
            builds.evictExcept(key);
            List<File> done = builds.complete(key);
            if (done == null) {
                throw new Outcome.Failure(Outcome.INTERNAL, "the finished build did not verify");
            }
            l.log("build " + key + " ready: " + done.size() + " apks");
            return done;
        } finally {
            Io.deleteTree(work);
            if (!committed) {
                Io.deleteTree(tmp);
            }
        }
    }

    /**
     * Check files the rider picked (copied into work/picked), and only when they are the right Waze
     * make them the cached source. A bad pick never replaces a good download.
     */
    public static SourceCache.Source acceptPicked(Env env, List<File> picked, String id,
            SourceCache sources, File work, JobState.Listener l, CancelToken cancel) throws IOException {
        try {
            BuildPipeline.inspect(picked, new File(work, "inspect"), env.abis(), JobState.steps(l), cancel);
        } finally {
            Io.deleteTree(new File(work, "inspect"));
        }
        SourceCache.Source s = sources.promote(picked, id, SourceCache.ORIGIN_PICKED);
        sources.discardPartial(); // a half download is moot once the rider's own files are in
        return s;
    }

    // --- download ---

    static SourceCache.Source download(Env env, SourceCache sources, final JobState.Listener l,
            CancelToken cancel) throws IOException {
        NetworkGate net = env.network();
        String abi = env.abis()[0];
        int attempts = 0;
        long lastHave = -1;
        boolean relooked = false;
        while (true) {
            cancel.check();
            SourceCache.Partial part = sources.partial();
            long have = part == null ? 0 : part.have;
            if (have > lastHave) {
                attempts = 0; // progress since the last failure: the retry budget starts over
                relooked = false;
            }
            lastHave = have;
            try {
                return downloadOnce(env, sources, abi, l, cancel);
            } catch (CancelToken.Cancelled e) {
                throw e;
            } catch (Outcome.Failure e) {
                throw e;
            } catch (Downloader.HttpStatus e) {
                if (e.code < 500 && e.code != 408 && e.code != 429) {
                    // A refused link is often just an expired token, and every attempt looks the
                    // link up again: one fresh try before calling it final.
                    if (relooked) {
                        throw new Outcome.Failure(Outcome.SERVER, "APKPure answered " + e.getMessage(), e);
                    }
                    relooked = true;
                    l.log("APKPure answered " + e.getMessage() + ", looking the link up again");
                    continue;
                }
                l.log("server busy (" + e.getMessage() + "), retrying");
                if (++attempts >= MAX_ATTEMPTS) {
                    throw new Outcome.Failure(Outcome.SERVER, "APKPure kept failing: " + e.getMessage(), e);
                }
                net.sleep(BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length) - 1], cancel);
            } catch (IOException e) {
                l.log("download interrupted: " + e);
                if (!net.isOnline()) {
                    l.onPhase(JobState.Phase.WAIT_NETWORK);
                    net.awaitOnline(10 * 60 * 1000L, cancel);
                    if (!net.isOnline()) {
                        throw new Outcome.Failure(Outcome.NETWORK, "no connection", e);
                    }
                    continue; // regained the network: not a failed attempt
                }
                if (++attempts >= MAX_ATTEMPTS) {
                    throw new Outcome.Failure(Outcome.NETWORK, "download kept failing: " + e, e);
                }
                net.sleep(BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length) - 1], cancel);
            }
        }
    }

    private static SourceCache.Source downloadOnce(Env env, SourceCache sources, String abi,
            final JobState.Listener l, CancelToken cancel) throws IOException {
        l.onPhase(JobState.Phase.LOOKUP);
        List<ApkPure.Candidate> candidates = env.lookup();
        if (candidates.isEmpty()) {
            throw new Outcome.Failure(Outcome.NOT_LISTED, "APKPure lists no versionCode "
                    + Pins.WAZE_VERSION_CODE);
        }
        // Download links carry a time-limited token, so a resume always looks the asset up again
        // and continues only when the same file (same SHA-1) is still offered.
        SourceCache.Partial part = sources.partial();
        ApkPure.Candidate pick = null;
        if (part != null && part.sha1 != null) {
            for (ApkPure.Candidate c : candidates) {
                if (part.sha1.equals(c.sha1)) {
                    pick = c;
                    l.log("resuming at " + part.have + " bytes");
                    break;
                }
            }
            if (pick == null) {
                l.log("the partial download is no longer offered; starting over");
                sources.discardPartial();
                part = null;
            }
        } else if (part != null) {
            // No SHA-1 advertised: continue only the same-sized asset, and only with an ETag so
            // If-Range stops the server from splicing a different file onto the bytes we have.
            if (part.etag != null && part.size > 0) {
                for (ApkPure.Candidate c : candidates) {
                    if (c.sha1 == null && c.size == part.size && c.type != null
                            && c.type.equals(part.type)) {
                        pick = c;
                        l.log("resuming at " + part.have + " bytes (no SHA-1 advertised)");
                        break;
                    }
                }
            }
            if (pick == null) {
                sources.discardPartial();
                part = null;
            }
        }
        if (pick == null) {
            for (ApkPure.Candidate c : candidates) {
                if ("XAPK".equals(c.type)) {
                    Boolean fits = ApkPure.remoteHasNativeSplit(c, abi, cancel);
                    if (Boolean.FALSE.equals(fits)) {
                        l.log("skipping a bundle without " + ApkPure.nativeSplitName(abi));
                        continue;
                    }
                }
                pick = c;
                break;
            }
            if (pick == null) {
                throw new Outcome.Failure(Outcome.NO_MATCHING_DOWNLOAD, "no bundle carries "
                        + ApkPure.nativeSplitName(abi));
            }
            sources.discardPartial();
            sources.beginPartial(pick.sha1, pick.size, pick.type);
        }

        l.onPhase(JobState.Phase.DOWNLOAD);
        final long expected = pick.size;
        String etag = sources.partial() == null ? null : sources.partial().etag;
        final SourceCache cache = sources;
        String newEtag = Downloader.fetch(pick.url, sources.partFile(), etag, new Downloader.EtagSink() {
            @Override
            public void onEtag(String e) throws IOException {
                cache.updateEtag(e);
            }
        }, new Progress() {
            @Override
            public void onBytes(long done, long total) {
                l.onProgress(done, total > 0 ? total : expected);
            }
        }, cancel);
        sources.updateEtag(newEtag);

        l.onPhase(JobState.Phase.VERIFY);
        File file = sources.partFile();
        if (pick.sha1 != null) {
            String got = Io.sha1Hex(file, cancel, new Progress() {
                @Override
                public void onBytes(long done, long total) {
                    l.onProgress(done, total);
                }
            });
            if (!got.equals(pick.sha1)) {
                sources.discardPartial();
                throw new Outcome.Failure(Outcome.CORRUPT_DOWNLOAD, "SHA-1 " + got + ", APKPure "
                        + "advertises " + pick.sha1);
            }
        }
        String name = pick.fileName(Pins.WAZE_PACKAGE);
        if ("XAPK".equals(pick.type)) {
            boolean native_;
            try {
                native_ = ApkPure.hasNativeSplit(file, abi);
            } catch (IOException e) {
                sources.discardPartial();
                throw new Outcome.Failure(Outcome.CORRUPT_DOWNLOAD, "not a readable bundle: " + e, e);
            }
            if (!native_) {
                sources.discardPartial();
                throw new Outcome.Failure(Outcome.NO_MATCHING_DOWNLOAD, "the bundle has no "
                        + ApkPure.nativeSplitName(abi));
            }
        }
        String id = pick.sha1 != null ? pick.sha1
                : Io.digestHex("SHA-256", file, cancel, null);
        SourceCache.Source s = sources.promoteDownload(name, id);
        sources.evictOthers();
        l.log("downloaded and verified " + name + " (" + s.bytes + " bytes)");
        return s;
    }
}
