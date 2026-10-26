package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A registered command (GET /v1/bot/commands). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Utterance {
    private String id;
    private String type;
    private String value;
    private String buttonName;
    private String description;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** Currently always {@code TEXT}. */
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getButtonName() {
        return buttonName;
    }

    public void setButtonName(String buttonName) {
        this.buttonName = buttonName;
    }

    /** May be null. */
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "Utterance{id='" + id + "', value='" + value + "', buttonName='" + buttonName + "'}";
    }
}
