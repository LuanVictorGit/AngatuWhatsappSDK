import type { WAPresence } from 'baileys';
import type { InstanceManager } from '../instance/InstanceManager.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';
import type { ButtonSpec, MessageTargetPayload } from '../adapter/ContentBuilder.js';
import * as Content from '../adapter/ContentBuilder.js';
import { toJid, type ChatChange, type PrivacySetting } from '../adapter/BaileysAdapter.js';

type Payload = Record<string, unknown>;
type Handler = (instances: InstanceManager, instanceId: string | undefined, payload: Payload) => Promise<unknown>;

const PRESENCES: ReadonlySet<string> = new Set(['unavailable', 'available', 'composing', 'recording', 'paused']);
const PARTICIPANT_ACTIONS: ReadonlySet<string> = new Set(['add', 'remove', 'promote', 'demote']);

function requireId(instanceId: string | undefined): string {
    if (!instanceId) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'instanceId is required for this action');
    }
    return instanceId;
}

function str(payload: Payload, field: string): string {
    const value = payload[field];
    if (typeof value !== 'string' || value.length === 0) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `missing or invalid field: ${field}`);
    }
    return value;
}

function optStr(payload: Payload, field: string): string | undefined {
    const value = payload[field];
    return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function optBool(payload: Payload, field: string): boolean | undefined {
    const value = payload[field];
    return typeof value === 'boolean' ? value : undefined;
}

function optNum(payload: Payload, field: string): number | undefined {
    const value = payload[field];
    return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function strings(payload: Payload, field: string): string[] {
    const value = payload[field];
    if (!Array.isArray(value) || value.some((v) => typeof v !== 'string')) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `missing or invalid field: ${field}`);
    }
    return value as string[];
}

function target(payload: Payload, defaultFromMe: boolean): MessageTargetPayload {
    return {
        chatId: toJid(str(payload, 'chatId')),
        messageId: str(payload, 'messageId'),
        fromMe: optBool(payload, 'fromMe') ?? defaultFromMe,
        participant: optStr(payload, 'participant')
    };
}

const adapter = (instances: InstanceManager, instanceId: string | undefined) => instances.get(requireId(instanceId)).adapter;

