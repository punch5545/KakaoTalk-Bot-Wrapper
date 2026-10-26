package com.kakaotalkbot;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kakao skill response (version 2.0) sent with the message callback / send message APIs.
 *
 * <pre>{@code
 * SkillResponse res = SkillResponse.builder()
 *     .text("안녕하세요 " + Components.mentionTag("u1") + "!")
 *     .mention("u1", event.getBotUserKey())
 *     .quickReply("도움말")
 *     .build();
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SkillResponse {
    public static final int MAX_OUTPUTS = 3;
    public static final int MAX_QUICK_REPLIES = 10;
    public static final int MAX_MENTIONS = 15;

    private final String version;
    private final Template template;
    private final Map<String, Object> extra;

    @JsonCreator
    public SkillResponse(
            @JsonProperty("version") String version,
            @JsonProperty("template") Template template,
            @JsonProperty("extra") Map<String, Object> extra) {
        this.version = version == null ? "2.0" : version;
        this.template = template;
        this.extra = extra;
    }

    public String getVersion() {
        return version;
    }

    public Template getTemplate() {
        return template;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    /** A single SimpleText bubble. */
    public static SkillResponse text(String text) {
        return builder().text(text).build();
    }

    /** A single SimpleImage bubble. */
    public static SkillResponse image(String imageUrl, String altText) {
        return builder().image(imageUrl, altText).build();
    }

    /** A response made of the given components (1 to 3). */
    public static SkillResponse of(List<Map<String, Object>> outputs) {
        Builder b = builder();
        for (Map<String, Object> o : outputs) {
            b.add(o);
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String toJson() {
        return Json.write(this);
    }

    public static SkillResponse fromJson(String json) {
        return Json.convert(Json.read(json), SkillResponse.class);
    }

    @Override
    public String toString() {
        return toJson();
    }

    /** Template: outputs (bubbles) and optional quick replies. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Template {
        private final List<Map<String, Object>> outputs;
        private final List<Map<String, Object>> quickReplies;

        @JsonCreator
        public Template(
                @JsonProperty("outputs") List<Map<String, Object>> outputs,
                @JsonProperty("quickReplies") List<Map<String, Object>> quickReplies) {
            this.outputs = outputs == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(outputs));
            this.quickReplies = quickReplies == null || quickReplies.isEmpty()
                    ? null : Collections.unmodifiableList(new ArrayList<>(quickReplies));
        }

        public List<Map<String, Object>> getOutputs() {
            return outputs;
        }

        public List<Map<String, Object>> getQuickReplies() {
            return quickReplies;
        }
    }

    /** Fluent builder. */
    public static final class Builder {
        private final List<Map<String, Object>> outputs = new ArrayList<>();
        private final List<Map<String, Object>> quickReplies = new ArrayList<>();
        private final Map<String, Object> mentions = new LinkedHashMap<>();
        private final Map<String, Object> extra = new LinkedHashMap<>();

        Builder() {}

        /** Add any component, e.g. from {@link Components}. */
        public Builder add(Map<String, Object> component) {
            outputs.add(component);
            return this;
        }

        public Builder text(String text) {
            return add(Components.simpleText(text));
        }

        public Builder image(String imageUrl, String altText) {
            return add(Components.simpleImage(imageUrl, altText));
        }

        public Builder quickReply(String label) {
            return quickReply(label, label, null);
        }

        public Builder quickReply(String label, String messageText) {
            return quickReply(label, messageText, null);
        }

        public Builder quickReply(String label, String messageText, Map<String, Object> extra) {
            quickReplies.add(Buttons.quickReply(label, messageText, extra));
            return this;
        }

        /** Register a mention target; put {@link Components#mentionTag(String)} inside the text. */
        public Builder mention(String userKey, String botUserKey) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "botUserKey");
            m.put("id", botUserKey);
            mentions.put(userKey, m);
            return this;
        }

        public Builder extra(String key, Object value) {
            extra.put(key, value);
            return this;
        }

        public SkillResponse build() {
            if (outputs.isEmpty() || outputs.size() > MAX_OUTPUTS) {
                throw new IllegalStateException("outputs must contain 1 to " + MAX_OUTPUTS + " components");
            }
            if (quickReplies.size() > MAX_QUICK_REPLIES) {
                throw new IllegalStateException("quickReplies must contain at most " + MAX_QUICK_REPLIES + " items");
            }
            if (mentions.size() > MAX_MENTIONS) {
                throw new IllegalStateException("At most " + MAX_MENTIONS + " mentions are allowed per response");
            }
            Map<String, Object> ex = null;
            if (!mentions.isEmpty() || !extra.isEmpty()) {
                ex = new LinkedHashMap<>(extra);
                if (!mentions.isEmpty()) {
                    ex.put("mentions", new LinkedHashMap<>(mentions));
                }
            }
            return new SkillResponse("2.0", new Template(outputs, quickReplies), ex);
        }
    }
}
