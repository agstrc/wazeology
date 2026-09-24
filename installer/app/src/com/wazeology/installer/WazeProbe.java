package com.wazeology.installer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.wazeology.core.Pins;
import com.wazeology.installer.core.UiModel;

import java.util.Arrays;

/**
 * Which Waze the phone has, checked before anything is downloaded so a rider with the Play Store Waze
 * learns up front that it must go (Android refuses to replace it: a different signer, and usually a
 * newer version too). "Ours" means signed with the key this installer embeds.
 */
final class WazeProbe {

    static final class Result {
        final UiModel.Waze waze;
        final String versionName;
        final long versionCode;

        Result(UiModel.Waze waze, String versionName, long versionCode) {
            this.waze = waze;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }

    private WazeProbe() {}

    @SuppressWarnings("deprecation")
    static Result probe(Context c, byte[] ourCert) {
        PackageManager pm = c.getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageInfo(Pins.WAZE_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return otherProfile(pm, ourCert);
        }
        long vc = versionCode(info);
        if ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return new Result(UiModel.Waze.SYSTEM_IMAGE, info.versionName, vc);
        }
        if (!isOurs(pm, ourCert)) {
            return new Result(UiModel.Waze.OTHER_SIGNER, info.versionName, vc);
        }
        UiModel.Waze w = vc == Pins.WAZE_VERSION_CODE ? UiModel.Waze.OURS_CURRENT
                : vc < Pins.WAZE_VERSION_CODE ? UiModel.Waze.OURS_OLDER : UiModel.Waze.OURS_NEWER;
        return new Result(w, info.versionName, vc);
    }

    /** Not installed for this user; a copy in another profile (work profile, Secure Folder, dual
     *  apps) still blocks an install when it has another signer. */
    private static Result otherProfile(PackageManager pm, byte[] ourCert) {
        try {
            int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0);
            PackageInfo info = pm.getPackageInfo(Pins.WAZE_PACKAGE, flags);
            // Known to the system but not installed for this user: it lives in another profile (or
            // was removed keeping its data). Another signer there still blocks our install.
            if ((info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0
                    && !signedBy(info, ourCert)) {
                return new Result(UiModel.Waze.OTHER_PROFILE, info.versionName, versionCode(info));
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // not anywhere on the device
        }
        return new Result(UiModel.Waze.NONE, null, 0);
    }

    @SuppressWarnings("deprecation")
    private static boolean isOurs(PackageManager pm, byte[] ourCert) {
        if (ourCert == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return pm.hasSigningCertificate(Pins.WAZE_PACKAGE, ourCert, PackageManager.CERT_INPUT_RAW_X509);
            }
            PackageInfo info = pm.getPackageInfo(Pins.WAZE_PACKAGE, PackageManager.GET_SIGNATURES);
            for (Signature s : info.signatures) {
                if (Arrays.equals(s.toByteArray(), ourCert)) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // treat as not ours
        }
        return false;
    }

    private static boolean signedBy(PackageInfo info, byte[] cert) {
        if (cert == null || Build.VERSION.SDK_INT < 28 || info.signingInfo == null) {
            return false;
        }
        Signature[] signers = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        if (signers == null) {
            return false;
        }
        for (Signature s : signers) {
            if (Arrays.equals(s.toByteArray(), cert)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
}
