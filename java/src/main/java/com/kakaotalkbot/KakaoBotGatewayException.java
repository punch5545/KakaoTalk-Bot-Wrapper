package com.kakaotalkbot;

/** Gateway protocol error, fatal close code, or reply attempted without a callback token. */
public class KakaoBotGatewayException extends KakaoBotException {
    private static final long serialVersionUID = 1L;

    private final Integer closeCode;
    private final boolean fatal;

    public KakaoBotGatewayException(String message) {
        this(message, null, false, null);
    }

    public KakaoBotGatewayException(String message, Throwable cause) {
        this(message, null, false, cause);
    }

    public KakaoBotGatewayException(String message, Integer closeCode, boolean fatal) {
        this(message, closeCode, fatal, null);
    }

    public KakaoBotGatewayException(String message, Integer closeCode, boolean fatal, Throwable cause) {
        super(message, cause);
        this.closeCode = closeCode;
        this.fatal = fatal;
    }

    /** WebSocket close code when the error originates from a close frame, else null. */
    public Integer getCloseCode() {
        return closeCode;
    }

    /** True when the gateway will not reconnect (4001 / 4003 / 4004). */
    public boolean isFatal() {
        return fatal;
    }
}
