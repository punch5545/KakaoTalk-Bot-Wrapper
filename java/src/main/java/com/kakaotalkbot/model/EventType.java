package com.kakaotalkbot.model;

/** Chat-room event types delivered in DISPATCH frames. */
public enum EventType {
    MESSAGE_CREATE,
    ENTRANCE,
    CHAT_JOIN,
    LEAVE,
    AD_REWARD;

    /** Returns the event type for its wire name, or null when unknown. */
    public static EventType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (EventType t : values()) {
            if (t.name().equals(value)) {
                return t;
            }
        }
        return null;
    }
}
