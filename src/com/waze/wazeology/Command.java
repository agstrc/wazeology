package com.waze.wazeology;

/** One control-point frame plus the opcode its ACK/response is expected to echo (null = fire-and-forget). */
public final class Command {
    public final String label;
    public final byte[] frame;
    public final Integer expects;

    public Command(String label, byte[] frame, Integer expects) {
        this.label = label;
        this.frame = frame;
        this.expects = expects;
    }
}
