import { getContentType, normalizeMessageContent, type WAMessage, type proto } from 'baileys';
import type { LidDirectory } from './LidDirectory.js';

/** The message shape shared with the Java `Message` record. */
export interface AbstractMessage {
    messageId: string;
    chatId: string;
    chatIdLid: string | null;
    senderId: string;
    participant: string | null;
    timestamp: number;
    text: string | null;
    type: string;
    isGroup: boolean;
    isFromMe: boolean;
    pushName: string | null;
    quotedMessage: AbstractQuotedMessage | null;
    buttonReply: ButtonReply | null;
    hasMedia: boolean;
    mimetype: string | null;
    rawJson: string | null;
}

export interface AbstractQuotedMessage {
    messageId: string;
    senderId: string;
    text: string | null;
}

/** Which button of which message a contact pressed. */
export interface ButtonReply {
    buttonId: string;
    text: string | null;
    sourceMessageId: string | null;
}

const TYPE_MAP: Record<string, string> = {
    conversation: 'TEXT',
    extendedTextMessage: 'TEXT',
    imageMessage: 'IMAGE',
    videoMessage: 'VIDEO',
    ptvMessage: 'VIDEO',
    audioMessage: 'AUDIO',
    documentMessage: 'DOCUMENT',
    documentWithCaptionMessage: 'DOCUMENT',
    stickerMessage: 'STICKER',
    locationMessage: 'LOCATION',
    liveLocationMessage: 'LOCATION',
    contactMessage: 'CONTACT',
    contactsArrayMessage: 'CONTACT',
    reactionMessage: 'REACTION',
    pollCreationMessage: 'POLL',
    pollCreationMessageV2: 'POLL',
    pollCreationMessageV3: 'POLL',
    buttonsResponseMessage: 'INTERACTIVE_REPLY',
    listResponseMessage: 'INTERACTIVE_REPLY',
    templateButtonReplyMessage: 'INTERACTIVE_REPLY',
    interactiveResponseMessage: 'INTERACTIVE_REPLY'
};

/** Content types that are protocol plumbing, never something a person wrote. */
export const IGNORED_CONTENT = new Set([
    'protocolMessage',
    'senderKeyDistributionMessage',
    'messageContextInfo',
    'pollUpdateMessage',
    'keepInChatMessage',
    'encReactionMessage',
    'peerDataOperationRequestResponseMessage'
]);

const MEDIA_TYPES = new Set(['imageMessage', 'videoMessage', 'ptvMessage', 'audioMessage', 'documentMessage', 'stickerMessage']);

export function contentTypeOf(message: WAMessage): string | undefined {
    const content = normalizeMessageContent(message.message);
    return content ? getContentType(content) : undefined;
}

function extractText(content: proto.IMessage | null | undefined, type: string | undefined): string | null {
    if (!content || !type) return null;
    switch (type) {
        case 'conversation':
            return content.conversation ?? null;
        case 'extendedTextMessage':
            return content.extendedTextMessage?.text ?? null;
        case 'imageMessage':
            return content.imageMessage?.caption ?? null;
        case 'videoMessage':
            return content.videoMessage?.caption ?? null;
        case 'documentMessage':
            return content.documentMessage?.caption ?? content.documentMessage?.fileName ?? null;
        case 'buttonsResponseMessage':
            return content.buttonsResponseMessage?.selectedDisplayText ?? null;
        case 'listResponseMessage':
            return content.listResponseMessage?.title ?? null;
        case 'templateButtonReplyMessage':
            return content.templateButtonReplyMessage?.selectedDisplayText ?? null;
        case 'interactiveResponseMessage':
            return content.interactiveResponseMessage?.body?.text ?? null;
        default:
            return null;
    }
}

function contextInfoOf(content: proto.IMessage | null | undefined, type: string | undefined): proto.IContextInfo | undefined {
    if (!content || !type) return undefined;
    const inner = (content as Record<string, unknown>)[type] as { contextInfo?: proto.IContextInfo } | undefined;
    return inner && typeof inner === 'object' ? inner.contextInfo ?? undefined : undefined;
}

