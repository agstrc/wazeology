package com.waze.wazeology;

/** Parsed from the 0x1A response: whether the cluster supports nav / meter-indication frames. */
public final class Capabilities {
    public final boolean navigationSupported;
    public final boolean meterIndicationSupported;
    public final int navNibble;

    public Capabilities(boolean navigationSupported, boolean meterIndicationSupported, int navNibble) {
        this.navigationSupported = navigationSupported;
        this.meterIndicationSupported = meterIndicationSupported;
        this.navNibble = navNibble;
    }
}
