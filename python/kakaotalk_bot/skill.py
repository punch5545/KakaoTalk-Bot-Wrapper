"""Helpers for building ``SkillResponse`` payloads."""
from __future__ import annotations

from typing import Any, Mapping, Sequence

from .types import (
    BasicCard,
    Button,
    ButtonAction,
    ButtonLayout,
    Carousel,
    CarouselHeader,
    CarouselType,
    CommerceCard,
    Component,
    ImageTitle,
    ItemCard,
    ItemCardHead,
    ItemCardItem,
    ItemListSummary,
    ListCard,
    ListItem,
    Mention,
    Profile,
    QuickReply,
    SkillResponse,
    SkillTemplate,
    TextCard,
    Thumbnail,
)

MAX_OUTPUTS = 3
MAX_QUICK_REPLIES = 10
MAX_MENTIONS = 15


def _strip_none(d: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in d.items() if v is not None}


# ---------------------------------------------------------------------------
# Responses
# ---------------------------------------------------------------------------


def skill_response(
    outputs: Component | Sequence[Component],
    *,
    quick_replies: Sequence[QuickReply] | None = None,
    extra: Mapping[str, Any] | None = None,
) -> SkillResponse:
    """Build a SkillResponse (version 2.0) from one or more components."""
    items = [outputs] if isinstance(outputs, dict) else list(outputs)
    if not 1 <= len(items) <= MAX_OUTPUTS:
        raise ValueError(f"outputs must contain 1 to {MAX_OUTPUTS} components")
    if quick_replies is not None and len(quick_replies) > MAX_QUICK_REPLIES:
        raise ValueError(f"quick_replies must contain at most {MAX_QUICK_REPLIES} items")
    template: SkillTemplate = {"outputs": items}
    if quick_replies:
        template["quickReplies"] = list(quick_replies)
    response: SkillResponse = {"version": "2.0", "template": template}
    if extra:
        response["extra"] = dict(extra)
    return response


def text_response(text: str, quick_replies: Sequence[QuickReply] | None = None) -> SkillResponse:
    """A single SimpleText bubble."""
    return skill_response(simple_text(text), quick_replies=quick_replies)


def image_response(image_url: str, alt_text: str | None = None) -> SkillResponse:
    """A single SimpleImage bubble."""
    return skill_response(simple_image(image_url, alt_text))


# ---------------------------------------------------------------------------
# Components
# ---------------------------------------------------------------------------


def simple_text(text: str) -> Component:
    return {"simpleText": {"text": text}}


def simple_image(image_url: str, alt_text: str | None = None) -> Component:
    return {"simpleImage": _strip_none({"imageUrl": image_url, "altText": alt_text})}


def text_card(
    title: str,
    description: str,
    buttons: Sequence[Button],
    *,
    button_layout: ButtonLayout | None = None,
) -> Component:
    card: TextCard = {"title": title, "description": description, "buttons": list(buttons)}
    if button_layout:
        card["buttonLayout"] = button_layout
    return {"textCard": card}


def basic_card(
    title: str,
    description: str,
    thumbnail: Thumbnail,
    buttons: Sequence[Button],
    *,
    button_layout: ButtonLayout | None = None,
) -> Component:
    card: BasicCard = {
        "title": title,
        "description": description,
        "thumbnail": thumbnail,
        "buttons": list(buttons),
    }
    if button_layout:
        card["buttonLayout"] = button_layout
    return {"basicCard": card}


def list_card(
    header: ListItem,
    items: Sequence[ListItem],
    buttons: Sequence[Button],
    *,
    button_layout: ButtonLayout | None = None,
) -> Component:
    card: ListCard = {"header": header, "items": list(items), "buttons": list(buttons)}
    if button_layout:
        card["buttonLayout"] = button_layout
    return {"listCard": card}


def item_card(
    item_list: Sequence[ItemCardItem],
    title: str,
    description: str,
    buttons: Sequence[Button],
    *,
    head: ItemCardHead | None = None,
    image_title: ImageTitle | None = None,
    item_list_alignment: str | None = None,
    item_list_summary: ItemListSummary | None = None,
    thumbnail: Thumbnail | None = None,
    profile: Profile | None = None,
    button_layout: ButtonLayout | None = None,
) -> Component:
    if head is not None and profile is not None:
        raise ValueError("ItemCard cannot have both head and profile")
    card: dict[str, Any] = _strip_none(
        {
            "itemList": list(item_list),
            "title": title,
            "description": description,
            "buttons": list(buttons),
            "head": head,
            "imageTitle": image_title,
            "itemListAlignment": item_list_alignment,
            "itemListSummary": item_list_summary,
            "thumbnail": thumbnail,
            "profile": profile,
            "buttonLayout": button_layout,
        }
    )
    return {"itemCard": card}


def commerce_card(
    price: int,
    thumbnails: Sequence[Thumbnail],
    buttons: Sequence[Button],
    *,
    title: str | None = None,
    description: str | None = None,
    discount: int | None = None,
    discount_rate: int | None = None,
    discounted_price: int | None = None,
    profile: Profile | None = None,
    button_layout: ButtonLayout | None = None,
) -> Component:
    card: dict[str, Any] = _strip_none(
        {
            "price": price,
            "currency": "won",
            "thumbnails": list(thumbnails),
            "buttons": list(buttons),
            "title": title,
            "description": description,
            "discount": discount,
            "discountRate": discount_rate,
            "discountedPrice": discounted_price,
            "profile": profile,
            "buttonLayout": button_layout,
        }
    )
    return {"commerceCard": card}


