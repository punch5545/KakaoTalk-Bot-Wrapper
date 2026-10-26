"""Type definitions for the KakaoTalk Bot API.

Source: https://developers.kakao.com/docs/ko/bot/rest-api
        https://developers.kakao.com/docs/ko/bot/gateway
"""
from __future__ import annotations

from enum import IntEnum
from typing import Any, Literal, TypedDict

# ---------------------------------------------------------------------------
# Common objects
# ---------------------------------------------------------------------------

ButtonAction = Literal[
    "message", "webLink", "phone", "share", "operator",
    "invite", "mention", "inviteMember", "settings", "guide",
]
ButtonLayout = Literal["vertical", "horizontal"]


class _ButtonRequired(TypedDict):
    action: ButtonAction
    label: str


class Button(_ButtonRequired, total=False):
    messageText: str
    webLinkUrl: str
    phoneNumber: str
    extra: dict[str, Any]


class Link(TypedDict, total=False):
    mobile: str
    pc: str
    web: str


class _ThumbnailRequired(TypedDict):
    imageUrl: str


class Thumbnail(_ThumbnailRequired, total=False):
    link: Link
    fixedRatio: bool
    altText: str


class Profile(TypedDict):
    nickname: str
    imageUrl: str


# ---------------------------------------------------------------------------
# Bubble components
# ---------------------------------------------------------------------------


class SimpleText(TypedDict):
    text: str


class _SimpleImageRequired(TypedDict):
    imageUrl: str


class SimpleImage(_SimpleImageRequired, total=False):
    altText: str


class _TextCardRequired(TypedDict):
    title: str
    description: str
    buttons: list[Button]


class TextCard(_TextCardRequired, total=False):
    buttonLayout: ButtonLayout


class _BasicCardRequired(TypedDict):
    title: str
    description: str
    thumbnail: Thumbnail
    buttons: list[Button]


class BasicCard(_BasicCardRequired, total=False):
    buttonLayout: ButtonLayout


class _ListItemRequired(TypedDict):
    title: str


class ListItem(_ListItemRequired, total=False):
    imageUrl: str
    link: Link
    description: str
    messageText: str
    extra: dict[str, Any]
    action: Literal["message"]


class _ListCardRequired(TypedDict):
    header: ListItem
    items: list[ListItem]
    buttons: list[Button]


class ListCard(_ListCardRequired, total=False):
    buttonLayout: ButtonLayout


class ItemCardItem(TypedDict):
    title: str
    description: str


class ItemCardHead(TypedDict):
    title: str


class _ImageTitleRequired(TypedDict):
    title: str


class ImageTitle(_ImageTitleRequired, total=False):
    description: str
    imageUrl: str


class ItemListSummary(TypedDict):
    title: str
    description: str


class _ItemCardRequired(TypedDict):
    itemList: list[ItemCardItem]
    title: str
    description: str
    buttons: list[Button]


class ItemCard(_ItemCardRequired, total=False):
    head: ItemCardHead
    imageTitle: ImageTitle
    itemListAlignment: Literal["left", "right"]
    itemListSummary: ItemListSummary
    thumbnail: Thumbnail
    profile: Profile
    buttonLayout: ButtonLayout


class _CommerceCardRequired(TypedDict):
    price: int
    currency: Literal["won"]
    thumbnails: list[Thumbnail]
    buttons: list[Button]


class CommerceCard(_CommerceCardRequired, total=False):
    title: str
    description: str
    discount: int
    discountRate: int
    discountedPrice: int
    profile: Profile
    buttonLayout: ButtonLayout


CarouselType = Literal["basicCard", "commerceCard", "listCard", "itemCard", "textCard"]


class CarouselHeader(TypedDict):
    title: str
    description: str
    thumbnail: Thumbnail


class _CarouselRequired(TypedDict):
    type: CarouselType
    items: list[dict[str, Any]]


class Carousel(_CarouselRequired, total=False):
    header: CarouselHeader


#: A single output component, e.g. ``{"simpleText": {"text": "hi"}}``.
Component = dict[str, Any]


class _QuickReplyRequired(TypedDict):
    label: str
    action: Literal["message"]


