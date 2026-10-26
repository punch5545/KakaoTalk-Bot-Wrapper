"""REST API client for the KakaoTalk Bot API (async and sync variants)."""
from __future__ import annotations

import json
from typing import Any, Mapping, Sequence

import httpx

from .errors import KakaoBotApiError
from .types import CommandsResponse, Guide, SendResult, SkillResponse, UtteranceRequest

DEFAULT_API_BASE_URL = "https://kapi.kakao.com"
MAX_COMMANDS = 30


class _BaseClient:
    def __init__(
        self,
        bot_token: str,
        *,
        base_url: str = DEFAULT_API_BASE_URL,
        timeout: float = 10.0,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        if not bot_token or not isinstance(bot_token, str):
            raise TypeError("bot_token is required")
        self._token = bot_token
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._extra_headers = dict(headers or {})

    # -- request helpers ---------------------------------------------------

    def _headers(self, extra: Mapping[str, str] | None = None, *, has_body: bool) -> dict[str, str]:
        headers = {
            **self._extra_headers,
            "Authorization": f"KakaoAK {self._token}",
            "Accept": "application/json",
        }
        if has_body:
            headers["Content-Type"] = "application/json"
        if extra:
            headers.update(extra)
        return headers

    @staticmethod
    def _encode(json_body: Any | None) -> bytes | None:
        if json_body is None:
            return None
        return json.dumps(json_body, ensure_ascii=False).encode("utf-8")

    @staticmethod
    def _handle(response: httpx.Response) -> Any:
        text = response.text
        body: Any = None
        if text:
            try:
                body = json.loads(text)
            except ValueError:
                body = None
        if response.status_code >= 400:
            raise KakaoBotApiError(response.status_code, body, text)
        return body

    @staticmethod
    def _message_body(
        skill_response: SkillResponse,
        bot_group_key: str | None,
        bot_user_key: str | None,
    ) -> dict[str, Any]:
        if bool(bot_group_key) == bool(bot_user_key):
            raise TypeError("Exactly one of bot_group_key or bot_user_key must be provided")
        body: dict[str, Any] = {}
        if bot_group_key:
            body["botGroupKey"] = bot_group_key
        else:
            body["botUserKey"] = bot_user_key
        body["skillResponse"] = skill_response
        return body

    @staticmethod
    def _validate_commands(utterances: Sequence[UtteranceRequest]) -> list[UtteranceRequest]:
        items = list(utterances)
        if len(items) > MAX_COMMANDS:
            raise ValueError(f"At most {MAX_COMMANDS} commands can be registered")
        return items


class KakaoBotClient(_BaseClient):
    """Async REST client.

    >>> async with KakaoBotClient(BOT_TOKEN) as client:
    ...     await client.send_callback(callback_token, text_response("안녕하세요!"))
    """

    def __init__(
        self,
        bot_token: str,
        *,
        base_url: str = DEFAULT_API_BASE_URL,
        timeout: float = 10.0,
        headers: Mapping[str, str] | None = None,
        http: httpx.AsyncClient | None = None,
    ) -> None:
        super().__init__(bot_token, base_url=base_url, timeout=timeout, headers=headers)
        self._owns_http = http is None
        self._http = http or httpx.AsyncClient(timeout=timeout)

    async def __aenter__(self) -> KakaoBotClient:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._owns_http:
            await self._http.aclose()

    async def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> Any:
        """Low-level request; exposed for endpoints not yet wrapped."""
        response = await self._http.request(
            method,
            self.base_url + path,
            content=self._encode(json_body),
            headers=self._headers(headers, has_body=json_body is not None),
            timeout=self.timeout,
        )
        return self._handle(response)

    # -- messages ----------------------------------------------------------

    async def send_callback(self, callback_token: str, skill_response: SkillResponse) -> SendResult:
        """POST /v1/bot/callback — reply to an event. Token is single-use and expires in 5 minutes."""
        if not callback_token:
            raise TypeError("callback_token is required")
        result: SendResult = await self.request(
            "POST",
            "/v1/bot/callback",
            json_body={"skillResponse": skill_response},
            headers={"X-Bot-Callback-Token": callback_token},
        )
        return result

    async def send_message(
        self,
        skill_response: SkillResponse,
        *,
        bot_group_key: str | None = None,
        bot_user_key: str | None = None,
    ) -> SendResult:
        """POST /v1/bot/send_message — proactively message a room or a channel-chat user."""
        result: SendResult = await self.request(
            "POST",
            "/v1/bot/send_message",
            json_body=self._message_body(skill_response, bot_group_key, bot_user_key),
        )
        return result

    async def send_to_group(self, bot_group_key: str, skill_response: SkillResponse) -> SendResult:
        return await self.send_message(skill_response, bot_group_key=bot_group_key)

    async def send_to_user(self, bot_user_key: str, skill_response: SkillResponse) -> SendResult:
        return await self.send_message(skill_response, bot_user_key=bot_user_key)

    # -- commands ----------------------------------------------------------

    async def get_commands(self) -> CommandsResponse:
        """GET /v1/bot/commands"""
        result: CommandsResponse = await self.request("GET", "/v1/bot/commands")
        return result

    async def update_commands(self, utterances: Sequence[UtteranceRequest]) -> CommandsResponse:
        """POST /v1/bot/commands/update — replaces the whole list (max 30)."""
        result: CommandsResponse = await self.request(
            "POST", "/v1/bot/commands/update", json_body={"utterances": self._validate_commands(utterances)}
        )
        return result

    # -- guide -------------------------------------------------------------

    async def get_guide(self) -> Guide:
        """GET /v1/bot/guide"""
        result: Guide = await self.request("GET", "/v1/bot/guide")
        return result

    async def update_guide(self, contents: str) -> Guide:
        """POST /v1/bot/guide/update"""
        result: Guide = await self.request("POST", "/v1/bot/guide/update", json_body={"contents": contents})
        return result


class KakaoBotSyncClient(_BaseClient):
    """Blocking REST client with the same surface as :class:`KakaoBotClient`."""

    def __init__(
        self,
        bot_token: str,
        *,
        base_url: str = DEFAULT_API_BASE_URL,
        timeout: float = 10.0,
        headers: Mapping[str, str] | None = None,
        http: httpx.Client | None = None,
    ) -> None:
        super().__init__(bot_token, base_url=base_url, timeout=timeout, headers=headers)
        self._owns_http = http is None
        self._http = http or httpx.Client(timeout=timeout)

    def __enter__(self) -> KakaoBotSyncClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        if self._owns_http:
            self._http.close()

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> Any:
        response = self._http.request(
            method,
            self.base_url + path,
            content=self._encode(json_body),
            headers=self._headers(headers, has_body=json_body is not None),
            timeout=self.timeout,
        )
        return self._handle(response)

    def send_callback(self, callback_token: str, skill_response: SkillResponse) -> SendResult:
        if not callback_token:
            raise TypeError("callback_token is required")
        result: SendResult = self.request(
            "POST",
            "/v1/bot/callback",
            json_body={"skillResponse": skill_response},
            headers={"X-Bot-Callback-Token": callback_token},
        )
        return result

    def send_message(
        self,
        skill_response: SkillResponse,
        *,
        bot_group_key: str | None = None,
        bot_user_key: str | None = None,
    ) -> SendResult:
        result: SendResult = self.request(
            "POST",
            "/v1/bot/send_message",
            json_body=self._message_body(skill_response, bot_group_key, bot_user_key),
        )
        return result

    def send_to_group(self, bot_group_key: str, skill_response: SkillResponse) -> SendResult:
        return self.send_message(skill_response, bot_group_key=bot_group_key)

    def send_to_user(self, bot_user_key: str, skill_response: SkillResponse) -> SendResult:
        return self.send_message(skill_response, bot_user_key=bot_user_key)

    def get_commands(self) -> CommandsResponse:
        result: CommandsResponse = self.request("GET", "/v1/bot/commands")
        return result

    def update_commands(self, utterances: Sequence[UtteranceRequest]) -> CommandsResponse:
        result: CommandsResponse = self.request(
            "POST", "/v1/bot/commands/update", json_body={"utterances": self._validate_commands(utterances)}
        )
        return result

    def get_guide(self) -> Guide:
        result: Guide = self.request("GET", "/v1/bot/guide")
        return result

    def update_guide(self, contents: str) -> Guide:
        result: Guide = self.request("POST", "/v1/bot/guide/update", json_body={"contents": contents})
        return result
