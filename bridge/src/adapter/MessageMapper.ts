import { getContentType, type WAMessage, type proto } from 'baileys';
import type { LidDirectory } from './LidDirectory.js';

/**
 * Abstract message shape shared with the Java `Message` model. Anything
 * Baileys-specific that has no clean abstract equivalent is left out of
 * this type and only available through `rawData` on the Java side.
 */
export interface AbstractMessage {
    messageId: string;
    chatId: string;
    senderId: string;
    timestamp: number;
    text: string | null;
    type: string;
    isGroup: boolean;
    isFromMe: boolean;
    pushName: string | null;
    quotedMessage: AbstractQuotedMessage | null;
    /**
     * LID original da conversa, quando `chatId` foi traduzido para o numero
     * de telefone. Permite ao consumidor juntar uma conversa que ele ja tenha
     * gravado sob o endereco anonimo.
     */
    chatIdLid: string | null;
    rawData: unknown;
}

export interface AbstractQuotedMessage {
    messageId: string;
    senderId: string;
    text: string | null;
}

const TYPE_MAP: Record<string, string> = {
    conversation: 'TEXT',
    extendedTextMessage: 'TEXT',
    imageMessage: 'IMAGE',
    videoMessage: 'VIDEO',
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
    protocolMessage: 'PROTOCOL'
};

export function mapMessageType(contentType: string | undefined): string {
    if (!contentType) return 'UNKNOWN';
    return TYPE_MAP[contentType] ?? 'UNKNOWN';
}

/** Best-effort human readable body: caption for media, text for text messages. */
function extractText(message: proto.IMessage | null | undefined, contentType: string | undefined): string | null {
    if (!message || !contentType) return null;
    switch (contentType) {
        case 'conversation':
            return message.conversation ?? null;
        case 'extendedTextMessage':
            return message.extendedTextMessage?.text ?? null;
        case 'imageMessage':
            return message.imageMessage?.caption ?? null;
        case 'videoMessage':
            return message.videoMessage?.caption ?? null;
        case 'documentMessage':
            return message.documentMessage?.caption ?? message.documentMessage?.fileName ?? null;
        case 'documentWithCaptionMessage':
            return (
                message.documentWithCaptionMessage?.message?.documentMessage?.caption ??
                message.documentWithCaptionMessage?.message?.documentMessage?.fileName ??
                null
            );
        case 'buttonsResponseMessage':
            return message.buttonsResponseMessage?.selectedDisplayText ?? null;
        case 'listResponseMessage':
            return message.listResponseMessage?.title ?? null;
        case 'templateButtonReplyMessage':
            return message.templateButtonReplyMessage?.selectedDisplayText ?? null;
        default:
            return null;
    }
}

/** contextInfo lives at a different nesting depth depending on the content type. */
function extractContextInfo(message: proto.IMessage | null | undefined): proto.IContextInfo | undefined {
    if (!message) return undefined;
    return (
        message.extendedTextMessage?.contextInfo ??
        message.imageMessage?.contextInfo ??
        message.videoMessage?.contextInfo ??
        message.audioMessage?.contextInfo ??
        message.documentMessage?.contextInfo ??
        message.stickerMessage?.contextInfo ??
        message.buttonsResponseMessage?.contextInfo ??
        message.listResponseMessage?.contextInfo ??
        undefined
    );
}

function extractQuoted(message: proto.IMessage | null | undefined): AbstractQuotedMessage | null {
    const ctx = extractContextInfo(message);
    if (!ctx?.quotedMessage || !ctx.stanzaId) return null;
    const quotedType = getContentType(ctx.quotedMessage);
    return {
        messageId: ctx.stanzaId,
        senderId: ctx.participant ?? '',
        text: extractText(ctx.quotedMessage, quotedType)
    };
}

export function toAbstractMessage(waMessage: WAMessage, lids?: LidDirectory): AbstractMessage {
    // O endereco que veio no stanza pode ser um LID anonimo; o resto do
    // sistema so sabe conversar em numero de telefone.
    const chatIdRaw = waMessage.key.remoteJid ?? '';
    const chatId = lids ? lids.resolve(chatIdRaw) : chatIdRaw;
    const chatIdLid = chatId !== chatIdRaw ? chatIdRaw : null;
    const isGroup = chatId.endsWith('@g.us');
    const isFromMe = waMessage.key.fromMe ?? false;
    const participanteRaw = (isGroup ? waMessage.key.participant : chatIdRaw) ?? chatIdRaw;
    const senderId = isFromMe
        ? 'me'
        : (lids ? lids.resolve(participanteRaw) : participanteRaw);
    const contentType = getContentType(waMessage.message ?? undefined);
    const timestampRaw = waMessage.messageTimestamp;
    const timestamp =
        typeof timestampRaw === 'number'
            ? timestampRaw
            : timestampRaw
              ? Number(timestampRaw.toString())
              : Math.floor(Date.now() / 1000);

    return {
        messageId: waMessage.key.id ?? '',
        chatId,
        senderId: senderId ?? '',
        timestamp,
        text: extractText(waMessage.message, contentType),
        type: mapMessageType(contentType),
        isGroup,
        isFromMe,
        pushName: waMessage.pushName ?? null,
        quotedMessage: extractQuoted(waMessage.message),
        chatIdLid,
        rawData: waMessage
    };
}
