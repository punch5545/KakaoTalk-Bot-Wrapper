/**
 * Type definitions for the KakaoTalk Bot API.
 * Source: https://developers.kakao.com/docs/ko/bot/rest-api
 *         https://developers.kakao.com/docs/ko/bot/gateway
 */

// ---------------------------------------------------------------------------
// Common objects
// ---------------------------------------------------------------------------

export type ButtonAction =
  | 'message'
  | 'webLink'
  | 'phone'
  | 'share'
  | 'operator'
  | 'invite'
  | 'mention'
  | 'inviteMember'
  | 'settings'
  | 'guide';

export type ButtonLayout = 'vertical' | 'horizontal';

export interface Button {
  action: ButtonAction;
  /** Max 14 chars (8 chars when two buttons are laid out horizontally). */
  label: string;
  /** Required when action is `message`. */
  messageText?: string;
  /** Required when action is `webLink`. */
  webLinkUrl?: string;
  /** Required when action is `phone`. */
  phoneNumber?: string;
  extra?: Record<string, unknown>;
}

export interface Link {
  mobile?: string;
  pc?: string;
  web?: string;
}

export interface Thumbnail {
  imageUrl: string;
  link?: Link;
  /** true: keep 1:1 ratio, false (default): center-crop to 2:1. */
  fixedRatio?: boolean;
  /** Max 50 chars. */
  altText?: string;
}

export interface Profile {
  nickname: string;
  /** 180x180 px recommended. */
  imageUrl: string;
}

// ---------------------------------------------------------------------------
// Bubble components
// ---------------------------------------------------------------------------

export interface SimpleText {
  /** Max 1,000 chars. Use `{{#mentions.<userKey>}}` to mention a user. */
  text: string;
}

export interface SimpleImage {
  imageUrl: string;
  altText?: string;
}

export interface TextCard {
  title: string;
  description: string;
  buttons: Button[];
  buttonLayout?: ButtonLayout;
}

export interface BasicCard {
  title: string;
  description: string;
  thumbnail: Thumbnail;
  buttons: Button[];
  buttonLayout?: ButtonLayout;
}

export interface ListItem {
  title: string;
  imageUrl?: string;
  link?: Link;
  description?: string;
  messageText?: string;
  extra?: Record<string, unknown>;
  action?: 'message';
}

export interface ListCard {
  header: ListItem;
  /** Max 5 (single) / 4 (carousel). */
  items: ListItem[];
  buttons: Button[];
  buttonLayout?: ButtonLayout;
}

export interface ItemCardItem {
  /** Max 6 chars. */
  title: string;
  /** Max 2 lines. */
  description: string;
}

export interface ItemCardHead {
  title: string;
}

export interface ImageTitle {
  title: string;
  description?: string;
  imageUrl?: string;
}

export interface ItemListSummary {
  /** Max 6 chars. */
  title: string;
  /** Max 14 chars; currency symbol, digits, comma, dot and spaces only. */
  description: string;
}

export interface ItemCard {
  /** Max 10 (single) / 5 (carousel). */
  itemList: ItemCardItem[];
  title: string;
  description: string;
  /** Cannot be used together with `profile`. */
  head?: ItemCardHead;
  imageTitle?: ImageTitle;
  itemListAlignment?: 'left' | 'right';
  itemListSummary?: ItemListSummary;
  thumbnail?: Thumbnail;
  /** Cannot be used together with `head`. */
  profile?: Profile;
  buttons: Button[];
  buttonLayout?: ButtonLayout;
}

export interface CommerceCard {
  price: number;
  /** Fixed to `won`. */
  currency: 'won';
  /** Currently exactly one thumbnail. */
  thumbnails: Thumbnail[];
  buttons: Button[];
  /** Max 30 chars. */
  title?: string;
  /** Max 40 chars. */
  description?: string;
  discount?: number;
  discountRate?: number;
  discountedPrice?: number;
  profile?: Profile;
  buttonLayout?: ButtonLayout;
}

export type CarouselType = 'basicCard' | 'commerceCard' | 'listCard' | 'itemCard' | 'textCard';

export interface CarouselHeader {
  title: string;
  description: string;
  thumbnail: Thumbnail;
}

export interface Carousel {
  type: CarouselType;
  /** Max 10 (listCard: 5). */
  items: Array<BasicCard | CommerceCard | ListCard | ItemCard | TextCard>;
  /** Not supported for textCard / listCard carousels. */
  header?: CarouselHeader;
}

export type Component =
  | { simpleText: SimpleText }
  | { simpleImage: SimpleImage }
  | { textCard: TextCard }
  | { basicCard: BasicCard }
  | { commerceCard: CommerceCard }
  | { listCard: ListCard }
  | { itemCard: ItemCard }
  | { carousel: Carousel };

export interface QuickReply {
  label: string;
  action: 'message';
  messageText?: string;
  extra?: Record<string, unknown>;
}

export interface SkillTemplate {
  /** 1 to 3 components. */
  outputs: Component[];
  /** Max 10. */
  quickReplies?: QuickReply[];
}

