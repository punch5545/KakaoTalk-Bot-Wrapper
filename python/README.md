# kakaotalk-bot (Python)

KakaoTalk Bot API client for Python 3.10+: REST API wrapper (async + sync), Gateway (WebSocket) client
and webhook helpers. Depends on `httpx` and `websockets`.

```bash
pip install kakaotalk-bot
```

## REST API

```python
from kakaotalk_bot import (
    KakaoBotClient, KakaoBotSyncClient, KakaoBotApiError,
    text_response, SkillResponseBuilder, basic_card, web_link_button, message_button, mention_tag,
)

async with KakaoBotClient(BOT_TOKEN) as client:
    await client.send_callback(callback_token, text_response("처리가 완료되었습니다."))
    await client.send_to_group(bot_group_key, text_response("안녕하세요!"))
    await client.send_to_user(bot_user_key, text_response("안녕하세요!"))  # channel chat only

    commands = await client.get_commands()
    await client.update_commands([{"buttonName": "도움말"}, {"buttonName": "상담원 연결", "description": "상담원과 연결합니다."}])
    await client.update_guide("🤖 저와 함께 이런 것들을 할 수 있어요.")

    response = (
        SkillResponseBuilder()
        .text(f"안녕하세요 {mention_tag('u1')}!")
        .mention("u1", bot_user_key)
        .add(basic_card("제목", "설명", {"imageUrl": "https://example.com/a.png"},
                        [web_link_button("열기", "https://example.com"), message_button("다시")]))
        .quick_reply("도움말")
        .build()
    )
    try:
        await client.send_callback(callback_token, response)
    except KakaoBotApiError as e:
        if e.is_callback_token_error:  # -881
            ...

# Blocking variant with the same methods
with KakaoBotSyncClient(BOT_TOKEN) as client:
    client.send_to_group(bot_group_key, text_response("hi"))
```

Options: `KakaoBotClient(token, base_url=..., timeout=10.0, headers=..., http=httpx.AsyncClient(...))`.

## Gateway (WebSocket)

```python
import asyncio
from kakaotalk_bot import KakaoBotGateway, DispatchEvent

gateway = KakaoBotGateway(
    BOT_TOKEN,
    # url=..., client=..., auto_reconnect=True, initial_backoff=1.0, max_backoff=30.0,
    # heartbeat_margin=5.0, handshake_timeout=15.0,
)

@gateway.on("ready")
def on_ready(ready):
    print("bot", ready["bot"]["id"], "session", ready["session_id"])

@gateway.on("MESSAGE_CREATE")
async def on_message(event: DispatchEvent):
    # event.data: {"id", "content", "timestamp", "isChannelChatroom", "botGroupKey", "botUserKey", "callbackToken"?}
    if event.can_reply:
        await event.reply_text(f"echo: {event.content}")

@gateway.on("error")
def on_error(exc):
    print("gateway error:", exc)  # fatal close codes: exc.fatal is True

asyncio.run(gateway.run())          # blocks; reconnects automatically
# or: task = await gateway.start()  # background task, returns once READY
#     await gateway.close()
```

Events: `ready`, `resumed`, `dispatch`, `MESSAGE_CREATE`, `ENTRANCE`, `CHAT_JOIN`, `LEAVE`, `AD_REWARD`,
`close`, `reconnecting`, `heartbeat_timeout`, `error`. Listeners may be sync or async.

Session persistence: save `gateway.session` and call `gateway.restore_session(session_id, last_seq)`
before `run()` to RESUME across process restarts.

## Webhook (FastAPI example)

```python
from fastapi import FastAPI, Request, Response, BackgroundTasks
from kakaotalk_bot import KakaoBotClient, parse_webhook_event, verify_custom_header

client = KakaoBotClient(BOT_TOKEN)
app = FastAPI()

@app.post("/webhook")
async def webhook(request: Request, background: BackgroundTasks) -> Response:
    if not verify_custom_header(request.headers.get("X-My-Secret"), WEBHOOK_SECRET):
        return Response(status_code=401)
    event = parse_webhook_event(await request.body(), client)
    if event.type == "MESSAGE_CREATE" and event.can_reply:
        background.add_task(event.reply_text, f"echo: {event.content}")
    return Response(status_code=204)  # within 1 second
```

## Development

```bash
pip install -e ".[dev]"
pytest
mypy kakaotalk_bot
```
