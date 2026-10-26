package com.kakaotalkbot.model;

/** Session state needed to RESUME a gateway connection. */
public final class GatewaySession {
    private final String sessionId;
    private final long lastSeq;
    private final String botId;

    public GatewaySession(String sessionId, long lastSeq, String botId) {
        this.sessionId = sessionId;
        this.lastSeq = lastSeq;
        this.botId = botId;
    }

    /** Null until READY has been received. */
    public String getSessionId() {
        return sessionId;
    }

    /** Last DISPATCH sequence number seen on this session. */
    public long getLastSeq() {
        return lastSeq;
    }

    public String getBotId() {
        return botId;
    }

    @Override
    public String toString() {
        return "GatewaySession{sessionId='" + sessionId + "', lastSeq=" + lastSeq + ", botId='" + botId + "'}";
    }
}
