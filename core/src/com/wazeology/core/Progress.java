package com.wazeology.core;

/** Byte or item progress, called from the working thread. total is -1 when unknown. */
public interface Progress {
    void onBytes(long done, long total);
}
