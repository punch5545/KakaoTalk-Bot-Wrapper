package com.kakaotalkbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.kakaotalkbot.model.CloseCode;
import com.kakaotalkbot.model.GatewaySession;
import com.kakaotalkbot.model.Opcode;
import com.kakaotalkbot.model.ReadyData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * KakaoTalk Bot Gateway (WebSocket) client built on {@link java.net.http.WebSocket}.
 *
 * <p>Implements HELLO → IDENTIFY/RESUME → READY, periodic HEARTBEAT with ACK tracking,
 * DISPATCH fan-out to {@link GatewayListener}s, and reconnection with exponential backoff
 * (1s → 2s → 4s … max 30s) following the official close-code rules.
 *
 * <pre>{@code
 * KakaoBotGateway gateway = KakaoBotGateway.builder(System.getenv("BOT_TOKEN"))
 *     .listener(new GatewayListener() {
 *         @Override public void onMessageCreate(DispatchEvent event) {
 *             event.replyText("echo: " + event.getContent());
 *         }
 *     })
 *     .build();
 * gateway.connect();
 * }</pre>
 *
 * <p>Listener callbacks run on a single-threaded event executor by default, so events are
 * delivered in order. A gateway instance is not reusable after {@link #close()}.
 */
public final class KakaoBotGateway implements AutoCloseable {
    public static final String DEFAULT_URL = "wss://bot-gateway.kakao.com/gateway";

    public enum State { IDLE, CONNECTING, READY, RECONNECTING, CLOSED }

    private static final String HEARTBEAT_JSON = "{\"op\":1}";

    private final String token;
    private final URI uri;
    private final KakaoBotClient client;
    private final HttpClient http;
    private final boolean autoReconnect;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final long heartbeatMarginMs;
    private final long handshakeTimeoutMs;
    private final ScheduledExecutorService scheduler;
    private final Executor eventExecutor;
    private final boolean ownsScheduler;
    private final boolean ownsEventExecutor;
    private final List<GatewayListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private volatile State state = State.IDLE;
    private volatile String sessionId;
    private final AtomicLong lastSeq = new AtomicLong();
    private volatile String botId;
    private volatile Long heartbeatIntervalMs;
    private final AtomicBoolean awaitingAck = new AtomicBoolean();
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> reconnectTask;
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final AtomicBoolean closedByUser = new AtomicBoolean();
    private volatile CompletableFuture<Void> handshake;
    private final Object sendLock = new Object();
    private CompletableFuture<Object> sendChain = CompletableFuture.completedFuture(null);

    public KakaoBotGateway(String botToken) {
        this(builder(botToken));
    }

    private KakaoBotGateway(Builder b) {
        this.token = b.botToken;
        this.uri = URI.create(b.url);
        this.client = b.client != null ? b.client : new KakaoBotClient(b.botToken);
        this.autoReconnect = b.autoReconnect;
        this.initialBackoffMs = b.initialBackoff.toMillis();
        this.maxBackoffMs = b.maxBackoff.toMillis();
        this.heartbeatMarginMs = b.heartbeatMargin.toMillis();
        this.handshakeTimeoutMs = b.handshakeTimeout.toMillis();
        this.http = b.httpClient != null
                ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(b.handshakeTimeout).build();
        this.ownsScheduler = b.scheduler == null;
        this.scheduler = b.scheduler != null
                ? b.scheduler
                : Executors.newSingleThreadScheduledExecutor(daemon("kakaotalk-bot-gateway"));
        this.ownsEventExecutor = b.eventExecutor == null;
        this.eventExecutor = b.eventExecutor != null
                ? b.eventExecutor
                : Executors.newSingleThreadExecutor(daemon("kakaotalk-bot-events"));
        this.listeners.addAll(b.listeners);
    }

    public static Builder builder(String botToken) {
        return new Builder(botToken);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void addListener(GatewayListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(GatewayListener listener) {
        listeners.remove(listener);
    }

    public State getState() {
        return state;
    }

    /** REST client used for {@link DispatchEvent#reply(SkillResponse)}. */
    public KakaoBotClient getClient() {
        return client;
    }

    /** Current session info (persist it to RESUME after a process restart). */
    public GatewaySession getSession() {
        return new GatewaySession(sessionId, lastSeq.get(), botId);
    }

    /** Last {@code heartbeat_interval} announced by HELLO, in milliseconds (null before HELLO). */
    public Long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    /** Make the next connection attempt RESUME instead of IDENTIFY. */
    public void restoreSession(String sessionId, long lastSeq) {
        this.sessionId = sessionId;
        this.lastSeq.set(lastSeq);
    }

    public void resetSession() {
        this.sessionId = null;
        this.lastSeq.set(0);
    }

    /**
     * Connect and complete once the session is READY (or RESUMEd). Fails if the first attempt
     * fails; later disconnects are handled by auto-reconnect and reported to listeners.
     */
    public CompletableFuture<Void> connectAsync() {
        CompletableFuture<Void> pending = handshake;
        if (socket.get() != null || (pending != null && !pending.isDone())) {
            return failed(new KakaoBotGatewayException("Gateway is already connected or connecting"));
        }
        if (closedByUser.get() && scheduler.isShutdown()) {
            return failed(new KakaoBotGatewayException("Gateway was closed and cannot be reused"));
        }
        closedByUser.set(false);
        reconnectAttempt.set(0);
        return openSocket(false);
    }

    /** Blocking form of {@link #connectAsync()}. */
    public void connect() {
        try {
            connectAsync().get(handshakeTimeoutMs + 5_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KakaoBotGatewayException("Interrupted while connecting", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof KakaoBotException) {
                throw (KakaoBotException) cause;
            }
            throw new KakaoBotGatewayException("Could not connect to gateway: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new KakaoBotGatewayException("Timed out waiting for the gateway handshake", e);
        }
    }

    /** Close the connection, stop reconnecting and shut down owned executors. */
    @Override
    public void close() {
        closedByUser.set(true);
        ScheduledFuture<?> rt = reconnectTask;
        if (rt != null) {
            rt.cancel(false);
        }
        stopHeartbeat();
        state = State.CLOSED;
        WebSocket ws = socket.getAndSet(null);
        if (ws != null && !ws.isOutputClosed()) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closed").exceptionally(e -> null);
            } catch (RuntimeException ignored) {
                ws.abort();
            }
        }
        CompletableFuture<Void> hs = handshake;
        if (hs != null) {
            hs.completeExceptionally(new KakaoBotGatewayException("Gateway closed by client"));
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
        if (ownsEventExecutor && eventExecutor instanceof ExecutorService) {
            ((ExecutorService) eventExecutor).shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    private CompletableFuture<Void> openSocket(boolean isReconnect) {
        state = isReconnect ? State.RECONNECTING : State.CONNECTING;
        CompletableFuture<Void> hs = new CompletableFuture<>();
        handshake = hs;
        SocketListener listener = new SocketListener(isReconnect, hs);

        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(handshakeTimeoutMs))
                .buildAsync(uri, listener)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                        emitError(cause);
                        hs.completeExceptionally(new KakaoBotGatewayException(
                                "Could not connect to gateway: " + cause.getMessage(), cause));
                        if (isReconnect) {
                            scheduleReconnect(1006);
                        } else {
                            state = State.CLOSED;
                        }
                    }
                });

        try {
            scheduler.schedule(() -> {
                if (!hs.isDone()) {
                    emitError(new KakaoBotGatewayException("Gateway handshake timed out"));
                    WebSocket ws = listener.ws;
                    if (ws != null) {
                        ws.abort();
                        listener.disconnected(ws, 1006, "handshake timeout");
                    }
                }
            }, handshakeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // scheduler shut down: close() was called
        }
        return hs;
    }

    /** Per-connection protocol handler. */
    private final class SocketListener implements WebSocket.Listener {
        private final boolean isReconnect;
        private final CompletableFuture<Void> hs;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicBoolean handled = new AtomicBoolean();
        private volatile WebSocket ws;

        SocketListener(boolean isReconnect, CompletableFuture<Void> hs) {
            this.isReconnect = isReconnect;
            this.hs = hs;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.ws = webSocket;
            socket.set(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    handleFrame(webSocket, text);
                } catch (RuntimeException e) {
                    emitError(e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected(webSocket, statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            emitError(error);
            disconnected(webSocket, 1006, String.valueOf(error.getMessage()));
        }

        void disconnected(WebSocket webSocket, int code, String reason) {
            if (!handled.compareAndSet(false, true)) {
                return;
            }
            socket.compareAndSet(webSocket, null);
            stopHeartbeat();
            emit(l -> l.onClose(code, reason == null ? "" : reason));
            boolean wasHandshaking = !hs.isDone();
            hs.completeExceptionally(new KakaoBotGatewayException(
                    "Gateway closed during handshake (code " + code + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")",
                    code, false));
            if (wasHandshaking && !isReconnect) {
                // Initial connect() failed: surface via the future, do not loop.
                state = State.CLOSED;
                return;
            }
            scheduleReconnect(code);
        }

        private void handleFrame(WebSocket webSocket, String text) {
            JsonNode frame;
            try {
                frame = Json.read(text);
            } catch (KakaoBotException e) {
                emitError(new KakaoBotGatewayException(e.getMessage(), e));
                return;
            }
            Opcode op = Opcode.fromValue(frame.path("op").asInt(-1));
            if (op == null) {
                return; // unknown opcode: ignore for forward compatibility
            }
            switch (op) {
                case HELLO: {
                    long interval = frame.path("d").path("heartbeat_interval").asLong(0);
                    if (interval <= 0) {
                        emitError(new KakaoBotGatewayException("HELLO frame without a valid heartbeat_interval"));
                        webSocket.abort();
                        disconnected(webSocket, 1006, "invalid HELLO");
                        return;
                    }
                    heartbeatIntervalMs = interval;
                    if (sessionId != null) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("token", token);
                        d.put("session_id", sessionId);
                        d.put("seq", lastSeq.get());
                        send(webSocket, Json.write(buildFrame(Opcode.RESUME, d)));
                        // The server does not re-send READY after a RESUME; treat the session as live.
                        becomeReady();
                        emit(l -> l.onResumed(getSession()));
                        hs.complete(null);
                    } else {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("token", token);
                        send(webSocket, Json.write(buildFrame(Opcode.IDENTIFY, d)));
                    }
                    startHeartbeat(webSocket, interval);
                    break;
                }
                case READY: {
                    ReadyData ready = Json.convert(frame.path("d"), ReadyData.class);
                    sessionId = ready.getSessionId();
                    botId = ready.getBot() != null ? ready.getBot().getId() : null;
                    lastSeq.set(0);
                    becomeReady();
                    emit(l -> l.onReady(ready));
                    hs.complete(null);
                    break;
                }
                case HEARTBEAT_ACK:
                    awaitingAck.set(false);
                    break;
                case DISPATCH: {
                    if (frame.hasNonNull("s") && frame.get("s").canConvertToLong()) {
                        lastSeq.set(frame.get("s").asLong());
                    }
                    DispatchEvent event = DispatchEvent.fromFrame(frame, client);
                    dispatch(event);
                    break;
                }
                default:
                    break;
            }
        }

        private void startHeartbeat(WebSocket webSocket, long intervalMs) {
            stopHeartbeat();
            long period = Math.max(1_000L, intervalMs - heartbeatMarginMs);
            awaitingAck.set(false);
            try {
                heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
                    if (socket.get() != webSocket) {
                        stopHeartbeat();
                        return;
                    }
                    if (awaitingAck.get()) {
                        // No ACK since the last heartbeat: the connection is probably dead.
                        emit(GatewayListener::onHeartbeatTimeout);
                        webSocket.abort();
                        disconnected(webSocket, 1006, "heartbeat timeout");
                        return;
                    }
                    awaitingAck.set(true);
                    send(webSocket, HEARTBEAT_JSON);
                }, period, period, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // closed
            }
        }
    }

    private static Map<String, Object> buildFrame(Opcode op, Map<String, Object> d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op.value());
        m.put("d", d);
        return m;
    }

    private void becomeReady() {
        state = State.READY;
        reconnectAttempt.set(0);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> t = heartbeatTask;
        if (t != null) {
            t.cancel(false);
            heartbeatTask = null;
        }
        awaitingAck.set(false);
    }

    /** Serialise outbound frames: java.net.http.WebSocket rejects overlapping sendText calls. */
    private void send(WebSocket ws, String text) {
        synchronized (sendLock) {
            sendChain = sendChain
                    .handle((v, e) -> null)
                    .thenCompose(v -> ws.isOutputClosed()
                            ? CompletableFuture.completedFuture((Object) null)
                            : ws.sendText(text, true).thenApply(w -> (Object) w))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            emitError(e instanceof CompletionException && e.getCause() != null ? e.getCause() : e);
                        }
                    });
        }
    }

    // ------------------------------------------------------------------
    // Reconnect
    // ------------------------------------------------------------------

    private void scheduleReconnect(int code) {
        if (closedByUser.get()) {
            state = State.CLOSED;
            return;
        }
        if (CloseCode.isFatal(code)) {
            state = State.CLOSED;
            emitError(new KakaoBotGatewayException(
                    "Gateway closed with fatal code " + code + ": " + CloseCode.describe(code), code, true));
            return;
        }
        if (code == CloseCode.SESSION_TIMEOUT) {
            resetSession();
        }
        if (!autoReconnect) {
            state = State.CLOSED;
            return;
        }
        int attempt = reconnectAttempt.incrementAndGet();
        boolean immediate = code == CloseCode.SERVER_RESTART && attempt == 1 && sessionId != null;
        long delay = immediate ? 0 : Math.min((long) (initialBackoffMs * Math.pow(2, attempt - 1)), maxBackoffMs);
        state = State.RECONNECTING;
        boolean willResume = sessionId != null;
        emit(l -> l.onReconnecting(attempt, Duration.ofMillis(delay), willResume));
        ScheduledFuture<?> prev = reconnectTask;
        if (prev != null) {
            prev.cancel(false);
        }
        try {
            reconnectTask = scheduler.schedule(() -> {
                if (!closedByUser.get()) {
                    openSocket(true);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            state = State.CLOSED;
        }
    }

    // ------------------------------------------------------------------
    // Listener fan-out
    // ------------------------------------------------------------------

    private void dispatch(DispatchEvent event) {
        emit(l -> {
            l.onDispatch(event);
            switch (event.getType()) {
                case MESSAGE_CREATE:
                    l.onMessageCreate(event);
                    break;
                case ENTRANCE:
                    l.onEntrance(event);
                    break;
                case CHAT_JOIN:
                    l.onChatJoin(event);
                    break;
                case LEAVE:
                    l.onLeave(event);
                    break;
                case AD_REWARD:
                    l.onAdReward(event);
                    break;
                default:
                    break;
            }
        });
    }

    private void emit(Consumer<GatewayListener> fn) {
        Runnable task = () -> {
            for (GatewayListener l : listeners) {
                try {
                    fn.accept(l);
                } catch (RuntimeException e) {
                    notifyError(e);
                }
            }
        };
        try {
            eventExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // event executor shut down: close() was called
        }
    }

    private void emitError(Throwable error) {
        emit(l -> l.onError(error));
    }

    private void notifyError(Throwable error) {
        for (GatewayListener l : listeners) {
            try {
                l.onError(error);
            } catch (RuntimeException ignored) {
                // nothing sensible left to do
            }
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Builder for {@link KakaoBotGateway}. */
    public static final class Builder {
        private final String botToken;
        private String url = DEFAULT_URL;
        private KakaoBotClient client;
        private HttpClient httpClient;
        private boolean autoReconnect = true;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(30);
        private Duration heartbeatMargin = Duration.ofSeconds(5);
        private Duration handshakeTimeout = Duration.ofSeconds(15);
        private ScheduledExecutorService scheduler;
        private Executor eventExecutor;
        private final List<GatewayListener> listeners = new CopyOnWriteArrayList<>();

        Builder(String botToken) {
            if (botToken == null || botToken.isBlank()) {
                throw new IllegalArgumentException("botToken is required");
            }
            this.botToken = botToken;
        }

        /** Gateway URL. Default: wss://bot-gateway.kakao.com/gateway */
        public Builder url(String url) {
            this.url = Objects.requireNonNull(url);
            return this;
        }

        /** REST client used by {@link DispatchEvent#reply(SkillResponse)}. Default: new client from the token. */
        public Builder client(KakaoBotClient client) {
            this.client = client;
            return this;
        }

        /** HttpClient used for the WebSocket connection. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Reconnect automatically on non-fatal disconnects. Default: true */
        public Builder autoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
            return this;
        }

        /** First reconnect delay (doubles each attempt). Default: 1s */
        public Builder initialBackoff(Duration d) {
            this.initialBackoff = Objects.requireNonNull(d);
            return this;
        }

        /** Upper bound for the reconnect delay. Default: 30s */
        public Builder maxBackoff(Duration d) {
            this.maxBackoff = Objects.requireNonNull(d);
            return this;
        }

        /** Send heartbeats this much earlier than {@code heartbeat_interval}. Default: 5s */
        public Builder heartbeatMargin(Duration d) {
            this.heartbeatMargin = Objects.requireNonNull(d);
            return this;
        }

        /** Abort a connection attempt when READY/RESUME does not complete in time. Default: 15s */
        public Builder handshakeTimeout(Duration d) {
            this.handshakeTimeout = Objects.requireNonNull(d);
            return this;
        }

        /** Scheduler for heartbeats and reconnects. Default: an owned single daemon thread. */
        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /** Executor that runs listener callbacks. Default: an owned single daemon thread (ordered delivery). */
        public Builder eventExecutor(Executor executor) {
            this.eventExecutor = executor;
            return this;
        }

        public Builder listener(GatewayListener listener) {
            listeners.add(Objects.requireNonNull(listener));
            return this;
        }

        public KakaoBotGateway build() {
            return new KakaoBotGateway(this);
        }
    }
}
