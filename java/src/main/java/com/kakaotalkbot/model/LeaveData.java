package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of a LEAVE event (the bot left a room). No callback token. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaveData {
    private String botGroupKey;
    private String timestamp;

    public String getBotGroupKey() {
        return botGroupKey;
    }

    public void setBotGroupKey(String botGroupKey) {
        this.botGroupKey = botGroupKey;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
