[![CI](https://github.com/punch5545/KakaoTalk-Bot-Wrapper/actions/workflows/ci.yml/badge.svg)](https://github.com/punch5545/KakaoTalk-Bot-Wrapper/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/kakaotalk-bot?logo=npm&label=npm)](https://www.npmjs.com/package/kakaotalk-bot)
[![PyPI](https://img.shields.io/pypi/v/kakaotalk-bot?logo=pypi&logoColor=white&label=PyPI)](https://pypi.org/project/kakaotalk-bot/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.punch5545/kakaotalk-bot?logo=apachemaven&label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.punch5545/kakaotalk-bot)
[![License](https://img.shields.io/github/license/punch5545/KakaoTalk-Bot-Wrapper)](LICENSE)

# KakaoTalk Bot Wrapper

[카카오톡 봇 API](https://developers.kakao.com/docs/ko/bot/common)의 REST API와 게이트웨이(WebSocket)를 감싼 라이브러리입니다.
Node.js, Python, Java 세 가지로 제공하며 클래스 구성과 동작은 같습니다.

| 언어 | 설치 | 요구 사항 | 문서 |
|------|------|-----------|------|
| Node.js | `npm install kakaotalk-bot` | Node.js 20+ | [nodejs/](nodejs/) |
| Python | `pip install kakaotalk-bot` | Python 3.10+ | [python/](python/) |
| Java | `io.github.punch5545:kakaotalk-bot:0.1.0` | Java 11+ | [java/](java/) |

## 구성

- **KakaoBotClient** — REST API. 메시지 콜백, 메시지 전송, 명령어 조회·수정, 도움말 조회·수정.
- **KakaoBotGateway** — 웹소켓 게이트웨이. HELLO → IDENTIFY/RESUME → READY 핸드셰이크, HEARTBEAT, DISPATCH 이벤트 수신, 재연결.
- **DispatchEvent** — 수신한 이벤트. `reply()` / `replyText()`로 콜백 토큰을 써서 바로 답장합니다.
- **웹훅 헬퍼** — 웹훅 본문을 같은 DispatchEvent로 파싱하고 커스텀 헤더를 검증합니다.
- **SkillResponse 빌더** — 말풍선 8종, 버튼, 퀵리플라이, 멘션을 만들고 개수 제한을 검사합니다.

## 사용 예

에코봇입니다. 받은 메시지를 그대로 돌려줍니다.

Node.js

```ts
import { KakaoBotGateway } from 'kakaotalk-bot';

const gateway = new KakaoBotGateway(process.env.BOT_TOKEN!);
gateway.on('MESSAGE_CREATE', (event) => event.replyText(`echo: ${event.data.content}`));
gateway.on('error', console.error);
await gateway.connect();
```

Python

```python
import asyncio
from kakaotalk_bot import KakaoBotGateway

gateway = KakaoBotGateway(BOT_TOKEN)

@gateway.on("MESSAGE_CREATE")
async def on_message(event):
    await event.reply_text(f"echo: {event.content}")

asyncio.run(gateway.run())
```

Java

```java
KakaoBotGateway gateway = KakaoBotGateway.builder(System.getenv("BOT_TOKEN"))
    .listener(new GatewayListener() {
        @Override public void onMessageCreate(DispatchEvent event) {
            event.replyText("echo: " + event.getContent());
        }
    })
    .build();
gateway.connect();
```

웹훅 방식과 메시지 전송 API, 카드형 말풍선 예제는 언어별 README에 있습니다.

## 재연결 동작

문서의 Close code 표를 그대로 따릅니다.

| Close code | 동작 |
|-----------|------|
| 1001 서버 재시작 | 즉시 RESUME |
| 네트워크 단절, HEARTBEAT_ACK 누락 | 1초 → 2초 → 4초 … 최대 30초 간격으로 RESUME 재시도 |
| 4009 세션 만료 | 세션을 버리고 새로 IDENTIFY |
| 4001, 4003, 4004 | 재연결하지 않고 fatal 에러 발행 |

## 알아둘 점

- 메시지 `id`는 64비트 정수입니다. Node.js에서는 정밀도 손실을 막기 위해 문자열로 노출합니다. Python은 `int`, Java는 `long`입니다.
- 웹훅 URL을 등록한 앱은 게이트웨이에 연결할 수 없습니다. 둘 중 하나만 씁니다.
- 웹훅은 1초 안에 2xx로 응답한 뒤 `reply()`를 호출해야 합니다.
- 콜백 토큰은 발급 후 5분간, 한 번만 쓸 수 있습니다.
- 게이트웨이의 이벤트 버퍼는 연결당 100개입니다. 오래 끊겼다가 RESUME하면 그 사이 이벤트는 못 받을 수 있습니다.

## 개발

```bash
cd nodejs && npm install && npm test
cd python && pip install -e ".[dev]" && pytest
cd java && mvn test
```

테스트는 모의 게이트웨이/REST 서버를 띄워 프로토콜 흐름(핸드셰이크, DISPATCH와 콜백, 1001/4009/4001 재연결)을 확인합니다.

배포 절차는 [PUBLISHING.md](PUBLISHING.md)에 있습니다.

## 라이선스

[MIT](LICENSE)