class QuickReply(_QuickReplyRequired, total=False):
    messageText: str
    extra: dict[str, Any]


class _SkillTemplateRequired(TypedDict):
    outputs: list[Component]


class SkillTemplate(_SkillTemplateRequired, total=False):
    quickReplies: list[QuickReply]


class Mention(TypedDict):
    type: Literal["botUserKey"]
    id: str


class _SkillResponseRequired(TypedDict):
    version: Literal["2.0"]
    template: SkillTemplate


class SkillResponse(_SkillResponseRequired, total=False):
    extra: dict[str, Any]


# ---------------------------------------------------------------------------
# REST API responses
# ---------------------------------------------------------------------------


class SendResult(TypedDict):
    totalCount: int
    sendCount: int
    errorCount: int


class Utterance(TypedDict):
    id: str
    type: Literal["TEXT"]
    value: str
    buttonName: str
    description: str | None


class _UtteranceRequestRequired(TypedDict):
    buttonName: str


class UtteranceRequest(_UtteranceRequestRequired, total=False):
    description: str | None


class CommandsResponse(TypedDict):
    utterances: list[Utterance]


class Guide(TypedDict):
    contents: str
    createdAt: str
    updatedAt: str | None


# ---------------------------------------------------------------------------
# Gateway protocol
# ---------------------------------------------------------------------------


class Opcode(IntEnum):
    DISPATCH = 0
    HEARTBEAT = 1
    IDENTIFY = 2
    READY = 3
    RESUME = 6
    HELLO = 10
    HEARTBEAT_ACK = 11


class CloseCode(IntEnum):
    SERVER_RESTART = 1001
    AUTH_FAILED = 4001
    INVALID_PAYLOAD = 4003
    REPLACED = 4004
    SESSION_TIMEOUT = 4009


#: Close codes after which the gateway must not reconnect.
FATAL_CLOSE_CODES: frozenset[int] = frozenset(
    {CloseCode.AUTH_FAILED, CloseCode.INVALID_PAYLOAD, CloseCode.REPLACED}
)

CLOSE_CODE_DESCRIPTIONS: dict[int, str] = {
    CloseCode.SERVER_RESTART: "서버 재시작 (Graceful shutdown)",
    CloseCode.AUTH_FAILED: "인증 실패 (토큰 누락 또는 오류)",
    CloseCode.INVALID_PAYLOAD: "유효하지 않은 페이로드",
    CloseCode.REPLACED: "다른 연결로 대체됨 (중복 IDENTIFY)",
    CloseCode.SESSION_TIMEOUT: "세션 만료",
}

EventType = Literal["MESSAGE_CREATE", "ENTRANCE", "CHAT_JOIN", "LEAVE", "AD_REWARD"]
EVENT_TYPES: tuple[str, ...] = ("MESSAGE_CREATE", "ENTRANCE", "CHAT_JOIN", "LEAVE", "AD_REWARD")


class _MessageCreateRequired(TypedDict):
    id: int
    content: str
    timestamp: str
    isChannelChatroom: bool
    botGroupKey: str
    botUserKey: str


class MessageCreateData(_MessageCreateRequired, total=False):
    callbackToken: str


class _UserRef(TypedDict):
    botUserKey: str


class _EntranceRequired(TypedDict):
    botGroupKey: str
    inviter: _UserRef
    params: dict[str, Any]
    timestamp: str


class EntranceData(_EntranceRequired, total=False):
    callbackToken: str


class _ChatJoinRequired(TypedDict):
    botGroupKey: str
    user: _UserRef
    timestamp: str


class ChatJoinData(_ChatJoinRequired, total=False):
    callbackToken: str


class LeaveData(TypedDict):
    botGroupKey: str
    timestamp: str


class _AdRewardRequired(TypedDict):
    botGroupKey: str
    viewer: _UserRef
    params: dict[str, Any]
    timestamp: str


class AdRewardData(_AdRewardRequired, total=False):
    callbackToken: str


class _BotRef(TypedDict):
    id: str


class ReadyData(TypedDict):
    session_id: str
    bot: _BotRef
