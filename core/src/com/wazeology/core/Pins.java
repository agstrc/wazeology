package com.wazeology.core;

/**
 * Pinned constants shared by both build targets (the installer on the phone, BuildWaze on the host)
 * and the host build gates.
 *
 * The patch is compiled against one exact Waze build (the smali anchors and the swapped-in dexes are
 * deterministic only for it), so the build refuses any other input. Keep these values in sync with
 * scripts/lib.sh (WAZE_VERSION, WAZE_VERSION_CODE) and docker/Dockerfile; scripts/build-assets.sh greps both
 * sides and fails the build when they drift.
 */
public final class Pins {

    private Pins() {}

    /** The only Waze version the patch assets are built for. */
    public static final String WAZE_PACKAGE = "com.waze";
    public static final int WAZE_VERSION_CODE = 1030730;
    public static final String WAZE_VERSION_NAME = "5.24.0.2";

    /** Size of the pinned arm64 XAPK on APKPure in MiB, rounded (190,651,245 bytes), shown to riders
     *  before they download. FetchWaze fails when the advertised size no longer rounds to it. */
    public static final int WAZE_DOWNLOAD_MB = 182;

    /** Wazeology's support floor: Android 12L (API 32). A deliberate choice, not Waze's: Waze 5.24.0.2
     *  itself declares minSdk 29. The installer refuses older phones. */
    public static final int MIN_SDK = 32;

    /** The pristine base.apk holds classes.dex .. classes10.dex; the payload dex is appended as the next
     *  contiguous index (ART loads every classesN.dex from the base APK, so no gaps are allowed). */
    public static final int BASE_DEX_COUNT = 10;
    public static final String PAYLOAD_DEX = "classes11.dex";

    /** Hook dexes the patch replaces wholesale. classes5.dex carries the FreeMapAppActivity startup
     *  hook, classes7.dex the four NavigationInfoNativeManager nav hooks. */
    public static final String[] HOOK_DEXES = {"classes5.dex", "classes7.dex"};

    /** Orphan resource whose file bytes the launcher-icon overwrite replaces (mipmap/launch_icon_round,
     *  AndResGuard-shortened path). Overwriting the content of an existing res/* entry never touches
     *  resources.arsc, which is the whole point of the golden rule. */
    public static final String ICON_PATH = "res/gBz.xml";

    public static final String MANIFEST_ENTRY = "AndroidManifest.xml";
    public static final String ARSC_ENTRY = "resources.arsc";

    /** Baked asset names: build/assets on the host, packed as-is into the installer app. */
    public static final String P12_ASSET = "signing/wazeology.p12";
    public static final String PATCH_ASSET_DIR = "patches";
    public static final String MANIFEST_ASSET = "manifest.bin";
    public static final String ICON_ASSET = "icon.bin";

    /** PKCS12 credentials for the key that signs the patched Waze on the phone. */
    public static final char[] P12_PASSWORD = "wazeology".toCharArray();
    public static final String P12_ALIAS = "wazeology";

    /** Signing floor for the patched apks (v2+v3 blocks, no v1). The installer requires API 32
     *  (MIN_SDK), so nothing lower ever has to verify these signatures. */
    public static final int SIGN_MIN_SDK = 28;

    public static String patchAsset(String name) {
        return PATCH_ASSET_DIR + "/" + name;
    }
}