function extractQuoted(context: proto.IContextInfo | undefined): AbstractQuotedMessage | null {
    if (!context?.quotedMessage || !context.stanzaId) return null;
    const quoted = normalizeMessageContent(context.quotedMessage);
    return {
        messageId: context.stanzaId,
        senderId: context.participant ?? '',
        text: extractText(quoted, quoted ? getContentType(quoted) : undefined)
    };
}

function extractButtonReply(content: proto.IMessage, type: string, context: proto.IContextInfo | undefined): ButtonReply | null {
    const source = context?.stanzaId ?? null;
    switch (type) {
        case 'buttonsResponseMessage': {
            const reply = content.buttonsResponseMessage;
            return reply?.selectedButtonId
                ? { buttonId: reply.selectedButtonId, text: reply.selectedDisplayText ?? null, sourceMessageId: source }
                : null;
        }
        case 'templateButtonReplyMessage': {
            const reply = content.templateButtonReplyMessage;
            return reply?.selectedId
                ? { buttonId: reply.selectedId, text: reply.selectedDisplayText ?? null, sourceMessageId: source }
                : null;
        }
        case 'listResponseMessage': {
            const reply = content.listResponseMessage;
            const id = reply?.singleSelectReply?.selectedRowId;
            return id ? { buttonId: id, text: reply?.title ?? null, sourceMessageId: source } : null;
        }
        case 'interactiveResponseMessage': {
            const reply = content.interactiveResponseMessage;
            const params = reply?.nativeFlowResponseMessage?.paramsJson;
            if (!params) return null;
            try {
                const parsed = JSON.parse(params) as { id?: unknown };
                return typeof parsed.id === 'string'
                    ? { buttonId: parsed.id, text: reply?.body?.text ?? null, sourceMessageId: source }
                    : null;
            } catch {
                return null;
            }
        }
        default:
            return null;
    }
}

function mimetypeOf(content: proto.IMessage, type: string): string | null {
    const inner = (content as Record<string, unknown>)[type] as { mimetype?: string | null } | undefined;
    return inner?.mimetype ?? null;
}

export function toAbstractMessage(message: WAMessage, lids: LidDirectory, includeRaw: boolean): AbstractMessage {
    const rawChatId = message.key.remoteJid ?? '';
    const chatId = lids.resolve(rawChatId);
    const isGroup = chatId.endsWith('@g.us');
    const isFromMe = message.key.fromMe ?? false;
    const rawParticipant = isGroup ? message.key.participant ?? null : null;
    const content = normalizeMessageContent(message.message);
    const type = content ? getContentType(content) : undefined;
    const context = contextInfoOf(content, type);
    const timestampRaw = message.messageTimestamp;
    const timestamp =
        typeof timestampRaw === 'number'
            ? timestampRaw
            : timestampRaw
              ? Number(timestampRaw.toString())
              : Math.floor(Date.now() / 1000);

    return {
        messageId: message.key.id ?? '',
        chatId,
        chatIdLid: chatId !== rawChatId ? rawChatId : null,
        senderId: isFromMe ? 'me' : lids.resolve(rawParticipant ?? rawChatId),
        participant: rawParticipant,
        timestamp,
        text: extractText(content, type),
        type: type ? TYPE_MAP[type] ?? 'UNKNOWN' : 'UNKNOWN',
        isGroup,
        isFromMe,
        pushName: message.pushName ?? null,
        quotedMessage: extractQuoted(context),
        buttonReply: content && type ? extractButtonReply(content, type, context) : null,
        hasMedia: type ? MEDIA_TYPES.has(type) : false,
        mimetype: content && type && MEDIA_TYPES.has(type) ? mimetypeOf(content, type) : null,
        rawJson: includeRaw ? JSON.stringify(message) : null
    };
}
