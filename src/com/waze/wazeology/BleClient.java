package com.waze.wazeology;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * GATT state machine for the Kawasaki motorcycle: connect -> MTU -> bond -> subscribe -> init -> ready,
 * with a single-in-flight write queue. Caller must hold BLUETOOTH_CONNECT (and BLUETOOTH_SCAN/location
 * for discovery) before invoking.
 */
public final class BleClient {

    public interface Listener {
        void onLog(String line);
        void onStateChanged(State state);
        /** The link dropped or a connection attempt failed without the app asking for it. */
        void onLinkLost(BluetoothDevice device);
        /** The device answered but is not a BLE5 cluster (or its GATT cache was stale): drop it as a
         *  target rather than wait for it again. */
        void onRejected(BluetoothDevice device);
    }

    /** WAITING is a passive (autoConnect) handle with no link yet; it hops to CONNECTING when the link
     *  comes up, after which passive and direct share every step. */
    public enum State { IDLE, WAITING, CONNECTING, BONDING, SUBSCRIBING, INITIALIZING, READY }

    private static final int REQUESTED_MTU = 300;
    private static final int MIN_USABLE_MTU = 48;          // largest frame (45 B init) + 3 B ATT write header
    private static final long RESPONSE_TIMEOUT_MS = 3250L;   // how long to wait for a command's ACK
    private static final long METER_TICK_MS = 5000L;         // periodic meter-indication keepalive
    private static final long INTER_COMMAND_GAP_MS = 50L;    // spacing between queued control-point writes
    private static final long CCCD_RETRY_MS = 500L;          // retry delay when a notification enable is rejected
    // Watchdogs for the transient states: a step that stalls without any callback ends the attempt.
    private static final long CONNECT_TIMEOUT_MS = 30000L;   // connect + service discovery + MTU
    private static final long BOND_TIMEOUT_MS = 90000L;      // passkey entry on the phone
    private static final long SUBSCRIBE_TIMEOUT_MS = 15000L; // all CCCD writes
    private static final long INIT_TIMEOUT_MS = 30000L;      // silence between init commands (re-armed per command)

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private State state = State.IDLE;
    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private final ArrayDeque<Command> queue = new ArrayDeque<>();
    private Command current;
    private boolean writeDone;
    private boolean responseSeen;
    private int subscribeIndex;
    private boolean bondReceiverRegistered;
    private Capabilities capabilities;
    private int lastBatteryNibble = -1;   // last battery segment sent, so the meter tick only logs on change
    // Per-handle progress toward the MTU step; the stack may deliver onMtuChanged before discovery.
    private boolean servicesDiscovered;
    private boolean mtuSeen;

    public BleClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public Capabilities getCapabilities() {
        return capabilities;
    }

    private final Runnable responseTimeout = new Runnable() {
        @Override
        public void run() {
            if (current != null) {
                log("no response to " + current.label + " after " + (RESPONSE_TIMEOUT_MS / 1000.0) + " s, continuing");
            }
            finishCurrent();
        }
    };

    private final Runnable setupTimeout = new Runnable() {
        @Override
        public void run() {
            abandon("timed out while " + state + "; giving up this attempt");
        }
    };

    private final Runnable meterTick = new Runnable() {
        @Override
        public void run() {
            boolean alreadyQueued = false;
            for (Command c : queue) {
                if ((c.frame[0] & 0xFF) == Kawasaki.OP_METER_INDICATION) {
                    alreadyQueued = true;
                    break;
                }
            }
            boolean meterOk = capabilities == null || capabilities.meterIndicationSupported;
            if (state == State.READY && meterOk && !alreadyQueued) {
                byte[] frame = Frames.meterIndication(readBatteryNibble(), 15, 3, 0);
                enqueue(new Command("meter indication (13)", frame, Kawasaki.OP_METER_INDICATION));
            }
            main.postDelayed(this, METER_TICK_MS);
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            BluetoothDevice changed = bondedDevice(intent);
            if (changed == null || device == null) {
                return;
            }
            if (!changed.getAddress().equals(device.getAddress())) {
                return;
            }
            int bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (bond == BluetoothDevice.BOND_BONDING) {
                log("bonding in progress, enter the passkey shown on the cluster");
            } else if (bond == BluetoothDevice.BOND_BONDED) {
                log("bonded");
                unregisterBondReceiver();
                if (state != State.BONDING) {
                    return;
                }
                if (gatt == null) {
                    // The stack dropped the link while the passkey was being entered; reopen it now that
                    // the bond exists so afterMtu takes the already-bonded path.
                    openGatt(false);
                } else {
                    startSubscribe();
                }
            } else if (bond == BluetoothDevice.BOND_NONE) {
                abandon("bonding failed or was cancelled");
            }
        }
    };

