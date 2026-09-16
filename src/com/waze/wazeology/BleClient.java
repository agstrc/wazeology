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
    }

    public enum State { IDLE, CONNECTING, BONDING, SUBSCRIBING, INITIALIZING, READY }

    private static final int REQUESTED_MTU = 300;
    private static final long RESPONSE_TIMEOUT_MS = 3250L;   // how long to wait for a command's ACK
    private static final long METER_TICK_MS = 5000L;         // periodic meter-indication keepalive
    private static final long INTER_COMMAND_GAP_MS = 50L;    // spacing between queued control-point writes
    private static final long CCCD_RETRY_MS = 500L;          // retry delay when a notification enable is rejected

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
                enqueue(new Command("meter indication (13)", Frames.meterIndication(), Kawasaki.OP_METER_INDICATION));
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
                if (state == State.BONDING) {
                    startSubscribe();
                }
            } else if (bond == BluetoothDevice.BOND_NONE) {
                log("bonding failed or was cancelled");
                disconnect();
            }
        }
    };

    public void connect(BluetoothDevice target) {
        disconnect();
        device = target;
        setState(State.CONNECTING);
        log("connecting to " + target.getAddress() + " over LE");
        gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            log("connectGatt returned null (is Bluetooth off?)");
            disconnect();
            listener.onLinkLost(target);
        }
    }

    public void disconnect() {
        main.removeCallbacks(responseTimeout);
        main.removeCallbacks(meterTick);
        queue.clear();
        current = null;
        unregisterBondReceiver();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            log("connection closed");
        }
        gatt = null;
        capabilities = null;
        setState(State.IDLE);
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
        log("TX " + command.label + ": " + Frames.hex(command.frame));
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
        pump();
    }

    private void onNotification(UUID uuid, byte[] value) {
        String tag = uuid.toString();
        log("RX[" + tag.substring(0, Math.min(4, tag.length())) + "] " + Frames.hex(value));
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
                    log("connected (status " + status + "), discovering services");
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("disconnected by remote or stack, status " + status);
                    BluetoothDevice lost = device;
                    disconnect();
                    if (lost != null) {
                        listener.onLinkLost(lost);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) {
                    return;
                }
                if (g.getService(Kawasaki.SERVICE) == null) {
                    String servicePrefix = Kawasaki.SERVICE.toString().substring(0, 8);
                    log("BLE5 service " + servicePrefix + " not found; this is not a BLE5 cluster");
                    disconnect();
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
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
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
        listener.onStateChanged(next);
    }

    private void log(String line) {
        listener.onLog(line);
    }
}
