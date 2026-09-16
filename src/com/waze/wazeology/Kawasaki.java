package com.waze.wazeology;

import java.util.UUID;

/** GATT topology + opcodes for the Kawasaki BLE5 cluster. */
public final class Kawasaki {
    private Kawasaki() {}

    public static final UUID SERVICE = UUID.fromString("92faec07-c075-4b7c-a6c2-bbd1d1a150f5");
    public static final UUID CONTROL_POINT = UUID.fromString("acf1b15c-10f9-4942-a32d-f9e019b95402");
    public static final UUID[] NOTIFY = new UUID[] {
        UUID.fromString("3aabbb34-eac0-40f5-9d50-3a1ee6787136"),
        UUID.fromString("02fad1bd-358e-441c-b296-fe874af38a7e"),
        UUID.fromString("5e119eba-35a7-4463-a7af-7fa40a302350"),
    };
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID[] ADVERTISED = new UUID[] {
        UUID.fromString("c5664e44-d60b-4342-8b7b-35bd6ae28e3f"),
        UUID.fromString("4fbb7609-f50a-40c6-89a9-9be7ea880e95"),
        UUID.fromString("99eb13ac-42c9-4745-8b76-c30141401ce5"),
    };

    public static final int OP_ACK = 0x20;
    public static final int OP_MODEL = 0x03;
    public static final int OP_CAPABILITIES = 0x1A;
    public static final int OP_METER_INDICATION = 0x13;
    public static final int OP_TURN_BY_TURN = 0x14;
    public static final int OP_PHONE_NAME = 0x0B;
}
