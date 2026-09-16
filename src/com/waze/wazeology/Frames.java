package com.waze.wazeology;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Frame builders + response parsers. The byte layout of each frame is preserved exactly. */
public final class Frames {
    private Frames() {}

    private static final byte FF = (byte) 0xFF;

    public static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append(String.format("%02X", bytes[i]));
        }
        return b.toString();
    }

    public static byte[] request(int op) {
        return request(op, 0);
    }

    public static byte[] request(int op, int arg) {
        return new byte[] { (byte) op, 0, (byte) arg };
    }

    private static byte[] filled(int size, int... header) {
        byte[] frame = new byte[size];
        Arrays.fill(frame, FF);
        for (int i = 0; i < header.length; i++) {
            frame[i] = (byte) header[i];
        }
        return frame;
    }

    public static byte[] phoneName(String model) {
        byte[] full;
        try {
            full = model.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            full = model.getBytes();
        }
        int len = Math.min(full.length, 24);
        byte[] name = Arrays.copyOf(full, len);
        byte[] frame = new byte[35]; // zero-filled (NOT 0xFF)
        frame[0] = (byte) Kawasaki.OP_PHONE_NAME;
        frame[1] = 0x20;
        frame[3] = FF;
        frame[4] = FF;
        for (int block = 0; block < 3; block++) {
            int start = block * 8;
            if (name.length <= start) {
                break;
            }
            int offset = 5 + block * 10;
            frame[offset] = 0x05;
            frame[offset + 1] = (byte) (block + 1);
            int end = Math.min(start + 8, name.length);
            System.arraycopy(name, start, frame, offset + 2, end - start);
        }
        return frame;
    }

    public static byte[] generalSettingsRequest() {
        return filled(15, 0x1B, 0x0C, 0x00, 0xFF, 0xFF, 0x05, 0x0A, 0x00);
    }

    public static byte[] vehicleSettingsRequest() {
        return filled(45, 0x48, 0x2A, 0x00, 0xFF, 0xFF, 0x05, 0x09, 0x00);
    }

    public static byte[] serviceIndicatorRequest() {
        byte[] frame = filled(45, 0x1E, 0x2A, 0x01, 0xFF, 0xFF, 0x05, 0x0C, 0x00);
        frame[15] = 0x05; frame[16] = 0x0D; frame[17] = 0x00;
        frame[25] = 0x05; frame[26] = 0x0E; frame[27] = 0x00;
        return frame;
    }

    public static byte[] config08() {
        return new byte[] {
            0x08, 0x0C, 0x00, FF, FF, 0x0A, 0x08, 0x01, 0x78, 0x03,
            (byte) 0xE8, 0x00, (byte) 0xC8, 0x00, 0x64,
        };
    }

    public static byte[] meterIndication() {
        return meterIndication(15, 15, 3, 0);
    }

    public static byte[] meterIndication(int battery, int voice, int headset, int ridingLog) {
        byte[] frame = filled(15, Kawasaki.OP_METER_INDICATION, 0x0C, 0x00, 0xFF, 0xFF, 0x05, 0x00);
        frame[7] = (byte) ((battery << 4) | (voice & 0x0F));
        frame[8] = (byte) ((headset << 4) | (ridingLog & 0x0F));
        return frame;
    }

    public static byte[] turnByTurn(FlagMode flag, TurnType turn, DistanceUnit unit, int distance) {
        byte[] frame = filled(35, Kawasaki.OP_TURN_BY_TURN, 0x08, 0x00, 0xFF, 0xFF, 0x05, 0x70);
        frame[7] = (byte) ((flag.id << 4) & 0xFF);
        frame[13] = (byte) (((unit.id & 0x0F) << 4) & 0xFF);
        frame[15] = 0x05;
        frame[16] = 0x71;
        frame[17] = (byte) turn.id;
        frame[18] = (byte) ((distance >> 16) & 0xFF);
        frame[19] = (byte) ((distance >> 8) & 0xFF);
        frame[20] = (byte) (distance & 0xFF);
        return frame;
    }

    public static List<Command> initSequence(String phoneModel) {
        List<Command> cmds = new ArrayList<>(13);
        cmds.add(new Command("model (03)", request(0x03), 0x03));
        cmds.add(new Command("mc config (40)", request(0x40), 0x40));
        cmds.add(new Command("capabilities (1A)", request(0x1A), 0x1A));
        cmds.add(new Command("info (1D)", request(0x1D), 0x1D));
        cmds.add(new Command("info (47)", request(0x47), 0x47));
        cmds.add(new Command("phone name (0B)", phoneName(phoneModel), Kawasaki.OP_PHONE_NAME));
        cmds.add(new Command("mc info (41)", request(0x41), 0x41));
        cmds.add(new Command("general settings (1B)", generalSettingsRequest(), 0x1B));
        cmds.add(new Command("vehicle settings (48)", vehicleSettingsRequest(), 0x48));
        cmds.add(new Command("service indicator (1E)", serviceIndicatorRequest(), 0x1E));
        cmds.add(new Command("config (08)", config08(), 0x08));
        cmds.add(new Command("unknown (45)", request(0x45), 0x45));
        cmds.add(new Command("unknown (42)", request(0x42, 1), 0x42));
        return cmds;
    }

    public static boolean isResponseTo(int expects, byte[] value) {
        if (value.length == 0) {
            return false;
        }
        int op = value[0] & 0xFF;
        if (op == expects) {
            return true;
        }
        if (op != Kawasaki.OP_ACK) {
            return false;
        }
        if (expects == Kawasaki.OP_PHONE_NAME) {
            return true;
        }
        return value.length > 3 && (value[3] & 0xFF) == expects;
    }

    public static String parseModel(byte[] value) {
        if (value.length < 28) {
            return null;
        }
        byte[] raw = new byte[17];
        System.arraycopy(value, 7, raw, 0, 8);
        System.arraycopy(value, 17, raw, 8, 8);
        raw[16] = value[27];
        if (raw[13] == FF) {
            Arrays.fill(raw, 13, 17, (byte) 0);
        }
        String s;
        try {
            s = new String(raw, "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            s = new String(raw);
        }
        return s.replaceAll("\\p{C}", "");
    }

    public static Capabilities parseCapabilities(byte[] value) {
        if (value.length < 14) {
            return null;
        }
        int b11 = value[11] & 0xFF;
        int b12 = value[12] & 0xFF;
        int b13 = value[13] & 0xFF;
        int navNibble = b12 >> 4;
        boolean meterOff = (b11 & 0x0F) == 15 && ((b12 & 0x0C) >> 2) == 3 && (b12 & 0x03) == 3
            && ((b13 & 0xC0) >> 6) == 3 && ((b13 & 0x30) >> 4) == 3;
        return new Capabilities(navNibble != 15, !meterOff, navNibble);
    }
}
