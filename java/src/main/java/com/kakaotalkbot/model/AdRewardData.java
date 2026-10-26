package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** Payload of an AD_REWARD event. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdRewardData {
    private String botGroupKey;
    private UserRef viewer;
    private Map<String, Object> params;
    private String timestamp;
    private String callbackToken;

    public String getBotGroupKey() {
        return botGroupKey;
    }

    public void setBotGroupKey(String botGroupKey) {
        this.botGroupKey = botGroupKey;
    }

    public UserRef getViewer() {
        return viewer;
    }

    public void setViewer(UserRef viewer) {
        this.viewer = viewer;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
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
