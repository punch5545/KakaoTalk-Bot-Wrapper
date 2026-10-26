# kakaotalk-bot (Node.js)

KakaoTalk Bot API client for Node.js 20+: REST API wrapper, Gateway (WebSocket) client and webhook helpers.
Written in TypeScript, shipped as ESM + CommonJS with type definitions.

```bash
npm install kakaotalk-bot
```

## REST API

```ts
import { KakaoBotClient, textResponse, SkillResponseBuilder, Buttons, Components, KakaoBotApiError } from 'kakaotalk-bot';

const client = new KakaoBotClient(process.env.BOT_TOKEN!);

// Reply to an event (token from MESSAGE_CREATE / ENTRANCE / CHAT_JOIN / AD_REWARD, valid 5 min, single use)
await client.sendCallback(callbackToken, textResponse('처리가 완료되었습니다.'));

// Proactive message to a room (group / open / team chat) or a channel-chat user
await client.sendToGroup(botGroupKey, textResponse('안녕하세요!'));
await client.sendToUser(botUserKey, textResponse('안녕하세요!'));

// Commands & guide
const { utterances } = await client.getCommands();
await client.updateCommands([{ buttonName: '도움말' }, { buttonName: '상담원 연결', description: '상담원과 연결합니다.' }]);
await client.updateGuide('🤖 저와 함께 이런 것들을 할 수 있어요.');

// Rich responses
const response = new SkillResponseBuilder()
  .basicCard({
    title: '카드 제목',
    description: '설명',
    thumbnail: { imageUrl: 'https://example.com/a.png' },
    buttons: [Buttons.webLink('열기', 'https://example.com'), Buttons.message('다시', '다시')],
  })
  .quickReply('도움말')
  .build();

try {
  await client.sendCallback(callbackToken, response);
} catch (err) {
  if (err instanceof KakaoBotApiError && err.isCallbackTokenError) {
    // -881: expired or already used token
  }
}
```

Options: `new KakaoBotClient(token, { baseUrl, fetch, timeoutMs, headers })`.

## Gateway (WebSocket)

```ts
import { KakaoBotGateway } from 'kakaotalk-bot';

const gateway = new KakaoBotGateway(process.env.BOT_TOKEN!, {
  // url, client, autoReconnect (true), initialBackoffMs (1000), maxBackoffMs (30000),
  // heartbeatMarginMs (5000), handshakeTimeoutMs (15000)
});

gateway.on('ready', (ready) => console.log('bot', ready.bot.id, 'session', ready.session_id));
gateway.on('MESSAGE_CREATE', async (event) => {
  // event.data: { id (string), content, timestamp, isChannelChatroom, botGroupKey, botUserKey, callbackToken? }
  if (event.canReply) await event.replyText(`echo: ${event.data.content}`);
});
gateway.on('ENTRANCE', (event) => event.replyText('초대해 주셔서 감사합니다!'));
gateway.on('reconnecting', (attempt, delayMs, willResume) => console.log({ attempt, delayMs, willResume }));
gateway.on('error', (err) => console.error(err)); // fatal close codes have err.fatal === true

await gateway.connect(); // resolves on READY
// ...
gateway.close();
```

Events: `ready`, `resumed`, `dispatch`, `MESSAGE_CREATE`, `ENTRANCE`, `CHAT_JOIN`, `LEAVE`, `AD_REWARD`,
`close`, `reconnecting`, `heartbeatTimeout`, `error`.

Session persistence: save `gateway.session` (`sessionId`, `lastSeq`) and call
`gateway.restoreSession(saved)` before `connect()` to RESUME across process restarts.

## Webhook

```ts
import express from 'express';
import { KakaoBotClient, parseWebhookEvent, verifyCustomHeader } from 'kakaotalk-bot';

const client = new KakaoBotClient(process.env.BOT_TOKEN!);
const app = express();

// Use the raw body so 64-bit message ids keep their precision.
app.post('/webhook', express.raw({ type: 'application/json' }), (req, res) => {
  if (!verifyCustomHeader(req.header('X-My-Secret'), process.env.WEBHOOK_SECRET!)) return res.sendStatus(401);
  const event = parseWebhookEvent(req.body, client);
  res.sendStatus(204); // respond within 1 second, then reply asynchronously
  if (event.type === 'MESSAGE_CREATE' && event.canReply) {
    event.replyText(`echo: ${event.data.content}`).catch(console.error);
  }
});
```

## Development

```bash
npm install
npm run typecheck
npm test        # builds, then runs tests against mock gateway/REST servers
npm run build   # dist/esm + dist/cjs
```
