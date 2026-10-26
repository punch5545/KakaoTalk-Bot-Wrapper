package com.kakaotalkbot.model;

/** WebSocket close codes used by the gateway. */
public final class CloseCode {
    /** Graceful server restart: RESUME immediately. */
    public static final int SERVER_RESTART = 1001;
    /** Token missing or invalid. Fatal. */
    public static final int AUTH_FAILED = 4001;
    /** Invalid payload. Fatal. */
    public static final int INVALID_PAYLOAD = 4003;
    /** Replaced by another connection (duplicate IDENTIFY). Fatal. */
    public static final int REPLACED = 4004;
    /** Session expired: drop the session and IDENTIFY again. */
    public static final int SESSION_TIMEOUT = 4009;

    private CloseCode() {}

    /** True for close codes after which the client must not reconnect. */
    public static boolean isFatal(int code) {
        return code == AUTH_FAILED || code == INVALID_PAYLOAD || code == REPLACED;
    }

    public static String describe(int code) {
        switch (code) {
            case SERVER_RESTART:
                return "서버 재시작 (Graceful shutdown)";
            case AUTH_FAILED:
                return "인증 실패 (토큰 누락 또는 오류)";
            case INVALID_PAYLOAD:
                return "유효하지 않은 페이로드";
            case REPLACED:
                return "다른 연결로 대체됨 (중복 IDENTIFY)";
            case SESSION_TIMEOUT:
                return "세션 만료";
            default:
                return "unknown close code " + code;
        }
    }
}
