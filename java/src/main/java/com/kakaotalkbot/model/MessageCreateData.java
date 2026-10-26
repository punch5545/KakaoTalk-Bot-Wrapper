package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of a MESSAGE_CREATE event. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageCreateData {
    private long id;
    private String content;
    private String timestamp;
    private boolean isChannelChatroom;
    private String botGroupKey;
    private String botUserKey;
    private String callbackToken;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /** ISO-8601 */
    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public boolean getIsChannelChatroom() {
        return isChannelChatroom;
    }

    public void setIsChannelChatroom(boolean isChannelChatroom) {
        this.isChannelChatroom = isChannelChatroom;
    }

    public String getBotGroupKey() {
        return botGroupKey;
    }

    public void setBotGroupKey(String botGroupKey) {
        this.botGroupKey = botGroupKey;
    }

    public String getBotUserKey() {
        return botUserKey;
    }

    public void setBotUserKey(String botUserKey) {
        this.botUserKey = botUserKey;
    }

    /** May be null; without it the event cannot be replied to. */
    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }
}
