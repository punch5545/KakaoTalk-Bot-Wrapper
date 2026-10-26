package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A user reference ({@code { "botUserKey": "..." }}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRef {
    private String botUserKey;

    public String getBotUserKey() {
        return botUserKey;
    }

    public void setBotUserKey(String botUserKey) {
        this.botUserKey = botUserKey;
    }
}
