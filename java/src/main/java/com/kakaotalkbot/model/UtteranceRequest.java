package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A command to register (POST /v1/bot/commands/update). */
public class UtteranceRequest {
    private String buttonName;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String description;

    public UtteranceRequest() {}

    public UtteranceRequest(String buttonName) {
        this(buttonName, null);
    }

    public UtteranceRequest(String buttonName, String description) {
        this.buttonName = buttonName;
        this.description = description;
    }

    public String getButtonName() {
        return buttonName;
    }

    public void setButtonName(String buttonName) {
        this.buttonName = buttonName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
