import { EventEmitter } from 'node:events';
import WebSocket from 'ws';

import { KakaoBotClient } from './client.js';
import type { KakaoBotClientOptions } from './client.js';
import { KakaoBotGatewayError } from './errors.js';
import { createDispatchEvent, parseFrame } from './events.js';
import type { DispatchEvent } from './events.js';
import { CloseCode, Opcode } from './types.js';
import type {
  AdRewardData,
  ChatJoinData,
  DispatchPayload,
  EntranceData,
  EventType,
  HeartbeatFrame,
  IdentifyFrame,
  LeaveData,
  MessageCreateData,
  ReadyData,
  ResumeFrame,
} from './types.js';

export const DEFAULT_GATEWAY_URL = 'wss://bot-gateway.kakao.com/gateway';

/** Close codes after which the gateway must not reconnect. */
export const FATAL_CLOSE_CODES: ReadonlySet<number> = new Set([
  CloseCode.AUTH_FAILED,
  CloseCode.INVALID_PAYLOAD,
  CloseCode.REPLACED,
]);

export type GatewayState = 'idle' | 'connecting' | 'ready' | 'reconnecting' | 'closed';

export interface GatewaySession {
  sessionId: string | null;
  lastSeq: number;
  botId: string | null;
}

export interface KakaoBotGatewayOptions {
  /** Gateway URL. Default: wss://bot-gateway.kakao.com/gateway */
  url?: string;
  /** REST client used by `event.reply()`. Default: a new client built from the bot token. */
  client?: KakaoBotClient;
  /** Options for the default REST client (ignored when `client` is given). */
  clientOptions?: KakaoBotClientOptions;
  /** WebSocket constructor override (defaults to `ws`). */
  webSocket?: typeof WebSocket;
  /** Reconnect automatically on non-fatal disconnects. Default: true */
  autoReconnect?: boolean;
  /** First reconnect delay. Default: 1000 ms (doubles each attempt). */
  initialBackoffMs?: number;
  /** Upper bound for the reconnect delay. Default: 30000 ms */
  maxBackoffMs?: number;
  /** Send heartbeats this much earlier than `heartbeat_interval`. Default: 5000 ms */
  heartbeatMarginMs?: number;
  /** Abort a connection attempt when READY/RESUME does not complete in time. Default: 15000 ms */
  handshakeTimeoutMs?: number;
}

export type GatewayEvents = {
  /** READY received after IDENTIFY. */
  ready: [data: ReadyData];
  /** RESUME frame sent on a reconnected socket. */
  resumed: [session: GatewaySession];
  /** Any DISPATCH event. */
  dispatch: [event: DispatchEvent];
  MESSAGE_CREATE: [event: DispatchEvent<'MESSAGE_CREATE'>];
  ENTRANCE: [event: DispatchEvent<'ENTRANCE'>];
  CHAT_JOIN: [event: DispatchEvent<'CHAT_JOIN'>];
  LEAVE: [event: DispatchEvent<'LEAVE'>];
  AD_REWARD: [event: DispatchEvent<'AD_REWARD'>];
  /** Socket closed (before any reconnect decision). */
  close: [code: number, reason: string];
  /** A reconnect has been scheduled. */
  reconnecting: [attempt: number, delayMs: number, willResume: boolean];
  /** No HEARTBEAT_ACK since the previous heartbeat; the socket is being terminated. */
  heartbeatTimeout: [];
  error: [error: Error];
};

type Listener<A extends unknown[]> = (...args: A) => void;

export interface TypedEmitter<E extends Record<string, unknown[]>> {
  on<K extends keyof E & string>(event: K, listener: Listener<E[K]>): this;
  once<K extends keyof E & string>(event: K, listener: Listener<E[K]>): this;
  off<K extends keyof E & string>(event: K, listener: Listener<E[K]>): this;
  addListener<K extends keyof E & string>(event: K, listener: Listener<E[K]>): this;
  removeListener<K extends keyof E & string>(event: K, listener: Listener<E[K]>): this;
  removeAllListeners(event?: keyof E & string): this;
  emit<K extends keyof E & string>(event: K, ...args: E[K]): boolean;
  listenerCount(event: keyof E & string): number;
}

const TypedEventEmitter = EventEmitter as unknown as new () => TypedEmitter<GatewayEvents>;

