"""DISPATCH event parsing shared by the gateway and webhook helpers."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from .errors import KakaoBotGatewayError
from .skill import text_response
from .types import EVENT_TYPES, Opcode, SendResult, SkillResponse

if TYPE_CHECKING:
    from .client import KakaoBotClient


@dataclass(frozen=True)
class DispatchEvent:
    """A chat-room event received via the gateway or a webhook."""

    #: MESSAGE_CREATE / ENTRANCE / CHAT_JOIN / LEAVE / AD_REWARD
    type: str
    #: Sequence number (gateway only; None for webhook requests).
    seq: int | None
    #: Event payload (``d``).
    data: dict[str, Any]
    #: The raw DISPATCH frame.
    raw: dict[str, Any]
    _client: KakaoBotClient | None = field(default=None, repr=False, compare=False)

    @property
    def callback_token(self) -> str | None:
        token = self.data.get("callbackToken")
        return token if isinstance(token, str) and token else None

    @property
    def can_reply(self) -> bool:
        return self.callback_token is not None

    @property
    def bot_group_key(self) -> str | None:
        value = self.data.get("botGroupKey")
        return value if isinstance(value, str) else None

    @property
    def bot_user_key(self) -> str | None:
        """Sender / inviter / joined user / viewer key, depending on the event type."""
        for path in (("botUserKey",), ("inviter", "botUserKey"), ("user", "botUserKey"), ("viewer", "botUserKey")):
            node: Any = self.data
            for key in path:
                node = node.get(key) if isinstance(node, dict) else None
            if isinstance(node, str):
                return node
        return None

    @property
    def content(self) -> str | None:
        """Message text (MESSAGE_CREATE only)."""
        value = self.data.get("content")
        return value if isinstance(value, str) else None

    async def reply(self, skill_response: SkillResponse) -> SendResult:
        """Reply through POST /v1/bot/callback using this event's callback token."""
        token = self.callback_token
        if token is None:
            raise KakaoBotGatewayError(f"Event {self.type} has no callbackToken; it cannot be replied to")
        if self._client is None:
            raise KakaoBotGatewayError("No KakaoBotClient attached; cannot send callback")
        return await self._client.send_callback(token, skill_response)

    async def reply_text(self, text: str) -> SendResult:
        return await self.reply(text_response(text))


def parse_frame(text: str | bytes) -> dict[str, Any]:
    """Parse a gateway/webhook frame from JSON text."""
    try:
        frame = json.loads(text)
    except ValueError as exc:
        raise KakaoBotGatewayError(f"Invalid frame: not JSON ({exc})") from exc
    return normalise_frame(frame)


def normalise_frame(frame: Any) -> dict[str, Any]:
    """Validate an already-parsed frame object."""
    if not isinstance(frame, dict) or not isinstance(frame.get("op"), int):
        raise KakaoBotGatewayError('Invalid frame: missing integer "op"')
    if frame["op"] == Opcode.DISPATCH:
        if frame.get("t") not in EVENT_TYPES:
            raise KakaoBotGatewayError(f'Invalid DISPATCH frame: unknown event type "{frame.get("t")}"')
        if not isinstance(frame.get("d"), dict):
            raise KakaoBotGatewayError('Invalid DISPATCH frame: missing "d"')
    return frame


def is_dispatch(frame: dict[str, Any]) -> bool:
    return frame.get("op") == Opcode.DISPATCH


def create_dispatch_event(payload: dict[str, Any], client: KakaoBotClient | None = None) -> DispatchEvent:
    """Wrap a DISPATCH payload into a :class:`DispatchEvent`."""
    seq = payload.get("s")
    return DispatchEvent(
        type=payload["t"],
        seq=seq if isinstance(seq, int) else None,
        data=payload["d"],
        raw=payload,
        _client=client,
    )