export const handlers: Record<string, Handler> = {
    async create_instance(instances, instanceId, p) {
        const instance = instances.create({
            instanceId: requireId(instanceId),
            sessionDirectory: str(p, 'sessionDirectory'),
            displayName: optStr(p, 'displayName'),
            alwaysOnline: optBool(p, 'alwaysOnline'),
            includeRawData: optBool(p, 'includeRawData'),
            acceptSince: optNum(p, 'acceptSince'),
            readsPerMinute: optNum(p, 'readsPerMinute'),
            minReconnectDelayMs: optNum(p, 'minReconnectDelayMs'),
            maxReconnectDelayMs: optNum(p, 'maxReconnectDelayMs'),
            maxLoginCycles: optNum(p, 'maxLoginCycles')
        });
        return { instanceId: instance.instanceId, state: instance.adapter.getState() };
    },
    async connect(i, id) {
        await adapter(i, id).connect();
        return {};
    },
    async disconnect(i, id) {
        await adapter(i, id).disconnect();
        return {};
    },
    async logout(i, id) {
        await adapter(i, id).logout();
        return {};
    },
    async destroy_instance(i, id) {
        await i.destroy(requireId(id));
        return {};
    },
    async request_pairing_code(i, id, p) {
        return { code: await adapter(i, id).requestPairingCode(str(p, 'phoneNumber'), optStr(p, 'customCode')) };
    },
    async get_status(i, id) {
        const instance = i.get(requireId(id));
        return {
            instanceId: instance.instanceId,
            state: instance.adapter.getState(),
            qr: instance.adapter.getCurrentQr(),
            userId: instance.adapter.getUserId(),
            createdAt: instance.createdAt
        };
    },
    async bridge_stats(i) {
        const memory = process.memoryUsage();
        return {
            pid: process.pid,
            uptimeSeconds: Math.round(process.uptime()),
            rss: memory.rss,
            heapUsed: memory.heapUsed,
            heapTotal: memory.heapTotal,
            external: memory.external,
            arrayBuffers: memory.arrayBuffers,
            instances: i.list().map((instance) => ({ instanceId: instance.instanceId, ...instance.adapter.stats() }))
        };
    },
    async send_text(i, id, p) {
        return { messageId: await adapter(i, id).send(str(p, 'to'), Content.textContent(p.text), optStr(p, 'quotedMessageId')) };
    },
    async send_image(i, id, p) {
        const content = Content.imageContent({ filePath: str(p, 'filePath'), caption: optStr(p, 'caption') });
        return { messageId: await adapter(i, id).send(str(p, 'to'), content, optStr(p, 'quotedMessageId')) };
    },
    async send_video(i, id, p) {
        const content = Content.videoContent({
            filePath: str(p, 'filePath'),
            caption: optStr(p, 'caption'),
            gifPlayback: optBool(p, 'gifPlayback')
        });
        return { messageId: await adapter(i, id).send(str(p, 'to'), content, optStr(p, 'quotedMessageId')) };
    },
    async send_audio(i, id, p) {
        const content = Content.audioContent({ filePath: str(p, 'filePath'), ptt: optBool(p, 'ptt') });
        return { messageId: await adapter(i, id).send(str(p, 'to'), content, optStr(p, 'quotedMessageId')) };
    },
    async send_document(i, id, p) {
        const content = Content.documentContent({
            filePath: str(p, 'filePath'),
            fileName: str(p, 'fileName'),
            mimetype: str(p, 'mimetype'),
            caption: optStr(p, 'caption')
        });
        return { messageId: await adapter(i, id).send(str(p, 'to'), content, optStr(p, 'quotedMessageId')) };
    },
    async send_sticker(i, id, p) {
        return { messageId: await adapter(i, id).send(str(p, 'to'), Content.stickerContent(p.filePath), optStr(p, 'quotedMessageId')) };
    },
    async send_location(i, id, p) {
        const content = Content.locationContent({
            latitude: p.latitude as number,
            longitude: p.longitude as number,
            name: optStr(p, 'name'),
            address: optStr(p, 'address')
        });
        return { messageId: await adapter(i, id).send(str(p, 'to'), content, optStr(p, 'quotedMessageId')) };
    },
    async send_contact(i, id, p) {
        const content = Content.contactContent({
            displayName: str(p, 'displayName'),
            phoneNumber: str(p, 'phoneNumber'),
            organization: optStr(p, 'organization')
        });
        return { messageId: await adapter(i, id).send(str(p, 'to'), content, optStr(p, 'quotedMessageId')) };
    },
    async send_buttons(i, id, p) {
        const buttons = Array.isArray(p.buttons) ? (p.buttons as ButtonSpec[]) : [];
        const messageId = await adapter(i, id).sendButtons(
            str(p, 'to'),
            { text: str(p, 'text'), title: optStr(p, 'title'), footer: optStr(p, 'footer'), buttons },
            optStr(p, 'quotedMessageId')
        );
        return { messageId };
    },
    async send_reaction(i, id, p) {
        await adapter(i, id).sendReaction(target(p, false), optStr(p, 'emoji') ?? '');
        return {};
    },
    async edit_message(i, id, p) {
        return { messageId: await adapter(i, id).editMessage(target(p, true), str(p, 'text')) };
    },
    async delete_message(i, id, p) {
        await adapter(i, id).deleteMessage(target(p, true));
        return {};
    },
    async send_presence(i, id, p) {
        const presence = str(p, 'presence');
        if (!PRESENCES.has(presence)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `invalid presence: ${presence}`);
        }
        await adapter(i, id).sendPresence(optStr(p, 'chatId'), presence as WAPresence);
        return {};
    },
    async mark_read(i, id, p) {
        await adapter(i, id).markRead(str(p, 'chatId'), optStr(p, 'messageId'), optStr(p, 'participant'));
        return {};
    },
    async subscribe_presence(i, id, p) {
        await adapter(i, id).subscribePresence(str(p, 'chatId'));
        return {};
    },
    async reject_call(i, id, p) {
        await adapter(i, id).rejectCall(str(p, 'callId'), str(p, 'from'));
        return {};
    },
    async download_media(i, id, p) {
        return adapter(i, id).downloadMedia(str(p, 'chatId'), str(p, 'messageId'), str(p, 'targetPath'));
    },
    async get_me(i, id) {
        return adapter(i, id).getMe();
    },
    async check_number(i, id, p) {
        return adapter(i, id).checkNumber(str(p, 'number'));
    },
    async get_contact(i, id, p) {
        return adapter(i, id).getContact(str(p, 'jid'));
    },
    async get_profile_picture(i, id, p) {
        return adapter(i, id).getProfilePicture(str(p, 'jid'));
    },
    async get_group_metadata(i, id, p) {
        return adapter(i, id).getGroupMetadata(str(p, 'groupJid'));
    },
    async list_groups(i, id) {
        return { groups: await adapter(i, id).listGroups() };
    },
    async create_group(i, id, p) {
        return adapter(i, id).createGroup(str(p, 'subject'), strings(p, 'participants'));
    },
    async update_group_participants(i, id, p) {
        const action = str(p, 'action');
        if (!PARTICIPANT_ACTIONS.has(action)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `invalid action: ${action}`);
        }
        const result = await adapter(i, id).updateGroupParticipants(
            str(p, 'groupJid'),
            strings(p, 'participants'),
            action as 'add' | 'remove' | 'promote' | 'demote'
        );
        return { results: result.map((r) => ({ jid: r.jid, status: r.status })) };
    },
    async update_group_subject(i, id, p) {
        await adapter(i, id).updateGroupSubject(str(p, 'groupJid'), str(p, 'subject'));
        return {};
    },
    async update_group_description(i, id, p) {
        await adapter(i, id).updateGroupDescription(str(p, 'groupJid'), str(p, 'description'));
        return {};
    },
    async leave_group(i, id, p) {
        await adapter(i, id).leaveGroup(str(p, 'groupJid'));
        return {};
    },
    async get_group_invite_code(i, id, p) {
        return { code: (await adapter(i, id).getGroupInviteCode(str(p, 'groupJid'))) ?? null };
    },
    async check_numbers(i, id, p) {
        return { results: await adapter(i, id).checkNumbers(strings(p, 'numbers')) };
    },
    async group_exists(i, id, p) {
        return adapter(i, id).groupExists(str(p, 'groupJid'));
    },
    async get_invite_info(i, id, p) {
        return adapter(i, id).getInviteInfo(str(p, 'code'));
    },
    async join_group(i, id, p) {
        return { groupJid: await adapter(i, id).joinGroup(str(p, 'code')) };
    },
    async revoke_invite_code(i, id, p) {
        return { code: await adapter(i, id).revokeInviteCode(str(p, 'groupJid')) };
    },
    async group_setting(i, id, p) {
        const setting = str(p, 'setting');
        if (!GROUP_SETTINGS.has(setting)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `invalid group setting: ${setting}`);
        }
        await adapter(i, id).setGroupSetting(str(p, 'groupJid'), setting as 'announcement' | 'not_announcement' | 'locked' | 'unlocked');
        return {};
    },
    async set_disappearing(i, id, p) {
        await adapter(i, id).setDisappearing(str(p, 'chatId'), optNum(p, 'seconds') ?? 0);
        return {};
    },
    async update_profile_picture(i, id, p) {
        await adapter(i, id).updateProfilePicture(optStr(p, 'jid'), str(p, 'filePath'));
        return {};
    },
    async remove_profile_picture(i, id, p) {
        await adapter(i, id).removeProfilePicture(optStr(p, 'jid'));
        return {};
    },
    async update_profile_name(i, id, p) {
        await adapter(i, id).updateProfileName(str(p, 'name'));
        return {};
    },
    async update_profile_status(i, id, p) {
        await adapter(i, id).updateProfileStatus(str(p, 'status'));
        return {};
    },
    async set_blocked(i, id, p) {
        await adapter(i, id).setBlocked(str(p, 'jid'), optBool(p, 'blocked') ?? true);
        return {};
    },
    async get_blocklist(i, id) {
        return { jids: await adapter(i, id).getBlocklist() };
    },
    async get_contact_status(i, id, p) {
        return adapter(i, id).getStatus(str(p, 'jid'));
    },
    async get_business_profile(i, id, p) {
        return { profile: await adapter(i, id).getBusinessProfile(str(p, 'jid')) };
    },
    async get_privacy_settings(i, id) {
        return { settings: await adapter(i, id).getPrivacySettings() };
    },
    async update_privacy(i, id, p) {
        await adapter(i, id).updatePrivacy(str(p, 'setting') as PrivacySetting, str(p, 'value'));
        return {};
    },
    async modify_chat(i, id, p) {
        const change = str(p, 'change');
        if (!CHAT_CHANGES.has(change)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `invalid chat change: ${change}`);
        }
        await adapter(i, id).modifyChat(str(p, 'chatId'), change as ChatChange, optNum(p, 'muteUntilMs'));
        return {};
    },
    async star_message(i, id, p) {
        await adapter(i, id).starMessage(target(p, false), optBool(p, 'star') ?? true);
        return {};
    },
    async forward_message(i, id, p) {
        return { messageId: await adapter(i, id).forwardMessage(str(p, 'chatId'), str(p, 'messageId'), str(p, 'to')) };
    },
    async send_poll(i, id, p) {
        const messageId = await adapter(i, id).sendPoll(str(p, 'to'), str(p, 'name'), strings(p, 'options'), optNum(p, 'selectableCount') ?? 1);
        return { messageId };
    }
};

const GROUP_SETTINGS: ReadonlySet<string> = new Set(['announcement', 'not_announcement', 'locked', 'unlocked']);
const CHAT_CHANGES: ReadonlySet<string> = new Set(['mute', 'unmute', 'pin', 'unpin', 'archive', 'unarchive', 'mark_unread']);

export async function dispatch(instances: InstanceManager, action: string, instanceId: string | undefined, payload: Payload): Promise<unknown> {
    const handler = handlers[action];
    if (!handler) {
        throw new BridgeCommandError(ErrorCode.UNKNOWN_ACTION, `unknown action: ${action}`);
    }
    return handler(instances, instanceId, payload);
}
