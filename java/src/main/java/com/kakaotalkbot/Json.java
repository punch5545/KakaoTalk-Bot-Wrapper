package com.kakaotalkbot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared Jackson configuration. */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() {}

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new KakaoBotException("Failed to serialise JSON", e);
        }
    }

    static JsonNode read(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node == null) {
                throw new KakaoBotException("Invalid JSON: empty document");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new KakaoBotException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    static <T> T convert(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new KakaoBotException("Failed to map JSON to " + type.getSimpleName(), e);
        }
    }
}
