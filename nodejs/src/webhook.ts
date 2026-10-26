import { timingSafeEqual } from 'node:crypto';

import type { KakaoBotClient } from './client.js';
import { KakaoBotGatewayError } from './errors.js';
import { createDispatchEvent, isDispatch, normaliseFrame, parseFrame } from './events.js';
import type { DispatchEvent } from './events.js';
import type { DispatchPayload } from './types.js';

/**
 * Parse a webhook request body (raw string/Buffer or an already-parsed object)
 * into a DISPATCH payload. Throws KakaoBotGatewayError for malformed input.
 *
 * Prefer passing the raw body: 64-bit message ids lose precision once a
 * generic JSON body parser has turned them into JavaScript numbers.
 */
export function parseWebhookPayload(body: string | Buffer | Uint8Array | object): DispatchPayload {
  let frame;
  if (typeof body === 'string') {
    frame = parseFrame(body);
  } else if (Buffer.isBuffer(body) || body instanceof Uint8Array) {
    frame = parseFrame(Buffer.from(body).toString('utf8'));
  } else {
    frame = normaliseFrame(body);
  }
  if (!isDispatch(frame)) {
    throw new KakaoBotGatewayError(`Webhook body is not a DISPATCH frame (op=${frame.op})`);
  }
  return frame;
}

/**
 * Parse a webhook request body and wrap it as a DispatchEvent with `reply()`.
 *
 * Remember: respond 2xx within 1 second, then reply asynchronously.
 */
export function parseWebhookEvent(body: string | Buffer | Uint8Array | object, client?: KakaoBotClient): DispatchEvent {
  return createDispatchEvent(parseWebhookPayload(body), client);
}

/**
 * Constant-time comparison of the custom header registered in the webhook
 * settings against the value received, to verify the request came from Kakao.
 */
export function verifyCustomHeader(received: string | string[] | undefined, expected: string): boolean {
  const value = Array.isArray(received) ? received[0] : received;
  if (typeof value !== 'string' || typeof expected !== 'string') return false;
  const a = Buffer.from(value, 'utf8');
  const b = Buffer.from(expected, 'utf8');
  if (a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}
