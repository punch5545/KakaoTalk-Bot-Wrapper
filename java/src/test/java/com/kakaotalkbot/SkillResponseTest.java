package com.kakaotalkbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.kakaotalkbot.model.EventType;
import com.kakaotalkbot.model.MessageCreateData;
import org.junit.jupiter.api.Test;

class SkillResponseTest {

    @Test
    void buildsSkillResponseWithMentionsAndQuickReplies() throws Exception {
        SkillResponse res = SkillResponse.builder()
                .text("hello " + Components.mentionTag("u1"))
                .mention("u1", "buk_1")
                .quickReply("도움말")
                .build();
        JsonNode json = Json.MAPPER.readTree(res.toJson());
        assertEquals("2.0", json.get("version").asText());
        assertEquals("hello {{#mentions.u1}}", json.at("/template/outputs/0/simpleText/text").asText());
        assertEquals("도움말", json.at("/template/quickReplies/0/messageText").asText());
        assertEquals("message", json.at("/template/quickReplies/0/action").asText());
        assertEquals("buk_1", json.at("/extra/mentions/u1/id").asText());
        assertEquals("botUserKey", json.at("/extra/mentions/u1/type").asText());
    }

    @Test
    void textResponseOmitsOptionalFields() throws Exception {
        JsonNode json = Json.MAPPER.readTree(SkillResponse.text("hi").toJson());
        assertEquals("{\"version\":\"2.0\",\"template\":{\"outputs\":[{\"simpleText\":{\"text\":\"hi\"}}]}}", json.toString());
    }

    @Test
    void rejectsEmptyOutputs() {
        assertThrows(IllegalStateException.class, () -> SkillResponse.builder().build());
    }

    @Test
    void parsesWebhookFrameWithLargeId() {
        String raw = "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":558185822630215770,\"content\":\"안녕\","
                + "\"timestamp\":\"t\",\"isChannelChatroom\":false,\"botGroupKey\":\"g\",\"botUserKey\":\"u\","
                + "\"callbackToken\":\"cb\"}}";
        DispatchEvent ev = Webhook.parse(raw);
        assertEquals(EventType.MESSAGE_CREATE, ev.getType());
        assertNull(ev.getSeq());
        assertTrue(ev.canReply());
        assertEquals("u", ev.getBotUserKey());
        MessageCreateData data = ev.asMessageCreate();
        assertEquals(558185822630215770L, data.getId());
        assertEquals("안녕", data.getContent());
        assertFalse(data.getIsChannelChatroom());
        assertThrows(KakaoBotGatewayException.class, () -> ev.reply(SkillResponse.text("x")));
    }

    @Test
    void rejectsNonDispatchFrames() {
        assertThrows(KakaoBotGatewayException.class, () -> Webhook.parse("{\"op\":10,\"d\":{}}"));
        assertThrows(KakaoBotGatewayException.class, () -> Webhook.parse("{\"op\":0,\"t\":\"NOPE\",\"d\":{}}"));
        assertThrows(KakaoBotGatewayException.class, () -> Webhook.parse("not json"));
    }

    @Test
    void verifiesCustomHeader() {
        assertTrue(Webhook.verifyCustomHeader("secret", "secret"));
        assertFalse(Webhook.verifyCustomHeader("secre", "secret"));
        assertFalse(Webhook.verifyCustomHeader(null, "secret"));
    }

    @Test
    void parsesApiErrorBody() {
        KakaoBotApiException e = new KakaoBotApiException(400,
                "{\"code\":-870,\"msg\":\"TalkBotCommonException\",\"extras\":{\"code\":10001,\"message\":\"유효하지 않은 앱입니다.\"}}");
        assertEquals(400, e.getStatus());
        assertEquals(Integer.valueOf(-870), e.getCode());
        assertEquals("TalkBotCommonException", e.getMsg());
        assertEquals(10001, e.getExtras().get("code").asInt());
        assertTrue(e.getMessage().contains("extras.message=유효하지 않은 앱입니다."));
        assertTrue(new KakaoBotApiException(400, "{\"code\":-881,\"msg\":\"x\"}").isCallbackTokenError());
        assertNull(new KakaoBotApiException(502, "<html>").getCode());
    }
}
