import { BaileysAdapter, type AdapterOptions } from '../adapter/BaileysAdapter.js';
import type { EventSink } from './EventSink.js';
import type { Logger } from '../util/logger.js';

/** Binds one account's adapter to the event transport; behaviour lives in the adapter. */
export class WhatsAppInstance {
    readonly adapter: BaileysAdapter;
    readonly instanceId: string;
    readonly sessionDirectory: string;
    readonly createdAt = Date.now();

    constructor(options: AdapterOptions, logger: Logger, sink: EventSink) {
        this.instanceId = options.instanceId;
        this.sessionDirectory = options.sessionDirectory;
        const id = options.instanceId;
        this.adapter = new BaileysAdapter(options, logger, {
            onConnectionStateChanged: (state, cause, detail) => sink.emit(id, 'connection_state_changed', { state, cause, detail }),
            onQr: (qr, cycle) => sink.emit(id, 'qr_code', { qr, cycle }),
            onPairingCode: (code) => sink.emit(id, 'pairing_code', { code }),
            onMessage: (message) => sink.emit(id, 'message_received', message),
            onMessageUpdated: (update) => sink.emit(id, 'message_updated', update, true),
            onMessageDeleted: (chatId, messageId) => sink.emit(id, 'message_deleted', { chatId, messageId }),
            onReaction: (reaction) => sink.emit(id, 'reaction_received', reaction),
            onGroupUpdated: (update) => sink.emit(id, 'group_updated', update),
            onPresenceUpdated: (update) => sink.emit(id, 'presence_updated', update, true),
            onCall: (call) => sink.emit(id, 'call_received', call),
            onPollVote: (vote) => sink.emit(id, 'poll_vote', vote)
        });
    }
}