    /** Forces one direct connection attempt (high-duty scan, ~30 s stack timeout). */
    public void connect(BluetoothDevice target) {
        disconnect();
        device = target;
        openGatt(false);
    }

    /** Arms a passive link: the stack connects whenever the (bonded) device appears. No timeout. */
    public void waitFor(BluetoothDevice target) {
        disconnect();
        device = target;
        openGatt(true);
    }

    /** The single opener for both modes. They differ only in the autoConnect flag here and in the
     *  WAITING -> CONNECTING hop when the link comes up; also used by the post-bond reopen. */
    private void openGatt(boolean autoConnect) {
        BluetoothDevice target = device;
        setState(autoConnect ? State.WAITING : State.CONNECTING);
        log((autoConnect ? "waiting for " : "connecting to ") + target.getAddress()
            + (autoConnect ? " (passive)" : " over LE"));
        BluetoothGatt g = null;
        try {
            g = target.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            log("connectGatt needs the Bluetooth permission granted");
        }
        gatt = g;
        if (g == null) {
            abandon("no GATT handle (is Bluetooth off?)");
        }
    }

    public void disconnect() {
        closeGatt();
        unregisterBondReceiver();
        setState(State.IDLE);
    }

    private void abandon(String reason) {
        abandon(reason, false);
    }

    /** Drops the stack's cached service table for this device so the next discovery is a real one
     *  (hidden API, same reflective pattern as removeBond; a failure just leaves the cache alone). */
    private void refreshCache(BluetoothGatt g) {
        try {
            g.getClass().getMethod("refresh").invoke(g);
        } catch (Throwable ignored) {
            // not available on this stack
        }
    }

    /** Ends the current attempt and hands the outcome to the bridge, which decides whether to wait again;
     *  {@code rejected} marks a device that answered but is not a cluster. */
    private void abandon(String reason, boolean rejected) {
        log(reason);
        BluetoothDevice lost = device;
        disconnect();
        if (lost == null) {
            return;
        }
        if (rejected) {
            listener.onRejected(lost);
        } else {
            listener.onLinkLost(lost);
        }
    }

