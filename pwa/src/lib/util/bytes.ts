/**
 * Byte plumbing shared by everything on the wire.
 *
 * The Android app is the reference implementation, and it uses `java.nio.ByteBuffer` and
 * `java.util.UUID`, both of which are big-endian. Every multi-byte number here is written the
 * same way — `DataView` defaults to big-endian only if you remember to pass `false`, and
 * forgetting is the kind of bug that produces a payload that decodes to garbage on the other
 * phone and nowhere else.
 */

const HEX = '0123456789abcdef';

export function concat(...parts: Uint8Array[]): Uint8Array {
	const total = parts.reduce((n, p) => n + p.length, 0);
	const out = new Uint8Array(total);
	let at = 0;
	for (const p of parts) {
		out.set(p, at);
		at += p.length;
	}
	return out;
}

export function randomBytes(size: number): Uint8Array {
	return crypto.getRandomValues(new Uint8Array(size));
}

export function toHex(bytes: Uint8Array): string {
	let s = '';
	for (const b of bytes) s += HEX[b >> 4] + HEX[b & 15];
	return s;
}

export function fromHex(hex: string): Uint8Array {
	const out = new Uint8Array(hex.length / 2);
	for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
	return out;
}

export function utf8(text: string): Uint8Array {
	return new TextEncoder().encode(text);
}

export function fromUtf8(bytes: Uint8Array): string {
	return new TextDecoder().decode(bytes);
}

/** Standard base64, no padding — what `java.util.Base64.getEncoder().withoutPadding()` emits. */
export function toBase64(bytes: Uint8Array): string {
	let binary = '';
	for (const b of bytes) binary += String.fromCharCode(b);
	return btoa(binary).replace(/=+$/, '');
}

export function fromBase64(text: string): Uint8Array {
	const padded = text.replace(/-/g, '+').replace(/_/g, '/');
	const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
	const out = new Uint8Array(binary.length);
	for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
	return out;
}

/** base64url, no padding — `Base64.getUrlEncoder().withoutPadding()`, used by the invite codec. */
export function toBase64Url(bytes: Uint8Array): string {
	return toBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_');
}

/** `fromBase64` already accepts the url alphabet, so decoding needs no separate function. */
export const fromBase64Url = fromBase64;

/**
 * A UUID string in the canonical 8-4-4-4-12 form, as `UUID.toString()` produces it, and its
 * 16 raw bytes in the order `UUID` lays them out: the most significant long first, both
 * big-endian. Ids travel between phones as strings and go on the wire as bytes, so both
 * directions have to agree exactly.
 */
export function uuidToBytes(uuid: string): Uint8Array {
	const hex = uuid.replace(/-/g, '');
	if (hex.length !== 32) throw new Error(`not a uuid: ${uuid}`);
	return fromHex(hex);
}

export function bytesToUuid(bytes: Uint8Array): string {
	const h = toHex(bytes);
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** A random v4 UUID. `crypto.randomUUID` needs a secure context, which a PWA always is. */
export function randomUuid(): string {
	return crypto.randomUUID();
}

/**
 * Counters are a Java `long`. JavaScript numbers hold integers exactly only to 2^53, and a
 * counter that increments once per message will not reach that in any human lifetime — but the
 * *wire* field is eight bytes regardless, so it is written and read as a `BigInt` and narrowed
 * at the edges where the database and the UI want a number.
 */
export function writeUint64(view: DataView, offset: number, value: bigint): void {
	view.setBigUint64(offset, value, false);
}

export function readUint64(view: DataView, offset: number): bigint {
	return view.getBigUint64(offset, false);
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
	return toHex(new Uint8Array(digest));
}
