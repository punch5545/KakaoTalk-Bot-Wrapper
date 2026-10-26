package com.kakaotalkbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Response of the command list / update APIs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommandsResponse {
    private List<Utterance> utterances;

    public List<Utterance> getUtterances() {
        return utterances;
    }

    public void setUtterances(List<Utterance> utterances) {
        this.utterances = utterances;
    }
}
