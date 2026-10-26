package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Payload of the READY frame. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReadyData {
    @JsonProperty("session_id")
    private String sessionId;
    private Bot bot;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Bot getBot() {
        return bot;
    }

    public void setBot(Bot bot) {
        this.bot = bot;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bot {
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
