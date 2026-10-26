package com.kakaotalkbot;

import com.kakaotalkbot.model.GatewaySession;
import com.kakaotalkbot.model.ReadyData;
import java.time.Duration;

/** Callbacks for {@link KakaoBotGateway}. All methods have no-op defaults; override what you need. */
public interface GatewayListener {
    /** READY received after IDENTIFY. */
    default void onReady(ReadyData ready) {}

    /** RESUME frame sent on a reconnected socket; the session is considered live again. */
    default void onResumed(GatewaySession session) {}

    /** Any DISPATCH event (called before the type-specific callback). */
    default void onDispatch(DispatchEvent event) {}

    default void onMessageCreate(DispatchEvent event) {}

    default void onEntrance(DispatchEvent event) {}

    default void onChatJoin(DispatchEvent event) {}

    default void onLeave(DispatchEvent event) {}

    default void onAdReward(DispatchEvent event) {}

    /** The socket closed (before any reconnect decision). */
    default void onClose(int code, String reason) {}

    /** A reconnect has been scheduled. */
    default void onReconnecting(int attempt, Duration delay, boolean willResume) {}

    /** No HEARTBEAT_ACK since the previous heartbeat; the socket is being dropped. */
    default void onHeartbeatTimeout() {}

    /** Protocol errors, fatal close codes ({@link KakaoBotGatewayException#isFatal()}), listener failures. */
    default void onError(Throwable error) {}
}