    /** Tears down the GATT handle and in-flight commands only; bond receiver and state are left alone. */
    private void closeGatt() {
        main.removeCallbacks(responseTimeout);
        main.removeCallbacks(meterTick);
        queue.clear();
        current = null;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            log("connection closed");
        }
        gatt = null;
        capabilities = null;
        servicesDiscovered = false;
        mtuSeen = false;
    }

    public void sendTurnByTurn(String label, FlagMode flag, TurnType turn, DistanceUnit unit, int distance) {
        if (state != State.READY) {
            log("not ready, skipping: " + label);
            return;
        }
        if (capabilities != null && !capabilities.navigationSupported) {
            log("warning: cluster reported navigation unsupported, sending anyway");
        }
        queue.removeIf(c -> (c.frame[0] & 0xFF) == Kawasaki.OP_TURN_BY_TURN);
        enqueue(new Command(label, Frames.turnByTurn(flag, turn, unit, distance), Kawasaki.OP_TURN_BY_TURN));
    }

    private void enqueue(Command command) {
        queue.addLast(command);
        pump();
    }

    private void pump() {
        if (current != null) {
            return;
        }
        Command next = queue.pollFirst();
        if (next == null) {
            if (state == State.INITIALIZING) {
                onInitComplete();
            }
            return;
        }
        current = next;
        writeDone = false;
        responseSeen = false;
        main.postDelayed(() -> write(next), INTER_COMMAND_GAP_MS);
    }

    private void write(Command command) {
        if (current != command) {
            return;
        }
        BluetoothGatt g = gatt;
        BluetoothGattService service = g == null ? null : g.getService(Kawasaki.SERVICE);
        BluetoothGattCharacteristic characteristic = service == null ? null : service.getCharacteristic(Kawasaki.CONTROL_POINT);
        if (g == null || characteristic == null) {
            log("control point unavailable, dropping " + command.label);
            finishCurrent();
            return;
        }
        // Every control-point frame is statically known, so the log shows its stable FrameType code; a hex
        // dump would only repeat constant bytes. The meter frame is constant apart from its battery nibble
        // (byte 7 high nibble), which the log includes.
        int txOp = command.frame.length > 0 ? command.frame[0] & 0xFF : -1;
        if (txOp == Kawasaki.OP_METER_INDICATION) {
            int batt = command.frame.length > 7 ? (command.frame[7] >> 4) & 0x0F : 0x0F;
            log("TX METER batt=0x" + Integer.toHexString(batt).toUpperCase());
        } else {
            log("TX " + FrameType.label(txOp));
        }
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            started = g.writeCharacteristic(characteristic, command.frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
        } else {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(command.frame);
            started = g.writeCharacteristic(characteristic);
        }
        if (!started) {
            log("stack rejected write of " + command.label);
            finishCurrent();
        }
    }

    private void onWriteComplete(int status) {
        Command command = current;
        if (command == null) {
            return;
        }
        writeDone = true;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("write of " + command.label + " failed, status " + status);
            finishCurrent();
            return;
        }
        if (command.expects == null || responseSeen) {
            finishCurrent();
        } else {
            main.postDelayed(responseTimeout, RESPONSE_TIMEOUT_MS);
        }
    }

    private void finishCurrent() {
        main.removeCallbacks(responseTimeout);
        current = null;
        if (state == State.INITIALIZING) {
            // Progress-based: the 13-command init sequence can legitimately take longer than any single
            // deadline when the cluster ignores opcodes, so the watchdog only fires when nothing moves.
            armSetupTimeout(State.INITIALIZING);
        }
        pump();
    }

    private void onNotification(UUID uuid, byte[] value) {
        // ACKs are short and repeat constantly (one per write), so log which opcode they acknowledge as a
        // code. Data responses (model / capabilities / settings) carry live payload and fire once per
        // connection, so keep their hex; inspect() adds the parsed summary for the ones we decode.
        int rxOp = value.length > 0 ? value[0] & 0xFF : -1;
        if (rxOp == Kawasaki.OP_ACK) {
            // Label the ACK by the opcode it echoes at value[3]; the phone-name ACK doesn't carry it there
            // (see Frames.isResponseTo), so fall back to the in-flight command's expected opcode.
            int echoed = value.length > 3 ? value[3] & 0xFF : -1;
            Command awaiting = current;
            String what = FrameType.of(echoed) != null ? FrameType.label(echoed)
                : awaiting != null && awaiting.expects != null ? FrameType.label(awaiting.expects)
                : echoed >= 0 ? FrameType.label(echoed) : null;
            log(what != null ? "RX ACK -> " + what : "RX ACK");
        } else {
            log("RX " + FrameType.label(rxOp) + " " + Frames.hex(value));
        }
        inspect(value);
        Command command = current;
        if (command == null || command.expects == null) {
            return;
        }
        if (!Frames.isResponseTo(command.expects, value)) {
            return;
        }
        if (writeDone) {
            finishCurrent();
        } else {
            responseSeen = true;
        }
    }

    private void inspect(byte[] value) {
        if (value.length == 0) {
            return;
        }
        int op = value[0] & 0xFF;
        if (op == Kawasaki.OP_MODEL) {
            String model = Frames.parseModel(value);
            if (model != null) {
                log("cluster model code: " + model);
            }
        } else if (op == Kawasaki.OP_CAPABILITIES) {
            Capabilities caps = Frames.parseCapabilities(value);
            if (caps != null) {
                capabilities = caps;
                log("capabilities: navigation=" + caps.navigationSupported + " (nibble " + caps.navNibble
                    + "), meterIndication=" + caps.meterIndicationSupported);
            }
        }
    }

    private void afterMtu() {
        // The MTU step is only reachable from CONNECTING. A re-delivered onMtuChanged (remote MTU
        // renegotiation) or a rediscovery on a live link must not re-bond or re-run the subscribe/init.
        if (state != State.CONNECTING) {
            log("ignoring MTU/discovery callback in state " + state);
            return;
        }
        BluetoothDevice target = device;
        if (target == null) {
            return;
        }
        if (target.getBondState() == BluetoothDevice.BOND_BONDED) {
            log("already bonded");
            startSubscribe();
            return;
        }
        setState(State.BONDING);
        registerBondReceiver();
        log("requesting bond; the cluster should show a passkey to type on this phone");
        if (!target.createBond()) {
            log("createBond returned false (bonding may already be in progress)");
        }
    }

    private void startSubscribe() {
        setState(State.SUBSCRIBING);
        subscribeIndex = 0;
        subscribeNext();
    }

    private void subscribeNext() {
        BluetoothGatt g = gatt;
        if (g == null) {
            return;
        }
        if (subscribeIndex >= Kawasaki.NOTIFY.length) {
            startInit();
            return;
        }
        UUID uuid = Kawasaki.NOTIFY[subscribeIndex];
        BluetoothGattService service = g.getService(Kawasaki.SERVICE);
        BluetoothGattCharacteristic characteristic = service == null ? null : service.getCharacteristic(uuid);
        BluetoothGattDescriptor descriptor = characteristic == null ? null : characteristic.getDescriptor(Kawasaki.CCCD);
        if (characteristic == null || descriptor == null) {
            log("notify characteristic " + uuid + " or its CCCD missing, skipping");
            subscribeIndex++;
            subscribeNext();
            return;
        }
        g.setCharacteristicNotification(characteristic, true);
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            started = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                == BluetoothStatusCodes.SUCCESS;
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            started = g.writeDescriptor(descriptor);
        }
        if (!started) {
            log("stack rejected CCCD write for " + uuid + ", retrying in " + CCCD_RETRY_MS + " ms");
            main.postDelayed(this::subscribeNext, CCCD_RETRY_MS);
        }
    }

    private void startInit() {
        setState(State.INITIALIZING);
        queue.clear();
        List<Command> seq = Frames.initSequence(Build.MODEL);
        for (Command c : seq) {
            queue.addLast(c);
        }
        pump();
    }

    private void onInitComplete() {
        setState(State.READY);
        log("init sequence finished; ready to send navigation cues");
        main.removeCallbacks(meterTick);
        main.post(meterTick);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) {
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (state == State.WAITING) {
                        setState(State.CONNECTING); // passive and direct share everything from here
                    }
                    log("connected (status " + status + "), discovering services");
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (state == State.BONDING && device != null
                            && device.getBondState() == BluetoothDevice.BOND_BONDING) {
                        // Android often drops the ACL while createBond runs the encryption handshake. The
                        // bond continues at the OS level, so keep BONDING and the receiver; BOND_BONDED
                        // reopens the link, BOND_NONE tears it down. Only wait when the OS really is
                        // mid-bond: if createBond never started one, nothing would ever fire.
                        log("link dropped while pairing (status " + status + "); waiting for bonding to finish");
                        closeGatt();
                        return;
                    }
                    abandon("disconnected by remote or stack, status " + status);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) {
                    return;
                }
                if (state != State.CONNECTING) {
                    // A spontaneous rediscovery on a live link must not tear it down or re-request the MTU.
                    log("ignoring service discovery in state " + state);
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Transient (common right after a fresh bond while the GATT cache refreshes).
                    abandon("service discovery failed, status " + status);
                    return;
                }
                if (g.getService(Kawasaki.SERVICE) == null) {
                    // Also the shape of a stale, empty GATT cache right after a re-bond, so the bridge decides:
                    // an unbonded non-cluster is dropped, a bonded target is waited for again.
                    String servicePrefix = Kawasaki.SERVICE.toString().substring(0, 8);
                    refreshCache(g);
                    abandon("BLE5 service " + servicePrefix + " not found; not a BLE5 cluster, or a stale cache", true);
                    return;
                }
                servicesDiscovered = true;
                if (mtuSeen) {
                    log("BLE5 cluster service found, MTU already negotiated");
                    afterMtu();
                    return;
                }
                log("BLE5 cluster service found, requesting MTU " + REQUESTED_MTU);
                if (!g.requestMtu(REQUESTED_MTU)) {
                    afterMtu();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> {
                if (g != gatt) {
                    return;
                }
                log("MTU " + mtu + " (status " + status + ")");
                // Only an early (pre-discovery) exchange is deferred on; a failed or tiny one must not
                // stop onServicesDiscovered from requesting ours. In the normal ordering the callback
                // proceeds regardless of status, as before.
                mtuSeen = status == BluetoothGatt.GATT_SUCCESS && mtu >= MIN_USABLE_MTU;
                if (!servicesDiscovered) {
                    // Delivered ahead of discovery (Android 14 fans MTU changes out to every client on
                    // the ACL). Subscribing now would run against an empty service table and reach
                    // READY with nothing enabled; onServicesDiscovered picks the step up instead.
                    log("MTU arrived before service discovery; waiting for services");
                    return;
                }
                afterMtu();
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (g != gatt || state != State.SUBSCRIBING) {
                    return;
                }
                String uuid = descriptor.getCharacteristic().getUuid().toString();
                String shortUuid = uuid.substring(0, Math.min(8, uuid.length()));
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("notifications enabled on " + shortUuid);
                } else {
                    log("enabling notifications on " + shortUuid + " failed, status " + status
                        + " (5 or 15 means the link needs bonding)");
                }
                subscribeIndex++;
                subscribeNext();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            main.post(() -> {
                if (g == gatt) {
                    onWriteComplete(status);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            byte[] copy = value.clone();
            UUID uuid = characteristic.getUuid();
            main.post(() -> {
                if (g == gatt) {
                    onNotification(uuid, copy);
                }
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw == null) {
                return;
            }
            byte[] copy = raw.clone();
            UUID uuid = characteristic.getUuid();
            main.post(() -> {
                if (g == gatt) {
                    onNotification(uuid, copy);
                }
            });
        }
    };

    @SuppressWarnings("deprecation")
    private BluetoothDevice bondedDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private void registerBondReceiver() {
        if (bondReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Sent by the Bluetooth app (not system_server), which a NOT_EXPORTED receiver can miss on 33+.
            // It is a protected broadcast, so only privileged senders can emit it: EXPORTED is safe.
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(bondReceiver, filter);
        }
        bondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!bondReceiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
        bondReceiverRegistered = false;
    }

    private void setState(State next) {
        if (state == next) {
            return;
        }
        state = next;
        armSetupTimeout(next);
        listener.onStateChanged(next);
    }

    /** Re-arms the watchdog for a transient state, or clears it for IDLE / READY. It deliberately
     *  survives the mid-bond closeGatt (state stays BONDING), the one case that could otherwise wait forever. */
    private void armSetupTimeout(State s) {
        main.removeCallbacks(setupTimeout);
        long ms;
        switch (s) {
            case CONNECTING:
                ms = CONNECT_TIMEOUT_MS;
                break;
            case BONDING:
                ms = BOND_TIMEOUT_MS;
                break;
            case SUBSCRIBING:
                ms = SUBSCRIBE_TIMEOUT_MS;
                break;
            case INITIALIZING:
                ms = INIT_TIMEOUT_MS;
                break;
            default:
                return;
        }
        main.postDelayed(setupTimeout, ms);
    }

    /** Reads the phone battery via the sticky ACTION_BATTERY_CHANGED intent and maps it to the cluster's
     *  battery segment code. Registering a null receiver is a synchronous query of the last sticky intent:
     *  nothing is registered, so there is no receiver to unregister. A null intent or missing extras read as
     *  unknown -> NOT_AVAILABLE. Logs only when the segment changes, to avoid spamming at the meter cadence. */
    private int readBatteryNibble() {
        int pct = -1;
        boolean charging = false;
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                pct = (int) Math.floor(level * 100.0 / scale);
            }
            charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        int nibble = Frames.batteryNibble(pct, charging);
        if (nibble != lastBatteryNibble) {
            log("battery " + (pct < 0 ? "unknown" : pct + "%") + (charging ? " charging" : "")
                + " -> segment 0x" + Integer.toHexString(nibble).toUpperCase());
            lastBatteryNibble = nibble;
        }
        return nibble;
    }

    private void log(String line) {
        listener.onLog(line);
    }
}
