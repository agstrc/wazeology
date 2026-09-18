package com.waze.wazeology;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Kawasaki motorcycle connection manager. Second launcher entry ("Wazeology"), same app/process as Waze.
 * Fully programmatic UI (no layout XML / no new resources) so the APK's resources stay pristine.
 * Styled as Material 3 (forest-green palette, elevated cards, filled/outlined buttons) reproduced with
 * framework primitives, and split into a Motorcycle tab and a Log tab. See {@link Palette}.
 */
public final class WazeologyActivity extends Activity implements ClusterBridge.Ui {

    private static final int REQ_PERMS = 41;
    private static final int LOG_VIEW_MAX_CHARS = 6000;

    /** UI states derived deterministically from (BleClient.State, hasTarget, isPaused, hasSavedDevice,
     *  isBluetoothOn). */
    private enum UiState {
        NONE, SAVED_UNBONDED, OFFLINE, PAUSED, WAITING, CONNECTING, PAIRING, CONNECTED
    }

    private ClusterBridge bridge;
    private Palette palette;
    private Strings strings;

    // Connection card views, all re-rendered through render().
    private TextView headerCaption;
    private View dotView;
    private TextView stateLabel;
    private TextView cueLabel;
    private View passkeyBanner;
    private View unsupportedBanner;
    private Button primaryBtn;
    private Button forgetBtn;
    private LinearLayout devCard;

    /** The dot carries all the motion signal (there is no progress bar). Passive wait and active connect
     *  are the same breathe at different speeds; error is a slow blink. */
    private enum DotMotion { STATIC, BREATHE_SLOW, BREATHE_FAST, BLINK }
    private ObjectAnimator dotAnim;
    private DotMotion dotMotion = DotMotion.STATIC;
    private UiState lastRendered;

    // Set from onRequestPermissionsResult; drives an on-screen recovery path instead of a silent Log line.
    private boolean mPermissionDenied;

    // Latest code state fed by the ClusterBridge.Ui callbacks; render() is a pure function of these + bridge.
    private BleClient.State mState = BleClient.State.IDLE;
    private String mCue = "";

    private TextView logView;
    private LinearLayout deviceList;
    private TextView devicePlaceholder;
    private final StringBuilder logBuffer = new StringBuilder();

