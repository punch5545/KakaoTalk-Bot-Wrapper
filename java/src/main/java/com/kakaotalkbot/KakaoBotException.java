package com.kakaotalkbot;

/** Base class for all exceptions thrown by this library. */
public class KakaoBotException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public KakaoBotException(String message) {
        super(message);
    }

    public KakaoBotException(String message, Throwable cause) {
        super(message, cause);
    }
}
