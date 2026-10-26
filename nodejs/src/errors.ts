import type { KakaoApiErrorBody } from './types.js';

/** Human-readable descriptions of bot API error codes (from the official error code page). */
export const BOT_ERROR_DESCRIPTIONS: Readonly<Record<number, string>> = {
  [-1]: '봇 메타 서버 내부 오류. 잠시 후 재시도하세요.',
  [-2]: '도움말/명령어 API 요청 본문이 형식에 맞지 않습니다.',
  [-870]: '메시지 API 관련 에러. extras의 상세 코드를 확인하세요.',
  [-871]: '도움말 API 관련 에러. extras의 상세 코드를 확인하세요.',
  [-872]: '명령어 API 관련 에러. extras의 상세 코드를 확인하세요.',
  [-873]: '유효하지 않은 봇입니다. 봇 생성 및 앱 연동 상태를 확인하세요.',
  [-874]: '명령어 최대 등록 개수(30개)를 초과했습니다.',
  [-875]: '중복된 명령어가 있습니다.',
  [-876]: '유효하지 않은 키입니다. botUserKey 또는 botGroupKey 중 하나만 입력해야 합니다.',
  [-877]: '채널 채팅에서 채널 친구가 아닌 사용자에게는 응답할 수 없습니다.',
  [-878]: '말풍선이 유효하지 않습니다. 컴포넌트 형식을 확인하세요.',
  [-879]: '메시지 전송 수신을 [Off]로 설정한 채팅방입니다.',
  [-880]: '봇이 초대되지 않은 채팅방입니다.',
  [-881]: '만료되었거나 이미 사용된 콜백 토큰입니다.',
};

/** Error thrown when the REST API responds with a non-2xx status. */
export class KakaoBotApiError extends Error {
  override readonly name = 'KakaoBotApiError';
  readonly status: number;
  /** Kakao error code, e.g. -881. Undefined when the body was not a Kakao error body. */
  readonly code: number | undefined;
  /** Kakao error message (`msg`). */
  readonly msg: string | undefined;
  readonly extras: KakaoApiErrorBody['extras'];
  /** Parsed response body (or raw text when not JSON). */
  readonly body: unknown;

  constructor(status: number, body: unknown, rawText?: string) {
    const parsed = isErrorBody(body) ? body : undefined;
    const code = parsed?.code;
    const msg = parsed?.msg;
    const hint = code !== undefined ? BOT_ERROR_DESCRIPTIONS[code] : undefined;
    const parts = [`Kakao Bot API request failed with HTTP ${status}`];
    if (code !== undefined) parts.push(`code=${code}`);
    if (msg) parts.push(`msg=${msg}`);
    if (parsed?.extras?.code !== undefined) parts.push(`extras.code=${parsed.extras.code}`);
    if (parsed?.extras?.message) parts.push(`extras.message=${parsed.extras.message}`);
    if (hint) parts.push(`(${hint})`);
    super(parts.join(' '));
    this.status = status;
    this.code = code;
    this.msg = msg;
    this.extras = parsed?.extras;
    this.body = body ?? rawText;
  }

  /** -881: expired or already-used callback token. */
  get isCallbackTokenError(): boolean {
    return this.code === -881;
  }

  /** -880: the bot is not in the chat room. */
  get isBotNotInvited(): boolean {
    return this.code === -880;
  }

  /** Human-readable hint for the error code, when known. */
  get description(): string | undefined {
    return this.code !== undefined ? BOT_ERROR_DESCRIPTIONS[this.code] : undefined;
  }
}

/** Error raised by the gateway client (protocol errors, fatal close codes, reply without token, ...). */
export class KakaoBotGatewayError extends Error {
  override readonly name = 'KakaoBotGatewayError';
  /** WebSocket close code when the error originates from a close frame. */
  readonly closeCode: number | undefined;
  /** True when the gateway will not reconnect (e.g. 4001 auth failure). */
  readonly fatal: boolean;

  constructor(message: string, options: { closeCode?: number; fatal?: boolean; cause?: unknown } = {}) {
    super(message, options.cause !== undefined ? { cause: options.cause } : undefined);
    this.closeCode = options.closeCode;
    this.fatal = options.fatal ?? false;
  }
}

function isErrorBody(body: unknown): body is KakaoApiErrorBody {
  return typeof body === 'object' && body !== null && ('code' in body || 'msg' in body);
}
