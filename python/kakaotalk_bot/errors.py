from __future__ import annotations

from typing import Any

#: Human-readable descriptions of bot API error codes (official error code page).
BOT_ERROR_DESCRIPTIONS: dict[int, str] = {
    -1: "봇 메타 서버 내부 오류. 잠시 후 재시도하세요.",
    -2: "도움말/명령어 API 요청 본문이 형식에 맞지 않습니다.",
    -870: "메시지 API 관련 에러. extras의 상세 코드를 확인하세요.",
    -871: "도움말 API 관련 에러. extras의 상세 코드를 확인하세요.",
    -872: "명령어 API 관련 에러. extras의 상세 코드를 확인하세요.",
    -873: "유효하지 않은 봇입니다. 봇 생성 및 앱 연동 상태를 확인하세요.",
    -874: "명령어 최대 등록 개수(30개)를 초과했습니다.",
    -875: "중복된 명령어가 있습니다.",
    -876: "유효하지 않은 키입니다. botUserKey 또는 botGroupKey 중 하나만 입력해야 합니다.",
    -877: "채널 채팅에서 채널 친구가 아닌 사용자에게는 응답할 수 없습니다.",
    -878: "말풍선이 유효하지 않습니다. 컴포넌트 형식을 확인하세요.",
    -879: "메시지 전송 수신을 [Off]로 설정한 채팅방입니다.",
    -880: "봇이 초대되지 않은 채팅방입니다.",
    -881: "만료되었거나 이미 사용된 콜백 토큰입니다.",
}


class KakaoBotError(Exception):
    """Base class for all errors raised by this library."""


class KakaoBotApiError(KakaoBotError):
    """The REST API responded with a non-2xx status."""

    def __init__(self, status: int, body: Any, raw_text: str | None = None) -> None:
        parsed = body if isinstance(body, dict) and ("code" in body or "msg" in body) else None
        self.status: int = status
        self.code: int | None = parsed.get("code") if parsed else None
        self.msg: str | None = parsed.get("msg") if parsed else None
        extras = parsed.get("extras") if parsed else None
        self.extras: dict[str, Any] | None = extras if isinstance(extras, dict) else None
        self.body: Any = body if body is not None else raw_text

        parts = [f"Kakao Bot API request failed with HTTP {status}"]
        if self.code is not None:
            parts.append(f"code={self.code}")
        if self.msg:
            parts.append(f"msg={self.msg}")
        if self.extras:
            if "code" in self.extras:
                parts.append(f"extras.code={self.extras['code']}")
            if self.extras.get("message"):
                parts.append(f"extras.message={self.extras['message']}")
        if self.description:
            parts.append(f"({self.description})")
        super().__init__(" ".join(parts))

    @property
    def description(self) -> str | None:
        """Human-readable hint for the error code, when known."""
        return BOT_ERROR_DESCRIPTIONS.get(self.code) if self.code is not None else None

    @property
    def is_callback_token_error(self) -> bool:
        """-881: expired or already-used callback token."""
        return self.code == -881

    @property
    def is_bot_not_invited(self) -> bool:
        """-880: the bot is not in the chat room."""
        return self.code == -880


class KakaoBotGatewayError(KakaoBotError):
    """Gateway protocol error, fatal close code, or reply without a callback token."""

    def __init__(self, message: str, *, close_code: int | None = None, fatal: bool = False) -> None:
        super().__init__(message)
        self.close_code = close_code
        self.fatal = fatal
