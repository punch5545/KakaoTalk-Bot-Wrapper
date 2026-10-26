import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';

import {
  KakaoBotClient,
  KakaoBotGateway,
  KakaoBotApiError,
  Opcode,
  textResponse,
  SkillResponseBuilder,
  mentionTag,
  parseWebhookEvent,
  verifyCustomHeader,
} from '../dist/esm/index.js';

const BOT_TOKEN = 'test-bot-token';

async function startRest() {
  const calls = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      calls.push({ method: req.method, url: req.url, headers: req.headers, body: body ? JSON.parse(body) : undefined });
      res.setHeader('Content-Type', 'application/json;charset=UTF-8');
      if (req.url === '/v1/bot/callback') {
        if (req.headers['x-bot-callback-token'] === 'expired') {
          res.statusCode = 400;
          res.end(JSON.stringify({ code: -881, msg: 'expired token' }));
          return;
        }
        res.end(JSON.stringify({ totalCount: 1, sendCount: 1, errorCount: 0 }));
      } else if (req.url === '/v1/bot/send_message') {
        res.end(JSON.stringify({ totalCount: 1, sendCount: 1, errorCount: 0 }));
      } else if (req.url === '/v1/bot/commands') {
        res.end(JSON.stringify({ utterances: [{ id: '1', type: 'TEXT', value: '도움말', buttonName: '도움말', description: null }] }));
      } else if (req.url === '/v1/bot/guide/update') {
        res.end(JSON.stringify({ contents: JSON.parse(body).contents, createdAt: '2024-03-01T09:00:00+09:00', updatedAt: null }));
      } else {
        res.statusCode = 404;
        res.end('{}');
      }
    });
  });
  server.listen(0);
  await once(server, 'listening');
  return { server, calls, url: `http://127.0.0.1:${server.address().port}` };
}

test('REST client: headers, bodies and error mapping', async () => {
  const rest = await startRest();
  try {
    const client = new KakaoBotClient(BOT_TOKEN, { baseUrl: rest.url });

    const r1 = await client.sendCallback('cb-1', textResponse('hi'));
    assert.deepEqual(r1, { totalCount: 1, sendCount: 1, errorCount: 0 });
    assert.equal(rest.calls[0].headers.authorization, `KakaoAK ${BOT_TOKEN}`);
    assert.equal(rest.calls[0].headers['x-bot-callback-token'], 'cb-1');
    assert.deepEqual(rest.calls[0].body, { skillResponse: { version: '2.0', template: { outputs: [{ simpleText: { text: 'hi' } }] } } });

    await client.sendToGroup('grp', textResponse('x'));
    assert.deepEqual(Object.keys(rest.calls[1].body), ['botGroupKey', 'skillResponse']);
    assert.throws(() => client.sendMessage({}, textResponse('x')), TypeError);

    const cmds = await client.getCommands();
    assert.equal(cmds.utterances[0].value, '도움말');
    assert.equal(rest.calls[2].method, 'GET');

    const guide = await client.updateGuide('새 도움말');
    assert.equal(guide.contents, '새 도움말');

    await assert.rejects(client.sendCallback('expired', textResponse('x')), (err) => {
      assert.ok(err instanceof KakaoBotApiError);
      assert.equal(err.status, 400);
      assert.equal(err.code, -881);
      assert.ok(err.isCallbackTokenError);
      assert.match(err.message, /-881/);
      return true;
    });

    assert.throws(() => client.updateCommands(new Array(31).fill({ buttonName: 'x' })), RangeError);
  } finally {
    rest.server.close();
  }
});

test('SkillResponseBuilder with mentions and quick replies', () => {
  const res = new SkillResponseBuilder()
    .text(`hello ${mentionTag('u1')}`)
    .mention('u1', 'buk_1')
    .quickReply('도움말')
    .build();
  assert.deepEqual(res, {
    version: '2.0',
    template: {
      outputs: [{ simpleText: { text: 'hello {{#mentions.u1}}' } }],
      quickReplies: [{ label: '도움말', action: 'message', messageText: '도움말' }],
    },
    extra: { mentions: { u1: { type: 'botUserKey', id: 'buk_1' } } },
  });
  assert.throws(() => new SkillResponseBuilder().build(), RangeError);
});

test('webhook parsing keeps 64-bit ids and verifies headers', async () => {
  const raw = '{"op":0,"t":"MESSAGE_CREATE","d":{"id":558185822630215770,"content":"안녕","timestamp":"t","isChannelChatroom":false,"botGroupKey":"g","botUserKey":"u","callbackToken":"cb"}}';
  const ev = parseWebhookEvent(raw);
  assert.equal(ev.type, 'MESSAGE_CREATE');
  assert.equal(ev.data.id, '558185822630215770');
  assert.equal(ev.seq, null);
  assert.ok(ev.canReply);
  await assert.rejects(ev.reply(textResponse('x')), /No KakaoBotClient/);

  const noToken = parseWebhookEvent({ op: 0, t: 'LEAVE', d: { botGroupKey: 'g', timestamp: 't' } });
  assert.equal(noToken.canReply, false);
  assert.throws(() => parseWebhookEvent({ op: 10, d: {} }), /not a DISPATCH/);
  assert.throws(() => parseWebhookEvent({ op: 0, t: 'NOPE', d: {} }), /unknown event type/);

  assert.ok(verifyCustomHeader('secret', 'secret'));
  assert.ok(!verifyCustomHeader('secre', 'secret'));
  assert.ok(!verifyCustomHeader(undefined, 'secret'));
});

