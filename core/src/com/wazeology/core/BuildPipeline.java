package com.wazeology.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The build pipeline shared by both targets (the installer on the phone, BuildWaze on the host) and
 * the host gate: check that the input really is the pinned, unmodified Waze split bundle for this
 * device, graft the Wazeology assets onto a copy of the pristine base, and sign the base plus every
 * split with the embedded key. Every refusal is an {@link Outcome.Failure}.
 */
public final class BuildPipeline {

    /** Reads one of the baked build assets (AssetManager on the phone, {@link FileAssets} on the
     *  host). */
    public interface Assets {
        byte[] read(String name) throws IOException;
    }

    /** The build's steps, in order. */
    public enum Step { UNPACK, CHECK, GRAFT, SIGN }

    /** Step and progress callbacks, plus a technical log line for the details view. */
    public interface Listener {
        void onStep(Step step);

        void onProgress(long done, long total);

        void log(String line);
    }

    private BuildPipeline() {}

    /** Every asset the output depends on, in a fixed order (the build key hashes them). */
    public static List<String> assetNames() {
        List<String> names = new ArrayList<String>();
        names.add(Pins.patchAsset(Pins.MANIFEST_ASSET));
        for (String dex : Pins.HOOK_DEXES) {
            names.add(Pins.patchAsset(dex));
        }
        names.add(Pins.patchAsset(Pins.ICON_ASSET));
        names.add(Pins.patchAsset(Pins.PAYLOAD_DEX));
        names.add(Pins.P12_ASSET);
        return names;
    }