export interface Mention {
  type: 'botUserKey';
  id: string;
}

export interface SkillResponseExtra {
  /** Max 15 mentions per response. SimpleText only. */
  mentions?: Record<string, Mention>;
  [key: string]: unknown;
}

export interface SkillResponse {
  version: '2.0';
  template: SkillTemplate;
  extra?: SkillResponseExtra;
}

// ---------------------------------------------------------------------------
// REST API request / response
// ---------------------------------------------------------------------------

export interface SendResult {
  totalCount: number;
  sendCount: number;
  errorCount: number;
}

/** Exactly one of botUserKey (channel chat) / botGroupKey (group, open, team chat). */
export type SendMessageTarget =
  | { botUserKey: string; botGroupKey?: undefined }
  | { botGroupKey: string; botUserKey?: undefined };

export interface Utterance {
  id: string;
  type: 'TEXT';
  value: string;
  buttonName: string;
  description: string | null;
}

export interface UtteranceRequest {
  buttonName: string;
  description?: string | null;
}

export interface CommandsResponse {
  utterances: Utterance[];
}

export interface Guide {
  contents: string;
  /** ISO-8601 */
  createdAt: string;
  /** ISO-8601, null when never updated */
  updatedAt: string | null;
}

export interface KakaoApiErrorBody {
  code?: number;
  msg?: string;
  extras?: {
    code?: string | number;
    message?: string;
  };
}

// ---------------------------------------------------------------------------
// Gateway protocol
// ---------------------------------------------------------------------------

export enum Opcode {
  DISPATCH = 0,
  HEARTBEAT = 1,
  IDENTIFY = 2,
  READY = 3,
  RESUME = 6,
  HELLO = 10,
  HEARTBEAT_ACK = 11,
}

export enum CloseCode {
  /** Graceful server restart. RESUME immediately. */
  SERVER_RESTART = 1001,
  /** Token missing or invalid. Fatal. */
  AUTH_FAILED = 4001,
  /** Invalid payload. Fatal. */
  INVALID_PAYLOAD = 4003,
  /** Replaced by another connection (duplicate IDENTIFY). Fatal. */
  REPLACED = 4004,
  /** Session expired. Drop the session and IDENTIFY again. */
  SESSION_TIMEOUT = 4009,
}

export type EventType = 'MESSAGE_CREATE' | 'ENTRANCE' | 'CHAT_JOIN' | 'LEAVE' | 'AD_REWARD';

export const EVENT_TYPES: readonly EventType[] = [
  'MESSAGE_CREATE',
  'ENTRANCE',
  'CHAT_JOIN',
  'LEAVE',
  'AD_REWARD',
];

export interface MessageCreateData {
  /**
   * Message id. The server sends a 64-bit integer which exceeds
   * Number.MAX_SAFE_INTEGER, so this library always exposes it as a string.
   */
  id: string;
  content: string;
  /** ISO-8601 */
  timestamp: string;
  isChannelChatroom: boolean;
  botGroupKey: string;
  botUserKey: string;
  callbackToken?: string;
}

export interface EntranceData {
  botGroupKey: string;
  inviter: { botUserKey: string };
  params: Record<string, unknown>;
  timestamp: string;
  callbackToken?: string;
}

export interface ChatJoinData {
  botGroupKey: string;
  user: { botUserKey: string };
  timestamp: string;
  callbackToken?: string;
}

export interface LeaveData {
  botGroupKey: string;
  timestamp: string;
}

export interface AdRewardData {
  botGroupKey: string;
  viewer: { botUserKey: string };
  params: Record<string, unknown>;
  timestamp: string;
  callbackToken?: string;
}

export interface EventDataMap {
  MESSAGE_CREATE: MessageCreateData;
  ENTRANCE: EntranceData;
  CHAT_JOIN: ChatJoinData;
  LEAVE: LeaveData;
  AD_REWARD: AdRewardData;
}

export type DispatchData = EventDataMap[EventType];

export type DispatchPayload<T extends EventType = EventType> = {
  [K in T]: {
    op: Opcode.DISPATCH;
    /** Sequence number. Present on gateway frames, absent on webhook requests. */
    s?: number;
    t: K;
    d: EventDataMap[K];
  };
}[T];

export interface HelloFrame {
  op: Opcode.HELLO;
  d: { heartbeat_interval: number };
}

export interface ReadyData {
  session_id: string;
  bot: { id: string };
}

export interface ReadyFrame {
  op: Opcode.READY;
  d: ReadyData;
}

export interface HeartbeatAckFrame {
  op: Opcode.HEARTBEAT_ACK;
}

export type GatewayFrame = DispatchPayload | HelloFrame | ReadyFrame | HeartbeatAckFrame;

export interface IdentifyFrame {
  op: Opcode.IDENTIFY;
  d: { token: string };
}

export interface ResumeFrame {
  op: Opcode.RESUME;
  d: { token: string; session_id: string; seq: number };
}

export interface HeartbeatFrame {
  op: Opcode.HEARTBEAT;
}
