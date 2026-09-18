package com.waze.wazeology;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Process-wide bridge between Waze's live guidance (fed by the smali hooks) and the Kawasaki cluster
 * BLE link. Owns the {@link BleClient}, folds in the send cadence (suppression, keepalive,
 * bonded-only reconnect), maps Waze maneuvers to cluster TurnTypes, and persists the chosen motorcycle MAC so it
 * reconnects on next launch. Runs without an Android Service: the link lives as long as the Waze process.
 */
public final class ClusterBridge implements BleClient.Listener {

    private static final int LOG_MAX_LINES = 2000;

    private static final long KEEPALIVE_MS = 5000L;
    private static final long MIN_SEND_INTERVAL_MS = 1000L;
    private static final long REARM_DELAY_MS = 4000L;      // spacing between passive re-arms after a stack failure
    private static final int MAX_REJECTIONS = 2;           // second looks for a bonded device with a stale service table
    private static final long SCAN_TIMEOUT_MS = 15000L;
    private static final String PREFS = "waze_motorcycle";
    private static final String KEY_MAC = "mac";
    private static final String KEY_NAME = "name";
    private static final String NO_NAVIGATION = "No navigation";

    private static volatile ClusterBridge INSTANCE;

    /** UI callbacks (the management Activity). All delivered on the main thread. */
    public interface Ui {
        void onLog(String line);
        void onState(BleClient.State state);
        void onStatus(String status);
        void onDevices(List<BluetoothDevice> devices);
    }

    private static final class Sent {
        final FlagMode flag;
        final TurnType turn;
        final DistanceUnit unit;
        final int value;
        final String label;
        long atMs;

