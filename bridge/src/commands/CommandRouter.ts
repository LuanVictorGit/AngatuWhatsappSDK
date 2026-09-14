import type { InstanceManager } from '../instance/InstanceManager.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';
import type { WAPresence } from 'baileys';

type Payload = Record<string, unknown>;
type Handler = (instances: InstanceManager, instanceId: string | undefined, payload: Payload) => Promise<unknown>;

function requireInstanceId(instanceId: string | undefined): string {
    if (!instanceId) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'instanceId e obrigatorio para esta acao');
    }
    return instanceId;
}

function requireString(payload: Payload, field: string): string {
    const value = payload[field];
    if (typeof value !== 'string' || value.length === 0) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `Campo obrigatorio ausente ou invalido: ${field}`);
    }
    return value;
}

function optionalString(payload: Payload, field: string): string | undefined {
    const value = payload[field];
    return typeof value === 'string' ? value : undefined;
}

function optionalBoolean(payload: Payload, field: string): boolean | undefined {
    const value = payload[field];
    return typeof value === 'boolean' ? value : undefined;
}

function optionalNumber(payload: Payload, field: string): number | undefined {
    const value = payload[field];
    return typeof value === 'number' ? value : undefined;
}

function requireStringArray(payload: Payload, field: string): string[] {
    const value = payload[field];
    if (!Array.isArray(value) || value.some((v) => typeof v !== 'string')) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `Campo obrigatorio ausente ou invalido: ${field}`);
    }
    return value as string[];
}

const PARTICIPANT_ACTIONS = new Set(['add', 'remove', 'promote', 'demote']);

/** Presencas que o WhatsApp aceita em sendPresenceUpdate. */
const PRESENCES: ReadonlySet<string> = new Set(['unavailable', 'available', 'composing', 'recording', 'paused']);

