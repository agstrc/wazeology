package com.waze.wazeology;

import java.util.Locale;

/**
 * Stable symbolic code per cluster frame, keyed by its opcode (the frame's first byte). Used by the
 * diagnostic log so a statically-known frame reads as a short code (e.g. {@code TX MODEL}, {@code RX ACK
 * -> METER}) instead of a full hex dump. The raw bytes of any coded frame are reconstructable from the
 * frame builders in {@link Frames} (asserted by {@code scripts/framecheck.sh}), so the code loses no
 * ground truth. Opcodes with no named {@link Kawasaki} constant appear here as the init labels do.
 */
public enum FrameType {
    MODEL(Kawasaki.OP_MODEL),
    MC_CONFIG(0x40),
    CAPABILITIES(Kawasaki.OP_CAPABILITIES),
    INFO_1D(0x1D),
    INFO_47(0x47),
    PHONE_NAME(Kawasaki.OP_PHONE_NAME),
    MC_INFO(0x41),
    GENERAL_SETTINGS(0x1B),
    VEHICLE_SETTINGS(0x48),
    SERVICE_INDICATOR(0x1E),
    CONFIG_08(0x08),
    UNKNOWN_45(0x45),
    UNKNOWN_42(0x42),
    METER(Kawasaki.OP_METER_INDICATION),
    NAV(Kawasaki.OP_TURN_BY_TURN),
    ACK(Kawasaki.OP_ACK);

    public final int op;

    FrameType(int op) {
        this.op = op;
    }

    private static final FrameType[] VALUES = values();

    /** The frame type for an opcode, or {@code null} when it is not one of the known frames. */
    public static FrameType of(int op) {
        for (FrameType t : VALUES) {
            if (t.op == op) {
                return t;
            }
        }
        return null;
    }

    /** A short label for an opcode: its symbolic code, or {@code 0xNN} when unrecognised. */
    public static String label(int op) {
        FrameType t = of(op);
        return t != null ? t.name() : String.format(Locale.US, "0x%02X", op);
    }
}
