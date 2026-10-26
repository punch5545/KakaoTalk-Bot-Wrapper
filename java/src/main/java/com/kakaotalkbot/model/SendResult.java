package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Response of the message callback / send message APIs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendResult {
    private int totalCount;
    private int sendCount;
    private int errorCount;

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getSendCount() {
        return sendCount;
    }

    public void setSendCount(int sendCount) {
        this.sendCount = sendCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    @Override
    public String toString() {
        return "SendResult{total=" + totalCount + ", sent=" + sendCount + ", errors=" + errorCount + '}';
    }
}
