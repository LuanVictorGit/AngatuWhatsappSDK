import type { AnyMessageContent, BinaryNode, WAMessageKey, proto } from 'baileys';
import { buildVCard } from '../util/vcard.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';

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

export interface ButtonSpec {
    id: string;
    text: string;
}

export interface SendButtonsPayload {
    text: string;
    title?: string;
    footer?: string;
    buttons: ButtonSpec[];
}

/** Identifies a message already sent, for edit, revoke and reactions. */
export interface MessageTargetPayload {
    chatId: string;
    messageId: string;
    fromMe: boolean;
    participant?: string;
}

/** WhatsApp renders at most three quick-reply buttons in one message. */
export const MAX_BUTTONS = 3;

/**
 * The `biz` node WhatsApp expects next to a native-flow interactive message;
 * without it recipients' clients drop the buttons.
 */
export const BUTTONS_NODE: BinaryNode = {
    tag: 'biz',
    attrs: {},
    content: [
        {
            tag: 'interactive',
            attrs: { type: 'native_flow', v: '1' },
            content: [{ tag: 'native_flow', attrs: { v: '9', name: 'mixed' } }]
        }
    ]
};

function requireNonEmpty(value: unknown, field: string): asserts value is string {
    if (typeof value !== 'string' || value.trim().length === 0) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `missing or empty field: ${field}`);
    }
}

export function textContent(text: unknown): AnyMessageContent {
    requireNonEmpty(text, 'text');
    return { text };
}

export function imageContent(payload: SendImagePayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    return { image: { url: payload.filePath }, caption: payload.caption };
}

export function videoContent(payload: SendVideoPayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    return { video: { url: payload.filePath }, caption: payload.caption, gifPlayback: payload.gifPlayback ?? false };
}

export function audioContent(payload: SendAudioPayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    return {
        audio: { url: payload.filePath },
        ptt: payload.ptt ?? false,
        mimetype: payload.ptt ? 'audio/ogg; codecs=opus' : 'audio/mpeg'
    };
}

export function documentContent(payload: SendDocumentPayload): AnyMessageContent {
    requireNonEmpty(payload.filePath, 'filePath');
    requireNonEmpty(payload.mimetype, 'mimetype');
    return {
        document: { url: payload.filePath },
        mimetype: payload.mimetype,
        fileName: payload.fileName,
        caption: payload.caption
    };
}

export function stickerContent(filePath: unknown): AnyMessageContent {
    requireNonEmpty(filePath, 'filePath');
    return { sticker: { url: filePath } };
}

export function locationContent(payload: SendLocationPayload): AnyMessageContent {
    if (typeof payload.latitude !== 'number' || typeof payload.longitude !== 'number') {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'latitude and longitude must be numbers');
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

export function contactContent(payload: SendContactPayload): AnyMessageContent {
    requireNonEmpty(payload.displayName, 'displayName');
    requireNonEmpty(payload.phoneNumber, 'phoneNumber');
    return {
        contacts: {
            displayName: payload.displayName,
            contacts: [{ displayName: payload.displayName, vcard: buildVCard(payload) }]
        }
    };
}

/**
 * Quick-reply buttons as a native-flow interactive message. A press comes back
 * as an `interactiveResponseMessage` whose params carry the button id, which
 * MessageMapper turns into a ButtonReply.
 */
export function buttonsContent(payload: SendButtonsPayload): proto.IMessage {
    requireNonEmpty(payload.text, 'text');
    if (!Array.isArray(payload.buttons) || payload.buttons.length === 0 || payload.buttons.length > MAX_BUTTONS) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `between 1 and ${MAX_BUTTONS} buttons are required`);
    }
    const ids = new Set<string>();
    for (const button of payload.buttons) {
        requireNonEmpty(button?.id, 'buttons[].id');
        requireNonEmpty(button?.text, 'buttons[].text');
        if (ids.has(button.id)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `duplicate button id: ${button.id}`);
        }
        ids.add(button.id);
    }
    return {
        viewOnceMessage: {
            message: {
                messageContextInfo: { deviceListMetadata: {}, deviceListMetadataVersion: 2 },
                interactiveMessage: {
                    header: payload.title ? { title: payload.title, hasMediaAttachment: false } : undefined,
                    body: { text: payload.text },
                    footer: payload.footer ? { text: payload.footer } : undefined,
                    nativeFlowMessage: {
                        buttons: payload.buttons.map((button) => ({
                            name: 'quick_reply',
                            buttonParamsJson: JSON.stringify({ display_text: button.text, id: button.id })
                        })),
                        messageParamsJson: ''
                    }
                }
            }
        }
    };
}

export function messageKey(payload: MessageTargetPayload): WAMessageKey {
    requireNonEmpty(payload.chatId, 'chatId');
    requireNonEmpty(payload.messageId, 'messageId');
    return { remoteJid: payload.chatId, fromMe: payload.fromMe, id: payload.messageId, participant: payload.participant };
}

/** WhatsApp accepts an edit only for our own message and only inside its (unpublished) edit window. */
export function editContent(payload: MessageTargetPayload, text: unknown): AnyMessageContent {
    requireNonEmpty(text, 'text');
    return { text, edit: messageKey(payload) };
}

export function deleteContent(payload: MessageTargetPayload): AnyMessageContent {
    return { delete: messageKey(payload) };
}

export function reactionContent(payload: MessageTargetPayload, emoji: string): AnyMessageContent {
    return { react: { text: emoji, key: messageKey(payload) } };
}
