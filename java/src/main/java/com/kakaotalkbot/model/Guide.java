package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Bot help text (GET /v1/bot/guide). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Guide {
    private String contents;
    private String createdAt;
    private String updatedAt;

    public String getContents() {
        return contents;
    }

    public void setContents(String contents) {
        this.contents = contents;
    }

    /** ISO-8601 */
    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** ISO-8601, null when never updated. */
    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
