package com.wazeology.cli;

import com.wazeology.core.CancelToken;
import com.wazeology.core.Io;
import com.wazeology.core.Pins;
import com.wazeology.core.Progress;
import com.wazeology.installer.core.ApkPure;
import com.wazeology.installer.core.Downloader;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Downloads the pinned Waze bundle from APKPure for scripts/fetch-apk.sh, with the installer's own
 * lookup: the same versions request, the same choice of the bundle that carries the native split for
 * the first ABI (APKPure lists a build's bundles in no reliable order), the same resumable download and
 * SHA-1 check. Writes the .xapk into the output dir for normalize_apks.py.
 *
 * Usage: FetchWaze &lt;repo&gt; [--out apk/_dl] [--abi arm64-v8a[,armeabi-v7a...]]
 */
public final class FetchWaze {

    private FetchWaze() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
        }
        File repo = new File(args[0]);
        File out = new File(repo, "apk/_dl");
        String[] abis = {"arm64-v8a"};
        for (int i = 1; i < args.length; i++) {
            if (i + 1 >= args.length) {
                usage();
            }
            String value = args[++i];
            switch (args[i - 1]) {
                case "--out":
                    out = new File(repo, value);
                    break;
                case "--abi":
                    abis = value.split(",");
                    break;
                default:
                    usage();
            }
        }
        String abi = abis[0];

        List<ApkPure.Candidate> candidates =
                ApkPure.find(Pins.WAZE_PACKAGE, Pins.WAZE_VERSION_CODE, abis);
        if (candidates.isEmpty()) {
            fail("APKPure lists no versionCode " + Pins.WAZE_VERSION_CODE);
        }
        ApkPure.Candidate pick = ApkPure.pick(candidates, abi, CancelToken.NONE, new ApkPure.Log() {
            @Override
            public void log(String line) {
                System.out.println("   " + line);
            }
        });
        if (pick == null) {
            fail("no bundle of versionCode " + Pins.WAZE_VERSION_CODE + " carries "
                    + ApkPure.nativeSplitName(abi));
        }
        // The installer tells riders how big the arm64 download is; keep that figure honest.
        if ("arm64-v8a".equals(abi) && pick.size > 0 && Math.round(pick.size / (1024.0 * 1024.0)) != Pins.WAZE_DOWNLOAD_MB) {
            fail("APKPure advertises " + pick.size + " bytes, but Pins.WAZE_DOWNLOAD_MB is "
                    + Pins.WAZE_DOWNLOAD_MB + ": update it");
        }

        if (!out.isDirectory() && !out.mkdirs()) {
            fail("cannot create " + out);
        }
        File dest = new File(out, pick.fileName(Pins.WAZE_PACKAGE));
        File part = new File(out, dest.getName() + ".part");
        System.out.println(">> downloading " + dest.getName()
                + (pick.size > 0 ? " (" + pick.size + " bytes)" : ""));
        // Kept as soon as the headers arrive, so an attempt cut mid-stream resumes with If-Range.
        final String[] etag = {null};
        Downloader.EtagSink sink = new Downloader.EtagSink() {
            @Override
            public void onEtag(String e) {
                etag[0] = e;
            }
        };
        for (int attempt = 1; ; attempt++) {
            try {
                Downloader.fetch(pick.url, part, etag[0], sink, new Progress() {
                    private long shown;

                    @Override
                    public void onBytes(long done, long total) {
                        if (done - shown >= 16L * 1024 * 1024 || done == total) {
                            shown = done;
                            System.out.println("   " + done / (1024 * 1024) + " MiB"
                                    + (total > 0 ? " / " + total / (1024 * 1024) + " MiB" : ""));
                        }
                    }
                }, CancelToken.NONE);
                break;
            } catch (IOException e) {
                if (attempt >= 3) {
                    fail("download kept failing: " + e);
                }
                System.out.println("   " + e.getMessage() + "; resuming");
            }
        }

        if (pick.sha1 != null) {
            String got = Io.sha1Hex(part, CancelToken.NONE, null);
            if (!got.equals(pick.sha1)) {
                part.delete();
                fail("SHA-1 " + got + ", APKPure advertises " + pick.sha1);
            }
        }
        if ("XAPK".equals(pick.type) && !ApkPure.hasNativeSplit(part, abi)) {
            part.delete();
            fail("the bundle has no " + ApkPure.nativeSplitName(abi));
        }
        if (!part.renameTo(dest)) {
            fail("cannot move " + part + " to " + dest);
        }
        System.out.println(">> fetched " + dest);
    }

    private static void usage() {
        fail("usage: FetchWaze <repo> [--out apk/_dl] [--abi arm64-v8a[,...]]");
    }

    private static void fail(String message) {
        System.err.println("FATAL: " + message);
        System.exit(1);
    }
}
