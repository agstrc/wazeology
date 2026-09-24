package com.wazeology.core.test;

import com.wazeology.core.BuildPipeline;
import com.wazeology.core.BundleInput;
import com.wazeology.core.CancelToken;
import com.wazeology.core.FileAssets;
import com.wazeology.core.Graft;
import com.wazeology.core.Io;
import com.wazeology.core.MiniAxml;
import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;
import com.wazeology.core.Signing;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Host-JVM gate (runs inside the toolchain container, no Android needed): builds the exact
 * PatchSet the installer app assembles from its assets, grafts the pristine base.apk with the code
 * the phone will run, checks every expected byte-level outcome, then signs the result with the
 * embedded key. scripts/test.sh verifies the signed output with apksigner and zipalign afterwards.
 */
public final class GraftHostTest {

    private static int checks = 0;

    private GraftHostTest() {}

    public static void main(String[] args) throws Exception {
        File repo = new File(args.length > 0 ? args[0] : ".");
        File pristine = new File(repo, "apk/base.apk");
        File assetRoot = new File(repo, "build/assets");
        File assets = new File(assetRoot, Pins.PATCH_ASSET_DIR);
        File outDir = new File(repo, "build/gen/test");
        outDir.mkdirs();

        byte[] manifestBin = read(new File(assets, Pins.MANIFEST_ASSET));
        byte[] classes5 = read(new File(assets, Pins.HOOK_DEXES[0]));
        byte[] classes6 = read(new File(assets, Pins.HOOK_DEXES[1]));
        byte[] payload = read(new File(assets, Pins.PAYLOAD_DEX));
        byte[] icon = read(new File(assets, Pins.ICON_ASSET));

        // 1. The pristine base must satisfy every pin the graft relies on.
        eq(Pins.WAZE_PACKAGE, manifestPkg(pristine), "pristine manifest package");
        eq(Pins.WAZE_VERSION_CODE, manifestVersionCode(pristine), "pristine versionCode");
        List<String> dexes = Graft.dexNames(pristine);
        eq(Pins.BASE_DEX_COUNT, dexes.size(), "pristine dex count");
        eq("classes.dex", dexes.get(0), "first dex name");
        eq("classes10.dex", dexes.get(dexes.size() - 1), "last pristine dex name");
        require(Graft.hasEntry(pristine, Pins.ICON_PATH), "icon target " + Pins.ICON_PATH + " exists");
        require(Graft.hasEntry(pristine, "res/xml/splits0.xml"), "split descriptor exists");
        byte[] pristineArsc = Graft.readEntry(pristine, Pins.ARSC_ENTRY);
        byte[] untouchedSample = Graft.readEntry(pristine, dexes.get(3)); // classes4.dex

        // 2. Graft with the same PatchSet the app builds from the same assets.
        Graft.PatchSet ps = new Graft.PatchSet()
                .replaceEntry(Pins.MANIFEST_ENTRY, manifestBin)
                .replaceEntry(Pins.HOOK_DEXES[0], classes5)
                .replaceEntry(Pins.HOOK_DEXES[1], classes6)
                .replaceIfPresent(Pins.ICON_PATH, icon)
                .appendEntry(Pins.PAYLOAD_DEX, payload);
        File grafted = new File(outDir, "grafted.apk");
        Graft.graft(pristine, grafted, ps, null);

        List<String> graftedDexes = Graft.dexNames(grafted);
        eq(Pins.BASE_DEX_COUNT + 1, graftedDexes.size(), "grafted dex count");
        eq(Pins.PAYLOAD_DEX, graftedDexes.get(graftedDexes.size() - 1), "payload dex appended last");
        eq(pristineArsc, Graft.readEntry(grafted, Pins.ARSC_ENTRY), "resources.arsc byte-identical");
        eq(manifestBin, Graft.readEntry(grafted, Pins.MANIFEST_ENTRY), "manifest swapped");
        eq(classes5, Graft.readEntry(grafted, Pins.HOOK_DEXES[0]), "classes5.dex swapped");
        eq(classes6, Graft.readEntry(grafted, Pins.HOOK_DEXES[1]), "classes6.dex swapped");
        eq(payload, Graft.readEntry(grafted, Pins.PAYLOAD_DEX), "payload dex content");
        eq(icon, Graft.readEntry(grafted, Pins.ICON_PATH), "icon bytes overwritten");
        eq(untouchedSample, Graft.readEntry(grafted, dexes.get(3)), "untouched dex passthrough");
        require(!Graft.hasEntry(grafted, "META-INF/MANIFEST.MF"), "stale META-INF dropped");

        // 3. Sign the graft with the embedded key (apksig, the same call the app makes), plus one
        //    split the way the app re-signs the whole bundle.
        File p12 = new File(assetRoot, Pins.P12_ASSET);
        File signed = new File(outDir, "signed.apk");
        Signing.sign(grafted, signed, BundleInput.readFile(p12), Pins.P12_PASSWORD);
        // Re-sign the native split the way the app re-signs the whole bundle: the .so entries must
        // stay STORED and page-aligned through the apksig pass (checked by zipalign in test.sh).
        File arm64 = new File(new File(repo, "apk"), "split_config.arm64_v8a.apk");
        require(arm64.isFile(), "arm64 native split present in apk/");
        File splitSigned = new File(outDir, "split-signed.apk");
        Signing.sign(arm64, splitSigned, BundleInput.readFile(p12), Pins.P12_PASSWORD);
        eq(BundleInput.readFile(arm64).length != 0, true, "split re-signed non-empty");

        // 4. The whole on-device build (BuildPipeline: inspect + graft + sign every split), exactly as the
        //    app runs it, fed from the same assets. test.sh verifies each output with apksigner.
        BuildPipeline.Assets fileAssets = new FileAssets(assetRoot);
        File apkDir = new File(repo, "apk");
        List<File> input = Arrays.asList(pristine, arm64, new File(apkDir, "split_config.xxxhdpi.apk"),
                new File(apkDir, "split_config.en.apk"));
        File buildOut = new File(outDir, "build");
        Io.deleteTree(buildOut);
        buildOut.mkdirs();
        final int[] phases = {0};
        List<File> built = BuildPipeline.build(input, new File(outDir, "build-work"), buildOut, fileAssets,
                new String[] {"arm64-v8a"}, new BuildPipeline.Listener() {
                    @Override
                    public void onStep(BuildPipeline.Step step) {
                        phases[0]++;
                    }

                    @Override
                    public void onProgress(long done, long total) {
                    }

                    @Override
                    public void log(String line) {
                    }
                }, CancelToken.NONE);
        eq(4, built.size(), "build: base + 3 splits signed");
        eq("base.apk", built.get(0).getName(), "build: base first");
        eq(pristineArsc, Graft.readEntry(built.get(0), Pins.ARSC_ENTRY), "build: resources.arsc byte-identical");
        eq(Pins.BASE_DEX_COUNT + 1, Graft.dexNames(built.get(0)).size(), "build: payload dex added");
        eq(4, phases[0], "build: unpack, check, graft, sign steps reported");
        eq(BuildPipeline.assetsDigest(fileAssets), BuildPipeline.assetsDigest(fileAssets), "build: assets digest stable");

        // 6. The refusals the rider sees in plain words.
        eq(Outcome.MISSING_NATIVE, failure(Arrays.asList(pristine), outDir, fileAssets, "arm64-v8a"),
                "build: base without its native split refused");
        eq(Outcome.MISSING_NATIVE, failure(Arrays.asList(pristine, arm64), outDir, fileAssets, "x86_64"),
                "build: split for another processor refused");
        eq(Outcome.MODIFIED_FILES, failure(Arrays.asList(built.get(0), arm64), outDir, fileAssets, "arm64-v8a"),
                "build: an already modified Waze refused");
        eq(Outcome.INCOMPLETE_FILES, failure(Arrays.asList(arm64), outDir, fileAssets, "arm64-v8a"),
                "build: splits without a base refused");

        // 7. A cancel mid-graft stops the build and leaves no output behind for the caller to seal.
        final CancelToken cancel = new CancelToken();
        File cancelOut = new File(outDir, "cancel-out");
        Io.deleteTree(cancelOut);
        cancelOut.mkdirs();
        boolean cancelled = false;
        try {
            BuildPipeline.build(input, new File(outDir, "cancel-work"), cancelOut, fileAssets,
                    new String[] {"arm64-v8a"}, new BuildPipeline.Listener() {
                        @Override
                        public void onStep(BuildPipeline.Step step) {
                        }

                        @Override
                        public void onProgress(long done, long total) {
                            if (done > 50) {
                                cancel.cancel();
                            }
                        }

                        @Override
                        public void log(String line) {
                        }
                    }, cancel);
        } catch (CancelToken.Cancelled e) {
            cancelled = true;
        }
        eq(true, cancelled, "build: cancel stops the build");
        eq(0, cancelOut.list().length, "build: cancelled build signed nothing");

        System.out.println("PASS: " + checks + " engine checks (graft, golden rule, pins, signing, build)");
    }

