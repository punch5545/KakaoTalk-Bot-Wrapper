import type { KakaoBotClient } from './client.js';
import { KakaoBotGatewayError } from './errors.js';
import { textResponse } from './skill.js';
import { EVENT_TYPES, Opcode } from './types.js';
import type { DispatchPayload, EventDataMap, EventType, GatewayFrame, SendResult, SkillResponse } from './types.js';

/** A chat-room event received via the gateway or a webhook. */
export interface DispatchEvent<T extends EventType = EventType> {
  type: T;
  /** Sequence number (gateway only; null for webhook requests). */
  seq: number | null;
  data: EventDataMap[T];
  /** The raw DISPATCH payload. */
  raw: DispatchPayload<T>;
  /** Callback token if this event can be replied to. */
  callbackToken: string | undefined;
  /** True when a callback token is present. */
  readonly canReply: boolean;
  /** Reply through POST /v1/bot/callback. Throws when there is no callback token / client. */
  reply(skillResponse: SkillResponse): Promise<SendResult>;
  /** Reply with a single SimpleText bubble. */
  replyText(text: string): Promise<SendResult>;
}

/** Large (64-bit) numeric ids would lose precision in JSON.parse; quote them first. */
const BIG_ID_PATTERN = /"id"\s*:\s*(-?\d{16,})/g;

/** Parse a gateway/webhook frame from text, normalising `d.id` to a string. */
export function parseFrame(text: string): GatewayFrame {
  const frame = JSON.parse(text.replace(BIG_ID_PATTERN, '"id":"$1"')) as unknown;
  return normaliseFrame(frame);
}

/** Validate an already-parsed object and normalise `d.id` to a string. */
export function normaliseFrame(frame: unknown): GatewayFrame {
  if (typeof frame !== 'object' || frame === null || typeof (frame as { op?: unknown }).op !== 'number') {
    throw new KakaoBotGatewayError('Invalid frame: missing numeric "op"');
  }
  const f = frame as { op: number; t?: unknown; d?: unknown; s?: unknown };
  if (f.op === Opcode.DISPATCH) {
    if (typeof f.t !== 'string' || !(EVENT_TYPES as readonly string[]).includes(f.t)) {
      throw new KakaoBotGatewayError(`Invalid DISPATCH frame: unknown event type "${String(f.t)}"`);
    }
    if (typeof f.d !== 'object' || f.d === null) {
      throw new KakaoBotGatewayError('Invalid DISPATCH frame: missing "d"');
    }
    const d = f.d as { id?: unknown };
    if (typeof d.id === 'number' || typeof d.id === 'bigint') d.id = String(d.id);
  }
  return frame as GatewayFrame;
}

export function isDispatch(frame: GatewayFrame): frame is DispatchPayload {
  return frame.op === Opcode.DISPATCH;
}

/** Wrap a DISPATCH payload into a DispatchEvent with reply helpers. */
export function createDispatchEvent<T extends EventType>(
  payload: DispatchPayload<T>,
  client?: KakaoBotClient,
): DispatchEvent<T> {
  const d = payload.d as { callbackToken?: string };
  const callbackToken = typeof d.callbackToken === 'string' && d.callbackToken.length > 0 ? d.callbackToken : undefined;

  const reply = async (skillResponse: SkillResponse): Promise<SendResult> => {
    if (!callbackToken) {
      throw new KakaoBotGatewayError(`Event ${payload.t} has no callbackToken; it cannot be replied to`);
    }
    if (!client) {
      throw new KakaoBotGatewayError('No KakaoBotClient attached; cannot send callback');
    }
    return client.sendCallback(callbackToken, skillResponse);
  };

  return {
    type: payload.t,
    seq: typeof payload.s === 'number' ? payload.s : null,
    data: payload.d,
    raw: payload,
    callbackToken,
    canReply: callbackToken !== undefined,
    reply,
    replyText: (text: string) => reply(textResponse(text)),
  };
}