        Sent(FlagMode flag, TurnType turn, DistanceUnit unit, int value, String label, long atMs) {
            this.flag = flag;
            this.turn = turn;
            this.unit = unit;
            this.value = value;
            this.label = label;
            this.atMs = atMs;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BleClient ble;

    private Ui ui;
    private Sent lastSent;
    private String status = NO_NAVIGATION;

    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat logClock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final SimpleDateFormat logFileClock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final LogStore logStore;

    // Latest Waze maneuver state, updated by the hooks.
    private volatile int currentManeuverCode = -1;
    private volatile String currentManeuverName = "";
    private volatile int currentExit = 0;

    // Scan state.
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private boolean scanning;      // a low-latency scan is currently running
    private boolean scanAttempted; // at least one scan has been started this process (drives the "none found" copy)
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();

    // Link target: the bonded motorcycle we intend to be linked to. A passive (autoConnect) handle is
    // pending whenever the process is up, unless paused by Disconnect (in-memory, so a relaunch resumes).
    private BluetoothDevice target;
    private boolean paused;
    private int rejections; // "not a cluster" answers from the current target; reset on connect / READY

    private ClusterBridge(Context appCtx) {
        this.context = appCtx;
        this.ble = new BleClient(appCtx, this);
        this.logStore = new LogStore(new File(appCtx.getFilesDir(), "wazeology-logs"));
        // Reload the tail of the persisted log so the screen isn't empty after the Waze process restarts.
        for (String l : logStore.readActiveTail(LOG_MAX_LINES)) {
            logLines.addLast(l);
        }
        main.post(keepalive);
        // A saved, still-bonded motorcycle is waited for passively from the moment the process is up
        // (ensurePassive resolves it from prefs; see resolveTarget for why that is lazy).
        registerAdapterReceiver();
        // The first get() can come from a Waze hook thread; BleClient state is main-thread only.
        main.post(this::ensurePassive);
    }

    // ---- singleton / static feed API used by the hooks ----------------------------------------

    public static ClusterBridge get(Context ctx) {
        ClusterBridge local = INSTANCE;
        if (local == null) {
            synchronized (ClusterBridge.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new ClusterBridge(ctx.getApplicationContext());
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    /** Returns the instance, lazily creating it from a reflectively-obtained app Context if needed. */
    public static ClusterBridge peek() {
        ClusterBridge local = INSTANCE;
        if (local == null) {
            Context c = appContext();
            if (c != null) {
                local = get(c);
            }
        }
        return local;
    }

    public static void maneuver(int code, String name) {
        ClusterBridge b = peek();
        if (b != null) {
            b.onManeuver(code, name);
        }
    }

    public static void distance(int meters, String text, String unit) {
        ClusterBridge b = peek();
        if (b != null) {
            b.onDistance(meters, text, unit);
        }
    }

    public static void exitNumber(int exit) {
        ClusterBridge b = peek();
        if (b != null) {
            b.onExitNumber(exit);
        }
    }

    public static void navState(boolean navigating) {
        ClusterBridge b = peek();
        if (b != null) {
            b.onNavStateChanged(navigating);
        }
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return ((Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
            // no app context available yet
        }
        return null;
    }

    // ---- UI wiring -----------------------------------------------------------------------------

    public void setUi(Ui ui) {
        this.ui = ui;
        if (ui != null) {
            final Ui u = ui;
            main.post(() -> {
                u.onState(ble.getState());
                u.onStatus(status);
                u.onDevices(new ArrayList<>(found.values()));
            });
        }
    }

    public Capabilities capabilities() {
        return ble.getCapabilities();
    }

    /** True once a motorcycle has been remembered (its MAC persisted after a successful connect). */
    public boolean hasSavedDevice() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAC, null) != null;
    }

    /** True once the saved motorcycle resolves to a bonded device (the one we wait for / connect to). */
    public boolean hasTarget() {
        return resolveTarget() != null;
    }

    /** Lazily (re)resolves the saved MAC to a bonded device. The constructor can run before
     *  BLUETOOTH_CONNECT is granted, and a failed unbonded scan-tap clears the target, so this is retried
     *  whenever the target is needed rather than fixed once at launch. */
    private BluetoothDevice resolveTarget() {
        BluetoothDevice dev = target;
        if (dev == null) {
            String mac = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAC, null);
            if (mac != null) {
                dev = bondedByMac(mac);
                target = dev;
            }
        }
        return dev;
    }

    /** True after Disconnect: the passive wait is off until Connect or the next Waze launch. */
    public boolean isPaused() {
        return paused;
    }

    public boolean isBluetoothOn() {
        BluetoothAdapter adapter = adapter();
        return adapter != null && adapter.isEnabled();
    }

    /** Human label for the device we intend to be linked to: the live target's name/address, else the
     *  saved name, else the saved MAC. Null only when nothing is targeted and nothing is remembered. */
    public String currentTargetLabel() {
        BluetoothDevice dev = target;
        if (dev != null) {
            try {
                String name = dev.getName();
                if (name != null) {
                    return name;
                }
            } catch (SecurityException ignored) {
                // needs BLUETOOTH_CONNECT; fall back to the address
            }
            return dev.getAddress();
        }
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = p.getString(KEY_NAME, null);
        return name != null ? name : p.getString(KEY_MAC, null);
    }

    // ---- scanning ------------------------------------------------------------------------------

    /** Start a BLE scan (caller must hold BLUETOOTH_SCAN + location). Surfaces named devices and any that
     *  advertise a known Kawasaki service UUID. */
    public void startScan() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("bluetooth is off; enable it and scan again");
            return;
        }
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("no BLE scanner available");
            return;
        }
        scanning = true;
        scanAttempted = true;
        found.clear();
        pushDevices();
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                consider(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) {
                    consider(r);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                log("scan failed, error " + errorCode);
            }
        };
        try {
            // Unfiltered scan for robustness; we match on name / advertised UUID in `consider`.
            scanner.startScan(null, settings, scanCallback);
            log("scanning for the motorcycle...");
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException e) {
            scanning = false;
            log("scan needs the Bluetooth/Location permissions granted");
        }
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean scanAttempted() {
        return scanAttempted;
    }

    // A stored Runnable so removeCallbacks matches; `this::stopScan` allocates a fresh object each call.
    private final Runnable scanTimeout = new Runnable() {
        @Override
        public void run() {
            stopScan();
            pushDevices(); // re-render at scan end (shows the found list, or the empty "none found" state)
        }
    };

