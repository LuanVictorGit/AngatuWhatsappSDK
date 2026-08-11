export interface VCardInput {
    displayName: string;
    phoneNumber: string;
    organization?: string;
}

function escapeVCardValue(value: string): string {
    return value.replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\n/g, '\\n');
}

/** Minimal vCard 3.0 generator - the subset of fields WhatsApp actually renders. */
export function buildVCard(input: VCardInput): string {
    const name = escapeVCardValue(input.displayName);
    const phone = escapeVCardValue(input.phoneNumber);
    const lines = ['BEGIN:VCARD', 'VERSION:3.0', `FN:${name}`, `TEL;type=CELL;type=VOICE;waid=${phone.replace(/[^0-9]/g, '')}:${phone}`];
    if (input.organization) {
        lines.push(`ORG:${escapeVCardValue(input.organization)}`);
    }
    lines.push('END:VCARD');
    return lines.join('\n');
}
