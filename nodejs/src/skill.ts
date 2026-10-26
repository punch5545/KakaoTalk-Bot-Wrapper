import type {
  BasicCard,
  Button,
  Carousel,
  CommerceCard,
  Component,
  ItemCard,
  ListCard,
  Mention,
  QuickReply,
  SimpleImage,
  SkillResponse,
  SkillResponseExtra,
  TextCard,
} from './types.js';

export const MAX_OUTPUTS = 3;
export const MAX_QUICK_REPLIES = 10;
export const MAX_MENTIONS = 15;

/** Build a SkillResponse from one or more components. */
export function skillResponse(
  outputs: Component | Component[],
  options: { quickReplies?: QuickReply[]; extra?: SkillResponseExtra } = {},
): SkillResponse {
  const list = Array.isArray(outputs) ? outputs : [outputs];
  if (list.length < 1 || list.length > MAX_OUTPUTS) {
    throw new RangeError(`outputs must contain 1 to ${MAX_OUTPUTS} components`);
  }
  if (options.quickReplies && options.quickReplies.length > MAX_QUICK_REPLIES) {
    throw new RangeError(`quickReplies must contain at most ${MAX_QUICK_REPLIES} items`);
  }
  const response: SkillResponse = {
    version: '2.0',
    template: { outputs: list },
  };
  if (options.quickReplies && options.quickReplies.length > 0) {
    response.template.quickReplies = options.quickReplies;
  }
  if (options.extra) response.extra = options.extra;
  return response;
}

/** Shortcut: a single SimpleText bubble. */
export function textResponse(text: string, quickReplies?: QuickReply[]): SkillResponse {
  return skillResponse(Components.simpleText(text), { quickReplies });
}

/** Shortcut: a single SimpleImage bubble. */
export function imageResponse(imageUrl: string, altText?: string): SkillResponse {
  return skillResponse(Components.simpleImage(imageUrl, altText));
}

/** Component factories. */
export const Components = {
  simpleText(text: string): Component {
    return { simpleText: { text } };
  },
  simpleImage(imageUrl: string, altText?: string): Component {
    const simpleImage: SimpleImage = { imageUrl };
    if (altText !== undefined) simpleImage.altText = altText;
    return { simpleImage };
  },
  textCard(card: TextCard): Component {
    return { textCard: card };
  },
  basicCard(card: BasicCard): Component {
    return { basicCard: card };
  },
  listCard(card: ListCard): Component {
    return { listCard: card };
  },
  itemCard(card: ItemCard): Component {
    return { itemCard: card };
  },
  commerceCard(card: CommerceCard): Component {
    return { commerceCard: card };
  },
  carousel(carousel: Carousel): Component {
    return { carousel };
  },
} as const;

/** Button factories. */
export const Buttons = {
  message(label: string, messageText: string = label, extra?: Record<string, unknown>): Button {
    const b: Button = { action: 'message', label, messageText };
    if (extra) b.extra = extra;
    return b;
  },
  webLink(label: string, webLinkUrl: string): Button {
    return { action: 'webLink', label, webLinkUrl };
  },
  phone(label: string, phoneNumber: string): Button {
    return { action: 'phone', label, phoneNumber };
  },
  share(label: string): Button {
    return { action: 'share', label };
  },
  operator(label: string): Button {
    return { action: 'operator', label };
  },
  invite(label: string): Button {
    return { action: 'invite', label };
  },
  mention(label: string): Button {
    return { action: 'mention', label };
  },
  inviteMember(label: string): Button {
    return { action: 'inviteMember', label };
  },
  settings(label: string): Button {
    return { action: 'settings', label };
  },
  guide(label: string): Button {
    return { action: 'guide', label };
  },
} as const;

/** Quick reply factory (action is always `message`). */
export function quickReply(label: string, messageText: string = label, extra?: Record<string, unknown>): QuickReply {
  const q: QuickReply = { label, action: 'message', messageText };
  if (extra) q.extra = extra;
  return q;
}

/** Placeholder to put inside SimpleText.text for a mention: `{{#mentions.<userKey>}}`. */
export function mentionTag(userKey: string): string {
  return `{{#mentions.${userKey}}}`;
}

/** Build `extra.mentions` from a `{ userKey: botUserKey }` map. */
export function mentions(map: Record<string, string>): { mentions: Record<string, Mention> } {
  const entries = Object.entries(map);
  if (entries.length > MAX_MENTIONS) {
    throw new RangeError(`At most ${MAX_MENTIONS} mentions are allowed per response`);
  }
  const out: Record<string, Mention> = {};
  for (const [key, id] of entries) out[key] = { type: 'botUserKey', id };
  return { mentions: out };
}

/**
 * Fluent builder for SkillResponse.
 *
 * ```ts
 * const res = new SkillResponseBuilder()
 *   .text(`안녕하세요 ${mentionTag('u1')}!`)
 *   .mention('u1', event.data.botUserKey)
 *   .quickReply('도움말')
 *   .build();
 * ```
 */
export class SkillResponseBuilder {
  private readonly outputs: Component[] = [];
  private readonly quickReplies: QuickReply[] = [];
  private readonly mentionMap: Record<string, Mention> = {};
  private extraFields: Record<string, unknown> = {};

  add(component: Component): this {
    this.outputs.push(component);
    return this;
  }
  text(text: string): this {
    return this.add(Components.simpleText(text));
  }
  image(imageUrl: string, altText?: string): this {
    return this.add(Components.simpleImage(imageUrl, altText));
  }
  textCard(card: TextCard): this {
    return this.add(Components.textCard(card));
  }
  basicCard(card: BasicCard): this {
    return this.add(Components.basicCard(card));
  }
  listCard(card: ListCard): this {
    return this.add(Components.listCard(card));
  }
  itemCard(card: ItemCard): this {
    return this.add(Components.itemCard(card));
  }
  commerceCard(card: CommerceCard): this {
    return this.add(Components.commerceCard(card));
  }
  carousel(carousel: Carousel): this {
    return this.add(Components.carousel(carousel));
  }
  quickReply(label: string, messageText: string = label, extra?: Record<string, unknown>): this {
    this.quickReplies.push(quickReply(label, messageText, extra));
    return this;
  }
  /** Register a mention target; use `mentionTag(userKey)` inside the text. */
  mention(userKey: string, botUserKey: string): this {
    this.mentionMap[userKey] = { type: 'botUserKey', id: botUserKey };
    return this;
  }
  extra(fields: Record<string, unknown>): this {
    this.extraFields = { ...this.extraFields, ...fields };
    return this;
  }

  build(): SkillResponse {
    const mentionKeys = Object.keys(this.mentionMap);
    if (mentionKeys.length > MAX_MENTIONS) {
      throw new RangeError(`At most ${MAX_MENTIONS} mentions are allowed per response`);
    }
    let extra: SkillResponseExtra | undefined;
    if (mentionKeys.length > 0 || Object.keys(this.extraFields).length > 0) {
      extra = { ...this.extraFields };
      if (mentionKeys.length > 0) extra.mentions = { ...this.mentionMap };
    }
    return skillResponse(this.outputs, { quickReplies: this.quickReplies, extra });
  }
}
