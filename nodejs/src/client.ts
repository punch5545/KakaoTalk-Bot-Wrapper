import { KakaoBotApiError } from './errors.js';
import type {
  CommandsResponse,
  Guide,
  SendMessageTarget,
  SendResult,
  SkillResponse,
  UtteranceRequest,
} from './types.js';

export const DEFAULT_API_BASE_URL = 'https://kapi.kakao.com';
export const MAX_COMMANDS = 30;

export interface KakaoBotClientOptions {
  /** API base URL. Default: https://kapi.kakao.com */
  baseUrl?: string;
  /** Custom fetch implementation. Default: globalThis.fetch (Node.js 20+). */
  fetch?: typeof globalThis.fetch;
  /** Per-request timeout in milliseconds. Default: 10000. */
  timeoutMs?: number;
  /** Extra headers sent with every request (e.g. User-Agent). */
  headers?: Record<string, string>;
}

/**
 * Thin wrapper around the KakaoTalk Bot REST API.
 *
 * ```ts
 * const client = new KakaoBotClient(process.env.BOT_TOKEN!);
 * await client.sendCallback(callbackToken, textResponse('안녕하세요!'));
 * ```
 */
export class KakaoBotClient {
  readonly baseUrl: string;
  private readonly token: string;
  private readonly fetchImpl: typeof globalThis.fetch;
  private readonly timeoutMs: number;
  private readonly extraHeaders: Record<string, string>;

  constructor(botToken: string, options: KakaoBotClientOptions = {}) {
    if (!botToken || typeof botToken !== 'string') {
      throw new TypeError('botToken is required');
    }
    const fetchImpl = options.fetch ?? globalThis.fetch;
    if (typeof fetchImpl !== 'function') {
      throw new Error('global fetch is not available. Use Node.js 20+ or pass options.fetch');
    }
    this.token = botToken;
    this.baseUrl = (options.baseUrl ?? DEFAULT_API_BASE_URL).replace(/\/+$/, '');
    this.fetchImpl = fetchImpl;
    this.timeoutMs = options.timeoutMs ?? 10_000;
    this.extraHeaders = { ...options.headers };
  }

  // ---------------------------------------------------------------------
  // Messages
  // ---------------------------------------------------------------------

  /**
   * POST /v1/bot/callback — reply to an event using its callback token.
   * The callback token expires 5 minutes after it was issued and is single-use.
   */
  sendCallback(callbackToken: string, skillResponse: SkillResponse): Promise<SendResult> {
    if (!callbackToken) throw new TypeError('callbackToken is required');
    return this.request<SendResult>('POST', '/v1/bot/callback', {
      body: { skillResponse },
      headers: { 'X-Bot-Callback-Token': callbackToken },
    });
  }

  /**
   * POST /v1/bot/send_message — proactively send a message to a room
   * (`botGroupKey`) or to a channel-chat user (`botUserKey`).
   * Quota: 200/sec, 1,000/day.
   */
  sendMessage(target: SendMessageTarget, skillResponse: SkillResponse): Promise<SendResult> {
    const hasUser = typeof target.botUserKey === 'string' && target.botUserKey.length > 0;
    const hasGroup = typeof target.botGroupKey === 'string' && target.botGroupKey.length > 0;
    if (hasUser === hasGroup) {
      throw new TypeError('Exactly one of botUserKey or botGroupKey must be provided');
    }
    const body = hasUser
      ? { botUserKey: target.botUserKey, skillResponse }
      : { botGroupKey: target.botGroupKey, skillResponse };
    return this.request<SendResult>('POST', '/v1/bot/send_message', { body });
  }

  /** Shortcut for sendMessage({ botGroupKey }, skillResponse). */
  sendToGroup(botGroupKey: string, skillResponse: SkillResponse): Promise<SendResult> {
    return this.sendMessage({ botGroupKey }, skillResponse);
  }

  /** Shortcut for sendMessage({ botUserKey }, skillResponse). Channel chat only. */
  sendToUser(botUserKey: string, skillResponse: SkillResponse): Promise<SendResult> {
    return this.sendMessage({ botUserKey }, skillResponse);
  }

  // ---------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------

  /** GET /v1/bot/commands */
  getCommands(): Promise<CommandsResponse> {
    return this.request<CommandsResponse>('GET', '/v1/bot/commands');
  }

  /** POST /v1/bot/commands/update — replaces the whole command list (max 30). */
  updateCommands(utterances: UtteranceRequest[]): Promise<CommandsResponse> {
    if (!Array.isArray(utterances)) throw new TypeError('utterances must be an array');
    if (utterances.length > MAX_COMMANDS) {
      throw new RangeError(`At most ${MAX_COMMANDS} commands can be registered`);
    }
    return this.request<CommandsResponse>('POST', '/v1/bot/commands/update', {
      body: { utterances },
    });
  }

  // ---------------------------------------------------------------------
  // Guide (help)
  // ---------------------------------------------------------------------

  /** GET /v1/bot/guide */
  getGuide(): Promise<Guide> {
    return this.request<Guide>('GET', '/v1/bot/guide');
  }

  /** POST /v1/bot/guide/update */
  updateGuide(contents: string): Promise<Guide> {
    if (typeof contents !== 'string') throw new TypeError('contents must be a string');
    return this.request<Guide>('POST', '/v1/bot/guide/update', { body: { contents } });
  }

  // ---------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------

  /** Low-level request helper. Exposed for endpoints not yet wrapped. */
  async request<T>(
    method: 'GET' | 'POST',
    path: string,
    init: { body?: unknown; headers?: Record<string, string> } = {},
  ): Promise<T> {
    const headers: Record<string, string> = {
      ...this.extraHeaders,
      Authorization: `KakaoAK ${this.token}`,
      Accept: 'application/json',
      ...init.headers,
    };
    let body: string | undefined;
    if (init.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(init.body);
    }

    const res = await this.fetchImpl(this.baseUrl + path, {
      method,
      headers,
      body,
      signal: AbortSignal.timeout(this.timeoutMs),
    });

    const text = await res.text();
    let json: unknown;
    if (text.length > 0) {
      try {
        json = JSON.parse(text);
      } catch {
        json = undefined;
      }
    }

    if (!res.ok) {
      throw new KakaoBotApiError(res.status, json, text);
    }
    return json as T;
  }
}
