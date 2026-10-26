"""Helpers for the webhook (HTTP) delivery mode."""
from __future__ import annotations

import hmac
from typing import Any

from .client import KakaoBotClient
from .errors import KakaoBotGatewayError
from .events import DispatchEvent, create_dispatch_event, is_dispatch, normalise_frame, parse_frame


def parse_webhook_payload(body: str | bytes | dict[str, Any]) -> dict[str, Any]:
    """Parse a webhook request body (raw JSON or a parsed dict) into a DISPATCH payload."""
    frame = normalise_frame(body) if isinstance(body, dict) else parse_frame(body)
    if not is_dispatch(frame):
        raise KakaoBotGatewayError(f"Webhook body is not a DISPATCH frame (op={frame.get('op')})")
    return frame


def parse_webhook_event(body: str | bytes | dict[str, Any], client: KakaoBotClient | None = None) -> DispatchEvent:
    """Parse a webhook body and wrap it as a :class:`DispatchEvent` with ``reply()``.

    Respond 2xx within one second first, then reply asynchronously.
    """
    return create_dispatch_event(parse_webhook_payload(body), client)


def verify_custom_header(received: str | None, expected: str) -> bool:
    """Constant-time check of the custom header registered in the webhook settings."""
    if not isinstance(received, str) or not isinstance(expected, str):
        return False
    return hmac.compare_digest(received.encode("utf-8"), expected.encode("utf-8"))
