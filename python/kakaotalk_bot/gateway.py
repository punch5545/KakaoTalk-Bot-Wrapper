"""Gateway (WebSocket) client for the KakaoTalk Bot API."""
from __future__ import annotations

import asyncio
import inspect
import json
import logging
import random
from typing import Any, Callable, Literal

import websockets
from websockets.exceptions import ConnectionClosed, InvalidHandshake, InvalidURI

from .client import KakaoBotClient
from .errors import KakaoBotGatewayError
from .events import DispatchEvent, create_dispatch_event, parse_frame
from .types import CLOSE_CODE_DESCRIPTIONS, EVENT_TYPES, FATAL_CLOSE_CODES, CloseCode, Opcode

DEFAULT_GATEWAY_URL = "wss://bot-gateway.kakao.com/gateway"

GatewayState = Literal["idle", "connecting", "ready", "reconnecting", "closed"]

#: Non-DISPATCH lifecycle events a listener can subscribe to.
LIFECYCLE_EVENTS: tuple[str, ...] = (
    "dispatch",
    "ready",
    "resumed",
    "close",
    "reconnecting",
    "heartbeat_timeout",
    "error",
)

Listener = Callable[..., Any]

log = logging.getLogger("kakaotalk_bot.gateway")


def describe_close_code(code: int | None) -> str:
    if code is None:
        return "connection lost"
    return CLOSE_CODE_DESCRIPTIONS.get(code, f"unknown close code {code}")


class _HeartbeatTimeout(Exception):
    pass


