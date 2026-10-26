"""End-to-end tests against mock REST and gateway servers (no network)."""
from __future__ import annotations

import asyncio
import json

import httpx
import pytest
import websockets

from kakaotalk_bot import (
    KakaoBotApiError,
    KakaoBotClient,
    KakaoBotGateway,
    KakaoBotGatewayError,
    KakaoBotSyncClient,
    SkillResponseBuilder,
    mention_tag,
    parse_webhook_event,
    text_response,
    verify_custom_header,
)

BOT_TOKEN = "test-bot-token"
BIG_ID_FRAME = (
    '{"op":0,"s":1,"t":"MESSAGE_CREATE","d":{"id":558185822630215770,"content":"hello","timestamp":"t",'
    '"isChannelChatroom":false,"botGroupKey":"g","botUserKey":"u","callbackToken":"cb-gw"}}'
)


def rest_transport(calls: list[httpx.Request]) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        path = request.url.path
        if path == "/v1/bot/callback":
            if request.headers.get("x-bot-callback-token") == "expired":
                return httpx.Response(400, json={"code": -881, "msg": "expired token"})
            return httpx.Response(200, json={"totalCount": 1, "sendCount": 1, "errorCount": 0})
        if path == "/v1/bot/send_message":
            return httpx.Response(200, json={"totalCount": 1, "sendCount": 1, "errorCount": 0})
        if path == "/v1/bot/commands":
            return httpx.Response(
                200,
                json={"utterances": [{"id": "1", "type": "TEXT", "value": "도움말", "buttonName": "도움말", "description": None}]},
            )
        if path == "/v1/bot/guide/update":
            return httpx.Response(
                200, json={"contents": json.loads(request.content)["contents"], "createdAt": "c", "updatedAt": None}
            )
        return httpx.Response(404, json={})

    return httpx.MockTransport(handler)


async def test_async_client() -> None:
    calls: list[httpx.Request] = []
    async with KakaoBotClient(BOT_TOKEN, http=httpx.AsyncClient(transport=rest_transport(calls))) as client:
        result = await client.send_callback("cb-1", text_response("hi"))
        assert result == {"totalCount": 1, "sendCount": 1, "errorCount": 0}
        assert calls[0].headers["authorization"] == f"KakaoAK {BOT_TOKEN}"
        assert calls[0].headers["x-bot-callback-token"] == "cb-1"
        assert json.loads(calls[0].content) == {
            "skillResponse": {"version": "2.0", "template": {"outputs": [{"simpleText": {"text": "hi"}}]}}
        }

        await client.send_to_group("grp", text_response("x"))
        assert list(json.loads(calls[1].content)) == ["botGroupKey", "skillResponse"]
        with pytest.raises(TypeError):
            await client.send_message(text_response("x"))

        cmds = await client.get_commands()
        assert cmds["utterances"][0]["value"] == "도움말"
        assert calls[2].method == "GET"

        guide = await client.update_guide("새 도움말")
        assert guide["contents"] == "새 도움말"

        with pytest.raises(KakaoBotApiError) as excinfo:
            await client.send_callback("expired", text_response("x"))
        assert excinfo.value.status == 400
        assert excinfo.value.code == -881
        assert excinfo.value.is_callback_token_error
        assert "-881" in str(excinfo.value)

        with pytest.raises(ValueError):
            await client.update_commands([{"buttonName": "x"}] * 31)


def test_sync_client() -> None:
    calls: list[httpx.Request] = []
    with KakaoBotSyncClient(BOT_TOKEN, http=httpx.Client(transport=rest_transport(calls))) as client:
        assert client.send_to_user("buk", text_response("hi"))["sendCount"] == 1
        assert json.loads(calls[0].content)["botUserKey"] == "buk"
        with pytest.raises(KakaoBotApiError):
            client.send_callback("expired", text_response("x"))


def test_builder() -> None:
    res = SkillResponseBuilder().text(f"hello {mention_tag('u1')}").mention("u1", "buk_1").quick_reply("도움말").build()
    assert res == {
        "version": "2.0",
        "template": {
            "outputs": [{"simpleText": {"text": "hello {{#mentions.u1}}"}}],
            "quickReplies": [{"label": "도움말", "action": "message", "messageText": "도움말"}],
        },
        "extra": {"mentions": {"u1": {"type": "botUserKey", "id": "buk_1"}}},
    }
    with pytest.raises(ValueError):
        SkillResponseBuilder().build()