export const handlers: Record<string, Handler> = {
    async create_instance(instances, instanceId, payload) {
        const id = requireInstanceId(instanceId);
        const instance = instances.create({
            instanceId: id,
            sessionDirectory: requireString(payload, 'sessionDirectory'),
            displayName: optionalString(payload, 'displayName'),
            markOnlineOnConnect: optionalBoolean(payload, 'markOnlineOnConnect'),
            minReconnectDelayMs: optionalNumber(payload, 'minReconnectDelayMs'),
            maxReconnectDelayMs: optionalNumber(payload, 'maxReconnectDelayMs')
        });
        return { instanceId: instance.instanceId };
    },

    async connect(instances, instanceId) {
        await instances.get(requireInstanceId(instanceId)).adapter.connect();
        return {};
    },

    async disconnect(instances, instanceId) {
        await instances.get(requireInstanceId(instanceId)).adapter.disconnect();
        return {};
    },

    async logout(instances, instanceId) {
        await instances.get(requireInstanceId(instanceId)).adapter.logout();
        return {};
    },

    async destroy_instance(instances, instanceId) {
        await instances.destroy(requireInstanceId(instanceId));
        return {};
    },

    async list_instances(instances) {
        return { instanceIds: instances.list() };
    },

    async request_pairing_code(instances, instanceId, payload) {
        const code = await instances.get(requireInstanceId(instanceId)).adapter.requestPairingCode(requireString(payload, 'phoneNumber'));
        return { code };
    },

    async get_qr(instances, instanceId) {
        return { qr: instances.get(requireInstanceId(instanceId)).adapter.getCurrentQr() };
    },

    async get_status(instances, instanceId) {
        const instance = instances.get(requireInstanceId(instanceId));
        return {
            instanceId: instance.instanceId,
            state: instance.adapter.getState(),
            userId: instance.adapter.getUserId(),
            sessionDirectory: instance.sessionDirectory,
            createdAt: instance.createdAt
        };
    },

    async send_text(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendText(
            requireString(payload, 'to'),
            { text: requireString(payload, 'text') },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_image(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendImage(
            requireString(payload, 'to'),
            { filePath: requireString(payload, 'filePath'), caption: optionalString(payload, 'caption') },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_video(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendVideo(
            requireString(payload, 'to'),
            {
                filePath: requireString(payload, 'filePath'),
                caption: optionalString(payload, 'caption'),
                gifPlayback: optionalBoolean(payload, 'gifPlayback')
            },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_audio(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendAudio(
            requireString(payload, 'to'),
            { filePath: requireString(payload, 'filePath'), ptt: optionalBoolean(payload, 'ptt') },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_document(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendDocument(
            requireString(payload, 'to'),
            {
                filePath: requireString(payload, 'filePath'),
                fileName: requireString(payload, 'fileName'),
                mimetype: requireString(payload, 'mimetype'),
                caption: optionalString(payload, 'caption')
            },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_sticker(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendSticker(
            requireString(payload, 'to'),
            { filePath: requireString(payload, 'filePath') },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_location(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendLocation(
            requireString(payload, 'to'),
            {
                latitude: payload.latitude as number,
                longitude: payload.longitude as number,
                name: optionalString(payload, 'name'),
                address: optionalString(payload, 'address')
            },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_contact(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.sendContact(
            requireString(payload, 'to'),
            {
                displayName: requireString(payload, 'displayName'),
                phoneNumber: requireString(payload, 'phoneNumber'),
                organization: optionalString(payload, 'organization')
            },
            optionalString(payload, 'quotedMessageId')
        );
        return { messageId };
    },

    async send_reaction(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        await instance.adapter.sendReaction({
            chatId: requireString(payload, 'chatId'),
            messageId: requireString(payload, 'messageId'),
            fromMe: optionalBoolean(payload, 'fromMe') ?? false,
            participant: optionalString(payload, 'participant'),
            emoji: optionalString(payload, 'emoji') ?? ''
        });
        return {};
    },

    async edit_message(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const messageId = await instance.adapter.editMessage({
            chatId: requireString(payload, 'chatId'),
            messageId: requireString(payload, 'messageId'),
            text: requireString(payload, 'text'),
            fromMe: optionalBoolean(payload, 'fromMe') ?? true,
            participant: optionalString(payload, 'participant')
        });
        return { messageId };
    },

    async delete_message(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        await instance.adapter.deleteMessage({
            chatId: requireString(payload, 'chatId'),
            messageId: requireString(payload, 'messageId'),
            fromMe: optionalBoolean(payload, 'fromMe') ?? true,
            participant: optionalString(payload, 'participant')
        });
        return {};
    },

    async send_presence(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const presence = requireString(payload, 'presence');
        if (!PRESENCES.has(presence)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `presence invalida: ${presence}`);
        }
        await instance.adapter.sendPresence(requireString(payload, 'chatId'), presence as WAPresence);
        return {};
    },

    async mark_read(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        await instance.adapter.markRead(requireString(payload, 'chatId'), optionalString(payload, 'messageId'));
        return {};
    },

    async subscribe_presence(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        await instance.adapter.subscribePresence(requireString(payload, 'chatId'));
        return {};
    },

    async download_media(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        return instance.adapter.downloadMedia(requireString(payload, 'chatId'), requireString(payload, 'messageId'));
    },

    async get_me(instances, instanceId) {
        const instance = instances.get(requireInstanceId(instanceId));
        return instance.adapter.getMe();
    },

    async check_number(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        return instance.adapter.checkNumber(requireString(payload, 'number'));
    },

    async get_contact(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        return instance.adapter.getContact(requireString(payload, 'jid'));
    },

    async get_profile_picture(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        return instance.adapter.getProfilePicture(requireString(payload, 'jid'));
    },

    async get_group_metadata(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        return instance.adapter.getGroupMetadata(requireString(payload, 'groupJid'));
    },

    async list_groups(instances, instanceId) {
        const instance = instances.get(requireInstanceId(instanceId));
        const groups = await instance.adapter.listGroups();
        return { groups };
    },

    async create_group(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        return instance.adapter.createGroup(requireString(payload, 'subject'), requireStringArray(payload, 'participants'));
    },

    async update_group_participants(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const action = requireString(payload, 'action');
        if (!PARTICIPANT_ACTIONS.has(action)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `action invalida: ${action}`);
        }
        const result = await instance.adapter.updateGroupParticipants(
            requireString(payload, 'groupJid'),
            requireStringArray(payload, 'participants'),
            action as 'add' | 'remove' | 'promote' | 'demote'
        );
        return { result };
    },

    async update_group_subject(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        await instance.adapter.updateGroupSubject(requireString(payload, 'groupJid'), requireString(payload, 'subject'));
        return {};
    },

    async update_group_description(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        await instance.adapter.updateGroupDescription(requireString(payload, 'groupJid'), requireString(payload, 'description'));
        return {};
    },

    async leave_group(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        await instance.adapter.leaveGroup(requireString(payload, 'groupJid'));
        return {};
    },

    async get_group_invite_code(instances, instanceId, payload) {
        const instance = instances.get(requireInstanceId(instanceId));
        const code = await instance.adapter.getGroupInviteCode(requireString(payload, 'groupJid'));
        return { code: code ?? null };
    }
};

export async function dispatch(
    instances: InstanceManager,
    action: string,
    instanceId: string | undefined,
    payload: Payload
): Promise<unknown> {
    const handler = handlers[action];
    if (!handler) {
        throw new BridgeCommandError(ErrorCode.UNKNOWN_ACTION, `Acao desconhecida: ${action}`);
    }
    return handler(instances, instanceId, payload);
}
