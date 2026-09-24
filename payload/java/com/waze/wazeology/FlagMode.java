package com.waze.wazeology;

/** Navigation flag mode -> high nibble of byte 7 of the 0x14 frame. */
public enum FlagMode {
    NAVIGATING(0),
    ROUTING(1),
    COMPLETED(2),
    NO_NAVIGATION(3),
    NOT_AVAILABLE(15);

    public final int id;

    FlagMode(int id) {
        this.id = id;
    }
}
