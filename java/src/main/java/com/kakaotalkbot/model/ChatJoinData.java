package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of a CHAT_JOIN event (a user joined a room). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatJoinData {
    private String botGroupKey;
    private UserRef user;
    private String timestamp;
    private String callbackToken;

    public String getBotGroupKey() {
        return botGroupKey;
    }

    public void setBotGroupKey(String botGroupKey) {
        this.botGroupKey = botGroupKey;
    }

    public UserRef getUser() {
        return user;
    }

    public void setUser(UserRef user) {
        this.user = user;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }
}