test('Gateway: HELLO → IDENTIFY → READY → DISPATCH/reply → heartbeat → 1001 RESUME → 4009 IDENTIFY → fatal 4001', async () => {
  const rest = await startRest();
  const wss = new WebSocketServer({ port: 0 });
  await once(wss, 'listening');
  const gwUrl = `ws://127.0.0.1:${wss.address().port}/gateway`;

  const serverLog = [];
  let connection = 0;
  wss.on('connection', (ws) => {
    connection += 1;
    const n = connection;
    ws.send(JSON.stringify({ op: 10, d: { heartbeat_interval: 1500 } }));
    ws.on('message', (raw) => {
      const frame = JSON.parse(raw.toString());
      serverLog.push({ n, frame });
      if (frame.op === Opcode.IDENTIFY) {
        assert.equal(frame.d.token, BOT_TOKEN);
        ws.send(JSON.stringify({ op: 3, d: { session_id: `sess_${n}`, bot: { id: 'bot1' } } }));
        if (n === 1) {
          ws.send(
            '{"op":0,"s":1,"t":"MESSAGE_CREATE","d":{"id":558185822630215770,"content":"hello","timestamp":"t","isChannelChatroom":false,"botGroupKey":"g","botUserKey":"u","callbackToken":"cb-gw"}}',
          );
        } else if (n === 3) {
          // Third connection: fatal auth failure.
          ws.close(4001, 'auth failed');
        }
      } else if (frame.op === Opcode.HEARTBEAT) {
        ws.send(JSON.stringify({ op: 11 }));
        if (n === 1) {
          // After the first heartbeat, simulate a graceful server restart.
          ws.close(1001, 'restart');
        }
      } else if (frame.op === Opcode.RESUME) {
        assert.equal(frame.d.session_id, 'sess_1');
        assert.equal(frame.d.seq, 1);
        ws.send(JSON.stringify({ op: 0, s: 2, t: 'LEAVE', d: { botGroupKey: 'g', timestamp: 't' } }));
        setTimeout(() => ws.close(4009, 'session expired'), 50);
      }
    });
  });

  const gateway = new KakaoBotGateway(BOT_TOKEN, {
    url: gwUrl,
    clientOptions: { baseUrl: rest.url },
    initialBackoffMs: 50,
    maxBackoffMs: 200,
    heartbeatMarginMs: 1000, // heartbeat every 500 ms
  });

  const events = [];
  const errors = [];
  gateway.on('ready', (d) => events.push(['ready', d.session_id]));
  gateway.on('resumed', (s) => events.push(['resumed', s.sessionId]));
  gateway.on('reconnecting', (attempt, delay, willResume) => events.push(['reconnecting', attempt, willResume]));
  gateway.on('close', (code) => events.push(['close', code]));
  gateway.on('error', (err) => errors.push(err));
  gateway.on('LEAVE', (ev) => events.push(['LEAVE', ev.seq]));
  gateway.on('MESSAGE_CREATE', async (ev) => {
    events.push(['MESSAGE_CREATE', ev.data.id, ev.data.content]);
    const result = await ev.replyText(`echo: ${ev.data.content}`);
    events.push(['replied', result.sendCount]);
  });

  await gateway.connect();
  assert.equal(gateway.state, 'ready');
  assert.equal(gateway.session.sessionId, 'sess_1');

  // Wait until the fatal close has been processed.
  const deadline = Date.now() + 8000;
  while (!errors.some((e) => e.fatal) && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 25));
  }
  gateway.close();
  wss.close();
  rest.server.close();

  assert.ok(errors.some((e) => e.fatal && e.closeCode === 4001), `expected fatal error, got ${errors.map(String)}`);
  assert.equal(gateway.state, 'closed');

  assert.deepEqual(events.slice(0, 3), [['ready', 'sess_1'], ['MESSAGE_CREATE', '558185822630215770', 'hello'], ['replied', 1]]);
  assert.ok(events.some((e) => e[0] === 'close' && e[1] === 1001));
  assert.ok(events.some((e) => e[0] === 'reconnecting' && e[1] === 1 && e[2] === true), 'should resume after 1001');
  assert.ok(events.some((e) => e[0] === 'resumed' && e[1] === 'sess_1'));
  assert.ok(events.some((e) => e[0] === 'LEAVE' && e[1] === 2));
  assert.ok(events.some((e) => e[0] === 'close' && e[1] === 4009));
  assert.ok(events.some((e) => e[0] === 'reconnecting' && e[2] === false), 'should IDENTIFY after 4009');
  assert.ok(events.some((e) => e[0] === 'ready' && e[1] === 'sess_3'));

  // REST callback was called with the gateway's callback token.
  const cb = rest.calls.find((c) => c.url === '/v1/bot/callback');
  assert.equal(cb.headers['x-bot-callback-token'], 'cb-gw');
  assert.equal(cb.body.skillResponse.template.outputs[0].simpleText.text, 'echo: hello');

  // Protocol order on the server side.
  const ops = serverLog.map((l) => `${l.n}:${l.frame.op}`);
  assert.equal(ops[0], '1:2'); // IDENTIFY
  assert.ok(ops.includes('1:1')); // HEARTBEAT
  assert.ok(ops.includes('2:6')); // RESUME
  assert.ok(ops.includes('3:2')); // IDENTIFY after session timeout
});
