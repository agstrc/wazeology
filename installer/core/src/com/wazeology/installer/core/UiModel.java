package com.wazeology.installer.core;

/**
 * What the installer screen shows, derived from facts every time anything changes, never stored.
 * The facts come from disk (cached download, finished build), the package manager (which Waze is
 * installed), the running job and a few persisted install flags; this class only decides. It is
 * pure so the host gate can check the decisions table without a device.
 */
public final class UiModel {

    /** Which Waze the phone has right now. */
    public enum Waze {
        NONE,
        /** Waze with Wazeology at the pinned version. */
        OURS_CURRENT,
        /** Waze with Wazeology at an older version: installing updates it in place. */
        OURS_OLDER,
        /** Waze with Wazeology at a newer version: Android refuses a downgrade. */
        OURS_NEWER,
        /** The Play Store (or any other) Waze: a different signer, so it must be removed first. */
        OTHER_SIGNER,
        /** Waze preinstalled with the system: removing it only strips its updates. */
        SYSTEM_IMAGE,
        /** Not installed for this user but present in another profile (work, Secure Folder). */
        OTHER_PROFILE
    }

    public static final class Facts {
        public boolean sdkOk = true;
        public boolean abiOk = true;
        public JobState.Snapshot job = JobState.IDLE;
        public boolean sourceReady;
        public long partialBytes;
        public long partialTotal;
        public boolean buildReady;
        public Waze waze = Waze.NONE;
        /** An install was handed to Android and waits for the rider's confirmation. */
        public boolean installCommitted;
        /** The confirmation screen can be shown again (its intent is held and not on screen). */
        public boolean confirmAvailable;
        /** Android is working on the committed session (installing, or its confirmation is up). */
        public boolean installWorking;
        public boolean uninstallPending;
    }

    public enum Prep { NOT_STARTED, PARTIAL, SOURCE_READY, READY, RUNNING }

    public enum PrepButton { PREPARE, CONTINUE, CANCEL, NONE }

    public enum InstallBlock { NONE, NOT_READY, REMOVE_CURRENT, SYSTEM_WAZE, OTHER_PROFILE }

    public enum InstallButton { INSTALL, UPDATE, REINSTALL, CONFIRM, WAITING, RESTART, CANCEL }

    public boolean supported;
    public Prep prep;
    public PrepButton prepButton;
    public boolean prepEnabled;
    public InstallBlock installBlock;
    public InstallButton installButton;
    public boolean installEnabled;
    public boolean installRunning;
    public boolean showWazeCard;
    public boolean uninstallVisible;
    public boolean uninstallEnabled;
    /** Removing the current Waze is the next required step. */
    public boolean uninstallEmphasized;
    public boolean showOpenWazeology;
    public boolean pickEnabled;
    public boolean exportEnabled;
    public boolean clearEnabled;
    public boolean keepScreenOn;

    public static UiModel derive(Facts f) {
        UiModel m = new UiModel();
        JobState.Kind kind = f.job.kind;
        boolean running = kind != null;
        boolean prepJob = kind == JobState.Kind.PREPARE || kind == JobState.Kind.PICK;
        m.supported = f.sdkOk && f.abiOk;
        m.keepScreenOn = running;

        if (prepJob) {
            m.prep = Prep.RUNNING;
            m.prepButton = PrepButton.CANCEL;
            m.prepEnabled = !f.job.cancelling;
        } else {
            if (f.buildReady) {
                m.prep = Prep.READY;
                m.prepButton = PrepButton.NONE;
            } else if (f.sourceReady) {
                m.prep = Prep.SOURCE_READY;
                m.prepButton = PrepButton.CONTINUE;
            } else if (f.partialBytes > 0) {
                m.prep = Prep.PARTIAL;
                m.prepButton = PrepButton.CONTINUE;
            } else {
                m.prep = Prep.NOT_STARTED;
                m.prepButton = PrepButton.PREPARE;
            }
            m.prepEnabled = m.supported && !running;
        }

        m.installBlock = InstallBlock.NONE;
        if (kind == JobState.Kind.INSTALL) {
            m.installRunning = true;
            m.installButton = InstallButton.CANCEL;
            m.installEnabled = !f.job.cancelling;
        } else if (f.installCommitted) {
            if (f.confirmAvailable) {
                m.installButton = InstallButton.CONFIRM;
                m.installEnabled = !running;
            } else if (f.installWorking) {
                m.installButton = InstallButton.WAITING;
                m.installEnabled = false;
            } else {
                m.installButton = InstallButton.RESTART;
                m.installEnabled = !running && f.buildReady;
            }
        } else {
            m.installButton = InstallButton.INSTALL;
            if (!f.buildReady) {
                m.installBlock = InstallBlock.NOT_READY;
            } else {
                switch (f.waze) {
                    case SYSTEM_IMAGE:
                        m.installBlock = InstallBlock.SYSTEM_WAZE;
                        break;
                    case OTHER_SIGNER:
                    case OURS_NEWER:
                        m.installBlock = InstallBlock.REMOVE_CURRENT;
                        break;
                    case OTHER_PROFILE:
                        m.installBlock = InstallBlock.OTHER_PROFILE;
                        break;
                    case OURS_CURRENT:
                        m.installButton = InstallButton.REINSTALL;
                        break;
                    case OURS_OLDER:
                        m.installButton = InstallButton.UPDATE;
                        break;
                    default:
                        break;
                }
            }
            m.installEnabled = m.supported && !running && !f.uninstallPending
                    && m.installBlock == InstallBlock.NONE;
        }

        m.showWazeCard = f.waze != Waze.NONE;
        m.uninstallVisible = f.waze == Waze.OTHER_SIGNER || f.waze == Waze.OURS_CURRENT
                || f.waze == Waze.OURS_OLDER || f.waze == Waze.OURS_NEWER;
        m.uninstallEnabled = m.uninstallVisible && !running && !f.uninstallPending && !f.installCommitted;
        m.uninstallEmphasized = f.waze == Waze.OTHER_SIGNER || f.waze == Waze.OURS_NEWER;
        m.showOpenWazeology = f.waze == Waze.OURS_CURRENT && !running;

        m.pickEnabled = m.supported && !running;
        m.exportEnabled = f.buildReady && !running;
        m.clearEnabled = !running && !f.installCommitted
                && (f.sourceReady || f.buildReady || f.partialBytes > 0);
        return m;
    }
}