class KakaoBotGateway:
    """Gateway WebSocket client.

    Implements HELLO → IDENTIFY/RESUME → READY, heartbeats with ACK tracking,
    DISPATCH fan-out and reconnection with exponential backoff
    (1s → 2s → 4s … max 30s) following the official close-code rules.

    >>> gateway = KakaoBotGateway(BOT_TOKEN)
    >>> @gateway.on("MESSAGE_CREATE")
    ... async def on_message(event: DispatchEvent) -> None:
    ...     await event.reply_text(f"echo: {event.content}")
    >>> asyncio.run(gateway.run())
    """

    def __init__(
        self,
        bot_token: str,
        *,
        url: str = DEFAULT_GATEWAY_URL,
        client: KakaoBotClient | None = None,
        auto_reconnect: bool = True,
        initial_backoff: float = 1.0,
        max_backoff: float = 30.0,
        heartbeat_margin: float = 5.0,
        handshake_timeout: float = 15.0,
        connect_kwargs: dict[str, Any] | None = None,
    ) -> None:
        if not bot_token or not isinstance(bot_token, str):
            raise TypeError("bot_token is required")
        self._token = bot_token
        self.url = url
        self.client = client or KakaoBotClient(bot_token)
        self.auto_reconnect = auto_reconnect
        self.initial_backoff = initial_backoff
        self.max_backoff = max_backoff
        self.heartbeat_margin = heartbeat_margin
        self.handshake_timeout = handshake_timeout
        self._connect_kwargs = {"ping_interval": None, **(connect_kwargs or {})}

        self._listeners: dict[str, list[Listener]] = {}
        self._state: GatewayState = "idle"
        self._session_id: str | None = None
        self._last_seq = 0
        self._bot_id: str | None = None
        self._heartbeat_interval: float | None = None
        self._awaiting_ack = False
        self._ws: Any = None
        self._closed = False
        self._ready_event: asyncio.Event | None = None
        self._run_task: asyncio.Task[None] | None = None

    # ------------------------------------------------------------------
    # Listener registration
    # ------------------------------------------------------------------

    def on(self, event: str) -> Callable[[Listener], Listener]:
        """Decorator form of :meth:`add_listener`."""

        def decorator(fn: Listener) -> Listener:
            self.add_listener(event, fn)
            return fn

        return decorator

    def add_listener(self, event: str, fn: Listener) -> None:
        if event not in EVENT_TYPES and event not in LIFECYCLE_EVENTS:
            raise ValueError(f"Unknown event '{event}'. Use one of {EVENT_TYPES + LIFECYCLE_EVENTS}")
        self._listeners.setdefault(event, []).append(fn)

    def remove_listener(self, event: str, fn: Listener) -> None:
        listeners = self._listeners.get(event)
        if listeners and fn in listeners:
            listeners.remove(fn)

    async def _emit(self, event: str, *args: Any) -> None:
        for fn in list(self._listeners.get(event, ())):
            try:
                result = fn(*args)
                if inspect.isawaitable(result):
                    await result
            except Exception as exc:  # noqa: BLE001 - listener errors must not kill the loop
                log.exception("listener for %s raised", event)
                if event != "error":
                    await self._emit("error", exc)

    # ------------------------------------------------------------------
    # State
    # ------------------------------------------------------------------

    @property
    def state(self) -> GatewayState:
        return self._state

    @property
    def session(self) -> dict[str, Any]:
        """Current session info (persist it to RESUME after a process restart)."""
        return {"session_id": self._session_id, "last_seq": self._last_seq, "bot_id": self._bot_id}

    @property
    def heartbeat_interval(self) -> float | None:
        """Last ``heartbeat_interval`` announced by HELLO, in seconds."""
        return self._heartbeat_interval

    def restore_session(self, session_id: str, last_seq: int) -> None:
        """Make the next connection attempt RESUME instead of IDENTIFY."""
        self._session_id = session_id
        self._last_seq = last_seq

    def reset_session(self) -> None:
        self._session_id = None
        self._last_seq = 0

    def _ready(self) -> asyncio.Event:
        if self._ready_event is None:
            self._ready_event = asyncio.Event()
        return self._ready_event

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def run(self) -> None:
        """Connect and keep the connection alive until :meth:`close` is called.

        Raises ``KakaoBotGatewayError`` with ``fatal=True`` for close codes 4001/4003/4004,
        and propagates the connection error if the very first attempt fails.
        """
        self._closed = False
        ready = self._ready()
        attempt = 0
        ever_ready = False
        while not self._closed:
            self._state = "reconnecting" if ever_ready or attempt else "connecting"
            code: int | None = None
            reason = ""
            heartbeat_timed_out = False
            try:
                code, reason = await self._run_once()
            except _HeartbeatTimeout:
                heartbeat_timed_out = True
            except ConnectionClosed as exc:
                code = exc.rcvd.code if exc.rcvd else None
                reason = exc.rcvd.reason if exc.rcvd else ""
            except (OSError, InvalidHandshake, InvalidURI, asyncio.TimeoutError, KakaoBotGatewayError) as exc:
                if not ever_ready and attempt == 0:
                    self._state = "closed"
                    if isinstance(exc, KakaoBotGatewayError):
                        raise
                    raise KakaoBotGatewayError(f"Could not connect to gateway: {exc}") from exc
                log.warning("gateway connection failed: %s", exc)
            finally:
                self._ws = None
                self._awaiting_ack = False

            if ready.is_set():
                ever_ready = True
                attempt = 0
            ready.clear()

            if heartbeat_timed_out:
                await self._emit("heartbeat_timeout")
            await self._emit("close", code, reason)

            if self._closed:
                break
            if code in FATAL_CLOSE_CODES:
                self._state = "closed"
                error = KakaoBotGatewayError(
                    f"Gateway closed with fatal code {code}: {describe_close_code(code)}",
                    close_code=code,
                    fatal=True,
                )
                await self._emit("error", error)
                raise error
            if code == CloseCode.SESSION_TIMEOUT:
                self.reset_session()
            if not self.auto_reconnect:
                break

            attempt += 1
            immediate = code == CloseCode.SERVER_RESTART and attempt == 1 and self._session_id is not None
            delay = 0.0 if immediate else min(self.initial_backoff * 2 ** (attempt - 1), self.max_backoff)
            if delay:
                delay += random.uniform(0, min(0.25 * delay, 1.0))
            self._state = "reconnecting"
            await self._emit("reconnecting", attempt, delay, self._session_id is not None)
            await asyncio.sleep(delay)

        self._state = "closed"

    async def start(self) -> asyncio.Task[None]:
        """Run the gateway in a background task and return once the session is ready."""
        if self._run_task and not self._run_task.done():
            raise KakaoBotGatewayError("Gateway is already running")
        ready = self._ready()
        ready.clear()
        self._run_task = asyncio.create_task(self.run(), name="kakaotalk-bot-gateway")
        waiter = asyncio.ensure_future(ready.wait())
        done, _ = await asyncio.wait({self._run_task, waiter}, return_when=asyncio.FIRST_COMPLETED)
        if self._run_task in done and not waiter.done():
            waiter.cancel()
            self._run_task.result()  # re-raise the connection error
            raise KakaoBotGatewayError("Gateway stopped before becoming ready")
        return self._run_task

    async def wait_closed(self) -> None:
        if self._run_task:
            await self._run_task

    async def close(self, code: int = 1000, reason: str = "client closed") -> None:
        """Close the connection and stop reconnecting."""
        self._closed = True
        self._state = "closed"
        ws = self._ws
        if ws is not None:
            try:
                await ws.close(code=code, reason=reason)
            except Exception:  # noqa: BLE001
                pass
        task = self._run_task
        if task and not task.done() and asyncio.current_task() is not task:
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=5)
            except Exception:  # noqa: BLE001
                pass

    # ------------------------------------------------------------------
    # Connection
    # ------------------------------------------------------------------

    async def _run_once(self) -> tuple[int | None, str]:
        """One connection: handshake, then heartbeat + receive until the socket closes."""
        log.info("connecting to %s", self.url)
        async with websockets.connect(self.url, open_timeout=self.handshake_timeout, **self._connect_kwargs) as ws:
            self._ws = ws
            hello = parse_frame(await asyncio.wait_for(ws.recv(), self.handshake_timeout))
            if hello.get("op") != Opcode.HELLO or not isinstance(hello.get("d"), dict):
                raise KakaoBotGatewayError("Expected HELLO as the first frame")
            interval_ms = hello["d"].get("heartbeat_interval")
            if not isinstance(interval_ms, (int, float)) or interval_ms <= 0:
                raise KakaoBotGatewayError("HELLO frame without a valid heartbeat_interval")
            self._heartbeat_interval = interval_ms / 1000.0

            if self._session_id is not None:
                await ws.send(
                    json.dumps(
                        {
                            "op": Opcode.RESUME,
                            "d": {"token": self._token, "session_id": self._session_id, "seq": self._last_seq},
                        }
                    )
                )
                # The server does not re-send READY after RESUME; treat the session as live.
                self._become_ready()
                await self._emit("resumed", self.session)
            else:
                await ws.send(json.dumps({"op": Opcode.IDENTIFY, "d": {"token": self._token}}))

            heartbeat = asyncio.create_task(self._heartbeat_loop(ws, self._heartbeat_interval))
            try:
                await self._receive_loop(ws)
            finally:
                if not heartbeat.done():
                    heartbeat.cancel()
                try:
                    await heartbeat
                except (asyncio.CancelledError, _HeartbeatTimeout):
                    pass
                except Exception:  # noqa: BLE001
                    log.exception("heartbeat loop failed")
            if heartbeat.done() and not heartbeat.cancelled() and isinstance(heartbeat.exception(), _HeartbeatTimeout):
                raise _HeartbeatTimeout()
            return ws.close_code, ws.close_reason or ""

    def _become_ready(self) -> None:
        self._state = "ready"
        self._ready().set()

    async def _receive_loop(self, ws: Any) -> None:
        # Until READY/RESUME completes, bound the wait so a silent server does not hang us.
        while not self._ready().is_set():
            raw = await asyncio.wait_for(ws.recv(), self.handshake_timeout)
            await self._handle_frame(raw)
        async for raw in ws:
            await self._handle_frame(raw)

    async def _handle_frame(self, raw: str | bytes) -> None:
        try:
            frame = parse_frame(raw)
        except KakaoBotGatewayError as exc:
            await self._emit("error", exc)
            return

        op = frame.get("op")
        if op == Opcode.DISPATCH:
            seq = frame.get("s")
            if isinstance(seq, int):
                self._last_seq = seq
            event = create_dispatch_event(frame, self.client)
            await self._emit("dispatch", event)
            await self._emit(event.type, event)
        elif op == Opcode.READY:
            d = frame.get("d") or {}
            self._session_id = d.get("session_id")
            self._bot_id = (d.get("bot") or {}).get("id")
            self._last_seq = 0
            self._become_ready()
            log.info("READY — bot id=%s session=%s", self._bot_id, self._session_id)
            await self._emit("ready", d)
        elif op == Opcode.HEARTBEAT_ACK:
            self._awaiting_ack = False
        # HELLO is consumed in _run_once; unknown opcodes are ignored.

    async def _heartbeat_loop(self, ws: Any, interval: float) -> None:
        period = max(1.0, interval - self.heartbeat_margin)
        self._awaiting_ack = False
        while True:
            await asyncio.sleep(period)
            if self._awaiting_ack:
                log.warning("no HEARTBEAT_ACK within %.1fs — reconnecting", period)
                await ws.close(code=1000, reason="heartbeat timeout")
                raise _HeartbeatTimeout()
            self._awaiting_ack = True
            await ws.send(json.dumps({"op": Opcode.HEARTBEAT}))
