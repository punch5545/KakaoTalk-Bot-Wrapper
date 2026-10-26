package com.kakaotalkbot;

import com.kakaotalkbot.model.CommandsResponse;
import com.kakaotalkbot.model.Guide;
import com.kakaotalkbot.model.SendResult;
import com.kakaotalkbot.model.UtteranceRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Thin wrapper around the KakaoTalk Bot REST API, built on {@link java.net.http.HttpClient}.
 *
 * <pre>{@code
 * KakaoBotClient client = new KakaoBotClient(System.getenv("BOT_TOKEN"));
 * client.sendCallback(callbackToken, SkillResponse.text("안녕하세요!"));
 * }</pre>
 *
 * Every operation has a blocking form and an {@code ...Async} form returning a {@link CompletableFuture}.
 */
public final class KakaoBotClient {
    public static final String DEFAULT_BASE_URL = "https://kapi.kakao.com";
    public static final int MAX_COMMANDS = 30;

    private final String token;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final Map<String, String> headers;

    public KakaoBotClient(String botToken) {
        this(builder(botToken));
    }

    private KakaoBotClient(Builder b) {
        this.token = b.botToken;
        this.baseUrl = b.baseUrl.replaceAll("/+$", "");
        this.timeout = b.timeout;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.http = b.httpClient != null
                ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(b.timeout).build();
    }

    public static Builder builder(String botToken) {
        return new Builder(botToken);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    /**
     * POST /v1/bot/callback — reply to an event using its callback token.
     * The token is single-use and expires 5 minutes after it was issued.
     */
    public SendResult sendCallback(String callbackToken, SkillResponse skillResponse) {
        return join(sendCallbackAsync(callbackToken, skillResponse));
    }

    public CompletableFuture<SendResult> sendCallbackAsync(String callbackToken, SkillResponse skillResponse) {
        if (callbackToken == null || callbackToken.isBlank()) {
            return failed(new IllegalArgumentException("callbackToken is required"));
        }
        Objects.requireNonNull(skillResponse, "skillResponse");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillResponse", skillResponse);
        return requestAsync("POST", "/v1/bot/callback", body,
                Collections.singletonMap("X-Bot-Callback-Token", callbackToken), SendResult.class);
    }

    /**
     * POST /v1/bot/send_message — proactively send a message. Exactly one of
     * {@code botGroupKey} (group/open/team chat) or {@code botUserKey} (channel chat) must be given.
     * Quota: 200/sec, 1,000/day.
     */
    public SendResult sendMessage(String botGroupKey, String botUserKey, SkillResponse skillResponse) {
        return join(sendMessageAsync(botGroupKey, botUserKey, skillResponse));
    }

    public CompletableFuture<SendResult> sendMessageAsync(String botGroupKey, String botUserKey, SkillResponse skillResponse) {
        boolean hasGroup = botGroupKey != null && !botGroupKey.isBlank();
        boolean hasUser = botUserKey != null && !botUserKey.isBlank();
        if (hasGroup == hasUser) {
            return failed(new IllegalArgumentException("Exactly one of botGroupKey or botUserKey must be provided"));
        }
        Objects.requireNonNull(skillResponse, "skillResponse");
        Map<String, Object> body = new LinkedHashMap<>();
        if (hasGroup) {
            body.put("botGroupKey", botGroupKey);
        } else {
            body.put("botUserKey", botUserKey);
        }
        body.put("skillResponse", skillResponse);
        return requestAsync("POST", "/v1/bot/send_message", body, Collections.emptyMap(), SendResult.class);
    }

    public SendResult sendToGroup(String botGroupKey, SkillResponse skillResponse) {
        return sendMessage(botGroupKey, null, skillResponse);
    }

    public CompletableFuture<SendResult> sendToGroupAsync(String botGroupKey, SkillResponse skillResponse) {
        return sendMessageAsync(botGroupKey, null, skillResponse);
    }

    /** Channel chat only. */
    public SendResult sendToUser(String botUserKey, SkillResponse skillResponse) {
        return sendMessage(null, botUserKey, skillResponse);
    }

    public CompletableFuture<SendResult> sendToUserAsync(String botUserKey, SkillResponse skillResponse) {
        return sendMessageAsync(null, botUserKey, skillResponse);
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    /** GET /v1/bot/commands */
    public CommandsResponse getCommands() {
        return join(getCommandsAsync());
    }

    public CompletableFuture<CommandsResponse> getCommandsAsync() {
        return requestAsync("GET", "/v1/bot/commands", null, Collections.emptyMap(), CommandsResponse.class);
    }

    /** POST /v1/bot/commands/update — replaces the whole command list (max 30). */
    public CommandsResponse updateCommands(List<UtteranceRequest> utterances) {
        return join(updateCommandsAsync(utterances));
    }

    public CompletableFuture<CommandsResponse> updateCommandsAsync(List<UtteranceRequest> utterances) {
        Objects.requireNonNull(utterances, "utterances");
        if (utterances.size() > MAX_COMMANDS) {
            return failed(new IllegalArgumentException("At most " + MAX_COMMANDS + " commands can be registered"));
        }
        return requestAsync("POST", "/v1/bot/commands/update",
                Collections.singletonMap("utterances", utterances), Collections.emptyMap(), CommandsResponse.class);
    }

    // ------------------------------------------------------------------
    // Guide
    // ------------------------------------------------------------------

    /** GET /v1/bot/guide */
    public Guide getGuide() {
        return join(getGuideAsync());
    }

    public CompletableFuture<Guide> getGuideAsync() {
        return requestAsync("GET", "/v1/bot/guide", null, Collections.emptyMap(), Guide.class);
    }

    /** POST /v1/bot/guide/update */
    public Guide updateGuide(String contents) {
        return join(updateGuideAsync(contents));
    }

    public CompletableFuture<Guide> updateGuideAsync(String contents) {
        Objects.requireNonNull(contents, "contents");
        return requestAsync("POST", "/v1/bot/guide/update",
                Collections.singletonMap("contents", contents), Collections.emptyMap(), Guide.class);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Low-level request; exposed for endpoints not yet wrapped. {@code body} is serialised with Jackson. */
    public <T> CompletableFuture<T> requestAsync(
            String method, String path, Object body, Map<String, String> extraHeaders, Class<T> type) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "KakaoAK " + token)
                .header("Accept", "application/json");
        headers.forEach(rb::header);
        extraHeaders.forEach(rb::header);
        if (body != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(res -> {
                    String text = res.body();
                    if (res.statusCode() >= 400) {
                        throw new KakaoBotApiException(res.statusCode(), text);
                    }
                    if (type == Void.class || text == null || text.isBlank()) {
                        return null;
                    }
                    return Json.convert(Json.read(text), type);
                });
    }

    static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof KakaoBotException) {
                throw (KakaoBotException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new KakaoBotException("Request failed: " + cause.getMessage(), cause);
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    /** Builder for {@link KakaoBotClient}. */
    public static final class Builder {
        private final String botToken;
        private String baseUrl = DEFAULT_BASE_URL;
        private HttpClient httpClient;
        private Duration timeout = Duration.ofSeconds(10);
        private final Map<String, String> headers = new LinkedHashMap<>();

        Builder(String botToken) {
            if (botToken == null || botToken.isBlank()) {
                throw new IllegalArgumentException("botToken is required");
            }
            this.botToken = botToken;
        }

        /** API base URL. Default: https://kapi.kakao.com */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl);
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Per-request timeout. Default: 10 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** Extra header sent with every request (e.g. User-Agent). */
        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public KakaoBotClient build() {
            return new KakaoBotClient(this);
        }
    }
}