    /** SHA-256 over every asset name and content: changes whenever new assets are baked. */
    public static String assetsDigest(Assets assets) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        for (String name : assetNames()) {
            md.update(name.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Graft.sha256(assets.read(name)));
        }
        return Io.hex(md.digest());
    }

    /**
     * Unpack and check the input: exactly one base, the pinned package and versionCode, the pristine
     * dex layout, and a native split this device can run.
     */
    public static BundleInput.Bundle inspect(List<File> files, File unpackDir, String[] abis,
            final Listener l, CancelToken cancel) throws IOException {
        l.onStep(Step.UNPACK);
        List<File> apks;
        try {
            apks = BundleInput.flatten(files, unpackDir, new Progress() {
                @Override
                public void onBytes(long done, long total) {
                    l.onProgress(done, total);
                }
            }, cancel);
        } catch (java.util.zip.ZipException e) {
            throw new Outcome.Failure(Outcome.INCOMPLETE_FILES, "not a readable bundle: " + e, e);
        }
        l.onStep(Step.CHECK);
        BundleInput.Bundle bundle;
        try {
            bundle = BundleInput.classify(apks);
        } catch (java.util.zip.ZipException e) {
            throw new Outcome.Failure(Outcome.INCOMPLETE_FILES, "an apk does not open: " + e, e);
        } catch (IOException e) {
            throw new Outcome.Failure(Outcome.INCOMPLETE_FILES, e.getMessage(), e);
        }
        l.log("base: " + bundle.base.getName());
        for (File s : bundle.splits) {
            l.log("split: " + s.getName());
        }

        MiniAxml.ManifestInfo info;
        try {
            info = MiniAxml.read(Graft.readEntry(bundle.base, Pins.MANIFEST_ENTRY));
        } catch (IOException e) {
            throw new Outcome.Failure(Outcome.NOT_WAZE, "manifest unreadable: " + e.getMessage(), e);
        }
        l.log("input manifest: " + info);
        if (!Pins.WAZE_PACKAGE.equals(info.pkg)) {
            throw new Outcome.Failure(Outcome.NOT_WAZE, "package " + info.pkg);
        }
        if (info.versionCode != Pins.WAZE_VERSION_CODE) {
            throw new Outcome.Failure(Outcome.WRONG_VERSION, "versionCode " + info.versionCode
                    + " (" + info.versionName + "), need " + Pins.WAZE_VERSION_CODE);
        }
        List<String> dexes = Graft.dexNames(bundle.base);
        if (dexes.size() != Pins.BASE_DEX_COUNT) {
            throw new Outcome.Failure(Outcome.MODIFIED_FILES, dexes.size() + " dexes, expected "
                    + Pins.BASE_DEX_COUNT + " (already modified?)");
        }
        if (!hasNativeCode(bundle, abis)) {
            throw new Outcome.Failure(Outcome.MISSING_NATIVE, "no native split for " + join(abis));
        }
        return bundle;
    }

    /**
     * Build the signed apks into outDir (base.apk first, then the splits under their own names).
     * work holds the unpacked input and the unsigned graft; the caller deletes it.
     */
    public static List<File> build(List<File> files, File work, File outDir, Assets assets,
            String[] abis, final Listener l, final CancelToken cancel) throws IOException {
        BundleInput.Bundle bundle = inspect(files, new File(work, "unpacked"), abis, l, cancel);

        Graft.PatchSet ps = new Graft.PatchSet()
                .replaceEntry(Pins.MANIFEST_ENTRY, assets.read(Pins.patchAsset(Pins.MANIFEST_ASSET)));
        for (String dex : Pins.HOOK_DEXES) {
            ps.replaceEntry(dex, assets.read(Pins.patchAsset(dex)));
        }
        ps.replaceIfPresent(Pins.ICON_PATH, assets.read(Pins.patchAsset(Pins.ICON_ASSET)))
                .appendEntry(Pins.PAYLOAD_DEX, assets.read(Pins.patchAsset(Pins.PAYLOAD_DEX)));

        l.onStep(Step.GRAFT);
        final int entries;
        try (ZipFile z = new ZipFile(bundle.base)) {
            entries = z.size();
        }
        final int[] tick = {0};
        File grafted = new File(work, "grafted-base.apk");
        Graft.graft(bundle.base, grafted, ps, new Graft.Progress() {
            @Override
            public void onEntry(String name) {
                cancel.check();
                l.onProgress(++tick[0], entries);
            }

            @Override
            public void onWarning(String message) {
                l.log("warning: " + message);
            }
        });
        l.log("grafted base: " + grafted.length() + " bytes");

        // Every apk of a split install must carry the same signer, so the splits are re-signed too.
        l.onStep(Step.SIGN);
        byte[] p12 = assets.read(Pins.P12_ASSET);
        long total = grafted.length();
        for (File s : bundle.splits) {
            total += s.length();
        }
        List<File> out = new ArrayList<File>();
        long done = 0;
        cancel.check();
        File signedBase = new File(outDir, "base.apk");
        sign(grafted, signedBase, p12);
        out.add(signedBase);
        done += grafted.length();
        l.onProgress(done, total);
        for (File split : bundle.splits) {
            cancel.check();
            File signed = new File(outDir, split.getName());
            sign(split, signed, p12);
            out.add(signed);
            done += split.length();
            l.onProgress(done, total);
            l.log("signed " + split.getName());
        }
        grafted.delete();
        return out;
    }

    private static void sign(File in, File out, byte[] p12) throws IOException {
        try {
            Signing.sign(in, out, p12, Pins.P12_PASSWORD);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new Outcome.Failure(Outcome.INTERNAL, "signing " + in.getName() + ": " + e, e);
        }
    }

    /** A split named for one of the device ABIs (config.arm64_v8a.apk, split_config.arm64_v8a.apk),
     *  or native libs in the base itself. */
    static boolean hasNativeCode(BundleInput.Bundle bundle, String[] abis) throws IOException {
        for (File s : bundle.splits) {
            String n = s.getName().toLowerCase();
            for (String abi : abis) {
                if (n.contains("config." + abi.toLowerCase().replace('-', '_') + ".apk")) {
                    return true;
                }
            }
        }
        try (ZipFile z = new ZipFile(bundle.base)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                String n = e.nextElement().getName();
                for (String abi : abis) {
                    if (n.startsWith("lib/" + abi + "/")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
