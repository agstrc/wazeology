package com.waze.wazeology;

/** Distance unit -> high nibble of byte 13 of the 0x14 frame. We always send M (see ClusterBridge). */
public enum DistanceUnit {
    KM(0),
    M(1),
    MILE(2),
    FT(3),
    YD(4),
    NOT_AVAILABLE(15);

    public final int id;

    DistanceUnit(int id) {
        this.id = id;
    }
}
