import { BaileysAdapter, type AdapterOptions } from '../adapter/BaileysAdapter.js';
import type { EventSink } from './EventSink.js';
import type { Logger } from '../util/logger.js';

/**
 * Binds one BaileysAdapter to the event transport. Kept deliberately thin -
 * all WhatsApp/Baileys behaviour lives in BaileysAdapter, all business
 * rules (conflict prevention, lookups) live in InstanceManager.
 */
export class WhatsAppInstance {
    readonly adapter: BaileysAdapter;
    readonly instanceId: string;
    readonly sessionDirectory: string;
    readonly createdAt: number;

    constructor(options: AdapterOptions, logger: Logger, eventSink: EventSink) {
        this.instanceId = options.instanceId;
        this.sessionDirectory = options.sessionDirectory;
        this.createdAt = Date.now();

        this.adapter = new BaileysAdapter(options, logger, {
            onConnectionStateChanged: (state, cause, detail) =>
                eventSink.emit(this.instanceId, 'connection_state_changed', { state, cause, detail }),
            onQr: (qr) => eventSink.emit(this.instanceId, 'qr_code', { qr }),
            onPairingCode: (code) => eventSink.emit(this.instanceId, 'pairing_code', { code }),
            onMessage: (message) => eventSink.emit(this.instanceId, 'message_received', message),
            onMessageUpdated: (chatId, messageId, updates) =>
                eventSink.emit(this.instanceId, 'message_updated', { chatId, messageId, updates }),
            onMessageDeleted: (chatId, messageId) =>
                eventSink.emit(this.instanceId, 'message_deleted', { chatId, messageId }),
            onReaction: (chatId, messageId, senderId, emoji) =>
                eventSink.emit(this.instanceId, 'reaction_received', {
                    chatId,
                    messageId,
                    senderId,
                    emoji,
                    removed: !emoji
                }),
            onGroupUpdated: (groupJid, updates) => eventSink.emit(this.instanceId, 'group_updated', { groupJid, updates }),
            onPresenceUpdated: (chatId, presences) =>
                eventSink.emit(this.instanceId, 'presence_updated', { chatId, presences })
        });
    }
}
