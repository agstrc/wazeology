package com.wazeology.core;

import java.io.IOException;

/**
 * Every result a build or an install can end with, success or failure, with the one action that
 * recovers from it. The installer maps each value to plain-language text (en + pt-BR) and the raw
 * cause only goes to its details log; BuildWaze on the host prints the name and the cause.
 */
public enum Outcome {
    // successes
    PREPARED(true, Action.NONE),
    INSTALLED(true, Action.OPEN_WAZEOLOGY),
    UNINSTALLED(true, Action.NONE),
    EXPORTED(true, Action.NONE),

    // preparing (download + build)
    INTERRUPTED(false, Action.NONE),
    NETWORK(false, Action.RETRY_PREPARE),
    SERVER(false, Action.RETRY_PREPARE),
    NOT_LISTED(false, Action.PICK_FILES),
    NO_MATCHING_DOWNLOAD(false, Action.PICK_FILES),
    CORRUPT_DOWNLOAD(false, Action.RETRY_PREPARE),
    NOT_WAZE(false, Action.PICK_FILES),
    WRONG_VERSION(false, Action.REDOWNLOAD),
    INCOMPLETE_FILES(false, Action.PICK_FILES),
    MISSING_NATIVE(false, Action.PICK_FILES),
    MODIFIED_FILES(false, Action.REDOWNLOAD),
    STORAGE(false, Action.FREE_SPACE),
    INTERNAL(false, Action.RETRY_PREPARE),
    EXPORT_FAILED(false, Action.EXPORT),

    // installing
    NEED_INSTALL_PERMISSION(false, Action.ALLOW_INSTALLS),
    INSTALL_CANCELLED(false, Action.INSTALL),
    PLAY_PROTECT(false, Action.INSTALL),
    INSTALL_BLOCKED(false, Action.OPEN_SECURITY_SETTINGS),
    REMOVE_CURRENT(false, Action.UNINSTALL_WAZE),
    NEWER_INSTALLED(false, Action.UNINSTALL_WAZE),
    DAMAGED_BUILD(false, Action.REBUILD),
    MISSING_PART(false, Action.REDOWNLOAD),
    WRONG_DEVICE(false, Action.REDOWNLOAD),
    ANDROID_TOO_OLD(false, Action.NONE),
    RESTRICTED(false, Action.OPEN_SECURITY_SETTINGS),
    INSTALL_FAILED(false, Action.INSTALL),
    NO_CONFIRM(false, Action.INSTALL),

    // uninstalling
    UNINSTALL_CANCELLED(false, Action.NONE),
    UNINSTALL_FAILED(false, Action.UNINSTALL_WAZE);

    /** The recovery the result banner offers. */
    public enum Action {
        NONE,
        RETRY_PREPARE,
        PICK_FILES,
        INSTALL,
        UNINSTALL_WAZE,
        FREE_SPACE,
        ALLOW_INSTALLS,
        OPEN_WAZEOLOGY,
        REBUILD,
        REDOWNLOAD,
        OPEN_SECURITY_SETTINGS,
        EXPORT
    }

    public final boolean success;
    public final Action action;

    Outcome(boolean success, Action action) {
        this.success = success;
        this.action = action;
    }

    /** A failure carrying the Outcome the rider sees plus a technical detail for the log. */
    public static final class Failure extends IOException {
        public final Outcome outcome;

        public Failure(Outcome outcome, String detail) {
            super(detail);
            this.outcome = outcome;
        }

        public Failure(Outcome outcome, String detail, Throwable cause) {
            super(detail, cause);
            this.outcome = outcome;
        }
    }

    /** Best-effort mapping of an unexpected exception: out-of-space is recognizable by its message,
     *  everything else is an internal error. */
    public static Outcome of(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof Failure) {
                return ((Failure) c).outcome;
            }
            String m = c.getMessage();
            if (m != null && (m.contains("ENOSPC") || m.contains("No space left"))) {
                return STORAGE;
            }
        }
        return INTERNAL;
    }
}
