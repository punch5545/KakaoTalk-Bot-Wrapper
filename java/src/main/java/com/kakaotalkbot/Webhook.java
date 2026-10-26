package com.kakaotalkbot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Helpers for the webhook (HTTP) delivery mode. Respond 2xx within one second, then reply
 * asynchronously with {@link DispatchEvent#reply(SkillResponse)}.
 */
public final class Webhook {
    private Webhook() {}

    /** Parse a webhook request body into a {@link DispatchEvent} with reply support. */
    public static DispatchEvent parse(String json, KakaoBotClient client) {
        return DispatchEvent.fromJson(json, client);
    }

    public static DispatchEvent parse(byte[] body, KakaoBotClient client) {
        return parse(new String(body, StandardCharsets.UTF_8), client);
    }

    /** Parse without a client; {@code reply()} will throw. */
    public static DispatchEvent parse(String json) {
        return parse(json, null);
    }

    /**
     * Constant-time comparison of the custom header registered in the webhook settings
     * against the value received, to verify the request came from Kakao.
     */
    public static boolean verifyCustomHeader(String received, String expected) {
        if (received == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                received.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