def carousel(
    type: CarouselType,
    items: Sequence[dict[str, Any]],
    *,
    header: CarouselHeader | None = None,
) -> Component:
    card: Carousel = {"type": type, "items": list(items)}
    if header is not None:
        card["header"] = header
    return {"carousel": card}


# ---------------------------------------------------------------------------
# Buttons, quick replies, mentions
# ---------------------------------------------------------------------------


def button(
    action: ButtonAction,
    label: str,
    *,
    message_text: str | None = None,
    web_link_url: str | None = None,
    phone_number: str | None = None,
    extra: Mapping[str, Any] | None = None,
) -> Button:
    if action == "message" and message_text is None:
        raise ValueError("message_text is required for a 'message' button")
    if action == "webLink" and web_link_url is None:
        raise ValueError("web_link_url is required for a 'webLink' button")
    if action == "phone" and phone_number is None:
        raise ValueError("phone_number is required for a 'phone' button")
    b: dict[str, Any] = _strip_none(
        {
            "action": action,
            "label": label,
            "messageText": message_text,
            "webLinkUrl": web_link_url,
            "phoneNumber": phone_number,
            "extra": dict(extra) if extra else None,
        }
    )
    return b  # type: ignore[return-value]


def message_button(label: str, message_text: str | None = None, extra: Mapping[str, Any] | None = None) -> Button:
    return button("message", label, message_text=message_text if message_text is not None else label, extra=extra)


def web_link_button(label: str, url: str) -> Button:
    return button("webLink", label, web_link_url=url)


def phone_button(label: str, phone_number: str) -> Button:
    return button("phone", label, phone_number=phone_number)


def share_button(label: str) -> Button:
    return button("share", label)


def operator_button(label: str) -> Button:
    return button("operator", label)


def invite_button(label: str) -> Button:
    return button("invite", label)


def mention_button(label: str) -> Button:
    return button("mention", label)


def invite_member_button(label: str) -> Button:
    return button("inviteMember", label)


def settings_button(label: str) -> Button:
    return button("settings", label)


def guide_button(label: str) -> Button:
    return button("guide", label)


def quick_reply(label: str, message_text: str | None = None, extra: Mapping[str, Any] | None = None) -> QuickReply:
    q: QuickReply = {
        "label": label,
        "action": "message",
        "messageText": message_text if message_text is not None else label,
    }
    if extra:
        q["extra"] = dict(extra)
    return q


def mention_tag(user_key: str) -> str:
    """Placeholder to put inside a SimpleText: ``{{#mentions.<user_key>}}``."""
    return f"{{{{#mentions.{user_key}}}}}"


def mentions(mapping: Mapping[str, str]) -> dict[str, dict[str, Mention]]:
    """Build ``extra`` with mentions from a ``{user_key: botUserKey}`` mapping."""
    if len(mapping) > MAX_MENTIONS:
        raise ValueError(f"At most {MAX_MENTIONS} mentions are allowed per response")
    return {"mentions": {k: {"type": "botUserKey", "id": v} for k, v in mapping.items()}}


class SkillResponseBuilder:
    """Fluent builder for SkillResponse.

    >>> res = (SkillResponseBuilder()
    ...        .text(f"안녕하세요 {mention_tag('u1')}!")
    ...        .mention("u1", "buk_xxx")
    ...        .quick_reply("도움말")
    ...        .build())
    """

    def __init__(self) -> None:
        self._outputs: list[Component] = []
        self._quick_replies: list[QuickReply] = []
        self._mentions: dict[str, Mention] = {}
        self._extra: dict[str, Any] = {}

    def add(self, component: Component) -> SkillResponseBuilder:
        self._outputs.append(component)
        return self

    def text(self, text: str) -> SkillResponseBuilder:
        return self.add(simple_text(text))

    def image(self, image_url: str, alt_text: str | None = None) -> SkillResponseBuilder:
        return self.add(simple_image(image_url, alt_text))

    def quick_reply(
        self, label: str, message_text: str | None = None, extra: Mapping[str, Any] | None = None
    ) -> SkillResponseBuilder:
        self._quick_replies.append(quick_reply(label, message_text, extra))
        return self

    def mention(self, user_key: str, bot_user_key: str) -> SkillResponseBuilder:
        self._mentions[user_key] = {"type": "botUserKey", "id": bot_user_key}
        return self

    def extra(self, **fields: Any) -> SkillResponseBuilder:
        self._extra.update(fields)
        return self

    def build(self) -> SkillResponse:
        if len(self._mentions) > MAX_MENTIONS:
            raise ValueError(f"At most {MAX_MENTIONS} mentions are allowed per response")
        extra: dict[str, Any] | None = None
        if self._mentions or self._extra:
            extra = dict(self._extra)
            if self._mentions:
                extra["mentions"] = dict(self._mentions)
        return skill_response(self._outputs, quick_replies=self._quick_replies, extra=extra)