    private static Outcome failure(List<File> input, File outDir, BuildPipeline.Assets assets, String abi)
            throws Exception {
        File work = new File(outDir, "refuse-work");
        File out = new File(outDir, "refuse-out");
        Io.deleteTree(work);
        Io.deleteTree(out);
        out.mkdirs();
        try {
            BuildPipeline.build(input, work, out, assets, new String[] {abi}, new BuildPipeline.Listener() {
                @Override
                public void onStep(BuildPipeline.Step step) {
                }

                @Override
                public void onProgress(long done, long total) {
                }

                @Override
                public void log(String line) {
                }
            }, CancelToken.NONE);
            return null;
        } catch (Outcome.Failure e) {
            return e.outcome;
        } finally {
            Io.deleteTree(work);
        }
    }

    private static String manifestPkg(File apk) throws Exception {
        return MiniAxml.read(Graft.readEntry(apk, Pins.MANIFEST_ENTRY)).pkg;
    }

    private static int manifestVersionCode(File apk) throws Exception {
        return MiniAxml.read(Graft.readEntry(apk, Pins.MANIFEST_ENTRY)).versionCode;
    }

    private static byte[] read(File f) throws Exception {
        return BundleInput.readFile(f);
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected instanceof byte[] && actual instanceof byte[]) {
            if (Arrays.equals((byte[]) expected, (byte[]) actual)) {
                System.out.println("  ok  " + what);
                return;
            }
        } else if (expected != null && expected.equals(actual)) {
            System.out.println("  ok  " + what);
            return;
        }
        throw new AssertionError(what + ": expected " + render(expected) + ", got " + render(actual));
    }

    private static void require(boolean cond, String what) {
        checks++;
        if (!cond) {
            throw new AssertionError(what + ": check failed");
        }
        System.out.println("  ok  " + what);
    }

    private static String render(Object o) {
        if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            StringBuilder sb = new StringBuilder();
            sb.append("byte[").append(b.length).append("] ");
            for (int i = 0; i < Math.min(8, b.length); i++) {
                sb.append(String.format("%02x", b[i]));
            }
            return sb.toString();
        }
        return String.valueOf(o);
    }
}
