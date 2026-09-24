package com.wazeology.cli;

import com.wazeology.core.BuildPipeline;
import com.wazeology.core.CancelToken;
import com.wazeology.core.FileAssets;
import com.wazeology.core.Graft;
import com.wazeology.core.Io;
import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The direct build target: runs the installer's own build pipeline on the host, over the pinned
 * Waze in apk/ (base.apk + split_*.apk from scripts/fetch-apk.sh) and the assets baked into
 * build/assets, and writes the signed Waze with Wazeology (base.apk + every split) to the output
 * dir, ready for adb install-multiple. Same refusals, same key, same bytes as the phone produces.
 *
 * Usage: BuildWaze &lt;repo&gt; [--in apk] [--out dist/waze] [--abi arm64-v8a[,armeabi-v7a...]]
 */
public final class BuildWaze {

    private BuildWaze() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
        }
        File repo = new File(args[0]);
        File in = new File(repo, "apk");
        File out = new File(repo, "dist/waze");
        String[] abis = {"arm64-v8a"};
        for (int i = 1; i < args.length; i++) {
            if (i + 1 >= args.length) {
                usage();
            }
            String value = args[++i];
            switch (args[i - 1]) {
                case "--in":
                    in = new File(repo, value);
                    break;
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

        List<File> input = inputs(in);
        File work = new File(repo, "build/gen/waze-work");
        Io.deleteTree(work);
        Io.deleteTree(out);
        if (!out.mkdirs()) {
            fail("cannot create " + out);
        }
        List<File> built;
        try {
            built = BuildPipeline.build(input, work, out, new FileAssets(new File(repo, "build/assets")),
                    abis, new BuildPipeline.Listener() {
                        @Override
                        public void onStep(BuildPipeline.Step step) {
                            System.out.println(">> " + step.name().toLowerCase());
                        }

                        @Override
                        public void onProgress(long done, long total) {
                            // steps are short on the host; no progress output
                        }

                        @Override
                        public void log(String line) {
                            System.out.println("   " + line);
                        }
                    }, CancelToken.NONE);
        } catch (Outcome.Failure e) {
            Io.deleteTree(out);
            fail(e.outcome.name() + ": " + e.getMessage());
            return;
        } finally {
            Io.deleteTree(work);
        }

        // The golden rule, checked on the artifact itself: the resource table is never re-encoded.
        if (!Arrays.equals(Graft.readEntry(new File(in, "base.apk"), Pins.ARSC_ENTRY),
                Graft.readEntry(built.get(0), Pins.ARSC_ENTRY))) {
            fail("resources.arsc changed in " + built.get(0));
        }
        System.out.println(">> built " + built.size() + " apks in " + out + ":");
        for (File f : built) {
            System.out.println("   " + f.getName() + " (" + f.length() + " bytes)");
        }
    }

    /** base.apk first, then every split_*.apk, from the fetched pinned bundle. */
    private static List<File> inputs(File in) {
        File base = new File(in, "base.apk");
        if (!base.isFile()) {
            fail(base + " missing: run scripts/fetch-apk.sh first");
        }
        List<File> splits = new ArrayList<File>();
        File[] all = in.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && f.getName().startsWith("split_") && f.getName().endsWith(".apk")) {
                    splits.add(f);
                }
            }
        }
        Collections.sort(splits);
        List<File> input = new ArrayList<File>();
        input.add(base);
        input.addAll(splits);
        return input;
    }

    private static void usage() {
        fail("usage: BuildWaze <repo> [--in apk] [--out dist/waze] [--abi arm64-v8a[,...]]");
    }

    private static void fail(String message) {
        System.err.println("FATAL: " + message);
        System.exit(1);
    }
}