    private ScrollView logScroll;
    private View motorcycleContent;
    private View logContent;
    private TextView motorcycleTab;
    private TextView logTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Wazeology");
        bridge = ClusterBridge.get(this);
        palette = new Palette(this);
        strings = new Strings(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.surface);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, 0);

        root.addView(headline("Wazeology"));
        headerCaption = body("");
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionLp.topMargin = dp(2);
        headerCaption.setLayoutParams(captionLp);
        headerCaption.setVisibility(View.GONE);
        root.addView(headerCaption);
        root.addView(buildTabBar());

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        motorcycleContent = buildMotorcycleTab();
        logContent = buildLogTab();
        content.addView(motorcycleContent);
        content.addView(logContent);
        LinearLayout.LayoutParams contentLp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, contentLp);

        setContentView(root);

        getWindow().setBackgroundDrawable(new ColorDrawable(palette.surface));
        getWindow().setStatusBarColor(palette.surface);
        if (!palette.dark) {
            // Dark icons on the near-white surface in light mode; the dark surface keeps default light icons.
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        showLogTab(false);
    }

    // ---- tab scaffolding -----------------------------------------------------------------------

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.surfaceVariant);
        bg.setCornerRadius(dp(22));
        bar.setBackground(bg);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));

        motorcycleTab = tabButton(strings.tabMotorcycle);
        motorcycleTab.setOnClickListener(v -> showLogTab(false));
        logTab = tabButton(strings.tabLog);
        logTab.setOnClickListener(v -> showLogTab(true));
        bar.addView(motorcycleTab, equalWeight());
        bar.addView(logTab, equalWeight());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View buildMotorcycleTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(0, 0, 0, dp(16));
        scroll.addView(col);

        LinearLayout connCard = card();
        connCard.addView(title(strings.connection));

        // Status row: a coloured state dot + a bold state label.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        // The dot scales up while breathing and sits flush at the card's left content edge; let it draw
        // past both the child bounds (clipChildren) and the card's padding band (clipToPadding).
        statusRow.setClipChildren(false);
        connCard.setClipChildren(false);
        connCard.setClipToPadding(false);
        dotView = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.rightMargin = dp(8);
        dotView.setLayoutParams(dotLp);
        statusRow.addView(dotView);
        stateLabel = new TextView(this);
        stateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        stateLabel.setTypeface(Typeface.DEFAULT_BOLD);
        stateLabel.setTextColor(palette.onSurface);
        statusRow.addView(stateLabel);
        connCard.addView(statusRow);

        // Cue line — its own view, so a state change never wipes the last navigation cue.
        cueLabel = body("");
        LinearLayout.LayoutParams cueLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cueLp.topMargin = dp(4);
        cueLabel.setLayoutParams(cueLp);
        connCard.addView(cueLabel);

        // Passkey banner (shown only while BONDING).
        passkeyBanner = buildPasskeyBanner();
        connCard.addView(passkeyBanner);

        // Unsupported-cluster banner (shown only when the connected cluster can't render navigation).
        unsupportedBanner = buildUnsupportedBanner();
        connCard.addView(unsupportedBanner);

        // One contextual primary action (Scan / Connect / Disconnect) + a secondary Forget.
        primaryBtn = filledButton(strings.scanForMotorcycle);
        connCard.addView(primaryBtn);
        forgetBtn = outlinedButton(strings.forget);
        forgetBtn.setOnClickListener(v -> bridge.forget());
        connCard.addView(forgetBtn);

        col.addView(connCard);

        devCard = card();
        devCard.addView(title(strings.devices));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        showDevices(new ArrayList<BluetoothDevice>());
        devCard.addView(deviceList);
        col.addView(devCard);

        render();
        return scroll;
    }

    private View buildPasskeyBanner() {
        return banner(palette.primaryContainer, palette.onPrimaryContainer, "🔑",
            strings.passkeyTitle,
            strings.passkeyBody);
    }

    private View buildUnsupportedBanner() {
        return banner(palette.errorContainer, palette.onErrorContainer, "⚠",
            strings.unsupportedTitle,
            strings.unsupportedBody);
    }

    /** A rounded, filled attention banner: a leading glyph beside a bold title and a body line. */
    private View banner(int bgColor, int fgColor, String glyph, String titleText, String bodyText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        row.setBackground(bg);
        int p = dp(16);
        row.setPadding(p, p, p, p);

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        icon.setTextColor(fgColor);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(fgColor);
        textCol.addView(title);
        TextView body = new TextView(this);
        body.setText(bodyText);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(fgColor);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(2);
        textCol.addView(body, bodyLp);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setContentDescription(titleText + ". " + bodyText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private View buildLogTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(2), 0, dp(16));
        col.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        Button share = filledButton(strings.share);
        share.setOnClickListener(v -> shareLog());
        Button copy = outlinedButton(strings.copy);
        copy.setOnClickListener(v -> copyLog());
        Button clear = outlinedButton(strings.clear);
        clear.setOnClickListener(v -> {
            bridge.clearLog();
            logBuffer.setLength(0);
            logView.setText("");
        });
        // Three buttons share a row, so a long label (e.g. pt-BR "Compartilhar") would wrap to a second
        // line and make its button taller than the others. Keep each on one line and let it shrink to fit.
        compactRowButton(share);
        compactRowButton(copy);
        compactRowButton(clear);
        logActions.addView(share, equalWeightMargin(0, dp(6)));
        logActions.addView(copy, equalWeightMargin(dp(6), dp(6)));
        logActions.addView(clear, equalWeightMargin(dp(6), 0));
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.bottomMargin = dp(12);
        col.addView(logActions, actionsLp);

        logScroll = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.surfaceVariant);
        bg.setCornerRadius(dp(16));
        logScroll.setBackground(bg);
        int lp = dp(12);
        logScroll.setPadding(lp, lp, lp, lp);
        logScroll.setClipToPadding(false);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setLineSpacing(dp(3), 1f);
        logView.setTextColor(palette.onSurface);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);

        col.addView(logScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private void showLogTab(boolean log) {
        motorcycleContent.setVisibility(log ? View.GONE : View.VISIBLE);
        logContent.setVisibility(log ? View.VISIBLE : View.GONE);
        styleTab(motorcycleTab, !log);
        styleTab(logTab, log);
        if (log) {
            scrollLogToBottom();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Seed the view with the full accumulated log (it keeps running while the screen is closed).
        logBuffer.setLength(0);
        logBuffer.append(bridge.getLog());
        if (logView != null) {
            logView.setText(logBuffer.toString());
            scrollLogToBottom();
        }
        bridge.setUi(this);
    }

    private void shareLog() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, strings.shareSubject);
        send.putExtra(Intent.EXTRA_TEXT, bridge.getLog());
        startActivity(Intent.createChooser(send, strings.shareChooser));
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(strings.shareSubject, bridge.getLog()));
            appendLog("(log copied to clipboard)");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Clear a stale "permission needed" state if the rider granted it from system Settings and returned.
        if (mPermissionDenied && !hasMissingPermissions()) {
            mPermissionDenied = false;
        }
        // Also covers a fresh install, where the bridge was built before BLUETOOTH_CONNECT was granted.
        ensurePermissionsThen(bridge::ensurePassive);
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        bridge.stopScan();
        // Don't leave the animator running while detached; reset so onResume's render() re-applies motion.
        cancelDotAnim();
        dotMotion = DotMotion.STATIC;
        lastRendered = null;
        bridge.setUi(null); // keep the singleton + BLE link alive; just detach the UI
    }

    private void doScan() {
        bridge.startScan();
    }

    // ---- permissions ---------------------------------------------------------------------------

    private String[] neededPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add("android.permission.BLUETOOTH_SCAN");
            perms.add("android.permission.BLUETOOTH_CONNECT");
        }
        perms.add("android.permission.ACCESS_FINE_LOCATION");
        return perms.toArray(new String[0]);
    }

    private boolean hasMissingPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private void ensurePermissionsThen(Runnable onGranted) {
        List<String> missing = new ArrayList<>();
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            if (onGranted != null) {
                onGranted.run();
            }
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }
        mPermissionDenied = !allGranted;
        appendLog(allGranted ? "permissions granted" : "some permissions denied; scanning/connecting may fail");
        if (allGranted) {
            bridge.ensurePassive();
        }
        render();
    }

    // ---- ClusterBridge.Ui (all on main thread) ----------------------------------------------------

    @Override
    public void onLog(String line) {
        appendLog(line);
    }

    @Override
    public void onState(BleClient.State state) {
        mState = state;
        render();
    }

    @Override
    public void onStatus(String status) {
        mCue = status == null ? "" : status;
        render();
    }

    @Override
    public void onDevices(List<BluetoothDevice> devices) {
        showDevices(devices);
        render();
    }

    /** Rebuilds the Devices list. When empty, keeps a placeholder whose wording render() keeps in step
     *  with the current state (scanning is only offered — and only mentioned — with nothing paired). */
    private void showDevices(List<BluetoothDevice> devices) {
        deviceList.removeAllViews();
        if (devices.isEmpty()) {
            devicePlaceholder = body("");
            deviceList.addView(devicePlaceholder);
        } else {
            devicePlaceholder = null;
            for (BluetoothDevice d : devices) {
                String name;
                try {
                    name = d.getName();
                } catch (SecurityException e) {
                    name = null;
                }
                final BluetoothDevice device = d;
                deviceList.addView(deviceRow(name == null ? strings.unnamed : name, d.getAddress(),
                    v -> bridge.connect(device)));
            }
        }
    }

    // ---- state -> UI (single render path) ------------------------------------------------------

    private UiState deriveUiState() {
        switch (mState) {
            case READY:
                return UiState.CONNECTED;
            case BONDING:
                return UiState.PAIRING;
            case WAITING:
                return UiState.WAITING;
            case CONNECTING:
            case SUBSCRIBING:
            case INITIALIZING:
                return UiState.CONNECTING;
            default:
                break;
        }
        // IDLE. Bluetooth off is checked first: the bonded set is empty then, so no target can resolve.
        // Reported whether or not a motorcycle is saved, so a fresh install with BT off isn't a dead end.
        if (!bridge.isBluetoothOn()) {
            return UiState.OFFLINE;
        }
        // With a target this is either paused or the brief gap before a re-arm.
        if (bridge.hasTarget()) {
            return bridge.isPaused() ? UiState.PAUSED : UiState.WAITING;
        }
        return bridge.hasSavedDevice() ? UiState.SAVED_UNBONDED : UiState.NONE;
    }

    /** Renders every control from the current state. Idempotent; safe to call from any Ui callback. */
    private void render() {
        if (stateLabel == null) {
            return; // views not built yet
        }
        Capabilities caps = bridge.capabilities();
        boolean unsupported = caps != null && !caps.navigationSupported;
        UiState u = deriveUiState();
        boolean scanning = bridge.isScanning();
        boolean scanOffered = u == UiState.NONE || u == UiState.SAVED_UNBONDED;
        boolean permBlocked = mPermissionDenied && scanOffered;

        // Header caption: the saved bike's name, so the header isn't a lone word.
        if (bridge.hasSavedDevice()) {
            headerCaption.setText(targetName());
            headerCaption.setVisibility(View.VISIBLE);
        } else {
            headerCaption.setVisibility(View.GONE);
        }

        // State label — never the raw enum.
        String label;
        switch (u) {
            case CONNECTED:
                label = strings.connected;
                break;
            case PAIRING:
                label = strings.pairing;
                break;
            case WAITING:
                label = strings.waitingFor(targetName());
                break;
            case CONNECTING:
                label = mState == BleClient.State.SUBSCRIBING ? strings.subscribing
                    : mState == BleClient.State.INITIALIZING ? strings.initializing
                    : strings.connectingTo(targetName());
                break;
            case PAUSED:
                label = strings.savedDisconnected(targetName());
                break;
            case OFFLINE:
                label = bridge.hasSavedDevice()
                    ? strings.savedBluetoothOff(targetName()) : strings.bluetoothOff;
                break;
            case SAVED_UNBONDED:
                label = strings.savedNeedsPairing(targetName());
                break;
            case NONE:
            default:
                label = strings.notConnected;
                break;
        }
        if (permBlocked) {
            label = strings.permissionNeeded; // recovery offered on the primary button below
        }

        // Error states get the error colour + a filled error dot; the unsupported suffix moved to a banner.
        boolean errorState = unsupported || u == UiState.OFFLINE || permBlocked;
        stateLabel.setText(label);
        stateLabel.setTextColor(errorState ? palette.error
            : u == UiState.CONNECTED ? palette.primary : palette.onSurface);

        // Status dot: shape + colour carry the state (ring = no link, filled = a link exists/is pursued),
        // while the breathe rate is the only difference between passive wait and an active connect.
        int dotColor;
        boolean filled;
        DotMotion motion;
        if (scanning) {
            dotColor = palette.primary; filled = false; motion = DotMotion.BREATHE_FAST; // searching, no link yet
        } else if (errorState) {
            dotColor = palette.error; filled = false; motion = DotMotion.BLINK;
        } else if (u == UiState.CONNECTED) {
            dotColor = palette.primary; filled = true; motion = DotMotion.STATIC;
        } else if (u == UiState.WAITING) {
            dotColor = palette.primary; filled = true; motion = DotMotion.BREATHE_SLOW; // passive wait
        } else if (u == UiState.CONNECTING || u == UiState.PAIRING) {
            dotColor = palette.primary; filled = true; motion = DotMotion.BREATHE_FAST; // active connect
        } else {
            dotColor = palette.onSurfaceVariant; filled = false; motion = DotMotion.STATIC; // NONE/PAUSED/SAVED
        }
        setDotStyle(dotColor, filled);
        dotView.setContentDescription(label);
        applyDotMotion(motion);
        // A single settle bounce on the transition into CONNECTED (not on every render while connected).
        if (u == UiState.CONNECTED && lastRendered != null && lastRendered != UiState.CONNECTED) {
            playConnectSettle();
        }
        lastRendered = u;

        // Cue line: only while connected and actually navigating (the idle default is "No navigation").
        if (u == UiState.CONNECTED && !mCue.isEmpty() && !mCue.equalsIgnoreCase("No navigation")) {
            cueLabel.setText(strings.cuePrefix + mCue);
            cueLabel.setVisibility(View.VISIBLE);
        } else {
            cueLabel.setVisibility(View.GONE);
        }

        passkeyBanner.setVisibility(u == UiState.PAIRING ? View.VISIBLE : View.GONE);
        unsupportedBanner.setVisibility(unsupported ? View.VISIBLE : View.GONE);

        // Contextual primary action. No Stop/Cancel: a saved motorcycle is waited for until Forget.
        boolean primaryEnabled = true;
        if (u == UiState.OFFLINE) {
            setPrimary(strings.turnOnBluetooth, v -> openBluetooth());
        } else if (permBlocked) {
            setPrimary(strings.openAppSettings, v -> openAppSettings());
        } else if (scanning) {
            setPrimary(strings.scanning, null);
            primaryEnabled = false;
        } else {
            switch (u) {
                case CONNECTED:
                    setPrimary(strings.disconnect, v -> bridge.disconnect());
                    break;
                case WAITING:
                case PAUSED:
                    setPrimary(strings.connectNow, v -> ensurePermissionsThen(bridge::connectNow));
                    break;
                case CONNECTING:
                case PAIRING:
                    setPrimary(strings.connect, null);
                    primaryEnabled = false;
                    break;
                default:
                    setPrimary(strings.scanForMotorcycle, v -> ensurePermissionsThen(this::doScan));
                    break;
            }
        }
        primaryBtn.setEnabled(primaryEnabled);
        primaryBtn.setAlpha(primaryEnabled ? 1f : 0.5f);

        // Forget appears only once a motorcycle is remembered.
        forgetBtn.setVisibility(bridge.hasSavedDevice() ? View.VISIBLE : View.GONE);

        // The Devices list only matters while scanning is offered; hide it once a motorcycle is remembered.
        devCard.setVisibility(scanOffered ? View.VISIBLE : View.GONE);
        if (devicePlaceholder != null) {
            String placeholder;
            if (scanning) {
                placeholder = strings.searchingDevices;
            } else if (bridge.scanAttempted()) {
                placeholder = strings.noMotorcycleFound;
            } else {
                placeholder = strings.noDevicesYet;
            }
            devicePlaceholder.setText(placeholder);
        }
    }

    private void openBluetooth() {
        try {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {
                appendLog("could not open Bluetooth settings");
            }
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            appendLog("could not open app settings");
        }
    }

    private void setPrimary(String text, View.OnClickListener click) {
        primaryBtn.setText(text);
        primaryBtn.setOnClickListener(click);
    }

    private String targetName() {
        String n = bridge.currentTargetLabel();
        return n == null ? strings.motorcycleFallback : n;
    }

    /** Draws the status dot: a hollow ring (at rest) or a filled disc, so state reads without relying on
     *  colour alone — legible in sunlight and to colour-blind riders. */
    private void setDotStyle(int color, boolean filled) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (filled) {
            g.setColor(color);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(2), color);
        }
        dotView.setBackground(g);
    }

    /** Idempotent: sets the dot's ongoing motion, restarting the animator only when the mode actually
     *  changes. Passive→active (and back) is a seamless speed change — the same breathe, its phase
     *  carried over — so tapping Connect never pops. */
    private void applyDotMotion(DotMotion m) {
        if (m == dotMotion) {
            return;
        }
        boolean seamless = dotAnim != null && isBreathe(dotMotion) && isBreathe(m);
        float phase = seamless ? dotAnim.getAnimatedFraction() : 0f;
        dotMotion = m;
        cancelDotAnim();
        switch (m) {
            case BREATHE_SLOW:
                startBreathe(1300, phase);
                break;
            case BREATHE_FAST:
                startBreathe(650, phase);
                break;
            case BLINK:
                startBlink();
                break;
            case STATIC:
            default:
                break; // cancelDotAnim already reset the transforms
        }
    }

    private static boolean isBreathe(DotMotion m) {
        return m == DotMotion.BREATHE_SLOW || m == DotMotion.BREATHE_FAST;
    }

    private void startBreathe(int periodMs, float phase) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(dotView,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.18f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.18f));
        a.setDuration(periodMs);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
        a.setCurrentFraction(phase); // continuous scale value across a speed change
        dotAnim = a;
    }

    private void startBlink() {
        ObjectAnimator a = ObjectAnimator.ofFloat(dotView, "alpha", 1f, 0.6f);
        a.setDuration(1400);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.setInterpolator(new LinearInterpolator());
        a.start();
        dotAnim = a;
    }

    private void cancelDotAnim() {
        if (dotAnim != null) {
            dotAnim.cancel();
            dotAnim = null;
        }
        dotView.setScaleX(1f);
        dotView.setScaleY(1f);
        dotView.setAlpha(1f);
    }

    /** A single "lock" bounce the moment the link goes ready, then dead still. */
    private void playConnectSettle() {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(dotView,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.28f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.28f, 1f));
        a.setDuration(280);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
    }

    private void appendLog(String line) {
        logBuffer.append(line).append('\n');
        if (logBuffer.length() > LOG_VIEW_MAX_CHARS) {
            logBuffer.delete(0, logBuffer.length() - LOG_VIEW_MAX_CHARS);
        }
        if (logView != null) {
            logView.setText(logBuffer.toString());
            scrollLogToBottom();
        }
    }

    private void scrollLogToBottom() {
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    // ---- Material 3 view helpers ---------------------------------------------------------------

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
        return t;
    }

    /** An elevated, rounded surface-variant card. Add child views, then add the card to its parent. */
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

    /** Tightens a button for a shared, equal-weight row: single line with trimmed side padding, and text
     *  that auto-shrinks to fit so a long localized label never wraps or overflows its third of the row. */
    private void compactRowButton(Button b) {
        b.setMaxLines(1);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, TypedValue.COMPLEX_UNIT_SP);
    }

    private Button filledButton(String text) {
        Button b = baseButton(text);
        b.setTextColor(palette.onPrimary);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.primary);
        bg.setCornerRadius(dp(20));
        b.setBackground(rippled(withAlpha(palette.onPrimary, 0x33), bg));
        return b;
    }

    private Button outlinedButton(String text) {
        Button b = baseButton(text);
        b.setTextColor(palette.primary);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), palette.outline);
        b.setBackground(rippled(withAlpha(palette.primary, 0x33), bg));
        return b;
    }

    /** A tappable device list row: bold name over a dim MAC, with a trailing chevron, so the list reads
     *  as a picker rather than a stack of generic pill buttons. */
    private View deviceRow(String name, String address, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        int p = dp(12);
        row.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.surface);
        bg.setCornerRadius(dp(12));
        row.setBackground(rippled(withAlpha(palette.primary, 0x33), bg));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(strings.connectToPrefix + name);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setTextColor(palette.onSurface);
        textCol.addView(nameView);
        TextView addrView = new TextView(this);
        addrView.setText(address);
        addrView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addrView.setTextColor(palette.onSurfaceVariant);
        textCol.addView(addrView);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›"); // ›
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        chevron.setTextColor(palette.onSurfaceVariant);
        row.addView(chevron);
        return row;
    }

    private Button baseButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView tabButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setMinHeight(dp(48));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private void styleTab(TextView t, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? palette.primaryContainer : Color.TRANSPARENT);
        bg.setCornerRadius(dp(18));
        t.setBackground(rippled(withAlpha(palette.primary, 0x33), bg));
        t.setTextColor(selected ? palette.onPrimaryContainer : palette.onSurfaceVariant);
        t.setSelected(selected);
        t.setContentDescription(t.getText() + (selected ? strings.selectedSuffix : ""));
    }

    private Drawable rippled(int rippleColor, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, content);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private LinearLayout.LayoutParams equalWeight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams equalWeightMargin(int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = left;
        lp.rightMargin = right;
        lp.topMargin = dp(8);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