    public void stopScan() {
        main.removeCallbacks(scanTimeout);
        scanning = false;
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Throwable ignored) {
                // adapter may be off
            }
        }
        scanCallback = null;
    }

    private void consider(ScanResult result) {
        BluetoothDevice dev = result.getDevice();
        if (dev == null) {
            return;
        }
        boolean advertisedMatch = false;
        if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
            for (ParcelUuid p : result.getScanRecord().getServiceUuids()) {
                for (UUID known : Kawasaki.ADVERTISED) {
                    if (p.getUuid().equals(known)) {
                        advertisedMatch = true;
                        break;
                    }
                }
            }
        }
        String name = null;
        try {
            name = dev.getName();
        } catch (SecurityException ignored) {
            // needs BLUETOOTH_CONNECT; leave name null
        }
        if (!advertisedMatch && name == null) {
            return; // skip anonymous noise
        }
        if (found.put(dev.getAddress(), dev) == null) {
            log("found " + (name == null ? dev.getAddress() : name) + (advertisedMatch ? " [Kawasaki]" : ""));
            pushDevices();
        }
    }

    private void pushDevices() {
        final List<BluetoothDevice> snapshot = new ArrayList<>(found.values());
        final Ui u = ui;
        if (u != null) {
            main.post(() -> u.onDevices(snapshot));
        }
    }

    // ---- connect / disconnect / forget ---------------------------------------------------------

    /** Forces one direct attempt at {@code device} (scan-row tap or the Connect button). It becomes the
     *  target; a failure falls back to the passive wait through onLinkLost. */
    public void connect(BluetoothDevice device) {
        stopScan();
        main.removeCallbacks(rearm);
        paused = false;
        rejections = 0;
        target = device;
        try {
            ble.connect(device);
        } catch (SecurityException e) {
            log("connecting needs the Bluetooth permission granted");
        }
    }

    /** The Connect button: a direct attempt at the current target. */
    public void connectNow() {
        BluetoothDevice dev = resolveTarget();
        if (dev != null) {
            connect(dev);
        }
    }

    /** The Disconnect button: drops the link and pauses the passive wait until Connect or the next launch. */
    public void disconnect() {
        paused = true;
        main.removeCallbacks(rearm);
        ble.disconnect();
    }

    /**
     * Arms the passive link if there is a target and nothing is pending. Idempotent; called at launch, after
     * a link loss (via {@link #rearm}), when Bluetooth comes back on, and once permissions are granted.
     */
    public void ensurePassive() {
        BluetoothDevice dev = resolveTarget();
        if (dev == null || paused || ble.getState() != BleClient.State.IDLE) {
            return;
        }
        if (!isBluetoothOn()) {
            log("bluetooth is off; will wait for " + currentTargetLabel() + " once it is back on");
            return;
        }
        if (!isBonded(dev)) {
            // Waiting for an unbonded device would pop the passkey dialog unprompted whenever it appears
            // (e.g. a Bluetooth toggle mid-pairing). The saved MAC re-resolves only once bonded.
            log("target is not bonded; not waiting for it");
            target = null;
            return;
        }
        try {
            ble.waitFor(dev);
        } catch (SecurityException e) {
            log("waiting needs the Bluetooth permission granted; open Wazeology to grant it");
        }
    }

    private final Runnable rearm = new Runnable() {
        @Override
        public void run() {
            ensurePassive();
        }
    };

    /** Bluetooth off kills every handle silently; on brings the passive wait back without user action. */
    private void registerAdapterReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (st == BluetoothAdapter.STATE_ON) {
                    log("bluetooth on");
                    ensurePassive();
                } else if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                    // Close on TURNING_OFF, before the stack's own DISCONNECTED callbacks land, so they are
                    // rejected as stale instead of reaching onLinkLost with an unreadable bond state.
                    if (ble.getState() != BleClient.State.IDLE) {
                        log("bluetooth off");
                    }
                    ble.disconnect();
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Protected broadcast; EXPORTED for the same reason as the bond receiver in BleClient.
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    public void forget() {
        BluetoothDevice dev = target;
        target = null;
        main.removeCallbacks(rearm);
        ble.disconnect();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_MAC).remove(KEY_NAME).apply();
        if (dev != null) {
            try {
                dev.getClass().getMethod("removeBond").invoke(dev);
                log("removed bond for " + dev.getAddress());
            } catch (Throwable t) {
                log("could not remove bond (leave it paired in system settings if needed)");
            }
        }
    }

    // ---- Waze feed (called via the static wrappers from the hooks) -----------------------------

    private void onManeuver(int code, String name) {
        currentManeuverCode = code;
        currentManeuverName = name == null ? "" : name;
        // The exit ordinal only matters for roundabouts. Waze fires onExitNumberChanged ONLY when the
        // ordinal changes, so zeroing our copy on every maneuver stranded every roundabout at exit 1
        // (Waze never re-sent the unchanged ordinal). Keep it across roundabout instruction transitions;
        // clear it only when we leave roundabouts, so a stale ordinal can't linger onto a later one.
        if (!isRoundabout(currentManeuverName)) {
            currentExit = 0;
        }
    }

    private void onExitNumber(int exit) {
        currentExit = exit;
    }

    private void onDistance(int meters, String text, String unit) {
        if (currentManeuverCode < 0 || meters < 0) {
            return; // pre-route / uninitialized
        }
        final String name = currentManeuverName;
        final int exit = currentExit;
        final int m = displayedMeters(text, unit, meters);
        final String label = (text == null ? "" : text) + " " + (unit == null ? "" : unit) + " " + name;
        main.post(() -> {
            if ("NAV_INSTR_NONE".equals(name)) {
                sendCue(FlagMode.NO_NAVIGATION, TurnType.RESERVE, DistanceUnit.NOT_AVAILABLE, 0, NO_NAVIGATION);
            } else {
                sendCue(FlagMode.NAVIGATING, mapInstruction(name, exit), DistanceUnit.M, m, label);
            }
        });
    }

    private void onNavStateChanged(boolean navigating) {
        main.post(() -> {
            if (!navigating) {
                currentManeuverCode = -1;
                currentManeuverName = "";
                sendCue(FlagMode.NO_NAVIGATION, TurnType.RESERVE, DistanceUnit.NOT_AVAILABLE, 0, NO_NAVIGATION);
            }
        });
    }

    /** Waze Instruction$Type name -> cluster TurnType. Roundabouts use the CCW exit ordinal (pt-BR RHT). */
    static TurnType mapInstruction(String name, int exitOrdinal) {
        if (name == null) {
            return TurnType.STRAIGHT;
        }
        switch (name) {
            case "TURN_LEFT":
            case "PREPARE_TURN_LEFT":
                return TurnType.LEFT;
            case "TURN_RIGHT":
            case "PREPARE_TURN_RIGHT":
                return TurnType.RIGHT;
            case "CONTINUE_STRAIGHT":
                return TurnType.STRAIGHT;
            case "KEEP_LEFT":
                return TurnType.FORK_LEFT;
            case "KEEP_RIGHT":
                return TurnType.FORK_RIGHT;
            case "SLIGHT_LEFT":
                return TurnType.SLIGHT_LEFT;
            case "SLIGHT_RIGHT":
                return TurnType.SLIGHT_RIGHT;
            case "SHARP_LEFT":
                return TurnType.SHARP_LEFT;
            case "SHARP_RIGHT":
                return TurnType.SHARP_RIGHT;
            case "U_TURN":
                return TurnType.U_TURN_LEFT; // pt-BR right-hand traffic
            case "EXIT_LEFT":
            case "PREPARE_EXIT_LEFT":
                return TurnType.RAMP_OFF_LEFT;
            case "EXIT_RIGHT":
            case "PREPARE_EXIT_RIGHT":
                return TurnType.RAMP_OFF_RIGHT;
            case "APPROACHING_DESTINATION":
            case "LAST_DIRECTION":
                return TurnType.DESTINATION;
            case "APPROACHING_STOP_POINT":
            case "WAYPOINT_DELAY":
                return TurnType.WAYPOINT_1;
            case "ENTER_HOV_LANE":
                return TurnType.STRAIGHT;
            case "ROUNDABOUT_ENTER":
            case "ROUNDABOUT_LEFT":
            case "ROUNDABOUT_RIGHT":
            case "ROUNDABOUT_STRAIGHT":
            case "ROUNDABOUT_U":
            case "ROUNDABOUT_EXIT":
            case "ROUNDABOUT_EXIT_LEFT":
            case "ROUNDABOUT_EXIT_RIGHT":
            case "ROUNDABOUT_EXIT_STRAIGHT":
            case "ROUNDABOUT_EXIT_U":
                return roundabout(exitOrdinal);
            default:
                return TurnType.STRAIGHT;
        }
    }

    private static TurnType roundabout(int exit) {
        int n = Math.max(1, Math.min(12, exit <= 0 ? 1 : exit));
        return TurnType.fromId(TurnType.ROUNDABOUT_CCW_EXIT_1.id + n - 1);
    }

    /** Every roundabout Instruction$Type name is ROUNDABOUT* (see mapInstruction); nothing else is. */
    private static boolean isRoundabout(String name) {
        return name != null && name.startsWith("ROUNDABOUT");
    }

    /**
     * Waze's {@code distanceMeters} field is exact, but the number it shows on screen (and the one the
     * Rideology app mirrors) comes from {@code instructionDistance}, which Waze pre-rounds to nice
     * increments (nearest 10 m up close, 50/100 m farther out). Forwarding the exact int makes the
     * cluster's last digit disagree with Waze on almost every update (e.g. exact 13 m shown as "10").
     * Reconstruct metres from the already-rounded display string + unit so the cluster (which auto-rolls
     * metres into km itself) shows the same number the rider sees. Only metric display units are folded
     * back; anything else (imperial, "now", empty, unparseable) falls back to the exact metres, preserving
     * the previous behaviour.
     */
    static int displayedMeters(String text, String unit, int exactMeters) {
        if (text == null) {
            return exactMeters;
        }
        String t = text.trim().replace(',', '.'); // pt-BR shows "1,2 km"
        if (t.isEmpty()) {
            return exactMeters;
        }
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.US);
        double factor;
        if (u.equals("m")) {
            factor = 1.0;
        } else if (u.equals("km")) {
            factor = 1000.0;
        } else {
            return exactMeters; // imperial or unknown unit: keep the exact metres
        }
        try {
            long meters = Math.round(Double.parseDouble(t) * factor);
            return meters < 0 ? exactMeters : (int) meters;
        } catch (NumberFormatException e) {
            return exactMeters;
        }
    }

    // ---- cadence (sendCue/transmit/keepalive) ---------------------------------------------------

    private void sendCue(FlagMode flag, TurnType turn, DistanceUnit unit, int value, String label) {
        setStatus(label);
        long now = SystemClock.elapsedRealtime();
        Sent previous = lastSent;
        if (previous != null) {
            boolean same = previous.flag == flag && previous.turn == turn && previous.unit == unit && previous.value == value;
            long elapsed = now - previous.atMs;
            if (same && elapsed < KEEPALIVE_MS) {
                return;
            }
            if (!same && previous.flag == flag && elapsed < MIN_SEND_INTERVAL_MS) {
                return;
            }
        }
        Sent sent = new Sent(flag, turn, unit, value, label, now);
        lastSent = sent;
        log("cue: " + label + " [flag " + flag.name() + ", turn " + turn.name() + " (" + turn.id + "), " + value + " " + unit.name() + "]");
        transmit(sent);
    }

    /** Always logs the frame hex (dry-run visibility); only writes to the cluster when the link is READY. */
    private void transmit(Sent sent) {
        byte[] frame = Frames.turnByTurn(sent.flag, sent.turn, sent.unit, sent.value);
        boolean ready = ble.getState() == BleClient.State.READY;
        String opTag = String.format(Locale.US, "TX 0x%02X ", Kawasaki.OP_TURN_BY_TURN);
        log(opTag + (ready ? "" : "(dry-run) ") + Frames.hex(frame));
        if (ready) {
            ble.sendTurnByTurn(sent.label, sent.flag, sent.turn, sent.unit, sent.value);
        }
    }

    private final Runnable keepalive = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            Sent s = lastSent;
            if (s != null && now - s.atMs >= KEEPALIVE_MS) {
                s.atMs = now;
                transmit(s);
            }
            main.postDelayed(this, KEEPALIVE_MS);
        }
    };

    private void setStatus(String text) {
        if (text.equals(status)) {
            return;
        }
        status = text;
        final Ui u = ui;
        final String t = text;
        if (u != null) {
            main.post(() -> u.onStatus(t));
        }
    }

    // ---- BleClient.Listener --------------------------------------------------------------------

    @Override
    public void onLog(String line) {
        log(line);
    }

    @Override
    public void onStateChanged(BleClient.State bleState) {
        if (bleState == BleClient.State.READY) {
            main.removeCallbacks(rearm);
            rejections = 0;
            BluetoothDevice dev = target;
            if (dev != null) {
                SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_MAC, dev.getAddress());
                try {
                    String name = dev.getName();
                    if (name != null) {
                        e.putString(KEY_NAME, name);
                    }
                } catch (SecurityException ignored) {
                    // needs BLUETOOTH_CONNECT; keep any previously saved name
                }
                e.apply();
            }
            Sent s = lastSent;
            if (s != null) {
                transmit(s);
            }
        }
        final Ui u = ui;
        if (u != null) {
            main.post(() -> u.onState(bleState));
        }
    }

    // Only a bonded motorcycle is waited for; an unbonded one would re-pop the passkey prompt on every attempt.
    @Override
    public void onLinkLost(BluetoothDevice device) {
        BluetoothDevice dev = target;
        if (dev == null || !dev.getAddress().equals(device.getAddress())) {
            return;
        }
        if (!isBluetoothOn()) {
            // getBondState() reads BOND_NONE while the adapter is off; keep the target, STATE_ON re-arms.
            log("link lost with bluetooth off; waiting for " + currentTargetLabel() + " once it is back on");
            return;
        }
        if (!isBonded(device)) {
            log("link lost and not bonded; not waiting for it");
            dropTarget();
            return;
        }
        if (paused) {
            return;
        }
        log("link lost; waiting for " + currentTargetLabel());
        // Spaced so a misbehaving adapter (instant failures) cannot hot-loop; the wait itself has no timer.
        main.removeCallbacks(rearm);
        main.postDelayed(rearm, REARM_DELAY_MS);
    }

    @Override
    public void onRejected(BluetoothDevice device) {
        BluetoothDevice dev = target;
        if (dev == null || !dev.getAddress().equals(device.getAddress())) {
            return;
        }
        // A bonded device gets a second look: right after a bond the stack can hand back an empty, stale
        // service table (BleClient refreshes the cache before reporting). A device that keeps being
        // rejected is not the cluster; a first-ever pairing is unsaved, so this is its only retry path.
        if (isBonded(device) && rejections < MAX_REJECTIONS) {
            rejections++;
            log("not recognised as a cluster; retrying after the service cache refreshes");
            main.removeCallbacks(rearm);
            main.postDelayed(rearm, REARM_DELAY_MS);
            return;
        }
        dropTarget();
    }

    /** Forgets the live target (not the saved MAC). The next re-arm re-resolves the saved motorcycle, so a
     *  failed tap on some other device (or a stale-cache rejection of the bike itself) falls back to it
     *  instead of stranding the screen on "Waiting" with nothing armed. */
    private void dropTarget() {
        target = null;
        main.removeCallbacks(rearm);
        main.postDelayed(rearm, REARM_DELAY_MS);
    }

    private static boolean isBonded(BluetoothDevice device) {
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            return false; // needs BLUETOOTH_CONNECT; nothing can be waited for without it anyway
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private BluetoothAdapter adapter() {
        BluetoothManager mgr = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return mgr == null ? null : mgr.getAdapter();
    }

    private BluetoothDevice bondedByMac(String mac) {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return null;
        }
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equalsIgnoreCase(mac)) {
                    return d;
                }
            }
        } catch (SecurityException ignored) {
            // needs BLUETOOTH_CONNECT
        }
        return null;
    }

    private void log(String line) {
        Date now = new Date();
        String stamped = logClock.format(now) + " " + line;
        synchronized (logLines) {
            logLines.addLast(stamped);
            while (logLines.size() > LOG_MAX_LINES) {
                logLines.removeFirst();
            }
        }
        // Persist a date-stamped copy so the log survives the process and can be exported/rotated.
        logStore.append(logFileClock.format(now) + " " + line);
        final Ui u = ui;
        if (u != null) {
            main.post(() -> u.onLog(stamped));
        }
    }

    /** Full in-memory log (accumulated even while the screen is closed), for the on-screen export. */
    public String getLog() {
        StringBuilder b = new StringBuilder();
        synchronized (logLines) {
            for (String l : logLines) {
                b.append(l).append('\n');
            }
        }
        return b.toString();
    }

    /**
     * Export the full on-disk history (all rotated archives, oldest-to-newest, then the live file) into a
     * single plaintext file under {@code shareDir}, for the on-screen Share. Blocking; call off the UI
     * thread. Returns the written file, or {@code null} on failure.
     */
    public File exportFullLog(File shareDir) {
        return logStore.exportCombined(new File(shareDir, "wazeology-log.txt"));
    }

    public void clearLog() {
        synchronized (logLines) {
            logLines.clear();
        }
        logStore.clear();
    }
}