async def test_webhook() -> None:
    event = parse_webhook_event(BIG_ID_FRAME.replace('"s":1,', ""))
    assert event.type == "MESSAGE_CREATE"
    assert event.data["id"] == 558185822630215770
    assert event.seq is None
    assert event.can_reply and event.content == "hello" and event.bot_user_key == "u"
    with pytest.raises(KakaoBotGatewayError, match="No KakaoBotClient"):
        await event.reply(text_response("x"))

    leave = parse_webhook_event({"op": 0, "t": "LEAVE", "d": {"botGroupKey": "g", "timestamp": "t"}})
    assert not leave.can_reply
    with pytest.raises(KakaoBotGatewayError, match="not a DISPATCH"):
        parse_webhook_event({"op": 10, "d": {}})
    with pytest.raises(KakaoBotGatewayError, match="unknown event type"):
        parse_webhook_event({"op": 0, "t": "NOPE", "d": {}})

    assert verify_custom_header("secret", "secret")
    assert not verify_custom_header("secre", "secret")
    assert not verify_custom_header(None, "secret")


async def test_gateway_protocol() -> None:
    rest_calls: list[httpx.Request] = []
    rest_client = KakaoBotClient(BOT_TOKEN, http=httpx.AsyncClient(transport=rest_transport(rest_calls)))
    server_log: list[tuple[int, int]] = []
    connections = {"n": 0}

    async def handler(ws):  # type: ignore[no-untyped-def]
        connections["n"] += 1
        n = connections["n"]
        await ws.send(json.dumps({"op": 10, "d": {"heartbeat_interval": 1500}}))
        async for raw in ws:
            frame = json.loads(raw)
            server_log.append((n, frame["op"]))
            if frame["op"] == 2:
                assert frame["d"]["token"] == BOT_TOKEN
                await ws.send(json.dumps({"op": 3, "d": {"session_id": f"sess_{n}", "bot": {"id": "bot1"}}}))
                if n == 1:
                    await ws.send(BIG_ID_FRAME)
                elif n == 3:
                    await ws.close(4001, "auth failed")
                    return
            elif frame["op"] == 1:
                await ws.send(json.dumps({"op": 11}))
                if n == 1:
                    await ws.close(1001, "restart")
                    return
            elif frame["op"] == 6:
                assert frame["d"]["session_id"] == "sess_1" and frame["d"]["seq"] == 1
                await ws.send(json.dumps({"op": 0, "s": 2, "t": "LEAVE", "d": {"botGroupKey": "g", "timestamp": "t"}}))
                await asyncio.sleep(0.05)
                await ws.close(4009, "session expired")
                return

    async with websockets.serve(handler, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        gateway = KakaoBotGateway(
            BOT_TOKEN,
            url=f"ws://127.0.0.1:{port}/gateway",
            client=rest_client,
            initial_backoff=0.05,
            max_backoff=0.2,
            heartbeat_margin=1.0,  # heartbeat every 1.0 s (1.5 s interval, min 1 s)
        )
        events: list[tuple] = []
        errors: list[Exception] = []
        gateway.add_listener("ready", lambda d: events.append(("ready", d["session_id"])))
        gateway.add_listener("resumed", lambda s: events.append(("resumed", s["session_id"])))
        gateway.add_listener("reconnecting", lambda a, d, r: events.append(("reconnecting", a, r)))
        gateway.add_listener("close", lambda c, r: events.append(("close", c)))
        gateway.add_listener("error", errors.append)
        gateway.add_listener("LEAVE", lambda ev: events.append(("LEAVE", ev.seq)))

        @gateway.on("MESSAGE_CREATE")
        async def on_message(ev) -> None:  # type: ignore[no-untyped-def]
            events.append(("MESSAGE_CREATE", ev.data["id"], ev.content))
            result = await ev.reply_text(f"echo: {ev.content}")
            events.append(("replied", result["sendCount"]))

        task = await gateway.start()
        assert gateway.state == "ready"
        assert gateway.session["session_id"] == "sess_1"

        with pytest.raises(KakaoBotGatewayError) as excinfo:
            await asyncio.wait_for(task, 8)
        assert excinfo.value.fatal and excinfo.value.close_code == 4001
        assert gateway.state == "closed"
        await rest_client.aclose()

    assert events[:3] == [("ready", "sess_1"), ("MESSAGE_CREATE", 558185822630215770, "hello"), ("replied", 1)]
    assert ("close", 1001) in events
    assert ("reconnecting", 1, True) in events, events
    assert ("resumed", "sess_1") in events
    assert ("LEAVE", 2) in events
    assert ("close", 4009) in events
    assert any(e[0] == "reconnecting" and e[2] is False for e in events), events
    assert ("ready", "sess_3") in events
    assert any(e.fatal for e in errors)

    callback = next(c for c in rest_calls if c.url.path == "/v1/bot/callback")
    assert callback.headers["x-bot-callback-token"] == "cb-gw"
    assert json.loads(callback.content)["skillResponse"]["template"]["outputs"][0]["simpleText"]["text"] == "echo: hello"

    assert server_log[0] == (1, 2)  # IDENTIFY
    assert (1, 1) in server_log  # HEARTBEAT
    assert (2, 6) in server_log  # RESUME
    assert (3, 2) in server_log  # IDENTIFY after session timeout
