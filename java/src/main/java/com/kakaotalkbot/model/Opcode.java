package com.kakaotalkbot.model;

/** Gateway frame opcodes. */
public enum Opcode {
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    READY(3),
    RESUME(6),
    HELLO(10),
    HEARTBEAT_ACK(11);

    private final int value;

    Opcode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** Returns the opcode for a numeric value, or null when unknown. */
    public static Opcode fromValue(int value) {
        for (Opcode op : values()) {
            if (op.value == value) {
                return op;
            }
        }
        return null;
    }
}
