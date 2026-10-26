package com.kakaotalkbot;

import java.util.Map;

/** Factories for buttons and quick replies. */
public final class Buttons {
    private Buttons() {}

    private static Map<String, Object> button(String action, String label) {
        return Components.map("action", action, "label", label);
    }

    /** Sends {@code messageText} as the user's utterance. */
    public static Map<String, Object> message(String label, String messageText) {
        return message(label, messageText, null);
    }

    public static Map<String, Object> message(String label, String messageText, Map<String, Object> extra) {
        return Components.map("action", "message", "label", label, "messageText", messageText, "extra", extra);
    }

    public static Map<String, Object> webLink(String label, String webLinkUrl) {
        return Components.map("action", "webLink", "label", label, "webLinkUrl", webLinkUrl);
    }

    /** Not shown on PC KakaoTalk. */
    public static Map<String, Object> phone(String label, String phoneNumber) {
        return Components.map("action", "phone", "label", label, "phoneNumber", phoneNumber);
    }

    public static Map<String, Object> share(String label) {
        return button("share", label);
    }

    public static Map<String, Object> operator(String label) {
        return button("operator", label);
    }

    public static Map<String, Object> invite(String label) {
        return button("invite", label);
    }

    public static Map<String, Object> mention(String label) {
        return button("mention", label);
    }

    public static Map<String, Object> inviteMember(String label) {
        return button("inviteMember", label);
    }

    public static Map<String, Object> settings(String label) {
        return button("settings", label);
    }

    public static Map<String, Object> guide(String label) {
        return button("guide", label);
    }

    public static Map<String, Object> quickReply(String label) {
        return quickReply(label, label, null);
    }

    public static Map<String, Object> quickReply(String label, String messageText) {
        return quickReply(label, messageText, null);
    }

    public static Map<String, Object> quickReply(String label, String messageText, Map<String, Object> extra) {
        return Components.map("label", label, "action", "message", "messageText", messageText, "extra", extra);
    }
}
