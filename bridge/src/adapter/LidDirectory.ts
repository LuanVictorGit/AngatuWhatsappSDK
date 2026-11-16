import { isLidUser } from 'baileys';
import { BoundedMap } from '../util/TtlCache.js';

/**
 * Pairs WhatsApp's anonymous LID addresses (`<id>@lid`) with phone-number
 * addresses (`<phone>@s.whatsapp.net`).
 *
 * WhatsApp is moving one-to-one chats to LID addressing: a message arrives
 * from `2116...@lid` while the application writes to `5562...@s.whatsapp.net`,
 * and without a translation one person shows up as two conversations. Pairs are
 * learned only from conversations the account takes part in (the stanza's
 * `sender_pn`/`participant_pn`, or a number the application asked about) -
 * never from the address book, which would be collecting third-party data the
 * product has no use for.
 *
 * Capped: losing a pair only costs relearning it from the next stanza.
 */
const MAX_PAIRS = 20_000;

export class LidDirectory {
    private readonly lidToPn = new BoundedMap<string, string>(MAX_PAIRS);
    private readonly pnToLid = new BoundedMap<string, string>(MAX_PAIRS);

    /** Records a pair given in either order; ignored unless exactly one side is a LID. */
    remember(first: string | null | undefined, second: string | null | undefined): void {
        const a = bare(first);
        const b = bare(second);
        if (!a || !b) return;
        const aIsLid = isLidUser(a) === true;
        const bIsLid = isLidUser(b) === true;
        if (aIsLid === bIsLid) return;
        const l = aIsLid ? a : b;
        const p = aIsLid ? b : a;
        this.lidToPn.set(l, p);
        this.pnToLid.set(p, l);
    }

    /** Phone-number JID for a LID, or the input unchanged when unknown. */
    resolve(jid: string | null | undefined): string {
        const j = bare(jid);
        if (!j || isLidUser(j) !== true) return jid ?? '';
        return this.lidToPn.get(j) ?? jid ?? '';
    }

    /**
     * LID for a phone-number JID, or the input unchanged. Ephemeral stanzas
     * (typing, presence, receipts) are delivered verbatim, so a chat that moved
     * to LID only shows them when they are addressed to the LID.
     */
    toLid(jid: string | null | undefined): string {
        const j = bare(jid);
        if (!j || isLidUser(j) === true) return jid ?? '';
        return this.pnToLid.get(j) ?? jid ?? '';
    }

    get size(): number {
        return this.lidToPn.size;
    }
}

/** Strips the device suffix ("2116...:12@lid") so one address always maps to one key. */
function bare(jid: string | null | undefined): string {
    if (!jid) return '';
    const [user = '', server = ''] = jid.split('@');
    if (!server) return '';
    return `${user.split(':')[0]}@${server}`;
}
