# KakaoTalk Bot Wrapper

카카오톡 봇 API([developers.kakao.com/docs/ko/bot](https://developers.kakao.com/docs/ko/bot/common))의
**REST API**와 **게이트웨이(WebSocket)** 를 감싼 라이브러리입니다. 세 언어로 동일한 구조를 제공합니다.

| 언어 | 패키지 | 요구 사항 | 디렉토리 |
|------|--------|-----------|----------|
| Node.js (TypeScript / JavaScript) | `kakaotalk-bot` (npm) | Node.js 20+ | [`nodejs/`](nodejs/) |
| Python | `kakaotalk-bot` (PyPI) | Python 3.10+ | [`python/`](python/) |
| Java | `com.kakaotalkbot:kakaotalk-bot` (Maven) | Java 11+ | [`java/`](java/) |

## 제공 기능

### REST API 클라이언트 (`KakaoBotClient`)

| 메서드 | 엔드포인트 | 비고 |
|--------|------------|------|
| `sendCallback(callbackToken, skillResponse)` | `POST /v1/bot/callback` | 콜백 토큰은 5분 내 1회만 사용 가능 |
| `sendMessage(target, skillResponse)` / `sendToGroup` / `sendToUser` | `POST /v1/bot/send_message` | `botGroupKey` 또는 `botUserKey` 중 하나만 |
| `getCommands()` | `GET /v1/bot/commands` | |
| `updateCommands(utterances)` | `POST /v1/bot/commands/update` | 최대 30개, 전체 교체 |
| `getGuide()` | `GET /v1/bot/guide` | |
| `updateGuide(contents)` | `POST /v1/bot/guide/update` | |

* `Authorization: KakaoAK ${BOT_TOKEN}` 헤더와 `X-Bot-Callback-Token` 헤더를 자동으로 붙입니다.
* 4xx/5xx 응답은 `KakaoBotApiError`(Python: `KakaoBotApiError`, Java: `KakaoBotApiException`)로 변환되며
  `status`, `code`(예: `-881`), `msg`, `extras`와 함께 에러 코드 문서의 한국어 설명을 담습니다.

### 게이트웨이 클라이언트 (`KakaoBotGateway`)

문서의 웹소켓 프로토콜 흐름을 그대로 구현했습니다.

1. `wss://bot-gateway.kakao.com/gateway` 연결 → `HELLO(10)` 수신, `heartbeat_interval` 저장
2. `IDENTIFY(2)` 전송 → `READY(3)` 수신, `session_id` 저장 (재연결 시에는 `RESUME(6)`)
3. `heartbeat_interval - margin` 주기로 `HEARTBEAT(1)` 전송, `HEARTBEAT_ACK(11)` 누락 감지 시 재연결
4. `DISPATCH(0)` 수신 → `s` 저장, `t`별 이벤트 발행 (`MESSAGE_CREATE`, `ENTRANCE`, `CHAT_JOIN`, `LEAVE`, `AD_REWARD`)
5. 이벤트 객체의 `reply(skillResponse)` / `replyText(text)`가 콜백 토큰으로 메시지 콜백 API를 호출

재연결 규칙(문서의 Close code 표 기준):

| Close code | 동작 |
|-----------|------|
| `1001` 서버 재시작 | 즉시 `RESUME` |
| 네트워크 단절 / ACK 누락 | 지수 백오프(1s → 2s → 4s … 최대 30s) 후 `RESUME`, 실패 시 `IDENTIFY` |
| `4009` 세션 만료 | 세션 초기화 후 새 `IDENTIFY` |
| `4001` / `4003` / `4004` | 재연결 중단, `fatal` 에러 발행 |

### 웹훅 헬퍼

`parseWebhookEvent(body)`는 웹훅 요청 본문을 게이트웨이와 동일한 이벤트 객체로 바꿔 주고,
`verifyCustomHeader(received, expected)`는 등록한 커스텀 헤더를 상수 시간으로 비교합니다.

### SkillResponse 빌더

`SimpleText`, `SimpleImage`, `TextCard`, `BasicCard`, `ListCard`, `ItemCard`, `CommerceCard`, `Carousel`
컴포넌트와 버튼(10가지 액션), 퀵리플라이, 사용자 멘션(`{{#mentions.key}}` + `extra.mentions`) 생성을 돕고
개수 제한(말풍선 3개, 퀵리플라이 10개, 멘션 15명)을 검증합니다.

## 빠른 시작 (에코봇)

**Node.js**

```ts
import { KakaoBotGateway } from 'kakaotalk-bot';

const gateway = new KakaoBotGateway(process.env.BOT_TOKEN!);
gateway.on('MESSAGE_CREATE', (event) => event.replyText(`echo: ${event.data.content}`));
gateway.on('error', console.error);
await gateway.connect();
```

**Python**

```python
import asyncio
from kakaotalk_bot import KakaoBotGateway

gateway = KakaoBotGateway(BOT_TOKEN)

@gateway.on("MESSAGE_CREATE")
async def on_message(event):
    await event.reply_text(f"echo: {event.content}")

asyncio.run(gateway.run())
```

**Java**

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

## 주의 사항

* **메시지 id는 64비트 정수**(예: `558185822630215770`)입니다. JavaScript의 `Number`로는 정밀도가 깨지므로
  Node.js 라이브러리는 `id`를 항상 문자열로 노출합니다. Python은 `int`, Java는 `long`으로 그대로 다룹니다.
  웹훅에서는 가능하면 파싱 전 원본 문자열을 `parseWebhookEvent`에 넘기세요.
* 웹훅 URL이 등록된 앱은 게이트웨이에 연결할 수 없습니다. 둘 중 하나만 사용하세요.
* 웹훅은 1초 안에 2xx로 응답한 뒤 비동기로 `reply()`를 호출해야 합니다.
* 게이트웨이의 이벤트 버퍼는 연결당 최근 100개입니다. 오랫동안 끊긴 뒤 `RESUME`하면 그 사이 이벤트는 복구되지 않을 수 있습니다.

## 검증 상태

| 언어 | 검증 |
|------|------|
| Node.js | `tsc` 타입체크 + ESM/CJS 빌드, 모의 게이트웨이/REST 서버 통합 테스트 4건 통과 (`npm test`) |
| Python | `compileall`, 모의 서버 통합 테스트 5건 통과 (`pytest`) |
| Java | OpenJDK 27 + Maven 3.10 (`--release 11`)으로 컴파일, 단위 테스트 7건 + 모의 서버 통합 테스트 2건 통과 (`mvn test`) |

## 라이선스

MIT
