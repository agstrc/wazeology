package com.wazeology.installer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;
import com.wazeology.installer.core.JobState;
import com.wazeology.installer.core.UiModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wazeology Installer: prepares Waze with Wazeology on the phone (download + build, one action) and
 * installs it (a separate action), removing a conflicting Waze first when needed.
 *
 * The activity holds no progress state. It renders {@link UiModel#derive} over facts from
 * {@link JobStore} (cached files, the running job, persisted install flags) and a probe of the
 * installed Waze, so rotation, dark mode, a locale change or a process restart just re-render. Every
 * long job runs in {@link JobService}; leaving the screen never stops one.
 *
 * UI is fully programmatic (no layout XML, no AppCompat), in the same Material 3 style as the
 * Wazeology screen.
 */
public class InstallerActivity extends Activity implements JobStore.Listener {

    private static final int REQ_PICK = 1;
    private static final int REQ_EXPORT = 2;
    private static final int REQ_NOTIFICATIONS = 3;
    private static final long WAZE_BYTES = 190L << 20;
    private static final long CONTINUE_WINDOW_MS = 10 * 60 * 1000L;

    private JobStore store;
    private Palette palette;
    private Texts tx;
    private WazeProbe.Result waze;
    private boolean probedWithCert;
    private BroadcastReceiver packageReceiver;
    private AlertDialog dialog;
    private boolean detailsOpen;
    private UiModel model;

    // views, rebuilt on every configuration change
    private LinearLayout banner;
    private TextView bannerTitle;
    private TextView bannerBody;
    private Button bannerAction;
    private LinearLayout unsupportedCard;
    private TextView unsupportedBody;
    private LinearLayout wazeCard;
    private TextView wazeBody;
    private Button uninstallButton;
    private TextView prepStatus;
    private ProgressBlock prepProgress;
    private Button prepButton;
    private Button pickButton;
    private TextView installStatus;
    private ProgressBlock installProgress;
    private Button installButton;
    private TextView installHint;
    private Button openWazeologyButton;
    private TextView storageText;
    private Button deleteButton;
    private Button exportButton;
    private ProgressBlock moreProgress;
    private Button detailsToggle;
    private LinearLayout detailsBox;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = JobStore.get(this);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.visible++;
        store.addListener(this);
        Notifications.cancelResult(this);
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                probe();
                render();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(Pins.WAZE_PACKAGE, PatternMatcher.PATTERN_LITERAL);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.uninstallPending = false;
        store.onActivityResumed(this);
        // Back from Android's confirmation: if no result follows, it was closed unanswered, so offer
        // it again rather than waiting forever.
        store.main.removeCallbacks(confirmLeftCheck);
        store.main.postDelayed(confirmLeftCheck, JobStore.CONFIRM_LEFT_MS);
        probe();
        continueAfterSettings();
        render();
    }

    private final Runnable confirmLeftCheck = new Runnable() {
        @Override
        public void run() {
            store.onConfirmMaybeLeft();
        }
    };

    @Override
    protected void onPause() {
        store.main.removeCallbacks(confirmLeftCheck);
        store.onActivityPaused(this);
        super.onPause();
    }

    @Override
    protected void onStop() {
        store.visible--;
        store.removeListener(this);
        if (packageReceiver != null) {
            unregisterReceiver(packageReceiver);
            packageReceiver = null;
        }
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        buildUi();
        render();
    }

    @Override
    public void onChanged() {
        if (!store.job.snapshot().running() || (!probedWithCert && store.ourCert() != null)) {
            probe();
        }
        render();
    }

    /** Until the embedded certificate is loaded, "ours" can't be told from "another Waze", so the
     *  Waze card stays hidden rather than wrongly asking the rider to remove their own install. */
    private void probe() {
        byte[] cert = store.ourCert();
        probedWithCert = cert != null;
        waze = cert == null ? null : WazeProbe.probe(this, cert);
    }

    // ---- actions ---------------------------------------------------------------------------------

    private void onPrepareClicked() {
        if (model != null && model.prepButton == UiModel.PrepButton.CANCEL) {
            store.job.cancel();
            store.changedNow();
            return;
        }
        startPrepare(null);
    }

    /** extra: "rebuild" drops the finished build first, "redownload" drops the download too. */
    private void startPrepare(final String extra) {
        UiModel.Facts f = store.facts(waze);
        JobStore.Disk d = store.disk();
        long need = 50L << 20;
        boolean download = !f.sourceReady || "redownload".equals(extra);
        if (download) {
            need += f.partialTotal > 0 ? f.partialTotal - f.partialBytes : WAZE_BYTES;
        }
        if (!f.buildReady || extra != null) {
            long src = d.source != null ? d.source.bytes : WAZE_BYTES;
            need += src * 2 + (100L << 20); // unpacked copy + signed build + the unsigned graft
        }
        if (!ensureSpace(need)) {
            return;
        }
        askNotificationsOnce();
        AndroidNetworkGate net = new AndroidNetworkGate(this);
        if (download && net.isMetered()) {
            long remaining = f.partialTotal > 0 ? f.partialTotal - f.partialBytes : WAZE_BYTES;
            showDialog(new AlertDialog.Builder(this, dialogTheme())
                    .setTitle(tx.meteredTitle())
                    .setMessage(tx.meteredBody(remaining))
                    .setPositiveButton(tx.downloadAnyway(), (dlg, w) -> launchPrepare(extra))
                    .setNegativeButton(tx.notNow(), null));
            return;
        }
        launchPrepare(extra);
    }

    private void launchPrepare(String extra) {
        Intent i = new Intent(this, JobService.class).putExtra(JobService.EXTRA_KIND,
                JobState.Kind.PREPARE.name());
        if (extra != null) {
            i.putExtra(JobService.EXTRA_RESET, extra);
        }
        startForegroundService(i);
    }

    private void onPickClicked() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            store.log("no file picker: " + e);
        }
    }

    private void onInstallClicked() {
        if (model == null) {
            return;
        }
        switch (model.installButton) {
            case CANCEL:
                store.job.cancel();
                store.changedNow();
                return;
            case CONFIRM:
                if (store.launchConfirm(this)) {
                    return;
                }
                InstallSession.abandonCommitted(store);
                startInstall();
                return;
            case RESTART:
                InstallSession.abandonCommitted(store);
                startInstall();
                return;
            default:
                startInstall();
        }
    }

    private void startInstall() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            showDialog(new AlertDialog.Builder(this, dialogTheme())
                    .setTitle(tx.allowInstallsTitle())
                    .setMessage(tx.allowInstallsBody())
                    .setPositiveButton(tx.openSettings(), (dlg, w) -> openInstallSettings())
                    .setNegativeButton(tx.notNow(), null));
            return;
        }
        List<File> build = store.currentBuild();
        long buildBytes = 0;
        if (build != null) {
            for (File f : build) {
                buildBytes += f.length();
            }
        }
        // Android stages a full copy, then installs and optimizes it next to the staged one.
        if (!ensureSpace(buildBytes * 3 / 2 + (100L << 20))) {
            return;
        }
        askNotificationsOnce();
        JobService.start(this, JobState.Kind.INSTALL, null, null);
    }

    private void openInstallSettings() {
        store.prefs.edit().putString(JobStore.K_CONTINUE, "install")
                .putLong(JobStore.K_CONTINUE_AT, System.currentTimeMillis()).apply();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            store.log("no install-permission screen: " + e);
        }
    }

    /** Back from the "Install unknown apps" screen: carry on with the install the rider asked for.
     *  Persisted, because some Android versions restart the app when that toggle changes. */
    private void continueAfterSettings() {
        String pending = store.prefs.getString(JobStore.K_CONTINUE, null);
        if (pending == null) {
            return;
        }
        long at = store.prefs.getLong(JobStore.K_CONTINUE_AT, 0);
        store.prefs.edit().remove(JobStore.K_CONTINUE).remove(JobStore.K_CONTINUE_AT).apply();
        if (System.currentTimeMillis() - at > CONTINUE_WINDOW_MS || store.job.snapshot().running()) {
            return;
        }
        if (getPackageManager().canRequestPackageInstalls()) {
            startInstall();
        } else {
            store.setOutcome(Outcome.NEED_INSTALL_PERMISSION, "install permission still off", 0);
        }
    }

    private void onUninstallClicked() {
        showDialog(new AlertDialog.Builder(this, dialogTheme())
                .setTitle(tx.removeConfirmTitle())
                .setMessage(tx.removeConfirmBody())
                .setPositiveButton(tx.remove(), (dlg, w) -> {
                    store.uninstallPending = true;
                    store.clearOutcome();
                    try {
                        InstallSession.uninstall(this);
                    } catch (RuntimeException e) {
                        store.onUninstallResult(Outcome.UNINSTALL_FAILED, String.valueOf(e));
                    }
                    render();
                })
                .setNegativeButton(tx.cancel(), null));
    }

    private void openWazeology() {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(Pins.WAZE_PACKAGE, "com.waze.wazeology.WazeologyActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(Pins.WAZE_PACKAGE);
            if (launch != null) {
                startActivity(launch);
            }
        }
    }

    private void onDeleteClicked() {
        showDialog(new AlertDialog.Builder(this, dialogTheme())
                .setTitle(tx.deleteConfirmTitle())
                .setMessage(tx.deleteConfirmBody())
                .setPositiveButton(tx.delete(), (dlg, w) ->
                        JobService.start(this, JobState.Kind.CLEAR, null, null))
                .setNegativeButton(tx.cancel(), null));
    }

    private void onExportClicked() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            store.log("no folder picker: " + e);
        }
    }

    private void onBannerAction(Outcome.Action a) {
        switch (a) {
            case RETRY_PREPARE:
                startPrepare(null);
                break;
            case PICK_FILES:
                onPickClicked();
                break;
            case INSTALL:
                startInstall();
                break;
            case UNINSTALL_WAZE:
                onUninstallClicked();
                break;
            case FREE_SPACE:
                openSettings(StorageManager.ACTION_MANAGE_STORAGE, Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                break;
            case ALLOW_INSTALLS:
                openInstallSettings();
                break;
            case OPEN_WAZEOLOGY:
                openWazeology();
                break;
            case REBUILD:
                startPrepare("rebuild");
                break;
            case REDOWNLOAD:
                startPrepare("redownload");
                break;
            case OPEN_SECURITY_SETTINGS:
                openSettings(Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS);
                break;
            case EXPORT:
                onExportClicked();
                break;
            default:
                break;
        }
    }

    private void openSettings(String action, String fallback) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(fallback));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK) {
            ArrayList<Uri> uris = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) {
                return;
            }
            for (Uri u : uris) {
                persist(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            askNotificationsOnce();
            JobService.start(this, JobState.Kind.PICK, uris, null);
        } else if (requestCode == REQ_EXPORT && data.getData() != null) {
            persist(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            JobService.start(this, JobState.Kind.EXPORT, null, data.getData());
        }
    }

    /** Keep access to a picked document for the job, which may outlive this activity. */
    private void persist(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (RuntimeException ignored) {
            // not persistable: the grant still lasts while the app runs
        }
    }

    private boolean ensureSpace(long need) {
        long free = allocatable();
        if (free >= need) {
            return true;
        }
        store.setOutcome(Outcome.STORAGE, "needs " + need + " bytes, " + free + " allocatable", need - free);
        return false;
    }

    private long allocatable() {
        File dir = getNoBackupFilesDir();
        try {
            StorageManager sm = getSystemService(StorageManager.class);
            return sm.getAllocatableBytes(sm.getUuidForPath(dir));
        } catch (IOException | RuntimeException e) {
            return dir.getUsableSpace();
        }
    }

    private void askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || store.prefs.getBoolean(JobStore.K_ASKED_NOTIFICATIONS, false)) {
            return;
        }
        store.prefs.edit().putBoolean(JobStore.K_ASKED_NOTIFICATIONS, true).apply();
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    private void showDialog(AlertDialog.Builder b) {
        if (dialog != null) {
            dialog.dismiss();
        }
        dialog = b.show();
    }

    private int dialogTheme() {
        return palette.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    // ---- rendering -------------------------------------------------------------------------------

    private void render() {
        if (prepStatus == null) {
            return;
        }
        UiModel.Facts f = store.facts(waze);
        UiModel m = UiModel.derive(f);
        model = m;
        JobState.Snapshot s = f.job;
        JobStore.Disk d = store.disk();

        if (m.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        renderBanner(m);

        unsupportedCard.setVisibility(m.supported ? View.GONE : View.VISIBLE);
        if (!m.supported) {
            unsupportedBody.setText(!f.sdkOk ? tx.unsupportedAndroid(Build.VERSION.SDK_INT) : tx.unsupportedAbi());
        }

        // current Waze
        wazeCard.setVisibility(m.showWazeCard ? View.VISIBLE : View.GONE);
        if (m.showWazeCard) {
            String v = waze.versionName != null ? waze.versionName : "?";
            switch (waze.waze) {
                case OTHER_SIGNER:
                    wazeBody.setText(tx.wazeOther(v));
                    break;
                case OURS_NEWER:
                    wazeBody.setText(tx.wazeOursNewer(v));
                    break;
                case SYSTEM_IMAGE:
                    wazeBody.setText(tx.wazeSystem());
                    break;
                case OTHER_PROFILE:
                    wazeBody.setText(tx.wazeOtherProfile());
                    break;
                default:
                    wazeBody.setText(tx.wazeOurs(v));
                    break;
            }
            uninstallButton.setVisibility(m.uninstallVisible ? View.VISIBLE : View.GONE);
            styleButton(uninstallButton, m.uninstallEmphasized);
            uninstallButton.setEnabled(m.uninstallEnabled);
        }

        // step 1
        boolean prepRunning = m.prep == UiModel.Prep.RUNNING;
        switch (m.prep) {
            case PARTIAL:
                prepStatus.setText(tx.preparePartial(f.partialBytes, f.partialTotal));
                break;
            case SOURCE_READY:
                prepStatus.setText(tx.prepareSourceReady());
                break;
            case READY:
                prepStatus.setText(tx.prepareReady());
                break;
            default:
                prepStatus.setText(tx.prepareIntro());
                break;
        }
        prepStatus.setVisibility(prepRunning ? View.GONE : View.VISIBLE);
        prepStatus.setTextColor(m.prep == UiModel.Prep.READY ? palette.primary : palette.onSurfaceVariant);
        prepProgress.show(prepRunning ? s : null);
        switch (m.prepButton) {
            case CANCEL:
                prepButton.setText(s.cancelling ? tx.stopping() : tx.cancel());
                styleButton(prepButton, false);
                break;
            case CONTINUE:
                prepButton.setText(tx.continueButton());
                styleButton(prepButton, true);
                break;
            default:
                prepButton.setText(tx.prepareButton());
                styleButton(prepButton, true);
                break;
        }
        prepButton.setVisibility(m.prepButton == UiModel.PrepButton.NONE ? View.GONE : View.VISIBLE);
        prepButton.setEnabled(m.prepEnabled);
        pickButton.setVisibility(prepRunning || m.prep == UiModel.Prep.READY ? View.GONE : View.VISIBLE);
        pickButton.setEnabled(m.pickEnabled);

        // step 2
        String status;
        boolean hint = false;
        if (m.installRunning) {
            status = null;
        } else if (m.installButton == UiModel.InstallButton.CONFIRM) {
            status = tx.awaitingConfirm();
        } else if (m.installButton == UiModel.InstallButton.WAITING) {
            status = null;
        } else if (m.installButton == UiModel.InstallButton.RESTART) {
            status = tx.installInterrupted();
        } else {
            switch (m.installBlock) {
                case NOT_READY:
                    status = tx.installNotReady();
                    break;
                case REMOVE_CURRENT:
                    status = tx.installRemoveFirst();
                    break;
                case SYSTEM_WAZE:
                case OTHER_PROFILE:
                    status = tx.installBlockedSystem();
                    break;
                default:
                    if (m.showOpenWazeology) {
                        status = tx.installedBody();
                    } else if (m.installButton == UiModel.InstallButton.UPDATE) {
                        status = tx.installUpdateHint();
                    } else {
                        status = null;
                        hint = true;
                    }
                    break;
            }
        }
        installStatus.setVisibility(status == null ? View.GONE : View.VISIBLE);
        if (status != null) {
            installStatus.setText(status);
        }
        installHint.setVisibility(hint && m.installEnabled ? View.VISIBLE : View.GONE);
        if (m.installButton == UiModel.InstallButton.WAITING) {
            installProgress.showWaiting(tx.androidInstalling(), tx.androidInstallingDetail());
        } else {
            installProgress.show(m.installRunning ? s : null);
        }
        switch (m.installButton) {
            case CANCEL:
                installButton.setText(s.cancelling ? tx.stopping() : tx.cancel());
                break;
            case CONFIRM:
                installButton.setText(tx.confirmButton());
                break;
            case WAITING:
                installButton.setText(tx.installButton());
                break;
            case RESTART:
                installButton.setText(tx.restartButton());
                break;
            case UPDATE:
                installButton.setText(tx.updateButton());
                break;
            case REINSTALL:
                installButton.setText(tx.reinstallButton());
                break;
            default:
                installButton.setText(tx.installButton());
                break;
        }
        styleButton(installButton, m.installButton != UiModel.InstallButton.CANCEL
                && m.installButton != UiModel.InstallButton.REINSTALL);
        installButton.setEnabled(m.installEnabled);
        installButton.setVisibility(m.installButton == UiModel.InstallButton.WAITING ? View.GONE : View.VISIBLE);
        openWazeologyButton.setVisibility(m.showOpenWazeology ? View.VISIBLE : View.GONE);

        // more
        storageText.setText(d.bytes > 0 ? tx.storageUsed(d.bytes) : tx.storageNone());
        deleteButton.setEnabled(m.clearEnabled);
        exportButton.setEnabled(m.exportEnabled);
        boolean moreRunning = s.kind == JobState.Kind.EXPORT || s.kind == JobState.Kind.CLEAR;
        moreProgress.show(moreRunning && s.kind == JobState.Kind.EXPORT ? s : null);
        if (detailsOpen) {
            logView.setText(store.logText());
        }
    }

    private void renderBanner(UiModel m) {
        final Outcome p = store.outcome();
        if (p == null || p == Outcome.PREPARED) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
        int bg = p.success ? palette.primaryContainer : palette.errorContainer;
        int fg = p.success ? palette.onPrimaryContainer : palette.onErrorContainer;
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(16));
        banner.setBackground(g);
        bannerTitle.setTextColor(fg);
        bannerBody.setTextColor(fg);
        bannerTitle.setText((p.success ? "✓ " : "⚠ ") + tx.title(p));
        bannerBody.setText(tx.body(p, store.outcomeBytes()));
        Outcome.Action a = p.action;
        boolean show = a != Outcome.Action.NONE && actionAvailable(a, m);
        bannerAction.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            bannerAction.setText(tx.action(a));
            bannerAction.setOnClickListener(v -> onBannerAction(a));
        }
    }

    private boolean actionAvailable(Outcome.Action a, UiModel m) {
        boolean idle = !store.job.snapshot().running();
        switch (a) {
            case RETRY_PREPARE:
                return m.prepEnabled && m.prep != UiModel.Prep.READY;
            case PICK_FILES:
                return m.pickEnabled;
            case INSTALL:
                return m.installEnabled;
            case UNINSTALL_WAZE:
                return m.uninstallVisible && m.uninstallEnabled;
            case EXPORT:
                return m.exportEnabled;
            case REBUILD:
            case REDOWNLOAD:
                return idle && m.supported;
            case OPEN_WAZEOLOGY:
                return m.showOpenWazeology;
            default:
                return true;
        }
    }

    // ---- screen scaffolding ----------------------------------------------------------------------

    private void buildUi() {
        palette = new Palette(this);
        tx = new Texts(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(palette.surface);
        int pad = dp(16);
        col.setPadding(pad, dp(24), pad, dp(24));

        col.addView(headline(tx.appName()));
        TextView caption = body(tx.unofficial());
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        caption.setPadding(0, dp(2), 0, dp(16));
        col.addView(caption);

        // result banner
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(16), dp(10));
        bannerTitle = new TextView(this);
        bannerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bannerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        banner.addView(bannerTitle);
        bannerBody = new TextView(this);
        bannerBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bannerBody.setPadding(0, dp(4), 0, 0);
        banner.addView(bannerBody);
        LinearLayout bannerRow = new LinearLayout(this);
        bannerRow.setOrientation(LinearLayout.HORIZONTAL);
        bannerRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button dismiss = textButton(tx.dismiss());
        dismiss.setOnClickListener(v -> store.clearOutcome());
        bannerRow.addView(dismiss);
        bannerAction = filledButton("");
        bannerAction.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bannerRow.addView(bannerAction);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        banner.addView(bannerRow, rowLp);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.bottomMargin = dp(16);
        banner.setLayoutParams(bannerLp);
        banner.setVisibility(View.GONE);
        col.addView(banner);

        // unsupported phone
        unsupportedCard = card();
        unsupportedCard.addView(title(tx.unsupportedTitle()));
        unsupportedBody = body("");
        unsupportedCard.addView(unsupportedBody);
        col.addView(unsupportedCard);

        // current Waze
        wazeCard = card();
        wazeCard.addView(title(tx.wazeCardTitle()));
        wazeBody = body("");
        wazeCard.addView(wazeBody);
        uninstallButton = filledButton(tx.removeWaze());
        uninstallButton.setOnClickListener(v -> onUninstallClicked());
        wazeCard.addView(uninstallButton);
        col.addView(wazeCard);

        // step 1: prepare
        LinearLayout prepCard = card();
        prepCard.addView(title(tx.prepareTitle()));
        prepStatus = body("");
        prepCard.addView(prepStatus);
        prepProgress = new ProgressBlock();
        prepCard.addView(prepProgress.root);
        prepButton = filledButton(tx.prepareButton());
        prepButton.setOnClickListener(v -> onPrepareClicked());
        prepCard.addView(prepButton);
        pickButton = textButton(tx.pickFiles());
        pickButton.setOnClickListener(v -> onPickClicked());
        prepCard.addView(pickButton);
        col.addView(prepCard);

        // step 2: install
        LinearLayout installCard = card();
        installCard.addView(title(tx.installTitle()));
        installStatus = body("");
        installCard.addView(installStatus);
        installProgress = new ProgressBlock();
        installCard.addView(installProgress.root);
        openWazeologyButton = filledButton(tx.openWazeology());
        openWazeologyButton.setOnClickListener(v -> openWazeology());
        installCard.addView(openWazeologyButton);
        installButton = filledButton(tx.installButton());
        installButton.setOnClickListener(v -> onInstallClicked());
        installCard.addView(installButton);
        installHint = body(tx.installHint());
        installHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        installHint.setPadding(0, dp(8), 0, 0);
        installCard.addView(installHint);
        col.addView(installCard);

        // more
        LinearLayout more = card();
        more.addView(title(tx.moreTitle()));
        storageText = body("");
        more.addView(storageText);
        deleteButton = outlinedButton(tx.deleteFiles());
        deleteButton.setOnClickListener(v -> onDeleteClicked());
        more.addView(deleteButton);
        exportButton = outlinedButton(tx.exportButton());
        exportButton.setOnClickListener(v -> onExportClicked());
        more.addView(exportButton);
        TextView exportHint = body(tx.exportHint());
        exportHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        exportHint.setPadding(0, dp(6), 0, 0);
        more.addView(exportHint);
        moreProgress = new ProgressBlock();
        more.addView(moreProgress.root);
        detailsToggle = textButton(detailsOpen ? tx.hideDetails() : tx.showDetails());
        detailsToggle.setOnClickListener(v -> toggleDetails());
        more.addView(detailsToggle);
        detailsBox = new LinearLayout(this);
        detailsBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView logScroll = new ScrollView(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(palette.surface);
        logBg.setCornerRadius(dp(12));
        logScroll.setBackground(logBg);
        logScroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextColor(palette.onSurface);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        detailsBox.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        Button copy = outlinedButton(tx.copy());
        copy.setOnClickListener(v -> copyDetails());
        detailsBox.addView(copy);
        detailsBox.setVisibility(detailsOpen ? View.VISIBLE : View.GONE);
        more.addView(detailsBox);
        col.addView(more);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.surface);
        scroll.addView(col);
        setContentView(scroll);

        getWindow().setBackgroundDrawable(new ColorDrawable(palette.surface));
        getWindow().setStatusBarColor(palette.surface);
        getWindow().setNavigationBarColor(palette.surface);
        if (!palette.dark) {
            scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void toggleDetails() {
        detailsOpen = !detailsOpen;
        detailsBox.setVisibility(detailsOpen ? View.VISIBLE : View.GONE);
        detailsToggle.setText(detailsOpen ? tx.hideDetails() : tx.showDetails());
        if (detailsOpen) {
            logView.setText(store.logText());
        }
    }

    private void copyDetails() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Wazeology Installer", store.logText()));
        if (Build.VERSION.SDK_INT < 33) { // Android 13+ shows its own copy confirmation
            Toast.makeText(this, tx.copied(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Phase title, a determinate bar (indeterminate while the size is unknown) and a detail line. */
    private final class ProgressBlock {
        final LinearLayout root;
        final TextView phase;
        final ProgressBar bar;
        final TextView detail;

        ProgressBlock() {
            root = new LinearLayout(InstallerActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(4), 0, dp(4));
            phase = new TextView(InstallerActivity.this);
            phase.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            phase.setTypeface(Typeface.DEFAULT_BOLD);
            phase.setTextColor(palette.onSurface);
            root.addView(phase);
            bar = new ProgressBar(InstallerActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(ColorStateList.valueOf(palette.primary));
            bar.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(palette.outline));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            root.addView(bar, lp);
            detail = new TextView(InstallerActivity.this);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            detail.setTextColor(palette.onSurfaceVariant);
            detail.setPadding(0, dp(4), 0, 0);
            root.addView(detail);
            root.setVisibility(View.GONE);
        }

        void showWaiting(String title, String text) {
            root.setVisibility(View.VISIBLE);
            phase.setText(title);
            bar.setIndeterminate(true);
            detail.setText(text);
            detail.setVisibility(View.VISIBLE);
        }

        void show(JobState.Snapshot s) {
            if (s == null || !s.running()) {
                root.setVisibility(View.GONE);
                return;
            }
            root.setVisibility(View.VISIBLE);
            phase.setText(s.cancelling ? tx.stopping() : tx.phaseTitle(s.kind, s.phase));
            boolean known = s.total > 0 && s.phase != JobState.Phase.WAIT_NETWORK
                    && s.phase != JobState.Phase.LOOKUP;
            bar.setIndeterminate(!known);
            if (known) {
                bar.setProgress((int) Math.min(1000, s.done * 1000 / s.total));
            }
            String d = tx.phaseDetail(s);
            detail.setText(d);
            detail.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ---- Material 3 view helpers (same look as the Wazeology screen) -----------------------------

    private TextView headline(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(palette.onSurface);
        markHeading(t);
        return t;
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(palette.onSurface);
        t.setPadding(0, 0, 0, dp(8));
        markHeading(t);
        return t;
    }

    private void markHeading(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            v.setAccessibilityHeading(true);
        }
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(palette.onSurfaceVariant);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.surfaceVariant);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    private Button filledButton(String text) {
        Button b = baseButton(text);
        styleButton(b, true);
        return b;
    }

    private Button outlinedButton(String text) {
        Button b = baseButton(text);
        styleButton(b, false);
        return b;
    }

    /** A borderless text button for secondary actions. */
    private Button textButton(String text) {
        Button b = baseButton(text);
        b.setTextColor(palette.primary);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(20));
        b.setBackground(rippled(withAlpha(palette.primary, 0x33), bg));
        return b;
    }

    /** Filled (the next step) or outlined (secondary); disabled buttons fade. */
    private void styleButton(Button b, boolean filled) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (filled) {
            bg.setColor(palette.primary);
            b.setTextColor(palette.onPrimary);
            b.setBackground(rippled(withAlpha(palette.onPrimary, 0x33), bg));
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), palette.outline);
            b.setTextColor(palette.primary);
            b.setBackground(rippled(withAlpha(palette.primary, 0x33), bg));
        }
    }

    private Button baseButton(String text) {
        Button b = new Button(this) {
            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                setAlpha(enabled ? 1f : 0.38f);
            }
        };
        b.setText(text);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Drawable rippled(int rippleColor, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, content);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