/**
 * KakaoTalk Bot Gateway (WebSocket) client.
 *
 * Implements the full protocol flow: HELLO → IDENTIFY/RESUME → READY,
 * periodic HEARTBEAT with ACK tracking, DISPATCH fan-out, and reconnection
 * with exponential backoff (1s → 2s → 4s … max 30s) following the official
 * close-code rules.
 *
 * ```ts
 * const gateway = new KakaoBotGateway(process.env.BOT_TOKEN!);
 * gateway.on('MESSAGE_CREATE', (event) => event.replyText(`echo: ${event.data.content}`));
 * await gateway.connect();
 * ```
 */
export class KakaoBotGateway extends TypedEventEmitter {
  readonly client: KakaoBotClient;
  readonly url: string;

  private readonly token: string;
  private readonly WS: typeof WebSocket;
  private readonly autoReconnect: boolean;
  private readonly initialBackoffMs: number;
  private readonly maxBackoffMs: number;
  private readonly heartbeatMarginMs: number;
  private readonly handshakeTimeoutMs: number;

  private ws: WebSocket | null = null;
  private _state: GatewayState = 'idle';
  private sessionId: string | null = null;
  private lastSeq = 0;
  private botId: string | null = null;

  private heartbeatTimer: NodeJS.Timeout | null = null;
  private heartbeatIntervalMs: number | null = null;
  private awaitingAck = false;

  private reconnectTimer: NodeJS.Timeout | null = null;
  private reconnectAttempt = 0;
  private closedByUser = false;

  /** Resolves/rejects the pending connect() handshake. */
  private pendingHandshake: { resolve: () => void; reject: (err: Error) => void } | null = null;

  constructor(botToken: string, options: KakaoBotGatewayOptions = {}) {
    super();
    if (!botToken || typeof botToken !== 'string') throw new TypeError('botToken is required');
    this.token = botToken;
    this.url = options.url ?? DEFAULT_GATEWAY_URL;
    this.client = options.client ?? new KakaoBotClient(botToken, options.clientOptions);
    this.WS = options.webSocket ?? WebSocket;
    this.autoReconnect = options.autoReconnect ?? true;
    this.initialBackoffMs = options.initialBackoffMs ?? 1_000;
    this.maxBackoffMs = options.maxBackoffMs ?? 30_000;
    this.heartbeatMarginMs = options.heartbeatMarginMs ?? 5_000;
    this.handshakeTimeoutMs = options.handshakeTimeoutMs ?? 15_000;
  }

  get state(): GatewayState {
    return this._state;
  }

  /** Current session info (useful for persisting across process restarts). */
  get session(): GatewaySession {
    return { sessionId: this.sessionId, lastSeq: this.lastSeq, botId: this.botId };
  }

  /** Last `heartbeat_interval` announced by HELLO (ms). */
  get heartbeatInterval(): number | null {
    return this.heartbeatIntervalMs;
  }

  /**
   * Connect and resolve once the session is READY (or RESUMEd).
   * Rejects if the first connection attempt fails; later disconnects are
   * handled by auto-reconnect and reported via events.
   */
  connect(): Promise<void> {
    if (this.ws) return Promise.reject(new KakaoBotGatewayError('Gateway is already connected or connecting'));
    this.closedByUser = false;
    this.reconnectAttempt = 0;
    return this.openSocket(false);
  }

