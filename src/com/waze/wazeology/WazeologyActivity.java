package com.waze.wazeology;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Activity;
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
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

    // Connection card views, all re-rendered through render().
    private View dotView;
    private TextView stateLabel;
    private TextView cueLabel;
    private View passkeyBanner;
    private ProgressBar progressBar;
    private Button primaryBtn;
    private Button forgetBtn;
    private LinearLayout devCard;
    private Animator dotPulse;

    // Latest code state fed by the ClusterBridge.Ui callbacks; render() is a pure function of these + bridge.
    private BleClient.State mState = BleClient.State.IDLE;
    private String mCue = "";

    private TextView logView;
    private LinearLayout deviceList;
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.surface);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, 0);

        root.addView(headline("Wazeology"));
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

        motorcycleTab = tabButton("Motorcycle");
        motorcycleTab.setOnClickListener(v -> showLogTab(false));
        logTab = tabButton("Log");
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
        col.setPadding(0, dp(2), 0, dp(16));
        scroll.addView(col);

        LinearLayout connCard = card();
        connCard.addView(title("Connection"));

        // Status row: a coloured state dot + a bold state label.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        dotView = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
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

        // Progress strip (shown only in the transient / reconnecting states).
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(palette.primary));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(palette.surfaceVariant));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = dp(12);
        progressBar.setLayoutParams(pbLp);
        connCard.addView(progressBar);

        // One contextual primary action (Scan / Connect / Disconnect) + a secondary Forget.
        primaryBtn = filledButton("Scan for motorcycle");
        connCard.addView(primaryBtn);
        forgetBtn = outlinedButton("Forget");
        forgetBtn.setOnClickListener(v -> bridge.forget());
        connCard.addView(forgetBtn);

        col.addView(connCard);

        devCard = card();
        devCard.addView(title("Devices"));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.addView(body("(no devices yet — tap Scan)"));
        devCard.addView(deviceList);
        col.addView(devCard);

        render();
        return scroll;
    }

    private View buildPasskeyBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.primaryContainer);
        bg.setCornerRadius(dp(12));
        banner.setBackground(bg);
        int p = dp(12);
        banner.setPadding(p, p, p, p);
        TextView t = new TextView(this);
        t.setText("Enter the passkey shown on your cluster");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(palette.onPrimaryContainer);
        banner.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        banner.setLayoutParams(lp);
        return banner;
    }

    private View buildLogTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(2), 0, dp(16));
        col.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        Button share = filledButton("Share");
        share.setOnClickListener(v -> shareLog());
        Button copy = outlinedButton("Copy");
        copy.setOnClickListener(v -> copyLog());
        Button clear = outlinedButton("Clear");
        clear.setOnClickListener(v -> {
            bridge.clearLog();
            logBuffer.setLength(0);
            logView.setText("");
        });
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
        send.putExtra(Intent.EXTRA_SUBJECT, "Kawasaki motorcycle log");
        send.putExtra(Intent.EXTRA_TEXT, bridge.getLog());
        startActivity(Intent.createChooser(send, "Share motorcycle log"));
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Kawasaki motorcycle log", bridge.getLog()));
            appendLog("(log copied to clipboard)");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also covers a fresh install, where the bridge was built before BLUETOOTH_CONNECT was granted.
        ensurePermissionsThen(bridge::ensurePassive);
    }

    @Override
    protected void onStop() {
        super.onStop();
        bridge.stopScan();
        stopDotPulse(); // don't leave the animator running while detached; render() restarts it on onStart
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
        appendLog(allGranted ? "permissions granted" : "some permissions denied; scanning/connecting may fail");
        if (allGranted) {
            bridge.ensurePassive();
        }
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
        deviceList.removeAllViews();
        if (devices.isEmpty()) {
            deviceList.addView(body("(no devices yet — tap Scan)"));
        } else {
            for (BluetoothDevice d : devices) {
                String name;
                try {
                    name = d.getName();
                } catch (SecurityException e) {
                    name = null;
                }
                String label = (name == null ? "(unnamed)" : name) + "\n" + d.getAddress();
                Button row = outlinedButton(label);
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                final BluetoothDevice device = d;
                row.setOnClickListener(v -> bridge.connect(device));
                deviceList.addView(row);
            }
        }
        render();
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
        if (!bridge.isBluetoothOn() && bridge.hasSavedDevice()) {
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
        boolean busy = u == UiState.CONNECTING || u == UiState.PAIRING || u == UiState.WAITING;

        // Status dot: grey at rest, primary (pulsing) while busy, steady primary when connected, error modifier.
        int dotColor = unsupported ? palette.error
            : (busy || u == UiState.CONNECTED) ? palette.primary : palette.outline;
        setDotColor(dotColor);
        if (busy) {
            startDotPulse();
        } else {
            stopDotPulse();
        }

        // State label — never the raw enum.
        String label;
        switch (u) {
            case CONNECTED:
                label = "Connected";
                break;
            case PAIRING:
                label = "Pairing — enter passkey";
                break;
            case WAITING:
                label = "Waiting for " + targetName() + "…";
                break;
            case CONNECTING:
                label = mState == BleClient.State.SUBSCRIBING ? "Subscribing…"
                    : mState == BleClient.State.INITIALIZING ? "Initializing…"
                    : "Connecting to " + targetName() + "…";
                break;
            case PAUSED:
                label = "Saved: " + targetName() + " — disconnected";
                break;
            case OFFLINE:
                label = "Saved: " + targetName() + " — Bluetooth off";
                break;
            case SAVED_UNBONDED:
                label = "Saved: " + targetName() + " — needs pairing";
                break;
            case NONE:
            default:
                label = "Not connected";
                break;
        }
        if (unsupported) {
            label = label + " · navigation unsupported";
        }
        stateLabel.setText(label);
        stateLabel.setTextColor(unsupported ? palette.error
            : u == UiState.CONNECTED ? palette.primary : palette.onSurface);

        // Cue line (independent of the state line).
        if (!mCue.isEmpty()) {
            cueLabel.setText("Cue: " + mCue);
            cueLabel.setVisibility(View.VISIBLE);
        } else {
            cueLabel.setVisibility(View.GONE);
        }

        passkeyBanner.setVisibility(u == UiState.PAIRING ? View.VISIBLE : View.GONE);

        // Progress strip: determinate step ladder while connecting, indeterminate while waiting,
        // fill-to-100 then collapse when it goes READY, hidden at rest.
        if (u == UiState.WAITING) {
            progressBar.setIndeterminate(true);
            progressBar.setVisibility(View.VISIBLE);
        } else if (u == UiState.CONNECTING || u == UiState.PAIRING) {
            progressBar.setIndeterminate(false);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(stepFor(mState));
        } else if (u == UiState.CONNECTED) {
            progressBar.setIndeterminate(false);
            if (progressBar.getVisibility() == View.VISIBLE && progressBar.getProgress() < 100) {
                animateProgressToFull();
            }
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setVisibility(View.GONE);
            progressBar.setProgress(0);
        }

        // Contextual primary action. No Stop/Cancel: a saved motorcycle is waited for until Forget.
        boolean primaryEnabled = true;
        switch (u) {
            case CONNECTED:
                setPrimary("Disconnect", v -> bridge.disconnect());
                break;
            case WAITING:
                setPrimary("Connect now", v -> ensurePermissionsThen(bridge::connectNow));
                break;
            case PAUSED:
                setPrimary("Connect", v -> ensurePermissionsThen(bridge::connectNow));
                break;
            case OFFLINE:
            case CONNECTING:
            case PAIRING:
                setPrimary("Connect", null);
                primaryEnabled = false;
                break;
            default:
                setPrimary("Scan for motorcycle", v -> ensurePermissionsThen(this::doScan));
                break;
        }
        primaryBtn.setEnabled(primaryEnabled);
        primaryBtn.setAlpha(primaryEnabled ? 1f : 0.5f);

        // Forget appears only once a motorcycle is remembered.
        forgetBtn.setVisibility(bridge.hasSavedDevice() ? View.VISIBLE : View.GONE);

        // Scan is only offered with nothing saved; dim the Devices card otherwise.
        devCard.setAlpha(u == UiState.NONE || u == UiState.SAVED_UNBONDED ? 1f : 0.5f);
    }

    private void setPrimary(String text, View.OnClickListener click) {
        primaryBtn.setText(text);
        primaryBtn.setOnClickListener(click);
    }

    private String targetName() {
        String n = bridge.currentTargetLabel();
        return n == null ? "motorcycle" : n;
    }

    private static int stepFor(BleClient.State s) {
        switch (s) {
            case CONNECTING:
                return 20;
            case BONDING:
                return 45;
            case SUBSCRIBING:
                return 70;
            case INITIALIZING:
                return 90;
            default:
                return 10;
        }
    }

    private void animateProgressToFull() {
        ObjectAnimator a = ObjectAnimator.ofInt(progressBar, "progress", progressBar.getProgress(), 100);
        a.setDuration(400);
        a.start();
        progressBar.postDelayed(() -> {
            if (deriveUiState() == UiState.CONNECTED) {
                progressBar.setVisibility(View.GONE);
            }
        }, 800);
    }

    private void setDotColor(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        dotView.setBackground(g);
    }

    private void startDotPulse() {
        if (dotPulse != null) {
            return;
        }
        ObjectAnimator a = ObjectAnimator.ofFloat(dotView, "alpha", 1f, 0.3f);
        a.setDuration(700);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.start();
        dotPulse = a;
    }

    private void stopDotPulse() {
        if (dotPulse != null) {
            dotPulse.cancel();
            dotPulse = null;
        }
        dotView.setAlpha(1f);
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
        return t;
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(palette.onSurface);
        t.setPadding(0, 0, 0, dp(8));
        return t;
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
        t.setPadding(0, dp(10), 0, dp(10));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private void styleTab(TextView t, boolean selected) {
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(palette.primaryContainer);
            bg.setCornerRadius(dp(18));
            t.setBackground(bg);
            t.setTextColor(palette.onPrimaryContainer);
        } else {
            t.setBackground(null);
            t.setTextColor(palette.onSurfaceVariant);
        }
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
