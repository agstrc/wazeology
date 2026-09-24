package com.wazeology.installer.core;

import com.wazeology.core.Outcome;

/**
 * Classifies a PackageInstaller result into the {@link Outcome} the rider sees. The legacy status
 * (PackageManager.INSTALL_FAILED_*, delivered as EXTRA_LEGACY_STATUS) is the most precise signal,
 * then the "INSTALL_FAILED_*:" prefix of the status message, then the coarse public status. The
 * coarse status alone is misleading: a newer Play Store Waze fails as a version downgrade, which
 * surfaces as STATUS_FAILURE_INVALID, and a user cancel shares STATUS_FAILURE_ABORTED with a Play
 * Protect rejection.
 *
 * The constants are copied (stable AOSP values) so this stays free of android.* and host-testable.
 */
public final class InstallStatus {

    private InstallStatus() {}

    // PackageInstaller.STATUS_*
    public static final int STATUS_PENDING_USER_ACTION = -1;
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_FAILURE = 1;
    public static final int STATUS_FAILURE_BLOCKED = 2;
    public static final int STATUS_FAILURE_ABORTED = 3;
    public static final int STATUS_FAILURE_INVALID = 4;
    public static final int STATUS_FAILURE_CONFLICT = 5;
    public static final int STATUS_FAILURE_STORAGE = 6;
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;
    public static final int STATUS_FAILURE_TIMEOUT = 8;

    // PackageManager legacy INSTALL_* codes (hidden from android.jar since API 26)
    static final int INSTALL_SUCCEEDED = 1;
    static final int INVALID_APK = -2;
    static final int INSUFFICIENT_STORAGE = -4;
    static final int UPDATE_INCOMPATIBLE = -7;
    static final int SHARED_USER_INCOMPATIBLE = -8;
    static final int OLDER_SDK = -12;
    static final int CPU_ABI_INCOMPATIBLE = -16;
    static final int CONTAINER_ERROR = -18;
    static final int MEDIA_UNAVAILABLE = -20;
    static final int VERIFICATION_TIMEOUT = -21;
    static final int VERIFICATION_FAILURE = -22;
    static final int VERSION_DOWNGRADE = -25;
    static final int MISSING_SPLIT = -28;
    static final int PARSE_FAILED_FIRST = -100; // -100 .. -109: the apk does not parse
    static final int PARSE_FAILED_LAST = -109;
    static final int INTERNAL_ERROR = -110;
    static final int USER_RESTRICTED = -111;
    static final int NO_MATCHING_ABIS = -113;
    static final int ABORTED = -115;
    static final int BAD_DEX_METADATA = -117;
    static final int BAD_SIGNATURE = -118;

    public static Outcome classify(int status, int legacy, String message) {
        if (status == STATUS_SUCCESS) {
            return Outcome.INSTALLED;
        }
        Outcome p = byLegacy(legacy);
        if (p == null) {
            p = byMessage(message);
        }
        if (p == null) {
            p = byStatus(status);
        }
        return p;
    }

    /** Uninstall results only distinguish success, a user cancel and everything else. */
    public static Outcome classifyUninstall(int status) {
        if (status == STATUS_SUCCESS) {
            return Outcome.UNINSTALLED;
        }
        return status == STATUS_FAILURE_ABORTED ? Outcome.UNINSTALL_CANCELLED : Outcome.UNINSTALL_FAILED;
    }

    private static Outcome byLegacy(int legacy) {
        switch (legacy) {
            case ABORTED:
                return Outcome.INSTALL_CANCELLED;
            case VERIFICATION_FAILURE:
            case VERIFICATION_TIMEOUT:
                return Outcome.PLAY_PROTECT;
            case UPDATE_INCOMPATIBLE:
            case SHARED_USER_INCOMPATIBLE:
                return Outcome.REMOVE_CURRENT;
            case VERSION_DOWNGRADE:
                return Outcome.NEWER_INSTALLED;
            case INSUFFICIENT_STORAGE:
            case CONTAINER_ERROR:
            case MEDIA_UNAVAILABLE:
                return Outcome.STORAGE;
            case MISSING_SPLIT:
                return Outcome.MISSING_PART;
            case CPU_ABI_INCOMPATIBLE:
            case NO_MATCHING_ABIS:
                return Outcome.WRONG_DEVICE;
            case OLDER_SDK:
                return Outcome.ANDROID_TOO_OLD;
            case USER_RESTRICTED:
                return Outcome.RESTRICTED;
            case INVALID_APK:
            case BAD_DEX_METADATA:
            case BAD_SIGNATURE:
                return Outcome.DAMAGED_BUILD;
            case INTERNAL_ERROR:
                return Outcome.INSTALL_FAILED;
            default:
                if (legacy <= PARSE_FAILED_FIRST && legacy >= PARSE_FAILED_LAST) {
                    return Outcome.DAMAGED_BUILD;
                }
                return null;
        }
    }

    private static Outcome byMessage(String message) {
        if (message == null) {
            return null;
        }
        if (message.startsWith("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return Outcome.NEWER_INSTALLED;
        }
        if (message.startsWith("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            return Outcome.REMOVE_CURRENT;
        }
        if (message.startsWith("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return Outcome.STORAGE;
        }
        if (message.startsWith("INSTALL_FAILED_MISSING_SPLIT")) {
            return Outcome.MISSING_PART;
        }
        if (message.startsWith("INSTALL_FAILED_USER_RESTRICTED")) {
            return Outcome.RESTRICTED;
        }
        if (message.startsWith("INSTALL_FAILED_VERIFICATION_FAILURE")) {
            return Outcome.PLAY_PROTECT;
        }
        if (message.startsWith("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return Outcome.WRONG_DEVICE;
        }
        if (message.startsWith("INSTALL_PARSE_FAILED")) {
            return Outcome.DAMAGED_BUILD;
        }
        return null;
    }

    private static Outcome byStatus(int status) {
        switch (status) {
            case STATUS_FAILURE_ABORTED:
                return Outcome.INSTALL_CANCELLED;
            case STATUS_FAILURE_BLOCKED:
                return Outcome.INSTALL_BLOCKED;
            case STATUS_FAILURE_CONFLICT:
                return Outcome.REMOVE_CURRENT;
            case STATUS_FAILURE_INVALID:
                return Outcome.DAMAGED_BUILD;
            case STATUS_FAILURE_STORAGE:
                return Outcome.STORAGE;
            case STATUS_FAILURE_INCOMPATIBLE:
                return Outcome.WRONG_DEVICE;
            default:
                return Outcome.INSTALL_FAILED;
        }
    }
}
