import { isLidUser } from 'baileys';

/**
 * Translates WhatsApp's anonymous LID addresses (`<id>@lid`) back to the
 * phone-number address (`<phone>@s.whatsapp.net`).
 *
 * Why this exists: WhatsApp is migrating individual chats to LID addressing.
 * An incoming message now arrives with `key.remoteJid = "2116...@lid"`, while
 * anything the application sends is addressed to `55629...@s.whatsapp.net`.
 * Without a translation the same human shows up as two unrelated
 * conversations — replies land in one and outgoing messages in the other,
 * which reads exactly like "messages are not arriving".
 *
 * Mappings come from three places, all of them free:
 *
 * 1. `key.senderPn` / `key.participantPn`, which Baileys fills from the
 *    `sender_pn` / `participant_pn` stanza attributes;
 * 2. `contacts.upsert` / `contacts.update`, whose `Contact` carries both
 *    `lid` and `jid`;
 * 3. `onWhatsApp()`, which returns `{ jid, exists, lid }`.
 *
 * The map is per-instance and in memory: it is a cache, not a source of
 * truth, and a cold start simply relearns from the first message of each
 * conversation.
 */
export class LidDirectory {
    private readonly lidToPn = new Map<string, string>();

    /**
     * Records a pair. Accepts either side in any order-independent form; a
     * pair is only stored when one side is a LID and the other is not.
     */
    remember(lid: string | null | undefined, pn: string | null | undefined): void {
        const l = bare(lid);
        const p = bare(pn);
        if (!l || !p || isLidUser(l) !== true || isLidUser(p) === true) return;
        this.lidToPn.set(l, p);
    }

    /** Phone-number JID for a LID, or the input unchanged when unknown. */
    resolve(jid: string | null | undefined): string {
        const j = bare(jid);
        if (!j || isLidUser(j) !== true) return jid ?? '';
        return this.lidToPn.get(j) ?? jid ?? '';
    }

    /** `true` when the JID is a LID we can translate. */
    knows(jid: string | null | undefined): boolean {
        const j = bare(jid);
        return !!j && isLidUser(j) === true && this.lidToPn.has(j);
    }

    get size(): number {
        return this.lidToPn.size;
    }
}

/**
 * Strips the device suffix ("2116...:12@lid") so the same address always maps
 * to the same key — Baileys uses the suffixed form in some stanzas.
 */
function bare(jid: string | null | undefined): string {
    if (!jid) return '';
    const [user = '', server = ''] = jid.split('@');
    if (!server) return '';
    return `${user.split(':')[0]}@${server}`;
}