  /** Close the connection and stop reconnecting. */
  close(code = 1000, reason = 'client closed'): void {
    this.closedByUser = true;
    this.clearReconnectTimer();
    this.stopHeartbeat();
    this._state = 'closed';
    const ws = this.ws;
    this.ws = null;
    if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) {
      ws.close(code, reason);
    }
    this.settleHandshake(new KakaoBotGatewayError('Gateway closed by client'));
  }

  /** Forget the session so the next connection uses IDENTIFY instead of RESUME. */
  resetSession(): void {
    this.sessionId = null;
    this.lastSeq = 0;
  }

  /**
   * Restore a session saved from `gateway.session` so the first connection
   * attempts RESUME (recovers up to the last 100 buffered events).
   */
  restoreSession(session: Pick<GatewaySession, 'sessionId' | 'lastSeq'>): void {
    this.sessionId = session.sessionId;
    this.lastSeq = session.lastSeq;
  }

  // ---------------------------------------------------------------------
  // Connection lifecycle
  // ---------------------------------------------------------------------

  private openSocket(isReconnect: boolean): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this._state = isReconnect ? 'reconnecting' : 'connecting';
      this.pendingHandshake = { resolve, reject };

      let ws: WebSocket;
      try {
        ws = new this.WS(this.url);
      } catch (err) {
        this.pendingHandshake = null;
        reject(err instanceof Error ? err : new Error(String(err)));
        if (isReconnect) this.scheduleReconnect(1006);
        return;
      }
      this.ws = ws;

      const handshakeTimer = setTimeout(() => {
        if (this.ws === ws && this.pendingHandshake) {
          this.emitError(new KakaoBotGatewayError('Gateway handshake timed out'));
          ws.terminate();
        }
      }, this.handshakeTimeoutMs);

      ws.on('message', (raw) => {
        if (this.ws !== ws) return;
        this.handleMessage(ws, raw);
      });

      ws.on('error', (err) => {
        if (this.ws !== ws) return;
        this.emitError(err);
      });

      ws.on('close', (code, reasonBuf) => {
        clearTimeout(handshakeTimer);
        if (this.ws !== ws) return;
        this.ws = null;
        this.stopHeartbeat();
        const reason = reasonBuf.toString();
        this.emit('close', code, reason);

        const wasHandshaking = this.pendingHandshake !== null;
        this.settleHandshake(
          new KakaoBotGatewayError(`Gateway closed during handshake (code ${code}${reason ? `: ${reason}` : ''})`, {
            closeCode: code,
          }),
        );
        if (wasHandshaking && !isReconnect) {
          // Initial connect() failed: surface via the rejected promise, do not loop.
          this._state = 'closed';
          return;
        }
        this.scheduleReconnect(code);
      });
    });
  }

  private handleMessage(ws: WebSocket, raw: WebSocket.RawData): void {
    let frame;
    try {
      frame = parseFrame(rawToString(raw));
    } catch (err) {
      this.emitError(err instanceof Error ? err : new KakaoBotGatewayError(String(err)));
      return;
    }

    switch (frame.op) {
      case Opcode.HELLO: {
        const interval = frame.d?.heartbeat_interval;
        if (typeof interval !== 'number' || interval <= 0) {
          this.emitError(new KakaoBotGatewayError('HELLO frame without a valid heartbeat_interval'));
          ws.terminate();
          return;
        }
        this.heartbeatIntervalMs = interval;
        if (this.sessionId) {
          const resume: ResumeFrame = {
            op: Opcode.RESUME,
            d: { token: this.token, session_id: this.sessionId, seq: this.lastSeq },
          };
          this.send(ws, resume);
          // The server does not re-send READY after a RESUME; treat the session as live.
          this.becomeReady();
          this.emit('resumed', this.session);
          this.settleHandshake();
        } else {
          const identify: IdentifyFrame = { op: Opcode.IDENTIFY, d: { token: this.token } };
          this.send(ws, identify);
        }
        this.startHeartbeat(ws, interval);
        break;
      }
      case Opcode.READY: {
        this.sessionId = frame.d.session_id;
        this.botId = frame.d.bot?.id ?? null;
        this.lastSeq = 0;
        this.becomeReady();
        this.emit('ready', frame.d);
        this.settleHandshake();
        break;
      }
      case Opcode.HEARTBEAT_ACK: {
        this.awaitingAck = false;
        break;
      }
      case Opcode.DISPATCH: {
        if (typeof frame.s === 'number') this.lastSeq = frame.s;
        this.dispatch(frame);
        break;
      }
      default:
        // Unknown opcode: ignore for forward compatibility.
        break;
    }
  }

  private dispatch(payload: DispatchPayload): void {
    const event = createDispatchEvent(payload, this.client);
    this.emit('dispatch', event);
    switch (payload.t) {
      case 'MESSAGE_CREATE':
        this.emit('MESSAGE_CREATE', event as DispatchEvent<'MESSAGE_CREATE'>);
        break;
      case 'ENTRANCE':
        this.emit('ENTRANCE', event as DispatchEvent<'ENTRANCE'>);
        break;
      case 'CHAT_JOIN':
        this.emit('CHAT_JOIN', event as DispatchEvent<'CHAT_JOIN'>);
        break;
      case 'LEAVE':
        this.emit('LEAVE', event as DispatchEvent<'LEAVE'>);
        break;
      case 'AD_REWARD':
        this.emit('AD_REWARD', event as DispatchEvent<'AD_REWARD'>);
        break;
    }
  }

  private becomeReady(): void {
    this._state = 'ready';
    this.reconnectAttempt = 0;
  }

  private settleHandshake(error?: Error): void {
    const pending = this.pendingHandshake;
    if (!pending) return;
    this.pendingHandshake = null;
    if (error) pending.reject(error);
    else pending.resolve();
  }

  // ---------------------------------------------------------------------
  // Heartbeat
  // ---------------------------------------------------------------------

  private startHeartbeat(ws: WebSocket, intervalMs: number): void {
    this.stopHeartbeat();
    const period = Math.max(1_000, intervalMs - this.heartbeatMarginMs);
    this.awaitingAck = false;
    this.heartbeatTimer = setInterval(() => {
      if (this.ws !== ws) {
        this.stopHeartbeat();
        return;
      }
      if (this.awaitingAck) {
        // No ACK since the last heartbeat: the connection is probably dead.
        this.emit('heartbeatTimeout');
        ws.terminate(); // triggers 'close' (1006) → reconnect with RESUME
        return;
      }
      this.awaitingAck = true;
      const heartbeat: HeartbeatFrame = { op: Opcode.HEARTBEAT };
      this.send(ws, heartbeat);
    }, period);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.awaitingAck = false;
  }

  // ---------------------------------------------------------------------
  // Reconnect
  // ---------------------------------------------------------------------

  private scheduleReconnect(code: number): void {
    if (this.closedByUser) {
      this._state = 'closed';
      return;
    }
    if (FATAL_CLOSE_CODES.has(code)) {
      this._state = 'closed';
      this.emitError(
        new KakaoBotGatewayError(`Gateway closed with fatal code ${code}: ${describeCloseCode(code)}`, {
          closeCode: code,
          fatal: true,
        }),
      );
      return;
    }
    if (code === CloseCode.SESSION_TIMEOUT) {
      this.resetSession();
    }
    if (!this.autoReconnect) {
      this._state = 'closed';
      return;
    }

    this.reconnectAttempt += 1;
    const immediate = code === CloseCode.SERVER_RESTART && this.reconnectAttempt === 1 && this.sessionId !== null;
    const delay = immediate
      ? 0
      : Math.min(this.initialBackoffMs * 2 ** (this.reconnectAttempt - 1), this.maxBackoffMs);

    this._state = 'reconnecting';
    this.emit('reconnecting', this.reconnectAttempt, delay, this.sessionId !== null);
    this.clearReconnectTimer();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.closedByUser) return;
      this.openSocket(true).catch(() => {
        /* failures are reported through 'close' / 'error' events and rescheduled */
      });
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private send(ws: WebSocket, frame: IdentifyFrame | ResumeFrame | HeartbeatFrame): void {
    if (ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify(frame), (err) => {
      if (err) this.emitError(err);
    });
  }

  private emitError(error: Error): void {
    // EventEmitter throws on unhandled 'error' events; only emit when someone listens.
    if (this.listenerCount('error') > 0) this.emit('error', error);
  }
}

export function describeCloseCode(code: number): string {
  switch (code) {
    case CloseCode.SERVER_RESTART:
      return '서버 재시작 (Graceful shutdown)';
    case CloseCode.AUTH_FAILED:
      return '인증 실패 (토큰 누락 또는 오류)';
    case CloseCode.INVALID_PAYLOAD:
      return '유효하지 않은 페이로드';
    case CloseCode.REPLACED:
      return '다른 연결로 대체됨 (중복 IDENTIFY)';
    case CloseCode.SESSION_TIMEOUT:
      return '세션 만료';
    default:
      return `unknown close code ${code}`;
  }
}

function rawToString(raw: WebSocket.RawData): string {
  if (typeof raw === 'string') return raw;
  if (Array.isArray(raw)) return Buffer.concat(raw).toString('utf8');
  if (raw instanceof ArrayBuffer) return Buffer.from(raw).toString('utf8');
  return raw.toString('utf8');
}

// Re-export the event data types for convenience.
export type { MessageCreateData, EntranceData, ChatJoinData, LeaveData, AdRewardData, EventType };
