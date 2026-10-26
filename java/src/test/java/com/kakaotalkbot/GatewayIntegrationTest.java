package com.kakaotalkbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.kakaotalkbot.model.GatewaySession;
import com.kakaotalkbot.model.ReadyData;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

/** Drives the gateway and REST client against in-process mock servers (no network). */
class GatewayIntegrationTest {
    private static final String BOT_TOKEN = "test-bot-token";
    private static final String BIG_ID_FRAME =
            "{\"op\":0,\"s\":1,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":558185822630215770,\"content\":\"hello\","
            + "\"timestamp\":\"t\",\"isChannelChatroom\":false,\"botGroupKey\":\"g\",\"botUserKey\":\"u\","
            + "\"callbackToken\":\"cb-gw\"}}";

    /** Recorded REST call. */
    static final class RestCall {
        final String method;
        final String path;
        final String callbackToken;
        final String authorization;
        final JsonNode body;

        RestCall(String method, String path, String callbackToken, String authorization, JsonNode body) {
            this.method = method;
            this.path = path;
            this.callbackToken = callbackToken;
            this.authorization = authorization;
            this.body = body;
        }
    }

    private static HttpServer startRest(List<RestCall> calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode json = body.isEmpty() ? null : Json.read(body);
            String path = exchange.getRequestURI().getPath();
            calls.add(new RestCall(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("X-Bot-Callback-Token"),
                    exchange.getRequestHeaders().getFirst("Authorization"), json));
            String response;
            int status = 200;
            if ("/v1/bot/callback".equals(path) || "/v1/bot/send_message".equals(path)) {
                if ("expired".equals(exchange.getRequestHeaders().getFirst("X-Bot-Callback-Token"))) {
                    status = 400;
                    response = "{\"code\":-881,\"msg\":\"expired token\"}";
                } else {
                    response = "{\"totalCount\":1,\"sendCount\":1,\"errorCount\":0}";
                }
            } else if ("/v1/bot/commands".equals(path)) {
                response = "{\"utterances\":[{\"id\":\"1\",\"type\":\"TEXT\",\"value\":\"도움말\",\"buttonName\":\"도움말\",\"description\":null}]}";
            } else if ("/v1/bot/guide/update".equals(path)) {
                response = "{\"contents\":" + json.get("contents") + ",\"createdAt\":\"c\",\"updatedAt\":null}";
            } else {
                status = 404;
                response = "{}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    @Test
    void restClientSendsHeadersAndMapsErrors() throws Exception {
        List<RestCall> calls = new CopyOnWriteArrayList<>();
        HttpServer rest = startRest(calls);
        try {
            KakaoBotClient client = KakaoBotClient.builder(BOT_TOKEN)
                    .baseUrl("http://127.0.0.1:" + rest.getAddress().getPort()).build();

            assertEquals(1, client.sendCallback("cb-1", SkillResponse.text("hi")).getSendCount());
            assertEquals("KakaoAK " + BOT_TOKEN, calls.get(0).authorization);
            assertEquals("cb-1", calls.get(0).callbackToken);
            assertEquals("hi", calls.get(0).body.at("/skillResponse/template/outputs/0/simpleText/text").asText());

            client.sendToGroup("grp", SkillResponse.text("x"));
            List<String> keys = new ArrayList<>();
            calls.get(1).body.fieldNames().forEachRemaining(keys::add);
            assertEquals(List.of("botGroupKey", "skillResponse"), keys);
            assertThrows(IllegalArgumentException.class, () -> client.sendMessage(null, null, SkillResponse.text("x")));

            assertEquals("도움말", client.getCommands().getUtterances().get(0).getValue());
            assertEquals("GET", calls.get(2).method);
            assertEquals("새 도움말", client.updateGuide("새 도움말").getContents());

            KakaoBotApiException e = assertThrows(KakaoBotApiException.class,
                    () -> client.sendCallback("expired", SkillResponse.text("x")));
            assertEquals(400, e.getStatus());
            assertEquals(Integer.valueOf(-881), e.getCode());
            assertTrue(e.isCallbackTokenError());
        } finally {
            rest.stop(0);
        }
    }

    @Test
    void gatewayFollowsProtocolAndReconnectRules() throws Exception {
        List<RestCall> restCalls = new CopyOnWriteArrayList<>();
        HttpServer rest = startRest(restCalls);

        List<String> serverLog = new CopyOnWriteArrayList<>();
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        WebSocketServer wss = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            @Override
            public void onStart() {
                started.countDown();
            }

            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                int n = connections.incrementAndGet();
                conn.setAttachment(n);
                conn.send("{\"op\":10,\"d\":{\"heartbeat_interval\":1500}}");
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                int n = conn.<Integer>getAttachment();
                JsonNode frame = Json.read(message);
                int op = frame.get("op").asInt();
                serverLog.add(n + ":" + op);
                if (op == 2) {
                    assertEquals(BOT_TOKEN, frame.at("/d/token").asText());
                    conn.send("{\"op\":3,\"d\":{\"session_id\":\"sess_" + n + "\",\"bot\":{\"id\":\"bot1\"}}}");
                    if (n == 1) {
                        conn.send(BIG_ID_FRAME);
                    } else if (n == 3) {
                        conn.close(4001, "auth failed");
                    }
                } else if (op == 1) {
                    conn.send("{\"op\":11}");
                    if (n == 1) {
                        conn.close(1001, "restart");
                    }
                } else if (op == 6) {
                    assertEquals("sess_1", frame.at("/d/session_id").asText());
                    assertEquals(1, frame.at("/d/seq").asInt());
                    conn.send("{\"op\":0,\"s\":2,\"t\":\"LEAVE\",\"d\":{\"botGroupKey\":\"g\",\"timestamp\":\"t\"}}");
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    conn.close(4009, "session expired");
                }
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

            @Override
            public void onError(WebSocket conn, Exception ex) {}
        };
        wss.setReuseAddr(true);
        wss.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "mock gateway did not start");

        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch fatal = new CountDownLatch(1);

        KakaoBotClient client = KakaoBotClient.builder(BOT_TOKEN)
                .baseUrl("http://127.0.0.1:" + rest.getAddress().getPort()).build();
        KakaoBotGateway gateway = KakaoBotGateway.builder(BOT_TOKEN)
                .url("ws://127.0.0.1:" + wss.getPort() + "/gateway")
                .client(client)
                .initialBackoff(Duration.ofMillis(50))
                .maxBackoff(Duration.ofMillis(200))
                .heartbeatMargin(Duration.ofMillis(1000)) // heartbeat every 1000 ms (minimum)
                .listener(new GatewayListener() {
                    @Override
                    public void onReady(ReadyData ready) {
                        events.add("ready:" + ready.getSessionId());
                    }

                    @Override
                    public void onResumed(GatewaySession session) {
                        events.add("resumed:" + session.getSessionId());
                    }

                    @Override
                    public void onReconnecting(int attempt, Duration delay, boolean willResume) {
                        events.add("reconnecting:" + attempt + ":" + willResume);
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        events.add("close:" + code);
                    }

                    @Override
                    public void onLeave(DispatchEvent event) {
                        events.add("LEAVE:" + event.getSeq());
                    }

                    @Override
                    public void onMessageCreate(DispatchEvent event) {
                        events.add("MESSAGE_CREATE:" + event.asMessageCreate().getId() + ":" + event.getContent());
                        events.add("replied:" + event.replyText("echo: " + event.getContent()).getSendCount());
                    }

                    @Override
                    public void onError(Throwable error) {
                        errors.add(error);
                        if (error instanceof KakaoBotGatewayException && ((KakaoBotGatewayException) error).isFatal()) {
                            fatal.countDown();
                        }
                    }
                })
                .build();

        try {
            gateway.connect();
            assertEquals(KakaoBotGateway.State.READY, gateway.getState());
            assertEquals("sess_1", gateway.getSession().getSessionId());

            assertTrue(fatal.await(10, TimeUnit.SECONDS), "expected a fatal error, events=" + events + " errors=" + errors);
            Thread.sleep(100); // let the event executor drain
        } finally {
            gateway.close();
            wss.stop(1000);
            rest.stop(0);
        }

        assertEquals(KakaoBotGateway.State.CLOSED, gateway.getState());
        assertEquals(List.of("ready:sess_1", "MESSAGE_CREATE:558185822630215770:hello", "replied:1"), events.subList(0, 3));
        assertTrue(events.contains("close:1001"), events.toString());
        assertTrue(events.contains("reconnecting:1:true"), "should RESUME after 1001: " + events);
        assertTrue(events.contains("resumed:sess_1"), events.toString());
        assertTrue(events.contains("LEAVE:2"), events.toString());
        assertTrue(events.contains("close:4009"), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("reconnecting:") && e.endsWith(":false")),
                "should IDENTIFY after 4009: " + events);
        assertTrue(events.contains("ready:sess_3"), events.toString());
        assertTrue(errors.stream().anyMatch(e -> e instanceof KakaoBotGatewayException
                && Integer.valueOf(4001).equals(((KakaoBotGatewayException) e).getCloseCode())));

        RestCall callback = restCalls.stream().filter(c -> c.path.equals("/v1/bot/callback")).findFirst().orElse(null);
        assertNotNull(callback);
        assertEquals("cb-gw", callback.callbackToken);
        assertEquals("echo: hello", callback.body.at("/skillResponse/template/outputs/0/simpleText/text").asText());

        assertEquals("1:2", serverLog.get(0)); // IDENTIFY
        assertTrue(serverLog.contains("1:1"), serverLog.toString()); // HEARTBEAT
        assertTrue(serverLog.contains("2:6"), serverLog.toString()); // RESUME
        assertTrue(serverLog.contains("3:2"), serverLog.toString()); // IDENTIFY after session timeout
    }
}
