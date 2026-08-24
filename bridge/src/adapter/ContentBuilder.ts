import type { AnyMessageContent, WAMessageKey } from 'baileys';
import { buildVCard } from '../util/vcard.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';

export interface SendTextPayload {
    text: string;
}

export interface SendImagePayload {
    filePath: string;
    caption?: string;
}

export interface SendVideoPayload {
    filePath: string;
    caption?: string;
    gifPlayback?: boolean;
}

export interface SendAudioPayload {
    filePath: string;
    ptt?: boolean;
}

export interface SendDocumentPayload {
    filePath: string;
    fileName: string;
    mimetype: string;
    caption?: string;
}

export interface SendStickerPayload {
    filePath: string;
}

export interface SendLocationPayload {
    latitude: number;
    longitude: number;
    name?: string;
    address?: string;
}

export interface SendContactPayload {
    displayName: string;
    phoneNumber: string;
    organization?: string;
}

export interface SendReactionPayload {
    chatId: string;
    messageId: string;
    emoji: string;
    fromMe: boolean;
    participant?: string;
}

/**
 * Identifies one already-sent message, for the operations that act on a
 * message instead of creating one (edit, revoke).
 *
 * `participant` only matters in groups and only when the target message is
 * not ours: it is how WhatsApp tells apart two members' messages inside the
 * same chat.
 */
export interface MessageTargetPayload {
    chatId: string;
    messageId: string;
    fromMe: boolean;
    participant?: string;
}

export interface EditMessagePayload extends MessageTargetPayload {
    text: string;
}

function requireNonEmpty(value: unknown, field: string): asserts value is string {
    if (typeof value !== 'string' || value.trim().length === 0) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `Campo obrigatorio ausente ou vazio: ${field}`);
    }
}

export function buildTextContent(payload: SendTextPayload): AnyMessageContent {
    requireNonEmpty(payload.text, 'text');
    return { text: payload.text };
}

export function buildImageContent(payload: SendImagePayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    return { image: { url: payload.filePath }, caption: payload.caption };
}

export function buildVideoContent(payload: SendVideoPayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    return {
        video: { url: payload.filePath },
        caption: payload.caption,
        gifPlayback: payload.gifPlayback ?? false
    };
}

export function buildAudioContent(payload: SendAudioPayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    return {
        audio: { url: payload.filePath },
        ptt: payload.ptt ?? false,
        mimetype: payload.ptt ? 'audio/ogg; codecs=opus' : 'audio/mpeg'
    };
}

export function buildDocumentContent(payload: SendDocumentPayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    requireNonEmpty(payload.mimetype, 'mimetype');
    return {
        document: { url: payload.filePath },
        mimetype: payload.mimetype,
        fileName: payload.fileName,
        caption: payload.caption
    };
}

export function buildStickerContent(payload: SendStickerPayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    return { sticker: { url: payload.filePath } };
}

export function buildLocationContent(payload: SendLocationPayload): AnyMessageContent {
    if (typeof payload.latitude !== 'number' || typeof payload.longitude !== 'number') {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'latitude/longitude devem ser numeros');
    }
    return {
        location: {
            degreesLatitude: payload.latitude,
            degreesLongitude: payload.longitude,
            name: payload.name,
            address: payload.address
        }
    };
}

export function buildContactContent(payload: SendContactPayload): AnyMessageContent {
    requireNonEmpty(payload.displayName, 'displayName');
    requireNonEmpty(payload.phoneNumber, 'phoneNumber');
    const vcard = buildVCard(payload);
    return {
        contacts: {
            displayName: payload.displayName,
            contacts: [{ displayName: payload.displayName, vcard }]
        }
    };
}

/**
 * Key of an already-sent message, as WhatsApp addresses it.
 *
 * Shared by edit and revoke so the two can never disagree about how a
 * message is identified.
 */
export function buildMessageKey(payload: MessageTargetPayload): WAMessageKey {
    requireNonEmpty(payload.chatId, 'chatId');
    requireNonEmpty(payload.messageId, 'messageId');
    return {
        remoteJid: payload.chatId,
        fromMe: payload.fromMe,
        id: payload.messageId,
        participant: payload.participant
    };
}

/**
 * Replaces the text of a message already delivered.
 *
 * WhatsApp only accepts this for messages WE sent and only inside its own
 * edit window (roughly 15 minutes, decided server-side and not published
 * anywhere): past it, the request is rejected. The caller has to be ready for
 * that answer - there is no way to ask beforehand.
 */
export function buildEditContent(payload: EditMessagePayload): AnyMessageContent {
    requireNonEmpty(payload.text, 'text');
    return { text: payload.text, edit: buildMessageKey(payload) };
}

/**
 * Revokes ("delete for everyone") a message already delivered.
 *
 * Same window caveat as the edit above, and the same rule about it having to
 * be our own message in a one-to-one chat. In a group, an admin may also
 * revoke someone else's - which is why `participant` exists.
 */
export function buildDeleteContent(payload: MessageTargetPayload): AnyMessageContent {
    return { delete: buildMessageKey(payload) };
}

export function buildReactionContent(payload: SendReactionPayload): AnyMessageContent {
    requireNonEmpty(payload.chatId, 'chatId');
    requireNonEmpty(payload.messageId, 'messageId');
    const key: WAMessageKey = {
        remoteJid: payload.chatId,
        fromMe: payload.fromMe,
        id: payload.messageId,
        participant: payload.participant
    };
    return { react: { text: payload.emoji ?? '', key } };
}
